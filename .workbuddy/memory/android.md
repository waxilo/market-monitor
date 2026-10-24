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
3. MuMu 同时挂两个入口（`emulator-5554` 与 `127.0.0.1:<adb_port>`）⇒ 不带 `-s` 就 `more than one device`。
   **端口别写死**：`MuMuManager.exe info -v 0` 的 `adb_port` 是多少就连多少（2026-10-09 是 16384，
   **2026-10-24 实测是 16385**）；TCP 那条通常可用，`emulator-5554` 经常 offline。连上后固定用 `-s` 全量指定。
4. **退出全屏后 MuMu 回竖屏有十几秒延迟**：`dumpsys` 立刻就是 `mCurrentAppOrientation=UNSPECIFIED` /
   `ROTATION_0`，但 `screencap` 仍是 1920×1080 —— **别据此判定方向没恢复**（应用侧是对的）。
5. ❗**在详情页做垂直滑动会被图表吃成纵轴刻度缩放**，页面根本不会滚（想滚下去找副图 chip，结果图被拉成
   1000~4000、冒出「刻度已缩放 · 复位」chip）。要滚页面必须从**非图表区域**（如周期行）起手。
6. ❗**要读库就直接拉着看，别猜 UI**：`adb exec-out "run-as com.waxilo.marketmonitor cat databases/market-monitor.db" > x.db`
   （**必须 `exec-out`**；`adb shell cat` 走 pty 会改换行，导出字节数对不上），WAL 模式还要连 `-wal` / `-shm`
   一起拉，Python `sqlite3` 打开才读得到最新提交。判断「线到底动没动」用它比截图硬得多。
   另外「复位」/自动量程**只框蜡烛不框线** —— 线被拖到远离行情区时，复位后它留在画面外是正常的，别当丢数据。

## ⚠️ LazyColumn 的 key 锚定
给了 `key` 就按「第一个可见项」锚定：重排时（测速弹窗按延迟逐条回填）被锚定那条一旦排到后面，**视口跟着往下滚 ⇒
排第一的反而跑到屏幕上方**（用户反馈「我明明在最上面，排序后还得往上滑」）。修法：`rememberLazyListState()` +
重排时 `scrollToItem(0)`；但**单行「重测」别抢视口**（用户正盯着那行看结果，key 锚定恰好是他要的），用 `pinToTop`
标志区分批量与单行。排序规则抽成纯函数 `ui/settings/ProbeOrdering.kt`（+7 单测）。

## 全屏划线：竖栏一副身子两种面孔（工具栏 5 枚 / 编辑栏 3 枚）· **2026-10-24 定版**

> **当前口径（2026-10-24，以此为准）**：点线 → **选中** → 右侧竖栏当场变成这条线的**编辑栏**
> （备注 / 删除 / 退出编辑）；没选中线时才是**工具栏**（锁 / 告警线 / 水平线 / 直线 / 清空）。
> **删除只剩这一条路**：`DrawTool.ERASE`（删除工具）整档删除、「拖到竖栏松手即删」整套撤掉、
> 竖栏**不再有常驻垃圾桶**。线还可以带**备注**（`note` 列，Room v11），备注与 `alert_rule.name`
> 必须分家。详见当日流水 `2026-10-24.md` 的同名小节。
> 下面标 `2026-10-14` 的段落是当年的设计（含已经作废的垃圾桶/ERASE），留着是因为其中的
> 手势定性、命中判定、竖栏尺寸那几条**现在仍然成立**；带【已撤】标记的段落只看教训、别照抄实现。

> 本节在 2026-10-12 版之上改了三处，都已就地更新：**竖栏由五枚变六枚**（新增最上面的锁按钮、
> 占位 30dp → 26dp）；**新增「解锁后长按线拖动 + 拖到竖栏变垃圾桶即删」**
> （2026-10-15 改口径：原「按下即拖」会误划拖走线，改为**按住满系统长按时长才转正**）；
> ~~**进入全屏默认锁定**~~ **2026-10-24 起默认不锁**，且「长按转正」整个废掉、改成
> **先单击选中再按住拖**（见文末「单击选中再拖动」一节）。下面标 `2026-10-14` 的就是当年那次改的。

`DrawTool = { NONE, ALERT, HORIZONTAL, TREND }`，单指手势一次只有一种语义。
`ERASE` **2026-10-24 已整档删除**（枚举值、`eraseHitAt`、`EraseHit`、手势里的 ERASE 分支全部移除）。

最后一枚「清空」**不进 `DrawTool`** —— 它不是一种模式，按一次就完事。
**2026-10-14**：锁**也不是** `DrawTool` 成员（它是「能不能动」的总开关，不是「用哪种工具」），
它单独占最上面一枚，用 `RailDivider` 与下面隔开。

**规格（2026-10-14）**：竖栏占位 **26dp**（含右留白 4dp）/ 按钮 24dp / 图形 12dp /
间距 4dp，常量在 `RAIL_BUTTON` / `RAIL_GLYPH`（图形一律手搓 Canvas，不拖进 `material-icons-extended`）。
图形线宽 `size.width * 0.11f`：12dp 画布在 3x 屏上是 36px，0.11 正好落 4px；沿用 0.10 落在 3.6px，
两侧被抗锯齿吃掉一圈会发虚。
❗**给 Glyph 加尺寸参数时别叫 `size`**：`Canvas` 作用域里的 `size` 是画布尺寸，同名参数会把它遮住，
里面每一行 `size.width` 都会报 `Function invocation 'width(...)' expected` ——
看着像缺扩展函数，其实是名字撞了。`TrashGlyph` 的参数因此叫 `glyphSize`（2026-10-14 实测，第一次编译就死在这）。
（2026-10-12 收窄时是 30dp 占位 / 五枚 ≈ 150dp；加了锁按钮后为 26dp / 六枚 ≈ 180dp。）

**❗最关键的一条：竖栏不许 `fillMaxHeight()`**。原先 `.width(34.dp).fillMaxHeight().background(wash)`
会在屏幕右缘拉出一条 **102px 宽 × 1080px 高**的整条灰带 —— 用户连着两轮说「太大」，指的就是它，
按钮本身一直很小。改成跟着内容走（六枚 ≈ 180dp）+ `Radius.fullShape` 胶囊底，占位面积降到 1/3。
判定「大不大」要**按像素量**，别只看 dp 数字：实测改造前 102×1080、改造后 78×443（含 4dp 右留白共 90px 宽）。

**【2026-10-24 已撤】2026-10-14：竖栏曾是「拖线时的垃圾桶落点」**。`FullscreenChart` 里
`val draggingLine = alertLines.any { it.dragging } || drawLines.any { it.dragging }`，
为真时在 `DrawToolRail` 的 Box 里叠一层 `matchParentSize()` + `colors.down` + 一只放大到
`RAIL_BUTTON * 0.62f` 的 `TrashGlyph(colors.paper, …)`。
- 盖在**同一块几何**上而不是替换内容：拖动途中落点不能自己动，尺寸位置必须与松手前逐像素一致，
  否则「看见落点、拖过去」会扑空。`matchParentSize` 取的是父 Box 的**内容**区（已扣掉 padding），
  正好等于胶囊本身。
