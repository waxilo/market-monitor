# Android（android/，Compose → APK）

索引见 `MEMORY.md`。

## 环境
- 单测：`JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" ANDROID_HOME="C:/Users/sloan.wang/android-sdk"
  ./gradlew --no-daemon testDebugUnitTest`（**默认 Java 8 会失败** —— 那才是「gradlew 跑不起来」的真因）。
  基线 **250 全绿**。
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

## ⚠️ LazyColumn 的 key 锚定
给了 `key` 就按「第一个可见项」锚定：重排时（测速弹窗按延迟逐条回填）被锚定那条一旦排到后面，**视口跟着往下滚 ⇒
排第一的反而跑到屏幕上方**（用户反馈「我明明在最上面，排序后还得往上滑」）。修法：`rememberLazyListState()` +
重排时 `scrollToItem(0)`；但**单行「重测」别抢视口**（用户正盯着那行看结果，key 锚定恰好是他要的），用 `pinToTop`
标志区分批量与单行。排序规则抽成纯函数 `ui/settings/ProbeOrdering.kt`（+7 单测）。

## 发版
改 `versionCode`/`versionName` → 推裸版本号 tag → 出 APK + `.sha256`。**签名固定**（`cf2e20c7…52f034`），别换。
步骤与验收见 skill `android-gh-release-verify`；通道模型见 `MEMORY.md` 的「通道与发版」。
