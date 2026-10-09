# Android（android/，Compose → APK）

索引见 `MEMORY.md`。

## 环境
- 单测：`JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" ANDROID_HOME="C:/Users/sloan.wang/android-sdk"
  ./gradlew --no-daemon testDebugUnitTest`（**默认 Java 8 会失败** —— 那才是「gradlew 跑不起来」的真因）。
  基线 **284 全绿**（2026-10-09）。用户开着 IDEA 时 `android/app/build` 被占，改用 MEMORY.md 的
  init script 挪 buildDir（`-I ../.workbuddy/tmp/init-builddir.gradle --project-cache-dir ../.workbuddy/tmp/agcache`）。
- 设备 MuMu `emulator-5554`；坐标先 `uiautomator dump`（`screencap` 可能是上一帧）。
- MuMu 的 WebView 会被反复 kill ⇒ 白屏，图表才改自研 Compose Canvas。

## 设计系统
极简杂志风，Ink/Paper 灰阶 + 涨绿跌红，语义色走 `MarketTheme.colors`，尺寸全在 `Tokens.kt`。**改前先 grep 调用点**。

## 图表视窗
`ChartViewport` 只存 `visibleBars` + `rightOffset`（**`barCount` 是方法入参、不存字段**）；复位 = 重挂
`remember(symbolKey, interval.storageKey)`，别用 `LaunchedEffect` 事后对齐。`window()` 管量程、`plotRange()` 管像素
（含留白）；每帧位移不足一根要累积。Sparkline 别硬要求周期：读不到按 `cachedIntervalCounts` 挑最粗回退（表 `kline`）。

## 序列就绪 = (raw, rawBase) 这一对（2026-10-09）
`ChartData` 里 `raw`（基础周期蜡烛）**必须**与 `rawBase: CandleInterval?`（它归属的基础周期）成对更新。
三条推论，缺一条就出「一根柱子的图」或「假图」：
- `displayCandles` 只在 `rawBase == baseOf(selected)` 时聚合 —— 否则换周期会把上一段按新周期重新分桶；
- **实时增量对不上就丢**：`klineUpdate` 是 limit=2 的轻请求，必然快过首屏 500 根整页；
  不判 base 就会把这「先到的一根」当整段序列画出来（用户报的「先冒一根柱子、再跳出正确的图」）；
- 判「图上没东西」用 `loadingCandles = data.loading || (rawBase != base && error == null)`：
  空 `candles` 有三种（还在取 / 取失败 / 取到了是空的），只看 `loading` 会在换周期那一帧误报空态。
`loading = false` 由取数链独占（增量、翻页都别碰它）。`baseOf(selected)` 是唯一的取数周期口径
（官方即自身，自定义取最大整除官方周期），首屏/翻页/OI/增量四处共用。

## 加载态 = 空图表 + 遮罩（2026-10-09）
`ChartSkeleton(subCount, readoutLines)`（在 KlineChart.kt 内，与真实图共用 `ChartGeo` + `drawGridAndAxes`）
只画网格与副图分隔线；`DetailScreen.ChartLoading` = 骨架 + 55% `paper` 遮罩 + 转圈，竖屏/全屏两处共用。
❗骨架图**不能挂手势**：`barCount = 0` 时 pan/zoom 会把 `visibleBars` 夹成 1 写进持久化视角偏好。
❗读数带行数有两个取法（真实图按曲线标签数、骨架图按配置数），已抽成
`ChartSeries.mainReadoutLineCount()` / `mainReadoutLineCount(maPeriods, showBoll)`，等价性有单测
（ChartModelTest「读数带行数的配置口径与序列口径完全等价」）—— 分叉就会在数据到位时整图跳一格。

## 十字光标（0.1.16 批：松手保持 + 竖线日期标）
- ❗**长按拖动松手后十字光标保持**（用户要求「拖动到 K 线，松手后十字线应该保持展示，只有在画布上做
  其他操作才消失」）：手势循环里长按分支的松手段**不再清**（原来无条件 `onCrosshair(null)`，现行
  `if (!longPressActive) …`）。清除时机 = ①平移/缩放开始（`travelled > touchSlop`，或两指落下
  `pressed.size >= 2` 且非长按）②轻点（非长按的手势收尾）③进划线模式（`LaunchedEffect(alertLineMode)`）。
  换标的/周期由 `remember(symbolKey, interval.storageKey)` 重挂自然归零，不用管。
  ⚠️ 触发顺序不能动：长按激活要求 `travelled <= touchSlop`，所以「先判长按、再判位移清」是安全的。