- 「正在拖」的唯一事实来源是线自己的 `dragging` 标记（`alertLines` 的 `alertDrag` 分支、
  `drawLines` 的 `drawDraft` 分支各置一次），**不另报一个布尔** —— 布尔在异常路径
  （换周期/旋屏/组合销毁）漏一次收尾，竖栏就永久变成垃圾桶。
  ⚠️ **2026-10-15 起轻点线什么都不发生**：解锁态抓线改为**挂起**——down 就近判「抓哪根」，
  但按住满系统长按时长且没滑出 slop 才转正开始拖；滑走/落第二指即作废，这场照常平移缩放。
  ⚠️ **2026-10-24 起整套「长按转正」作废**：改成「先单击选中，再按住拖」（长按还给十字光标，
  见文末那节）。下面这段「轻点什么都不发生」只描述 10-15 ~ 10-23 的行为。
  挂起期间**不发任何拖线回调** ⇒ 轻点不再「垃圾桶亮一下再落库」，旧版那笔「轻点亮一下」
  的取舍连同原因一起作废。拖到竖栏垃圾桶=先长按转正、再拖过去松手。
  与长按十字光标共用「时长 + slop」判定：按在线上转正拖线、按在空处出十字光标。
- 落点判据用**原始** x（`lastRawX`）：循环里逐帧那份 x 已 `coerceIn(0f, plotWidthPx)`，
  永远到不了竖栏所在的画布右缘之外，拿它判恒为假。`atDropTarget = { x -> lineDropToTrailingRail && x >= canvasSize.width }`，
  竖栏正好从画布右缘开始（Row 里分出来的一栏）。

**⚠️ 24dp 按钮的触控区是被 Compose 撑开的，会**互相重叠**（实测）**：pitch 只有 28dp（=84px），
在 4dp 间距正中（y=324，上一枚下沿 318、下一枚上沿 330）点一下，落到的是**下面那一枚**
（选中态出现在 330–401）。所以「点偏一点」会选错工具 —— 可接受的原因有二：选中的按钮有实心反色、
底部提示条会报出当前工具名，用户看得见也能改。想彻底避免就得把间距拉到 ≥8dp 或按钮回到 30dp，
那是拿宽度换精度，别再无声地改回去。

**删线的粒度（2026-10-24 定版）：只有一条主路 + 一个清空**。
① **点线选中 → 右侧竖栏（编辑栏）「删除」**，一次一条，删完由调用方把 `selectedLine` 清空
（线已不存在，图表收不到通知，会一直举着这个 ref）；
② **清空**一次删光本标的的全部划线。清空口径与 `alertLines` 严格对齐（`threshold != null` 的规则），
于是手动在预警页建的价位预警也会被一起清 —— 这是有意的，否则会出现「清空了但还剩一条线」。
~~③ 竖栏「删除」工具（`DrawTool.ERASE`）点线即删~~ 与 ~~④ 长按转正后拖到竖栏松手即删~~
都在 2026-10-24 撤掉了：删除曾经有三条路，每条都要求用户先记住「删除住在哪」，
而删除的对象恰恰是眼前这条线 ⇒ 改成「选中即进编辑栏」。**别再把人请回来。**

**❗2026-10-12 晚曾撤掉「把线拖到画布右上角的垃圾桶落点」**（用户口径：「拖线时那个小垃圾桶
去掉，竖栏已经能删了」）。撤的范围当时是**整条**：渲染层那个 `showTrash` Box、
命中矩形 `alertDeleteRect()`、悬停高亮 `alertTrashHot`、`CORNER_BUTTON_INSET` 之外那三个
`TRASH_*`/`SPACING_TRASH_INSET` 常量、手势里的 `overTrash` 判定与 `deleteRect()`/`onOverTrash()`/
`onHorizontalDelete()` 三个参数、以及 `Icons.Default.Delete`/`Icon`/`Rect` 三个 import —— 全删。
留下的 `onAlertLineDelete`/`onDrawLineDelete` 改由 ERASE 那条路喂
（`onEraseAlertLine`/`onEraseDrawLine`），语义收敛成「按 id 删」。

**2026-10-14 用户又要求加回拖线删除，但换了落点**：不再是小垃圾桶，而是**整条竖栏**，
且只在**拖线途中**才变成垃圾桶。这是上一次失败的根因所在 —— 那个落点**平时不存在**、要靠猜；
这次落点拖起来看得见（整栏高亮成 `down` 色），才敢拖。教训有两条：
① 一套交互有第二实现时，删要比加想得多（当时垃圾桶还牵着底部提示文案里的「拖到右上角垃圾桶删除」）；
② 撤掉一条路之后**手头那份 `onHorizontalDelete` 的 `LineRef` 抽象别一起丢** ——
这次重加正是把它捡回来（`LineRef.Alert(ruleId)` / `LineRef.Guide(lineId)`，见下）。

**清空的实现分两拨**（存储上本来就不是一回事，别硬凑成一个 delete）：
告警线由规则派生 ⇒ 逐条 `alerts.deleteRule`；水平线/直线在 `chart_line` ⇒ 新增
`ChartLineDao.deleteAll(market, symbol)` 一条语句删净（逐条删中途失败会留下半清空的图）。
`clearAllLines()` 再把 `drawDraft` 与 `alertDrag` 清掉（手上那笔还没落库的）。
**必须先弹确认框**（`ClearAllLinesDialog`）：清空连带走预警规则、不可撤销，而这一下是闭着眼扫掉一整张图；
框里报两类线的条数（条数只有在 `FullscreenChart` 里同时拿得到 `alertLines` 与 `chartLines` 时才算得准，
所以是调用方算好传进去）。没有可清的线时这一枚**置灰**（`enabled=false` → `hairlineStrong`）。
`DrawToolButton` 因此多了 `enabled` 参数（默认 true）。

**清空的图形**：不用第二只垃圾桶（会与「删一条」那只混淆），画的是**内容** ——
三条横线 + 一道贯穿斜杠（`ClearAllGlyph`），形状自己说明「这些线全没了」。

**设备实测（MuMu，1080×1920/480dpi，横屏 640×360dp）**：
空图时清空图形最暗像素 (211,211,206)=`hairlineStrong`（置灰）→ 画 2 水平线+1 直线后变
(109,115,128)=`muted`（可用）→ 点清空弹框文案「1 条告警线（对应的预警规则一并删除）、3 条水平线 / 直线」
→ 确认后 `chart_line` 0 条、`alert_rule` 里**只**少了 BTCUSDT 那条（2 条 ETHUSDT 原样保留，证明按标的作用域对）
→ 图形回到置灰。取消路径单独验过：画 1 条 → 清空 → 取消 → `chart_line` 仍为 1。

