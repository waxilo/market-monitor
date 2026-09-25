package com.waxilo.marketmonitor.data.alert

import android.content.Context
import android.content.Intent
import com.waxilo.marketmonitor.data.remote.WebhookSender
import com.waxilo.marketmonitor.domain.alert.AlertCondition
import com.waxilo.marketmonitor.domain.alert.AlertDecision
import com.waxilo.marketmonitor.domain.alert.AlertDirection
import com.waxilo.marketmonitor.domain.alert.AlertEvaluator
import com.waxilo.marketmonitor.domain.alert.AlertRepeatMode
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertRuleSource
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.alert.BandAnchorDisplay
import com.waxilo.marketmonitor.domain.alert.IndicatorKind
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.alert.LineAlertMode
import com.waxilo.marketmonitor.domain.alert.LineMember
import com.waxilo.marketmonitor.domain.indicator.Indicators
import com.waxilo.marketmonitor.domain.indicator.MaBand
import com.waxilo.marketmonitor.domain.kline.CandleInterval
import com.waxilo.marketmonitor.domain.model.MarketTicker
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import com.waxilo.marketmonitor.domain.repository.AlertRepository
import com.waxilo.marketmonitor.domain.repository.MarketRepository
import com.waxilo.marketmonitor.domain.repository.SettingsRepository
import com.waxilo.marketmonitor.domain.repository.tickerSnapshots
import com.waxilo.marketmonitor.domain.repository.WebhookDelivery
import com.waxilo.marketmonitor.domain.repository.WebhookRepository
import com.waxilo.marketmonitor.domain.webhook.AlertEvent
import com.waxilo.marketmonitor.domain.webhook.WebhookTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 预警检测中枢（PRD FR-3.2 / FR-3.3 / FR-4.2）。
 *
 * 三条链路分开跑：
 * - 观察：订阅启用标的的 ticker 流（Room 缓存视图，随各页轮询落库而更新），逐条规则判定并落状态。
 * - 轮询：按设置间隔主动拉单个标的快照。观察流依赖别人刷新，页面全退出时就没有源头，
 *   所以这里独立保底。
 * - 补发：推送失败的 Webhook 在下一轮重试，避免一次网络抖动就永久丢通知。
 *
 * 另有一条同步链路维护「指标划线」挂出的规则（见 [indicatorSyncLoop]）。
 *
 * 生命周期跟随进程：进程被系统杀死后无法提醒，UI 必须显式告知该限制（PRD FR-3.2）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlertEngine(
    private val context: Context,
    private val alerts: AlertRepository,
    private val webhooks: WebhookRepository,
    private val market: MarketRepository,
    private val settings: SettingsRepository,
    private val notifier: AlertNotifier,
    private val sender: WebhookSender,
    private val scope: CoroutineScope,
) {

    /** 均线带当前锚点：lineId → 条件 → 成员键，触发时据此知道该让哪个成员进冷却。 */
    private val bandAnchors = ConcurrentHashMap<Long, ConcurrentHashMap<AlertCondition, String>>()

    /** 被穿越成员冷却到期时刻：成员键 → 毫秒；进程内状态，重启后重新跟随。 */
    private val bandCooldownUntil = ConcurrentHashMap<String, Long>()

    /** 「指标线」模式上一轮锚点值：lineId → 条件 → 值；价格越过它即视为穿越，据此换锚。 */
    private val bandDisplayValues = ConcurrentHashMap<Long, ConcurrentHashMap<AlertCondition, Double>>()

    /** 「指标线」模式均线带的实时锚点：引擎按轮询算，图上画灰色水平线（不挂规则）。 */
    private val _bandDisplayLines = MutableStateFlow<List<BandAnchorDisplay>>(emptyList())
    val bandDisplayLines = _bandDisplayLines.asStateFlow()

    private val started = AtomicBoolean(false)
    private val enabledRules = MutableStateFlow<List<AlertRule>>(emptyList())

    /** 常驻通知里显示的规则数。 */
    val enabledRuleCount: Int get() = enabledRules.value.size

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { observeRules() }
        scope.launch { observePrices() }
        scope.launch { pollLoop() }
        scope.launch { indicatorSyncLoop() }
        scope.launch { retryLoop() }
    }

    /**
     * 界面回到前台时重新对齐常驻通知：Android 12 起后台启动前台服务会被直接拒绝，
     * 冷启动那一轮如果失败，只有这个时刻能补上，否则监测随时可能被系统回收。
     */
    fun refreshMonitorService() {
        syncMonitorService(enabledRules.value.size)
    }

    private suspend fun observeRules() {
        alerts.rules()
            .map { list -> list.filter { it.enabled } }
            .collect { list ->
                enabledRules.value = list
                syncMonitorService(list.size)
            }
    }

    private suspend fun observePrices() {
        enabledRules
            .flatMapLatest { rules -> market.tickerSnapshots(rules.map { SymbolId(it.market, it.symbol) }) }
            .collect { prices -> evaluateAll(prices) }
    }

    private suspend fun evaluateAll(prices: Map<SymbolId, MarketTicker>) {
        val now = System.currentTimeMillis()
        enabledRules.value.forEach { rule ->
            val ticker = prices[SymbolId(rule.market, rule.symbol)] ?: return@forEach
            val previous = alerts.state(rule.id)
            val (decision, next) = AlertEvaluator.evaluate(rule, ticker.lastPrice, ticker.changePercent, now, previous)
            val retired = decision is AlertDecision.Triggered && onTriggered(rule, decision)
            // 已退场的规则不能再写状态：deleteRule 连状态一起删了，回写等于凭空造出一行孤儿
            // （仓库层落库前会再确认规则仍存在，这里只需跳过本轮自己的那条）。
            if (!retired && changed(previous, next)) alerts.saveState(rule.id, next)
        }
    }

    /** lastPrice 每 tick 都在变，只有语义字段变化才值得写库。 */
    private fun changed(previous: AlertState, next: AlertState): Boolean =
        previous.lastPrice == null ||
            previous.wasSatisfied != next.wasSatisfied ||
            previous.lastTriggeredAt != next.lastTriggeredAt ||
            previous.fired != next.fired

    /**
     * 记录一次触发。返回 true 表示这条规则已经「用尽」并被移除。
     *
     * 单次规则命中即退场：留在列表里既不会再提醒（fired 闸门），又要用户手动清理，
     * 是纯粹的残留物。记录与通知都做完才删，避免删库把还没发出去的通知一起带走。
     */
    private suspend fun onTriggered(rule: AlertRule, decision: AlertDecision.Triggered): Boolean {
        // 冷却必须先于一切挂起点记账：record/通知/Webhook 可能耗时数秒，期间同步循环（5s 一轮）
        // 会把锚点换到新成员头上——晚到的冷却会错杀无辜，被穿越的线反而贴着现价反复触发
        startBandCooldown(rule)
        val message = AlertMessage(
            ruleId = rule.id,
            market = rule.market,
            symbol = rule.symbol,
            alertName = indicatorAlertName(rule, decision),
            direction = decision.direction,
            price = decision.price,
            threshold = thresholdOf(rule, decision.direction),
            changePercent = decision.changePercent,
            triggeredAt = decision.atMs,
            webhookDelivery = WebhookDelivery.NONE,
        )
        val id = alerts.record(message)
        val stored = message.copy(id = id)
        if (settings.current().notificationEnabled) notifier.notify(stored, rule)
        val delivery = deliver(rule, stored)
        if (delivery != WebhookDelivery.NONE) alerts.setDelivery(id, delivery)
        startBandCooldown(rule)
        if (rule.repeatMode != AlertRepeatMode.ONCE) return false
        alerts.deleteRule(rule.id)
        if (rule.source == AlertRuleSource.INDICATOR) retireIndicatorLine(rule)
        return true
    }

    /**
     * 指标划线预警的标题（通知栏与消息中心共用）：「BTCUSDT 上破 1h MA30均线264」。
     * 均线带取本次穿越的那条成员线（[bandAnchors] 在挂规则时记好），认不出成员才退回整带标识；
     * 手工价格预警等非划线规则仍用规则名。
     */
    private suspend fun indicatorAlertName(rule: AlertRule, decision: AlertDecision.Triggered): String {
        val lineId = rule.indicatorLineId ?: return rule.name
        val line = alerts.indicatorLines().first().firstOrNull { it.id == lineId } ?: return rule.name
        val lineLabel = when (line.kind) {
            IndicatorKind.MA -> "${line.interval.label} MA${line.maPeriod}均线"
            IndicatorKind.MA_BAND -> bandAnchors[lineId]?.get(rule.condition)
                ?.let { memberKey -> LineMember.parse(memberKey.substringAfter('|')) }
                ?.let { "${it.interval.label} MA${it.maPeriod}均线" }
                ?: line.label
        }
        return AlertText.indicatorAlertName(
            symbol = rule.symbol,
            direction = decision.direction,
            lineLabel = lineLabel,
            threshold = thresholdOf(rule, decision.direction),
        )
    }

    /**
     * 均线带成员被穿越即进冷却：刚穿过的线就在脚下/头顶，立刻当锚点只会画出贴着价、
     * 反复触发的假信号。锚点键在 [ensureBandRule] 改阈值之前记好，这里取的是触发那一刻的旧锚。
     * [bandAnchors] 只由均线带链路（[ensureBandRule]/[syncBandDisplay]）填充，单周期线查不到键，
     * 不必再查库判型；全程同步无挂起，才能在换锚窗口关闭前记到人头上。
     */
    private fun startBandCooldown(rule: AlertRule) {
        val lineId = rule.indicatorLineId ?: return
        val memberKey = bandAnchors[lineId]?.get(rule.condition) ?: return
        bandCooldownUntil[memberKey] = System.currentTimeMillis() + BAND_MEMBER_COOLDOWN_MS
    }

    /**
     * 单次模式的划线命中即作废：规则已随 ONCE 退场，把划线告警置回 OFF——
     * 触发后现价站到线的另一侧，留着只会画出一条不再被任何逻辑更新的假线。
     * 用户要再来一次得在划线管理里重新开启。
     */
    private suspend fun retireIndicatorLine(rule: AlertRule) {
        val lineId = rule.indicatorLineId ?: return
        alerts.setIndicatorLineAlertMode(lineId, LineAlertMode.OFF)
    }

    // ---- 指标划线的规则维护 ----

    /**
     * 线由引擎维护而非详情页 VM：划线是后台设置，页面没打开时线也要跟着指标实时移动。
     * 节奏与预警轮询一致（默认 5s），每条开了告警的划线至多挂一条规则。
     */
    private suspend fun indicatorSyncLoop() {
        while (true) {
            runCatching { syncIndicatorRules() }
            delay(pollIntervalMs())
        }
    }

    private suspend fun syncIndicatorRules() {
        // 关闭（enabled=false）的划线整体当不存在：active/offBand 都不含它，
        // 于是规则走下面的孤儿回收、灰锚点走末尾的 display 修剪，配置本身原样保留
        val all = alerts.indicatorLines().first().filter { it.enabled }
        val active = all.filter { it.alertMode != LineAlertMode.OFF }
        val activeIds = active.map { it.id }.toSet()
        // 孤儿回收：划线删除、停用或告警关闭后规则必须一起退场，图上不能留没人更新的假线
        alerts.rules().first()
            .filter { it.source == AlertRuleSource.INDICATOR && it.indicatorLineId !in activeIds }
            .forEach { alerts.deleteRule(it.id) }
        active.forEach { line ->
            // 单条网络失败只跳过这一条，不带崩整个循环
            runCatching { syncLineRule(line) }
        }
        // 「指标线」模式的均线带：不挂规则，但同样按轮询维护锚点供图上画灰线
        val offBandIds = all
            .filter { it.alertMode == LineAlertMode.OFF && it.kind == IndicatorKind.MA_BAND }
            .map { it.id }
            .toSet()
        all.forEach { line ->
            if (line.id in offBandIds) runCatching { syncBandDisplay(line) }
        }
        // 划线删除/改回预警/改成别的类型时，灰线与上轮锚点值一并收掉
        _bandDisplayLines.update { list -> list.filter { it.lineId in offBandIds } }
        bandDisplayValues.entries.removeIf { it.key !in offBandIds }
    }

    /**
     * 给一条划线挂/移规则（均线带走 [syncBandRules]，不在这里）。
     *
     * 旧的单周期 MA 线每个周期各维护一条规则，按规则名对号；
     * 名字与 [IndicatorLine.label] 一致，升级不会引发无谓的改名。
     * 上破还是下破按线相对现价的位置定，之后不随位置翻转改条件——
     * 线自己越过现价同样会让条件满足，那就是一次货真价实的穿越，不该被换向吞掉。
     * 阈值对齐 tick 用向外取整（上破向上、下破向下），保证写下的那一刻条件必然未满足。
     * 蜡烛不足指标周期时保持现状不动，等下一轮。
     */
    private suspend fun syncLineRule(line: IndicatorLine) {
        if (line.kind == IndicatorKind.MA_BAND) {
            syncBandRules(line)
            return
        }
        val id = SymbolId(line.market, line.symbol)
        val price = (market.refreshTicker(id) ?: market.ticker(id).first())?.lastPrice ?: return
        val required = line.maPeriod
        if (required <= 0) return
        val tick = market.instrument(id).first()?.priceTickSize
        val mine = alerts.rules().first().filter { it.indicatorLineId == line.id }
        val wanted = mutableListOf<String>()
        line.coverIntervals.forEach { interval ->
            // 单个周期的拉取/计算失败只跳过它，其余周期照常同步
            runCatching {
                val page = market.klines(id, interval, required + 2)
                val closes = DoubleArray(page.klines.size) { page.klines[it].close.toDouble() }
                val value = when (line.kind) {
                    IndicatorKind.MA -> Indicators.latestMa(closes, line.maPeriod)
                    IndicatorKind.MA_BAND -> null // 已在函数开头分流
                } ?: return@runCatching
                val above = value > price.toDouble()
                val condition = if (above) AlertCondition.ABOVE else AlertCondition.BELOW
                val rounding = if (above) RoundingMode.CEILING else RoundingMode.FLOOR
                val target = alignToTick(BigDecimal(value.toString()), tick, rounding)
                val name = ruleNameOf(line, interval, condition)
                wanted += name
                val existing = mine.firstOrNull { it.name == name }
                val repeat = line.alertMode.repeatMode
                if (existing == null) {
                    alerts.saveRule(
                        AlertRule(
                            market = line.market,
                            symbol = line.symbol,
                            name = name,
                            condition = condition,
                            threshold = target,
                            repeatMode = repeat,
                            createdAt = System.currentTimeMillis(),
                            source = AlertRuleSource.INDICATOR,
                            indicatorLineId = line.id,
                        ),
                    )
                    return@runCatching
                }
                if (existing.name == name && existing.threshold?.compareTo(target) == 0 &&
                    existing.repeatMode == repeat
                ) {
                    return@runCatching
                }
                alerts.saveRule(existing.copy(name = name, threshold = target, repeatMode = repeat))
                if (existing.repeatMode != repeat) {
                    // 换告警方式等于换一条新规则看待：清掉 fired 闸门与边沿基线，否则新模式永不触发
                    alerts.saveState(existing.id, AlertState())
                }
            }
        }
        // 周期被取消/名字对不上号的旧规则一律收掉，图上不留没人更新的假线
        mine.filter { it.name !in wanted }.forEach { alerts.deleteRule(it.id) }
    }

    /** 划线规则的稳定标识：靠它把规则认到具体周期上，改配置不产生孤儿。 */
    private fun ruleNameOf(line: IndicatorLine, interval: CandleInterval, condition: AlertCondition): String {
        val indicator = when (line.kind) {
            IndicatorKind.MA -> "${interval.label} MA${line.maPeriod}"
            IndicatorKind.MA_BAND -> line.label
        }
        return "${line.symbol} $indicator ${condition.label}"
    }

    /**
     * 均线带聚合条目：把所有成员的末根均线值当一个集合，
     * 现价上方最近值挂「上破」、下方最近值挂「下破」，各一条规则、每次穿越持续跟随。
     *
     * 与单条线的关键差别是不锁方向也不退场：穿越后那个值落到现价另一侧，
     * 下一轮自动换锚到集合里下一条最近的均线，图上两条虚线始终贴着现价的上下沿。
     * 集合里某一侧空了（如价格在所有均线之上）就收掉那一侧的规则，等均线追上来再挂。
     * 被穿越的成员进入 [BAND_MEMBER_COOLDOWN_MS] 冷却：刚穿过的线就在脚下/头顶，
     * 立刻当锚点只会画出一条贴着价、反复触发的假信号，冷却期内不参与上下破选取。
     */
    private suspend fun syncBandRules(line: IndicatorLine) {
        val id = SymbolId(line.market, line.symbol)
        val price = (market.refreshTicker(id) ?: market.ticker(id).first())?.lastPrice ?: return
        val now = System.currentTimeMillis()
        bandCooldownUntil.entries.removeIf { it.value <= now }
        val (upper, lower) = MaBand.pick(bandCandidates(line, id, now), price.toDouble())
        ensureBandRule(line, id, AlertCondition.ABOVE, upper)
        ensureBandRule(line, id, AlertCondition.BELOW, lower)
    }

    /**
     * 「指标线」模式（不响）的均线带锚点维护：与 [syncBandRules] 共用同一套候选集合与
     * 冷却——现价上/下各取最近成员，价格越过上一轮锚点即视为穿越，该成员进冷却、下轮换锚。
     * 差别只在产出：不挂任何规则，锚点值发布到 [bandDisplayLines] 供图上画灰色水平线。
     */
    private suspend fun syncBandDisplay(line: IndicatorLine) {
        val id = SymbolId(line.market, line.symbol)
        val price = (market.refreshTicker(id) ?: market.ticker(id).first())?.lastPrice ?: return
        val now = System.currentTimeMillis()
        bandCooldownUntil.entries.removeIf { it.value <= now }
        val priceValue = price.toDouble()
        bandDisplayValues[line.id]?.let { previous ->
            previous[AlertCondition.ABOVE]?.takeIf { priceValue >= it }
                ?.let { coolBandMember(line.id, AlertCondition.ABOVE, now) }
            previous[AlertCondition.BELOW]?.takeIf { priceValue <= it }
                ?.let { coolBandMember(line.id, AlertCondition.BELOW, now) }
        }
        val (upper, lower) = MaBand.pick(bandCandidates(line, id, now), priceValue)
        val anchors = bandAnchors.getOrPut(line.id) { ConcurrentHashMap() }
        val values = bandDisplayValues.getOrPut(line.id) { ConcurrentHashMap() }
        // 记下本轮锚点：成员键供穿越时进冷却，数值供下一轮判断价格是否越过
        upper?.let { anchors[AlertCondition.ABOVE] = it.first } ?: anchors.remove(AlertCondition.ABOVE)
        lower?.let { anchors[AlertCondition.BELOW] = it.first } ?: anchors.remove(AlertCondition.BELOW)
        upper?.let { values[AlertCondition.ABOVE] = it.second } ?: values.remove(AlertCondition.ABOVE)
        lower?.let { values[AlertCondition.BELOW] = it.second } ?: values.remove(AlertCondition.BELOW)
        _bandDisplayLines.update { list ->
            list.filter { it.lineId != line.id } +
                if (upper == null && lower == null) emptyList()
                else listOf(BandAnchorDisplay(line.id, line.market, line.symbol, upper?.second, lower?.second))
        }
    }

    /** 让某一侧的当前锚点成员进冷却（「指标线」模式靠价格越过锚点判定穿越）。 */
    private fun coolBandMember(lineId: Long, condition: AlertCondition, now: Long) {
        val memberKey = bandAnchors[lineId]?.remove(condition) ?: return
        bandCooldownUntil[memberKey] = now + BAND_MEMBER_COOLDOWN_MS
    }

    /** 均线带的候选集合：冷却期外、能算出末根均线值的成员，键为可反查的冷却成员标识。 */
    private suspend fun bandCandidates(
        line: IndicatorLine,
        id: SymbolId,
        now: Long,
    ): List<Pair<String, Double>> = line.members.mapNotNull { member ->
        // 单个成员拉取/计算失败只少一个候选值，不影响其余
        runCatching {
            if (member.maPeriod <= 0) return@runCatching null
            val key = bandMemberKey(line.id, member)
            if ((bandCooldownUntil[key] ?: 0L) > now) return@runCatching null
            val page = market.klines(id, member.interval, member.maPeriod + 2)
            val closes = DoubleArray(page.klines.size) { page.klines[it].close.toDouble() }
            Indicators.latestMa(closes, member.maPeriod)?.let { key to it }
        }.getOrNull()
    }

    /** 挂/移/更新均线带在某一侧的规则；anchor 为 null 表示该侧无候选，规则退场。 */
    private suspend fun ensureBandRule(
        line: IndicatorLine,
        symbolId: SymbolId,
        condition: AlertCondition,
        anchor: Pair<String, Double>?,
    ) {
        val mine = alerts.rules().first()
            .filter { it.indicatorLineId == line.id && it.condition == condition }
        if (anchor == null) {
            mine.forEach { alerts.deleteRule(it.id) }
            bandAnchors[line.id]?.remove(condition)
            return
        }
        val (memberKey, value) = anchor
        val tick = market.instrument(symbolId).first()?.priceTickSize
        // 向外取整：写下的那一刻条件必然未满足，避免刚同步就误报一次穿越
        val rounding = if (condition == AlertCondition.ABOVE) RoundingMode.CEILING else RoundingMode.FLOOR
        val target = alignToTick(BigDecimal(value.toString()), tick, rounding)
        val name = "${line.symbol} ${line.label} ${condition.label}"
        val existing = mine.firstOrNull()
        mine.drop(1).forEach { alerts.deleteRule(it.id) }
        if (existing == null) {
            alerts.saveRule(
                AlertRule(
                    market = line.market,
                    symbol = line.symbol,
                    name = name,
                    condition = condition,
                    threshold = target,
                    repeatMode = AlertRepeatMode.EVERY_CROSS,
                    createdAt = System.currentTimeMillis(),
                    source = AlertRuleSource.INDICATOR,
                    indicatorLineId = line.id,
                ),
            )
            bandAnchors.getOrPut(line.id) { ConcurrentHashMap() }[condition] = memberKey
            return
        }
        // 先记锚点再改阈值：两者之间若有触发，冷却要记到旧成员头上
        bandAnchors.getOrPut(line.id) { ConcurrentHashMap() }[condition] = memberKey
        if (existing.name == name && existing.threshold?.compareTo(target) == 0) return
        alerts.saveRule(existing.copy(name = name, threshold = target))
    }

    private fun bandMemberKey(lineId: Long, member: LineMember) =
        "$lineId|${member.interval.storageKey}|${member.maPeriod}"

    /**
     * 把价格对齐到交易规则的最小变动单位，避免被目标价精度校验挡下。
     *
     * stripTrailingZeros 不是洁癖：有的接口把 tickSize 报成「0.001000000000」，
     * 乘回去会带出一串假精度小数位，直接污染阈值存储与推送报文。
     */
    private fun alignToTick(price: BigDecimal, tick: BigDecimal?, rounding: RoundingMode): BigDecimal {
        if (tick == null || tick.signum() == 0) return price
        return price.divide(tick, 0, rounding).multiply(tick).stripTrailingZeros()
    }

    /** 区间外条件下哪一侧越界由方向决定，消息里只记那一个阈值。 */
    private fun thresholdOf(rule: AlertRule, direction: AlertDirection): BigDecimal? = when {
        rule.threshold != null -> rule.threshold
        direction == AlertDirection.BELOW -> rule.rangeLower ?: rule.rangeUpper
        else -> rule.rangeUpper ?: rule.rangeLower
    }

    private suspend fun deliver(rule: AlertRule, message: AlertMessage): WebhookDelivery {
        val snapshot = settings.current()
        if (!snapshot.webhookEnabled) return WebhookDelivery.NONE
        val endpoints = webhooks.all().filter { endpoint ->
            endpoint.appliesTo(rule.id) && (rule.webhookIds.isEmpty() || endpoint.id in rule.webhookIds)
        }
        if (endpoints.isEmpty()) return WebhookDelivery.NONE
        val event = eventOf(message)
        var failures = 0
        endpoints.forEach { endpoint ->
            val urlProblem = WebhookTemplate.validateUrl(endpoint.url, snapshot.allowInsecureWebhook)
            val payload = WebhookTemplate.render(endpoint.effectiveTemplate, event)
            if (urlProblem != null || sender.post(endpoint.url, payload.text) != null) failures++
        }
        // 任一端点失败就整条记 FAILED：补发会重放全部端点，宁可重复推送也不静默漏掉
        return if (failures == 0) WebhookDelivery.SENT else WebhookDelivery.FAILED
    }

    private fun eventOf(message: AlertMessage): AlertEvent = AlertEvent(
        alertName = message.alertName,
        marketLabel = message.market.label,
        symbol = message.symbol,
        directionKey = message.direction.key,
        price = message.price,
        threshold = message.threshold,
        changePercent = message.changePercent,
        triggeredAtIso = AlertText.isoUtc(message.triggeredAt),
        timestampMs = message.triggeredAt,
    )

    /** 主动轮询：权重 1/次，远低于全量快照，且覆盖没有任何页面在刷新的标的。 */
    private suspend fun pollLoop() {
        while (true) {
            delay(pollIntervalMs())
            val targets = enabledRules.value.map { SymbolId(it.market, it.symbol) }.distinct()
            if (targets.isEmpty()) continue
            // 逐条串行：RateBudget 会排队，并发打满只会让整条链路一起等待
            targets.forEach { market.refreshTicker(it) }
        }
    }

    private suspend fun pollIntervalMs(): Long =
        settings.current().alertPollingSeconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS) * 1_000L

    private suspend fun retryLoop() {
        pruneOldMessages()
        while (true) {
            delay(RETRY_INTERVAL_MS)
            val pending = alerts.messagesWithDelivery(WebhookDelivery.FAILED, RETRY_BATCH)
            pending.forEach { message ->
                val rule = alerts.rule(message.ruleId)
                if (rule == null || !rule.enabled) {
                    // 规则已删除或停用，不再补发，避免僵尸重试
                    alerts.setDelivery(message.id, WebhookDelivery.NONE)
                    return@forEach
                }
                if (deliver(rule, message) == WebhookDelivery.SENT) alerts.setDelivery(message.id, WebhookDelivery.SENT)
            }
        }
    }

    private suspend fun pruneOldMessages() {
        runCatching { alerts.pruneMessages(System.currentTimeMillis() - MESSAGE_RETENTION_MS) }
    }

    /** 有启用规则时拉起前台服务保持进程；规则清空后立刻停掉，不长期占状态栏。 */
    private fun syncMonitorService(ruleCount: Int) {
        val intent = Intent(context, AlertMonitorService::class.java)
        if (ruleCount == 0) {
            context.stopService(intent)
            return
        }
        // 后台状态下启动前台服务会抛 IllegalStateException：规则已落库，不能因此崩掉进程
        runCatching { context.startForegroundService(intent) }
    }

    private companion object {
        /** 下限即为默认值：预警按 5 秒一轮检测。 */
        const val MIN_POLL_SECONDS = 5
        const val MAX_POLL_SECONDS = 600
        const val RETRY_INTERVAL_MS = 60_000L
        const val RETRY_BATCH = 10
        const val MESSAGE_RETENTION_MS = 30 * 24 * 60 * 60 * 1_000L

        /** 均线带成员被穿越后的静默时长。 */
        const val BAND_MEMBER_COOLDOWN_MS = 5 * 60_000L
    }
}