- **竖线日期标 `CrosshairTimeBadge`**：横线的价格标 `CrosshairPriceBadge` 早就有（`PriceTag` 样式，右轴），
  这轮补竖线 —— 与时间轴刻度同带（`centerY = geo.plotHeightPx + geo.timeAxisHeightPx / 2`），文本
  `ChartModel.formatTime(openTime, interval.minutes)`；以竖线为心中再 `coerceIn(0, plotWidthPx - boxWidth)`
  夹进绘图区（贴边不切字）。排在 `TimeAxisLabels` 之后 ⇒ 实底压住同位的刻度文字。
  样式复用 `rememberPriceTagStyle()`（`colors.ink` 底 / `colors.paper` 字 / `Radius.xsShape`）。

## 测速 5 秒超时（0.1.16 批）
- ❗**探测请求 5 秒不响应即判超时**（用户口径，与桌面端同）：`BinanceMarketApi.probeFutures` 用
  `withTimeoutOrNull(PROBE_TIMEOUT_MS = 5_000L)` 包 `executeText`，到点抛 `SocketTimeoutException`
  ⇒ 设置页 `probeFailure` 折成「连接超时」。取消链：withTimeout → `awaitText` 的
  `invokeOnCancellation { cancel() }` → OkHttp `call.cancel()`。**只动探测**：数据请求仍是
  OkHttp 的 30s callTimeout（`ping()` 借道 probeFutures、会顺带吃到 5s —— 它当前无生产调用点）。

## 持仓量副图（OI，0.1.16 批）
- 口径与桌面端对齐（见 desktop.md「持仓量副图」）：永续专属，四家源（Gate / 币安系 / OKX / Bybit）
  有历史，值统一折美元名义值。数据层 = `MarketRepository.openInterest(id, baseMinutes, limit)` /
  `supportsOpenInterest(market)`；模型 `domain/model/OpenInterest.kt`（`OpenInterestSeries.baseCoin`
  标 Bybit 基础币口径）；方言规格 `FuturesDialectAdapter.openInterest`（`OpenInterestSpec`，
  **与 K 线梯形表分开的两张表**）。
- **取数周期 `oiBaseFor`**（`BinanceMarketApi.kt` 顶层 internal 函数，直接落单测）：不超过图周期的
  最大原生周期；全比图粗时取最细。⚠️ 请求基准用**基础周期**（`baseInterval().minutes`）而非展示周期 ——
  自定义周期下按基础口径取 500 根恰好覆盖整段原始窗口（官方周期两者相等，与桌面端一致）。
  网络失败 / 来源无 OI 在仓库层都归 null（副图静默缺席，不弹错）。
- **对齐**：`ChartSeries.alignOpenInterest` 前向填充（每根取 ≤ 开盘时间的最新采样、阶梯保持、
  开始前 NaN；Bybit 基础币口径乘该根收盘价折美元）。副图 = `SubPaneKind.OI("OI")` +
  `SubPaneData.compactAxis`（纵轴 K/M/B 缩写），readout `OI: 4.15B` —— ⚠️ App 用缩写 `OI`
  （与 VOL / MACD 同规），桌面端用「持仓量」做标签/读数，两端标签有意不一致。
- **ViewModel**：`oiSupported`（构造时定，只看接口能力）；`OI_POLL_MS = 10_000` 轮询，
  `combine(OI 是否选中, interval).distinctUntilChanged().flatMapLatest` —— 组合里带 interval 让换周期
  触发重取（只比 Boolean 会把换周期吞掉），进流先 `openInterest = null` 清旧序列（上一段的持仓量
  画在新 K 线上是错线）。
- **chip 置灰仍可点**（Android 没有 hover，桌面那套 title 提示够不着用户）：`toggleSubPane` 只挡
  「加上」不挡「撤下」——副图偏好全局一份，在合约页选过 OI 再到现货页必须撤得掉（否则是关不掉的
  死开关）；点击弹 `showNotice`「现货没有持仓量（永续专属）」/「当前接口没有持仓量历史
  （Gate / 币安系 / OKX / Bybit 有）」。
- **图表高度按有效块数**：`DetailUiState.subPaneCount`（选了 OI 但数据为 null 时那块不算）驱动
  `ChartArea` 的 `chartHeight`，否则会为不存在的块留一条空档；两处 `ChartModel.build` 的 `remember`
  key 都要带 `state.openInterest`。