**分组**：前三枚（画什么）与后两枚（怎么撤）之间隔一条 12×1dp 的 `hairline`；
不隔开的话五枚圆钮看着像一个集合里的五种线型。未选中图形用 `muted` 不用 `ink`
（三根纯黑粗条曾是全屏最重的墨迹）。

**【已撤】`DrawTool.ERASE` 的两条设计约束**（工具本身 2026-10-24 删了，留着看约束思路）：
- 可点的都必须看得见 ⇒ `visibleDrawLines` 在 ERASE 下取**全部**（不看同型过滤、也不看那只眼睛），
  `alertLinesShown` 强制为真。藏起来一半等于「明明有这条线却删不掉」。
- 它不拖线 ⇒ 右上角不再有任何落点（那条路整条已撤，见上）。

**命中判定 `eraseHitAt(point)`（函数 2026-10-24 已随 ERASE 删除，判据思路沿用）**：告警线 / 水平线 / 直线**放在一起比距离**取最近的一条。
分类各判一次再按优先级挑会删错（水平线与告警线只差一个价位、直线能从任何东西上穿过）。
距离口径随线型：水平类是 `|y - point.y|`，直线复用 `closestTrend`（点到**线段**）。
容差沿用拖拽抓取半径 `ALERT_GRAB_WIDTH`(24dp)。
`EraseHit` 分 `Alert(ruleId)` / `Draw(lineId)` 两型 —— 告警线删的是**规则**（顺带通知配置），
画线删的是 `chart_line` 一行，两张表两条回调，混成一个 id 空间迟早删错。
（这套两型分流现在由 `LineRef.Alert/Guide` 承担，删除走 `viewModel.deleteAlertLine/deleteDrawLine`。）

**手势接线（这次的真 bug，务必别再踩）**：所有「按下命中什么」的判定都必须在 **down 那一刻**
用 `first.position` 定死。`awaitFirstDown()` 已经把 down 事件消费掉，循环里第一帧
`awaitPointerEvent()` 拿到的是**第一个 MOVE**；手指一动就滑出抓取半径，
「按住已有的线拖走」会被判成「新建一根」⇒ 表象就是「划的线无法删除」。
（直线工具从一开始就是 down 定性，所以它没这个毛病 —— 水平/告警线是这次补上的。）
删除动作放在**松手后**（2026-10-24 起删除是竖栏按钮，这条只剩「松手才选中」那半仍然成立）。
~~ERASE 分支里 `(raw - first.position).getDistance() > touchSlop` 就作废命中~~
（ERASE 已删；同一条「滑出 slop 即作废」的思路现在用在「单击选中」上）。

**别的删除入口**：没有了。删线只有「选中 → 编辑栏删除」与「清空」这两处（2026-10-24 定版）。

**单击也要落线（2026-10-12 用户反馈后补）**：光有上面的 down 定性还不够 —— 轻点（down→up、
中间没有 MOVE）时循环首帧 `pressed` 已空，而 `break` 排在**循环体之前**，循环体一次都不进，
首帧落点根本发不出来，表象变成「单击不划线、非得拖一下」。修法：ALERT/HORIZONTAL 在 down
定性之后**立刻补发一帧 `onHorizontalDrag(grabbedId, y)` 并把 `dragActive` 一起置真**
（y 同样夹进主图区），与循环里那帧同源，于是「点一下」与「点一下再拖」落点语义完全一致。
`dragActive` 的语义随之从「拖过」改成「这场落到线上」，不再拿它当防误触闸门。
对照实测（同一个模拟器，先旧包后新包）：旧包单击 → `chart_line` 仍 0；新包单击 → 落 1 行，
价位与 `input tap` 的 y 经刻度线性换算吻合（y=600 → 83754.20）。
`README.md` 也补了「Android 端画线交互」一节（与桌面端交互约定同体例）。

## 划线锁（`linesLocked`）：2026-10-14 新增，并在真机验收里抓到一个真 bug

竖栏最上面一枚，`rememberSaveable { mutableStateOf(false) }`（**2026-10-24 起进全屏默认不锁**；
此前是 true。旋屏重建不丢锁态）。
`linesEditable = !linesLocked`、`lineDropToTrailingRail = !linesLocked` 两个参数传给 `KlineChart`；
锁定态 `drawTool` 一律收成 `NONE`（手上举着工具时上锁，单指会被划线通道吃掉却画不出东西，看着像图卡住）。
口径（用户原话）：锁 = 不能拖已有线 + **新建也一起锁**；解锁后**不必先选工具**，按住线即可拖。
图形 `LockGlyph`：锁体梯形（与垃圾桶同轮廓语言），锁梁两腿 `legBottom = if (locked) h*0.54f else h*0.38f`
—— **插进去 = 已锁，拔出来 = 解锁**；不用 `drawArc`（要引 `Size`，且与其它 Glyph 的直线语言不一致）。
锁定态点置灰的线型按钮 → `notifyLinesLocked()` 弹「已锁定，图上划线全部只读 · 点竖栏顶部的锁解锁」；
锁定态不摆常驻提示条（`toolHint` 第一分支直接返回 `null`）。

### ❗❗`pointerInput` 的 key 必须包含闭包捕获的普通参数（真机对照实测）

- **表象**：上锁 → 竖拖 60px，`chart_line.id=21.price` 从 `83312.04` 变 `83335.64`。
  UI 侧（提示条消失、线型按钮置灰）已经摆出「锁了」的样子，**手势侧还拿着 `linesEditable = true`**，
  而且只在最常用的「解锁 → 看 → 上锁」路径上出事。
- **根因**：`pointerInput(drawTool, symbolKey, interval.storageKey)` 的 key 里**没有**
  `linesEditable` / `lineDropToTrailingRail`。上锁时 `drawTool` 本来就是 `NONE`（工具本收着）、
  `symbolKey`/`interval` 不变 ⇒ **key 不变、协程不重启**，闭包继续用上锁前那份 `true`。
  同根因还有第二处：`horizontalAnchors` lambda 里 `DrawTool.NONE -> if (linesEditable) alertAnchors() + hAnchors() else emptyList()`，
  它也是每次组合新建、**按值**捕获 `linesEditable` 的普通 lambda，被同一个永不重启的协程拿着。
- **修法**：key 补成 `.pointerInput(drawTool, linesEditable, lineDropToTrailingRail, symbolKey, interval.storageKey)`。
- **为什么能放心重启**：这两个开关只由竖栏按钮的点击改变，点按钮时手上不可能同时按在图上，不会打断进行中的手势。
  反过来 `liveGeo` / `liveBarCount` / `liveAutoRange` 这些**会在手势进行中被数据更新改掉**的量**绝不能**进 key
  （进去就重启、拖动半途断掉），它们走 `rememberUpdatedState`。
- **可推广的判据**：闭包里读的东西分两种 —— ① `by remember { mutableStateOf(...) }` 这类**委托对象**
  （捕获的是状态对象，读的是实时值 ✓）；② 组合期算出来的**普通 `val` / 参数**（按值快照 ✗，必须进 key）。
  已核过另两处 `pointerInput` 属于①，无需改：`DetailScreen` 的行拖拽排序（`rows` 是 `mutableStateOf` 委托）、
  `MarketScreen` 的下拉刷新（`onGestureEnd` 只读状态持有者、`thresholdPx` 稳定）。

