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

## ⚠️ LazyColumn 的 key 锚定
给了 `key` 就按「第一个可见项」锚定：重排时（测速弹窗按延迟逐条回填）被锚定那条一旦排到后面，**视口跟着往下滚 ⇒
排第一的反而跑到屏幕上方**（用户反馈「我明明在最上面，排序后还得往上滑」）。修法：`rememberLazyListState()` +
重排时 `scrollToItem(0)`；但**单行「重测」别抢视口**（用户正盯着那行看结果，key 锚定恰好是他要的），用 `pinToTop`
标志区分批量与单行。排序规则抽成纯函数 `ui/settings/ProbeOrdering.kt`（+7 单测）。

## 发版
改 `versionCode`/`versionName` → 推裸版本号 tag → 出 APK + `.sha256`。**签名固定**（`cf2e20c7…52f034`），别换。
步骤与验收见 skill `android-gh-release-verify`；通道模型见 `MEMORY.md` 的「通道与发版」。