- 单测：`oiBaseFor` 周期选取（DataRemoteTest）/ 四家 OI 方言解析（FuturesDialectAdaptersTest，
  含 Bybit 基础币标记与越界周期抛出）/ 对齐 + 出块 + 读数（ChartModelTest）/ `formatCompact(Double?)`
  （PriceFormatterTest）。本地无 JDK 只做静态核对，编译过不过看 CI。

## 告警模型：无冷却 + 默认「每次穿越」（2026-10-09）
用户口径：**图上划出的告警线默认每次穿越告警、不设冷却、每次穿越都推送**。冷却整体下线（连带
「重复提醒」模式 —— 它本就靠冷却定义），规则只剩「单次 / 每次穿越」，默认取每次穿越。
- 领域：`AlertRule.cooldownMinutes` 删；`AlertRepeatMode.REPEAT` 删、`fromKey` 未知 key 回落
  `EVERY_CROSS`（历史值 `repeat` 即此）；`AlertEvaluator` 去 `cooldownMs`/`inCooldown`，触发收敛为
  `satisfied && !fired && edge`。⚠️ `AlertState.lastTriggeredAt` **保留** —— 预警页「最近触发」在用。
- `LineAlertMode.ONCE` 一并下线（划线只剩 OFF / EVERY_CROSS），`fromKey("once")` 归一到 EVERY_CROSS
  （与迁移脚本同口径，免旧数据被静默降级成「不告警」）。⇒ `AlertEngine.retireIndicatorLine` 不可达已删，
  `LineAlertMode.repeatMode` 属性已删，`syncLineRule` 直接写 `AlertRepeatMode.EVERY_CROSS`。
- ❗**均线带「成员穿越后暂不进锚点池」不是告警冷却**，别一起删 —— 它防「刚穿过的均线贴价反复触发」。
  为免混淆本轮改名 cooldown→settle：`bandSettleUntil` / `BAND_MEMBER_SETTLE_MS` /
  `markBandMemberSettled` / `settleBandMember`。
- 设置项 `AppSettings.alertDefaultCooldownMinutes` 删（**从来没有 UI 入口**，只有 DataStore 读写的死配置），
  DataStore 的 `alert_cooldown_minutes` key 一并撤（老装机残留值成孤儿，无害）。
- Room v7→v8：minSdk 26 无 DROP COLUMN ⇒ `alert_rule` **整表重建**去掉 `cooldownMinutes`；存量
  `repeatMode` 一并归一：`repeat`→`every_cross`、`source='indicator'` 的 `once`→`every_cross`
  （手动规则的 `once` 原样保留，那是用户显式选的单次）；`indicator_line.alertMode='once'`→`'every_cross'`。
  ⚠️ 重建表的 `source` 必须带 `DEFAULT 'manual'`：Room 的 schema 校验对「实体声明了默认值、库里没有」
  是硬失败，带上 DEFAULT 则在两种情形下都安全。
- 迁移进不了单测（无 instrumentation）⇒ 另立**离线实证** `.workbuddy/tmp/verify_migration_7_8.py`
  （Python sqlite3：按 v7 建表 → 灌 4 种存量组合 → 跑迁移 SQL → 断言列已去/归一正确/id 与字段无损/
  重建后自增不倒退）。跑迁移这类改动值得照此办。

## 全屏看图版面（2026-10-09）
用户口径：**全屏（横屏）里「周期」与「指标」都常驻展示；全屏不展示副图**。
- 顶部两条固定窄带：上 = `IntervalSelector(compact=true)`（周期左端可横滚，右侧只剩「划线 / 退出」——
  **「指标」开关 chip 已删**，它原本只用于弹出底部浮层）；下 = `IndicatorBar(compact=true, showSubPanes=false)`。
- `IndicatorBar` 新增 `showSubPanes: Boolean = true`：false 时「分隔竖线 + 副图标签 + 5 个 chip」整组不画
  （全屏既然不画副图，留着能点却不生效的开关等于坏开关）；竖屏不传即老样子。
- ❗**全屏不画副图**的实现是「`ChartModel.build(subPanes = emptyList())`」，**不是**改 `state.subPanes`
  —— 副图选择是全局偏好（竖屏那份），全屏只影响这一帧；`remember` key 相应去掉 `state.subPanes`。
  退出全屏后竖屏照旧显示原来选中的副图。
- 原 `indicatorOpen` 状态与底部指标浮层整块删除（浮层压顶盖读数带、压底盖时间轴，都不如钉成图上方一条）。
- 分组标签统一叫**「指标」**（2026-10-09 用户口径）：竖屏 `SectionOverline("叠加指标")` 与全屏 `InlineLabel("叠加")`
  都改成 `"指标"`。只动显示文案，MA/BOLL 的自选逻辑与「副图 · 可多选」一行不变。
  ⚠️ 代码里其余「叠加」多是技术含义（主图叠加均线、双指缩放不许叠加），**不要一起改**。