### 该环境注入拖动的坑（下次验收直接用）

- `input swipe` / `input motionevent` 都**能**注入有效手势（横向平移验过：时间轴 `03:52 → 04:06`）。
  但**竖向往上拖会被绘图区顶边夹住**：`y` 在 down 与每帧都 `coerceIn(plotTopPx, …)`，
  而这台机 `plotTopPx = readoutHeightPx ≈ 347px`（读数带很高）⇒ 「往上拖」看着像不跟手，**换向下拖**即可。
- **中途截图法**：`( adb shell input swipe … ) & sleep 1.6; adb shell screencap`，
  能拍到竖栏变红的垃圾桶覆盖层 —— 这是「确实抓到了线（草稿 `dragging`）」的直接证据，
  比只看松手后的库更早定性。
- **判别锁定的实验口径**（只用「按下 + 抬起」就够，不依赖位移帧）：
  在**离线 30px** 处按下（仍在 `ALERT_GRAB_WIDTH` = 24dp = 72px 半径内），向下拖 60px。
  解锁 → 抓住线并把价位提交到**终点**；锁定 → 线纹丝不动，手势转成**价格轴平移**。
  实测三组：A 解锁 `83254.57 → 83241.93`；B 锁定 `83241.93 → 83241.93`（轴整体下移 47px）；
  C 再解锁 `83241.93 → 83229.87`。
- 竖栏各按钮的真实像素中心可以用像素聚类量（`.workbuddy/tmp/rail_probe.py`）：
  横屏 1920×1080 下**工具栏（5 枚）**锁 y=276、告警线 384、水平线 468、直线 556、清空 649；
  **编辑栏（3 枚，选中线之后）**锁 276、备注 384、删除 468、✕ 552。**x 一律 1865~1881**。
  （2026-10-24 实测；旧表里「垃圾桶 648 / 清空 732」是六枚版，已作废。）

## 划线工具 = 只新建 + 纯净画布 + 直线十字辅助线（2026-10-14 下午，用户五条需求的 3/4/5）

- **工具模式下不再抓旧线**：编辑已归解锁态「长按拖动」，工具再抓线只会与预期打架。
  实现是两处同时收：`horizontalAnchors` 只在 `NONE + linesEditable` 给线（工具下 `emptyList`，
  天然没有抓取来源）；TREND 分支删掉 `closestTrend` 平移（`TrendMode.MOVE` 只剩解锁态一条路）。
- **纯净画布**：选中工具那一刻已划的线全部让位 —— `visibleAlertLines` / `visibleDrawLines`
  只留 `dragging` 的那笔草稿（草稿由 ViewModel 的 `alertDrag`/`drawDraft` 合并进列表，
  `dragging=true` 是「手上这笔」的唯一标记）。ERASE 例外：全画，能点的必须看得见。
  原「水平/直线工具只画同型线」的规则随抓取一起废掉。
- **直线（TREND）两敲带十字辅助线**：竖线+横线虚线（`trendCrosshair`，与 `trendSeed` 同 key 的
  `remember` 态）。**自由像素、不吸附蜡烛**。PLACE2 场预览线从种子连到十字交点。
  与长按十字光标语义相反：那个「松手停住」留读数，这个随工具走。
- ❗**准星交互二稿（同日晚用户追加）**：选中工具**准星就出现在图中央待命**（`null` =
  画 `plotWidth/2, mainTop+mainHeight/2`；有种子时停在种子点），用户**拖准星、松手定点**，
  再重复一次成线 ⇒ 种子**不在 down 落**、改在收尾 SEED 分支落（`trendAnchorAt(trendLast)`）；
  准星**松手不清**，留原地等下一次拖动，随 drawTool 变化由 remember key 作废。
  SEED 拖到垃圾桶 = 取消（种子还没落，正好不用清）。轻点仍可用：down 把准星拉到手指处 +
  收尾按 trendLast 落种子。**坑**：种子落点在 down 改成收尾时，「轻点无 MOVE 帧」的旧注释
  逻辑要跟着搬 —— down 就位 + 收尾定点两条腿缺一不可。
- ❗**准星交互三稿（同日稍晚）**：「点击屏幕准心直接移到点击位置」也不对 —— **按下不传送，
  抓着拖**：down 只记基准（`onTrendCrosshairGet()` 读当前准星，null 时实体化成待命位；
  `trendCrosshairBase` + `trendDragFrom`），MOVE 每帧 准星 = 基准 + 手指位移（再夹进绘图区），
  SEED/PLACE2 两分支同款。轻点 = 不拖直接松手 = 确认准星当前位置（第二敲轻点即与种子重合
  → 走「太近=撤销」，天然自洽）。**真机数值闭环**：中央 (828,683) + 位移 (200,100)，
  种子实测 (1028,783)，而手指终点在 (700,900) —— 传送与抓拖两种行为可精确区分。
- **提示文案跟着改**：ALERT/HORIZONTAL 删掉「按住可改价位」（工具下已不可能）；
  TREND 终稿「拖动图中准星，松手定第一点 · 再拖一次松手连成线（太近=撤销）」。
- 真机冒烟（MuMu）：选中工具不碰屏截图证实准星在图中央；第一敲松手 `chart_line` 不变
  （种子是内部态）；第二敲成交落库 `kind=trend`；清空复原。单测 301/301。

## 均线目录：整份交给用户（2026-10-12 晚）

用户口径：「（管理弹窗）自定义均线现在只能新增，要能删掉原有的，比如 MA5」。⇒ 把「预置五档写死 +
自定义档另存」重构成**一整份可编辑目录**，与桌面端 `MaPanel` / `DEFAULT_MA_CATALOG` 同口径。

- 纯函数在 `ui/detail/DetailViewModel.kt`：`normalizeMaCatalog`（范围/去重/升序/截断）、
  `maSelectionIn`、`MA_LIMIT = 8`。出厂目录搬到 `domain/repository/AppSettings.kt` 的
  `DEFAULT_MA_CATALOG`（原 `MA_CHOICES` 删掉）—— 数据层的升级迁移也要用它，放 domain 才不跨层。
- 持久化 `AppSettings.maCatalog: List<Int>?`：**null = 没设过（用出厂五档）、空表 = 用户真的清空了**。
  DataStore 新 key `ma_catalog`；老 key `ma_custom_periods` 变**只读迁移**（老值里只有自定义档 ⇒
  取 `DEFAULT_MA_CATALOG + 老值`；不补五档的话升级后指标条上五档凭空消失）。写时 null ⇒ `prefs.remove(key)`。
  ❗**有意不采用桌面那条「空目录回退出厂五档」**：桌面从 localStorage 判不出「没设过」与「清空了」，
  Android 用 null 判得出；回填会让「删光了」在重启后自己变回来。
