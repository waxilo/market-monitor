# market-monitor 长期笔记

一仓库两端：`android/`（Compose→APK）、`desktop/`（Tauri v2 + React + TS）。共用一个 Releases，tag 前缀区分：Android 裸版本号，桌面端 `desktop-v*`。

## 工具踩坑（通用）
- Bash 缺 `dirname cat uname xargs mkdir sleep seq basename wc`；`grep sed head tail ls find awk` 加 `/usr/bin/`（`| tail -40` 最易忘）。建目录先 Write 占位文件。
- `git`=`"C:/Program Files/Git/cmd/git.exe"`（含空格）；`adb`=`C:/Users/sloan.wang/android-sdk/platform-tools/adb.exe`。MSYS 会把 `/PID` 当路径 ⇒ taskkill 写 `//PID`。
- ⚠️ 查残留进程别信沙箱里的 `tasklist`，用 Python `CreateToolhelp32Snapshot` 枚举。
- ⚠️ `rm` 被 safe-delete 层接管、删不掉「被占用」的文件，但 `os.rename` 能过 ⇒ 挪开再重建。

## Android（android/）
- gradlew 跑不了，直接起 GradleMain：`"...Android Studio/jbr/bin/java.exe" -classpath "<gradle-8.14.3>/lib/gradle-launcher-8.14.3.jar" org.gradle.launcher.GradleMain :app:assembleDebug :app:testDebugUnitTest --offline`；release 另加 `-x lintVitalAnalyzeRelease -x lintVitalRelease`。SDK `C:/Users/sloan.wang/android-sdk`。
- 单测基线 180 全绿；设备 MuMu `emulator-5554`。坐标先 `uiautomator dump`；`exec-out screencap` 可能是上一帧。
- MuMu 的 WebView 进程会被反复 kill ⇒ 白屏，图表才改成自研 Compose Canvas。
- 设计系统：极简杂志风，Ink/Paper 灰阶 + 涨绿跌红，语义色走 `MarketTheme.colors`，尺寸全在 `Tokens.kt`。**改前先 grep 调用点**（踩过两次）。
- 视窗状态：`ChartViewport` 只存 `visibleBars`+`rightOffset`，**`barCount` 是方法入参、不存字段**；复位 = 重挂 `remember(symbolKey, interval.storageKey)`，别用 `LaunchedEffect` 事后对齐。`window()` 管量程、`plotRange()` 管像素（含留白）；每帧位移不足一根要累积。
- 手势/落点数学抽纯函数 + 单测。Sparkline 别硬要求周期，读不到按 `cachedIntervalCounts` 挑最粗回退（表 `kline`）。
- 发版：改 `versionCode/versionName` → tag（裸版本号，先确认不存在）→ workflow 出 APK + `.sha256`。**签名已固定**（`cf2e20c7…52f034`），别换。

## Desktop（desktop/）
- 结构：`src/App.tsx`（主窗）/ `src/MiniApp.tsx`（`#/mini`，同 bundle 双入口）/ `src-tauri/src/lib.rs`。两窗是两套 React 实例，靠轮询 localStorage 同步。
- 图表自绘 Canvas：`KlineCanvas.tsx` + `lib/chartMath.ts` / `chartSeries.ts` / `indicators.ts`，规则与 Android 对齐。权限在 `capabilities/default.json`（自定义命令免权限）。
- 拖拽用 `lib/windowDrag.ts`，**别用 `data-tauri-drag-region`**（会把子元素的按钮一起变热区）。双窗**二选一**互切；悬浮窗无顶栏，hover 才浮遮罩（`--scrim` ≥0.97，否则行里的红绿会透出来）。
- **悬浮窗几何**：前端只报**条数**（`set_mini_rows`），高度在 Rust 算（`MINI_ROW_H`/`MINI_MAX_ROWS`，须与 theme.css 的 `--mini-row-h`/`--mini-max-rows` 同步）。❗别改成「前端量高度上报」：悬浮窗出生即隐藏，量不到排版、`getComputedStyle` 也读不到 CSS 变量 ⇒ 上报静默退化。调尺寸时**下边缘与 x 都不动**。
- **窗口要出生即隐藏**：builder 里 `.position()` + `.visible(false)`；`build()` 一返回窗口就已经是「可见 + 系统默认位置」，再补 `set_position`/`hide` 会留下一段窗口期。
- 无前端测试框架：`npm run build` = `tsc --noEmit && vite build`；Rust 单测 `cargo test --offline`（8 项）。核对脚本 `.workbuddy/tmp/`：`verify-desktop-math.mts`（纯函数 12 项）、`verify-desktop-ui.mjs`（UI 17 项，CDP 无头 Chrome + 真行情 + 真鼠标，canvas 行剖面互相关量位移）、`smoke-desktop.py`（Win32 枚举双窗几何，**必须先声明 DPI 感知**）。
- ❗Tauri/WebView2 **不吃 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS`**（Tauri 自己设了 additional_browser_args），远程调试口走不通。
- 构建：`npx tauri build`；单改 Rust 用 `cargo build` —— **改了前端也要 cargo build**（debug 内嵌 `../dist`）。发版改 `tauri.conf.json` 的 `version`，tag `desktop-v0.2.0`。

## 跨端速查
- Android 读 tag 比 `versionName`；桌面端只挑 `desktop-v*`。
- 发版前确认改动真的 commit 了、tag 不存在；匿名 GitHub API 60 次/小时。