- 两条窄带的**垂直留白用 `Spacing.Xxs`(4dp)**，不是竖屏的 `Sm`(12dp)（2026-10-09 用户反馈「周期和指标两行占屏太多」）：
  横屏只有 540dp 高，按 12dp 走时两行（含分隔线）吃掉 ≈92dp，占 17% 屏高。收紧后**行距 153px→105px（-31%）**，
  chip 本体 22dp 与点击区不变；竖屏走非 compact 分支，完全不受影响。
  ⚠️ 量这类高度别只看截图：同一设备两次 `uiautomator dump`，取「周期」「指标」两个 label 的 bounds 中心差，
  前后可比且能反推出理论值（实测比例 0.686 与「每行各省 16dp」的理论值完全吻合）。
- 验收：`compileDebugKotlin` + `testDebugUnitTest` **284/284 绿**；**MuMu 真机验收通过**（见下）。
  - 对照实验（这是关键，光看全屏不够）：先在竖屏**把 VOL 打开**（chip 变实心、图下出现 `VOL: 1579.399` 量柱副图）
    → 再进全屏 ⇒ **副图消失、顶部只剩「周期 / 划线 ✕」+「指标 MA…BOLL」两行**；退出全屏 VOL 仍在。
    同时 `uiautomator dump` 的全屏页里 `副图 / MACD / RSI / KDJ / VOL / OI` 命中数**全为 0**（非全屏页为 1）。
  - 底包：装 debug APK（`assembleDebug`，applicationId 与正式版同名 `com.waxilo.marketmonitor`、无 suffix，
    设备上原有 0.9.28 → `install -r` 覆盖成 0.9.36）。测完把误开/误触的状态还原（VOL 关、纵轴 `复位`）。

### ⚠️ MuMu 验收的五个坑（2026-10-09 实测）
1. **adb server 每次 Bash 调用都重启**（`* daemon not running` 反复出现）⇒ 命令里的**第一条 adb 会抢在设备就绪前
   执行**，报 `device offline`（`install`、`force-stop`、`uiautomator dump` 都栽过，且失败得很像「命令本身不对」）。
   解法：同一条命令开头先 `adb start-server >/dev/null; adb -s <S> wait-for-device`，再干正事。
2. **MSYS 会把设备路径 `/sdcard/x.xml` 翻译成 Windows 路径**（dump 输出变成
   `/C:/…/PortableGit/sdcard/x.xml`，`cat` 回来是 0 字节）。必须 `MSYS_NO_PATHCONV=1 adb … shell …`。
3. MuMu 同时挂两个入口（`emulator-5554` 与 `127.0.0.1:16384`）⇒ 不带 `-s` 就 `more than one device`。
4. **退出全屏后 MuMu 回竖屏有十几秒延迟**：`dumpsys` 立刻就是 `mCurrentAppOrientation=UNSPECIFIED` /
   `ROTATION_0`，但 `screencap` 仍是 1920×1080 —— **别据此判定方向没恢复**（应用侧是对的）。
5. ❗**在详情页做垂直滑动会被图表吃成纵轴刻度缩放**，页面根本不会滚（想滚下去找副图 chip，结果图被拉成
   1000~4000、冒出「刻度已缩放 · 复位」chip）。要滚页面必须从**非图表区域**（如周期行）起手。

## ⚠️ LazyColumn 的 key 锚定
给了 `key` 就按「第一个可见项」锚定：重排时（测速弹窗按延迟逐条回填）被锚定那条一旦排到后面，**视口跟着往下滚 ⇒
排第一的反而跑到屏幕上方**（用户反馈「我明明在最上面，排序后还得往上滑」）。修法：`rememberLazyListState()` +
重排时 `scrollToItem(0)`；但**单行「重测」别抢视口**（用户正盯着那行看结果，key 锚定恰好是他要的），用 `pinToTop`
标志区分批量与单行。排序规则抽成纯函数 `ui/settings/ProbeOrdering.kt`（+7 单测）。

## 发版
改 `versionCode`/`versionName` → 推裸版本号 tag → 出 APK + `.sha256`。**签名固定**（`cf2e20c7…52f034`），别换。
步骤与验收见 skill `android-gh-release-verify`；通道模型见 `MEMORY.md` 的「通道与发版」。