- 不变量**「选中 ⊆ 目录」只有一个执行点** `maSelectionIn`（恢复偏好 + 保存目录两条写入路径共用）。
  漏一次就是「图上画着一条、指标条上没有 chip 可撤」的死线。删 MA5 时选中集里的 5 也靠它一起走。
- `MaManagerDialog` 只管「有哪些档位」（选不选仍照旧在条上点），行尾 × 移除、底部「恢复默认」，
  仍旧是草稿 + 保存（同 `IntervalManagerDialog`）；`IndicatorBar`/`IndicatorLineDialog` 的参数
  `maChoices` 一律改名 `maCatalog`。
- `MA_LIMIT` 从「自定义 ≤ 8」改成「**目录总数 ≤ 8**」（桌面同值）⇒ 老装机 5+8=13 条的下次保存会被截到 8。
- 单测 `ui/detail/MaCatalogTest.kt`（8 条）：越界/去重/升序/截断/空表不回填 + 删 MA5 带走选中。
  本机 **301 全绿**（2026-10-12，`testDebugUnitTest`，跑法同上面「环境」那节）。

## MA / EMA 两族 + 两级显隐（0.9.48，2026-10-22）

用户口径：「支持 ema 均线配置查看 / 自定义级别 / 收敛所有 ma、ema / 主页只展示 MA、EMA / 每次点 MA 展示配置的所有均线」
→ 再补一句「设置均线时可以单独设置小眼睛」。落地成**两层模型 + 两级显隐**。

- **族模型** `domain/indicator/MaFamily.kt`：`MaFamily { MA(“简单均线”), EMA(“指数均线”) }`、
  `defaultCatalog(family)`（MA 5/10/20/30/60、EMA 12/26/50）。`DEFAULT_MA_CATALOG` 从 `AppSettings` **搬到这里**
  （与 10-12 那次同理：数据层迁移也要用，放 domain 不跨层）。
- **目录就是「画什么」**：原先「目录 + 选中子集」两层并成一层，`maSelectionIn` / `toggleMaPeriod` /
  `saveMaCatalog` 全删。增删一条即图上的增减，不存在「配了但没画」。
- **两级显隐（正交，互不覆盖）**：`maVisible/emaVisible` = 指标条族 chip（总闸）；`maMuted/emaMuted` = 面板里
  每行行尾的小眼睛（分闸）。`visiblePeriods(family)` = 族藏 ? 空表 : `catalog − muted`。
  ❗**关族再开不能重置 muted** —— 总闸只读不改分闸，这是用户最在意的语义。
  ❗不变量 `muted ⊆ catalog`：删行时 `normalizeMuted` 一并剔除，否则「删了再加回来」会加了却不显示。
- **色槽分族**：MA 用 0~4/8~10、BOLL 用 5~7、EMA 新增 11~18。`LineRole` 里 `EMA1..EMA8` 必须插在
  `UP/DOWN/LABEL` **之前**（颜色按下标取，`ChartLineColors` 同序加了 8 个）。
  `maRoleForSlot(family, slot)` 越界 `coerceIn(0, roles.lastIndex)`（钳到最后一色，不是回第 0 色）。
- **读数带按族分行**：`mainReadoutGroups(index, decimals)`，分组用 `readoutFamilyOf(role)` 按**角色**判，
  不靠标签前缀（`EMA12` 与 `MA12` 前缀能骗人）。
- **持久化**：新 key `ma_visible/ma_catalog/ma_colors/ma_muted`（EMA 同构四枚）；老 key `ma_periods`/`ma_hidden`/
  `ma_custom_periods` **只读用于迁移**（当时画着的那几条升为新目录，观感不变）。写侧不再写 `ma_periods`。
- `saveCatalog(family, next, colors, muted)` 多一个参数；`IndicatorBar` 两枚族 chip 的**置灰判据 = 能画的条数
  （catalog − muted）**，不是目录条数（5 条全闭眼时点亮也画不出线）。
- `EyeGlyph` 从 `KlineChart.kt` 的私有函数抽到 `ui/common/EyeGlyph.kt`；参数叫 `glyphSize` 不叫 `size`
  —— `DrawScope.size` 同名会让 Canvas lambda 里的解析含糊。
- 测试：`MaCatalogTest`（归一 / 画=目录−闭眼 / 翻眼睛可逆 / 全隐全显）、`ChartModelTest`（三族行数、
  `mainReadoutGroups` 顺序、EMA 色位不撞、**SMA≠EMA 必须用锯齿价**——线性序列上两者恒等，断言会假绿）。


## 翻页加载（loadMore）的两个坑（2026-10-14 修，用户症状「切换周期后就不加载了」）

两个**互相独立**的缺陷叠在一起，任一都足以让翻页整段失效：

1. **「贴到最老一根」的判据**：原来是 `startIndex(barCount) == 0`，改成新增的
   `ChartViewport.atOldestEdge(barCount) = barCount > 0 && startIndex(barCount) <= 0`。
   `startIndex` 是 `floor(barCount + rightOffset - visibleBars)` —— **三个浮点量相减再取整**。
   `clamp` 把 `rightOffset` 夹到最小值的那个瞬间本应得 0，但 `visibleBars` 只要不是整数
   （双指缩放后必然如此），舍入会让结果落在 0 左侧一丁点（实测 `raw = -3.814697265625e-06`），
   `floor` 给出 **-1** ⇒ `== 0` 永远不成立。默认 `60f` 时算式精确、恰好命中 0，旧写法看着能用纯属巧合。
   序列比窗口窄时（1M 只有 13 根）`rightOffset` 夹成 0、左端停在 **-47**，同样永远不成立。
2. **去重标记的生命周期**：`oldestRequested` 原挂在画布 `remember`（**不带 key**），
   图那侧不知道序列换过没有，标记「用掉」就不放行 ⇒ 症状正是「换过周期之后再也翻不动」。
   搬进 `DetailViewModel.ChartData.oldestRequested`，与序列**同生共死**（`loadBase` 两条分支都归零）。

配套：`loadMore()` 的闸门全在**一次快照**上判（`loadingMore` / `rawBase == base` / `hasMore` /
`oldestRequested`），任何一条不满足都**不写任何状态** —— 旧写法「先写状态再返回」会把
「这次没请求成」记成「已经请求过」，一次误记就永久堵死；先落标记再起协程，同一帧的重复触发当场挡掉；
回写前**再校验一次** `rawBase == base`，在途期间换过的周期整页丢掉（否则旧周期的基础蜡烛会混进 raw）。

回归 `ui/chart/ChartViewportTest`（5 条，含 -1 / -47 这两格证据）。
另注：`visibleBars = 57.37f` 这组输入的期望值是用 float32 复算钉住的
（`tmp/f32check.py`）—— 手算 double 会得到 0，正是这个 bug 骗过人的地方。

## 行情源：Bitunix 下线（2026-10-14，两端同）

用户症状「K 线不一致但价格一致」，第一直觉是时区 —— **证伪了**：逐根比对 1m…1M 与 Gate / OKX，
时间戳完全对齐（都是 UTC 毫秒）。真因是它的 REST K 线**只返回已收盘蜡烛**：
`startTime` 被服务端忽略、`type=LAST_PRICE|MARK_PRICE` 也一样只回已收盘、`endTime = now + 1d` 直接回空
⇒ 图上最后一根永远落后一个周期。客户端不合成当前蜡烛，于是**删源**（用户拍板「删除这个 api」+
范围「两端一起删」）。

- Android 候选 **10 → 9**：`FuturesEndpoint` 删枚举项与 `ALL` 条目；
  `data/remote/dialect/FuturesDialects.kt`（1289 → 1168 行）删分派 / `checkBitunix()` /
  文件末尾整个 `BitunixDialect`；`FuturesDialectAdaptersTest` 54 → 47 条（删 7 个用例 + 4 份夹具）。
- 桌面端 12 → 9：`src/lib/sources.ts` 删条目、`src/lib/dialects.ts`（1343 → 1230 行）删
  `| 'BITUNIX'` / `checkBitunix()` / `const bitunix = (() => …)()` 整块。
- 文档同步：两份 README、`basedata/2026-09-29_行情来源独立性与可接入候选.md`（原文保留 + 加变更说明）。
- 教训：**「K 线不一致但价格一致」这类症状先怀疑「最后一根怎么来的」**（是否含进行中蜡烛、
  是否客户端合成），而不是先怀疑时区 —— 时间戳对齐是最容易验的一步，验完就该换方向。

## 方言层：周期码是**大小写敏感的白名单**（2026-10-22，与桌面同）

`data/remote/dialect/FuturesDialects.kt` 的 `BitgetDialect` 原先把 `granularity` 写成小写
`1h/4h/6h/12h/1d/3d/1w` ⇒ **小时及以上全被拒**，服务端回
`HTTP 200 + {"code":"400171","msg":"Parameter verification failed k-line time range should be [...]"}`
（白名单原文：`[1m,3m,5m,15m,30m,1H,4H,6H,12H,1D,1W,1M,6Hutc,12Hutc,1Dutc,3Dutc,1Wutc,1Mutc]`）。
⇒ 症状极具欺骗性：分钟档一直好、小时档从来没成功过，自定义 16 时（4H 打底聚合）必然空图。

- ❗**周期码是枚举字面量，不是参数**：写错大小写不报参数错，而是 200 + 业务码，客户端只看到「没数据」。
  ⚠️ 口诀：**同一家源「分钟档正常、小时档全空」= 先查周期码大小写**，别先查网络/代理/CORS。
- ✅ 现 ladder（**与桌面 `src/lib/dialects.ts` 的 bitget 逐项一致，改一边必须改另一边**）：
  `1m/3m/5m/15m/30m` + `60:'1H' 240:'4H' 360:'6Hutc' 720:'12Hutc' 1440:'1Dutc' 4320:'3Dutc'
  10080:'1Wutc' 43200:'1Mutc'`。`120` 无原生码已删（走 1H 聚合），`4320` 用原生 `3Dutc`。
- ❗**粗档取 `utc` 变体**：Bitget 非 utc 档按 UTC+8 对齐（`1D` 起点 `16:00Z`），而分桶是 `floor(ts/周期)`
  ⇒ 只有 UTC 边界才是整数倍；`1H`/`4H` 例外（+8h 是整数倍）。
- ⚠️ **历史深度服务端上限（与 `limit` 无关）**：4H 540 根 / 1D 90 根 / 1W 13 根 / 1M 4 根 ⇒ 约 90 天；
  1H 可 1000 根。粗周期翻页提前到空页就停（`loadMore` 的正常路径，别当 bug）。
- 回归：`FuturesDialectAdaptersTest` 的断言已从 `granularity=1h` 改为 `granularity=1H` 并新增 `1Dutc`；
  跑法 `JAVA_HOME=~/.jdks/ms-17.0.19 ./gradlew -I ../.workbuddy/tmp/init-builddir.gradle
  --project-cache-dir ../.workbuddy/tmp/agcache :app:testDebugUnitTest --tests "*FuturesDialectAdaptersTest*"`
  （`init-builddir` 是为了绕开用户开 IDEA 时的 `app/build` 文件锁，见 MEMORY.md）。

## 发版
改 `versionCode`/`versionName` → 推裸版本号 tag → 出 APK + `.sha256`。**签名固定**（`cf2e20c7…52f034`），别换。
步骤与验收见 skill `android-gh-release-verify`；通道模型见 `MEMORY.md` 的「通道与发版」。

✅ **release buildType 是 `isMinifyEnabled = false`**（CI 里注明「暂不启用混淆以免定位困难」）⇒
**release APK 与 debug 包行为同观感**，模拟器上验过的改动不必再装一遍 release 包。
这条也解释了「为什么可以不发真机走查就发版」（0.9.38 就是这么发的）。
⚠️ 但**同 applicationId（`com.waxilo.marketmonitor`）**⇒ 想在装过 debug 包的模拟器上装 release 包，
必须先 `adb uninstall`（会清掉机器上的自选/规则），所以「顺手装一下官方包」并不廉价 —— 先问值不值。

## 指标划线已移除（2026-10-23）
「指标划线」（均线/均线带自动参考线 + 自动上下破告警）已整体删除，DB v9 → v10，保留全屏画线与图上告警线。
坑：删 Room 实体的**列**必须同时写「整表重建」迁移（minSdk 26 无 DROP COLUMN），否则 Room 打开库校验表结构直接崩；只删表不删列同样崩。
细节见 `2026-10-23.md`。

## 单击选中再拖动 + 全屏默认不锁（2026-10-24）

用户口径两条：**全屏默认不锁定**；**拖动划线必须先单击选中**（原先是「按住满长按时长才转正」）。

**口径**
- `linesLocked` 初值改 `false`。配套：锁定态本来就不摆 `toolHint`，默认解锁后那条横幅会常驻 ⇒
  `DrawTool.NONE` 档加「图上一根线都没有就返回 `null`」，有划线才提示
  「点线选中再按住拖动 · 点空白取消 · 选中后右侧可备注/删除」（2026-10-24 后半句改成这样：
  拖到竖栏删除已撤，删除/备注都在选中后的编辑栏里）。
- 判据是纯函数 `lineTouch(hit, selected): LineTouch`：命中==已选中 → `Drag`（当场接管）；
  命中别的 → `Select`（松手且没滑走才选中）；落空 → `None`（松手取消选中）。
  配 `LineRef.selectable()`：**草稿不可选中**（`Alert(ruleId == null)` / `Guide(id == 0)`）。
- 长按空出来**还给十字光标**；长按激活那一刻清掉 `pendingSelect` / `tapOnBlank`
  （长按 = 看读数，不是选线）。
- 选中态 `selectedLine` 挂在 KlineChart 内部（`remember(symbolKey, interval.storageKey)`）：
  上锁、切工具由 `LaunchedEffect` 清；换标的/周期随 key 作废。
  ~~三个删除出口顺手清~~ **2026-10-24 起删除只剩竖栏编辑栏那一个出口，且清空由调用方负责**：
  高亮 = **同色半透明外发光 + 加粗**（`.copy(alpha = 0.22f)`、宽 9f），直线锚点圆点 ×1.7。
  **不换色** —— 红/黄是语义（会响 / 用户记号），临时状态不许借。

**三个手势坑（都踩过，改之前先读）**
1. ❗**抓已有线时不能在 down 发首帧**。原写法 `if (horizontalActive) onHorizontalDrag(grab, downY)`
   是为「工具下单击落线」补的；Drag 通道也走这条 ⇒ 「选中后点一下线」就把价位设成手指所在处
   （容差 24dp，最多偏 72px），松手还 commit 落库 = 「点一下线，线自己跳了一格」。
   现在 `if (horizontalActive && horizontalGrab == null)`，拖已有线从**第一帧 MOVE** 起算。
2. ❗**`grabOffsetY`**：24dp 抓取半径内按下，线不该往指尖跳。down 记「线 y − 手指 y」，
   每帧落点 `y + grabOffsetY` 再夹进绘图区。
3. ❗**快速滑一帧会漏判**：循环里的作废判定读的是**上一帧** `travelled`（累加在循环末尾），
   「一帧 MOVE 就抬手」的快速滑动漏判 ⇒ 被当成点击选中。**收尾必须再用最终 `travelled` 判一次**。

**验收现状（2026-10-24 真机已过）**：单测 `LineTouchTest.kt`（8 条）+ 全量 `--rerun` 全绿；
MuMu 真机 6 项手势全过 —— 默认不锁（锁钮透明 / 线型彩色）、未选中按住拖只平移画布（线价不动）、
单击出 halo（像素 2 行→9 行黄）、选中后拖改价（50409.22→41303.08 且落库）、
点空白掉 halo、上锁后按住拖 DB 价位不变。截图 `.workbuddy/tmp/v4-fullscreen.png` / `v8-dragged.png` / `v9-locked.png`。

**关于「MuMu 起不来」的订正**：沙箱里 `MuMuManager launch` 确实起不来（拦 `sc.exe`，进程秒退、
`emulator-5554` 永久 offline），**但用户手动启动后完全可连** —— 所以这不是「无法真机验收」，
而是「沙箱不能替用户启动」。遇到同样情况直接请用户开模拟器，别重试命令、也别下「验不了」的结论。
连法见 `MEMORY.md` 工具链里那几条（端口从 `info -v 0` 的 `adb_port` 读，本机 **16385**；每次 Bash 调用 adb server 都重启，
connect 必须与操作同条命令；导库要 `exec-out`）。

## 全屏「编辑栏」+ 划线备注（2026-10-24，用户：去掉常驻垃圾桶与拖动删除）

**一条状态决定竖栏长什么样**：图表把内部 `selectedLine` 通过 `onSelectionChange: (LineRef?) -> Unit`
逐次上报，页面 `FullscreenChart` 存一份，`DrawToolRail(editing = selectedLine != null)`。
上报必须用 `val sink by rememberUpdatedState(onSelectionChange)` + `LaunchedEffect(selectedLine)`，
否则调用方换了 lambda 这边还捏着旧的。
❗**删除后由调用方 `selectedLine = null`**：线已经不在，图表收不到通知，会一直举着这个 ref，
竖栏永远停在编辑栏、备注按钮拿到空 note。

**编辑栏三枚**：备注（`NoteGlyph`）/ 删除（复用 `TrashGlyph` + `colors.down`）/ 分隔 / ✕（`CloseGlyph`）。
`hasNote = true` 时备注那枚走 `colors.accent` —— 竖栏放不下正文，只能用「亮不亮」说「写过」。
两者都受锁定态约束（`dimmed`，点了 `notifyLinesLocked()`）。

**备注不加在 `alert_rule.name` 上（关键）**：`name` 是 EMA 面板的认领键（自动名 `8时EMA50`
是 (周期, 档位) 的双射），拿备注改 name ⇒ 面板回显全丢。单开一列：
- Room `version = 10 → 11`，`MIGRATION_10_11` = 两条 `ALTER TABLE alert_rule / chart_line ADD COLUMN note TEXT`
  （minSdk 26 的 SQLite 支持加列，不用整表重建；迁移在既有 v10 库上实测过：`user_version` 变 11、老数据还在）。
- 两个实体 / 两个领域模型（`AlertRule` / `ChartLine`）/ 两个 DAO / 两个仓库 + `updateLineNote(ref, note)`。
- 写入**只改一列** `UPDATE ... SET note = :note WHERE id = :id`（`AlertDao.setNote` / `ChartLineDao.setNote`）。
  别走整行 upsert：那是「读出→写回」，会把图表刚拖到的价位盖回旧值，也会在调用方没拿全字段时
  抹掉重复模式 / Webhook。`dragDrawLine` 要 `note = existing?.note` 透传，否则拖一下价位就没备注了。
- 弹窗 `LineNoteDialog`：`BasicTextField` + 占位 + `n / 60` 计数，新行被换成空格，留空 = 删备注。
  备注**不上图**（常驻文字牌跟着线飘、盖住 K 线）。

**两条 adb 注入的坑（验收时会误判成功能坏了）**：
- ❗`input swipe x y x y <ms>`（起止同坐标）被当成 **tap**、**不发 MOVE 帧**。长按判定写在事件循环里
  （`held >= longPressMs && travelled <= touchSlop`），没有 MOVE 就永远轮不到 ⇒ 十字光标「按不出来」。
  带 1–2px 位移（`input swipe 800 400 802 401 1500`）立刻正常。
- ❗**直线只能用「拖动」画**：纯 tap 一场是 `SEED`（准星不动），第二下 tap 的落点与种子重合 ⇒ 恒判
  「太近」→ 撤销，且撤销会把工具收成 `NONE`（表象：点了两下什么也没出现、栏里高亮也没了）。
  要造线就发两次带位移的 `input swipe`。

## EMA 压力支撑分析面板（2026-10-24，桌面端功能的 Android 复刻）

三层：`domain/indicator/EmaAnalysis.kt`（纯逻辑，22 条单测）→ `DetailViewModel`（扫描 / 静默合并 /
每分钟定时 / 点击动作）→ `ui/detail/EmaAnalysisSheet.kt`（`Dialog` 面板）。

**入口位置变过两次**：最初在指标栏 `＋` 与 `BOLL` 之间的 `EMA分析` chip；2026-10-24 用户要求搬到
**详情页顶栏右上角**（`DetailTopBar` 的 actions 最左、自选之前），指标栏那枚 chip 一并删掉 ——
它是弹窗不是图表开关，跟旁边「点了会记状态」的 chip 不是一回事；且指标栏在页面中段要滚动才看见。
全屏没有顶栏，入口放在**周期条那一行的右端**（`IntervalSelector` 的 actions，`✕` 左边）。
`IndicatorBar` 因此**不再有 `onOpenEmaAnalysis` 参数**。按钮是文字不是图标：只引了
`material-icons-core`，Insights / ShowChart 都在 extended 包里，为一个入口拉整包依赖不划算。

**口径**（与桌面端 `emaAnalysis.ts` 同）：每档下限 300 / 上限 1000 根；算不出的不进表（NaN 会毁排序）；
`merge` 静默刷新（失败沿用旧值记 `stale`，根数不够也沿用并从 `missing` 划掉，只认当前配置范围）；
`split` 按现价分压力/支撑，恰好等于现价两边都不进；同价按「细周期 → 短档位 → label」定序。

**回显只能靠名字**（结构性差异，动手前先认清）：桌面端的系统划线是带 `origin` 的独立实体；
Android 的线由 `alert_rule` 派生，规则表**没有 (周期, 档位) 的结构化身份字段**。自动名 `8时EMA50`
是 (周期, 档位) 的双射，靠它认领就够，不必改表。**不做 adopt（收编）** —— 这里的规则是会响、会推送、
可能挂 Webhook 的真资产，悄悄改名比多一条重合线糟得多。**EMA 原值不对齐 tickSize**：一是与桌面端数值一致，
二是一旦四舍五入「已对齐」判据永不成立（满屏「待对齐」），三是 `alert_rule` 表本来就不校验刻度。

**周期短名是中文，与桌面端同源**（2026-10-24 用户要求，`1h → 1时`）：全 App 从 `CandleInterval.label` 出，
单位表 分/时/天/周/月，`humanize` 由粗到细判（月→周→天→时→分，否则 10080 会写成「7 天」）。
`storageKey` / `apiCode` **不动**（界面「8时」、请求仍 `8h`）。❗自动名因此从 `8hEMA50` 变成 `8时EMA50`，
而它同时是回显的识别键 ⇒ 必须留 `legacyLabel` / `EmaAnalysis.legacyNote`：`lineOf` 新旧名都认、
`orphanLineNames` 的已配置集合两种命名都放进去，`AUTO_NAME` 正则改成 `[0-9]+[\p{IsHan}a-zA-Z]+EMA[0-9]+`。
迁移靠「点一下」（`alignEmaRule` 会把 `name = note` 写回），不主动扫库改用户的规则名。

**竖屏改成页签（2026-10-24 用户要求）**：原先单列把两侧叠在一起（压力倒序 → 现价分隔 → 支撑正序），
压力十几道时「下方支撑」被顶出屏幕，用户根本不知道下面还有内容。改用项目自带的 `SegmentedControl`
（下划线指示那种）两枚页签，各带条数（`上方压力 16` / `下方支撑 11`），一次只渲一侧。
- 页签挂在外层 `Column`（**不随列表滚**，滚到十几行还能切），且只在 `!twoColumn && hasTable` 时出现；横屏仍两列。
- **行序跟着改**：两侧都按「离现价由近到远」（= `buckets` 原序：压力升序 / 支撑降序）。原先压力倒序
  是为了与图的纵轴同向，页签化后这个约束没了，第一条该是最有用的那条，也与表头「最近 X · EMA Y」对得上；
  横屏仍保留倒序（贴着图看，纵向对得上图更重要）。
- 表头走 `ColumnHead(showTitle = false)`：title 与条数已在页签上，只留左「已画 N」（用本侧色，页签是墨黑的，
  这点色接住「这列是哪一类」）/ 右「最近 X · EMA Y」。
- 默认页签压力；`picked == -1`（还没手动切过）且压力为空、支撑非空时默认支撑 —— 现价站在所有 EMA 之上时
  迎面一张空表会像取数坏了。手动切过就认用户的选择，不给他弹回去。
- ❗**两侧滚动位置要各持一个 `LazyListState`，且都挂在 `EmaAnalysisSheet` 这一层**。
  `rememberSaveable(side, saver = LazyListState.Saver)` 看着最省事，实际 **inputs 一变就丢旧值重新 init**
  ⇒ 切走再切回跳回顶部（真机验过）；共用一个 state 又会让行数少的一侧直接落到空白处。
- 单列那条「现价分隔线」一并删掉（现价在抬头已经写了）。

**三个 UI 坑（都真机踩过）**
1. ❗**提示条不能当 `Column` 的普通子节点** —— notice 出现/消失改变 `LazyColumn` 高度，列表在那一秒
   自己挪几行（症状：点一下，正在看的那行跑了）。改成外层 `Box` + `align(BottomCenter)` 浮层；
   `Banner` 底色只有 7% 透明，浮起来透出底下文字，要再垫一层 `Surface(paper, shadowElevation = 6.dp)`。
2. ❗**偏离配色按列取，不按正负号** —— 压力列偏离恒为正，交给 `forChange` 渲成「涨绿」，与红色列头自相矛盾。
3. ❗**`decorFitsSystemWindows = false` 之后状态栏图标整片消失** —— dialog 是新窗口，不继承 Activity 的
   `enableEdgeToEdge`，默认浅色图标画在纸白顶栏上（看着像「时间、电量没了」）。面板里 `SideEffect` +
   `DialogWindowProvider` 设 `isAppearanceLightStatusBars/NavigationBars = colors.paper.luminance() > 0.5f`。

**小坑**：`IntervalBadge` 的 `clickable` 触控区被 Compose 扩到 48dp ⇒ 会吞掉「行首那段点整行」的落点
（点 EMA50 旁边会切周期而不是画线），自动化验收点行请落在价格列。砂箱 `screencap` 在 MuMu 上旋转后仍是
1080×1920 —— 横屏要走 App 自己的全屏看图（`requestedOrientation = LANDSCAPE`），别用 `settings put system user_rotation`。

**验收（2026-10-24 真机 8 项全过）**：表与分档 / Add（`已画` + 列头 `已画 N` + 浮层回执）/
Align（`待对齐` → 点一下 → `已对齐 8hEMA50 到 82417.68`）/ Remove（DB `alert_rule` 清空）/
周期小方框切周期并关面板 / 每分钟自动刷新（`上次` 时间戳与整表数值同步变）/ 横屏两列 / 竖屏单列滚到支撑区。
截图 `.workbuddy/tmp/e6-*` `e7-*` `e8-a.png`。

**页签版验收（同日第二次，截图 `tab-*` `pos-*` `fin-*`）**：两枚页签带条数、点即切（18/9 → 20/7，随现价变动）✓；
压力页滚到中段 → 切支撑 → 切回，**仍停在原位** ✓；页签内第一条恒为最近的一档（表头「最近」与首行同一行）✓；
画线后左侧出现彩色「已画 1」+ 行尾 `已画` + 浮层回执，切到支撑页该标记不串 ✓；
横屏仍两列、无页签 ✓。验收完 `DELETE FROM alert_rule` 复原（顺带确认了告警真的会响：
那条测试线在横屏时弹了「BTCUSDT 上破 80958.79」）。

**面板头已压成两行（2026-10-24）**：三行 → 两行，横屏 ~124dp → ~64dp，按 `screenWidthDp >= 480` 分「单行装全部」与「窄屏价格下移」两版。

