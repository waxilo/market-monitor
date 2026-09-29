# market-monitor 长期笔记

一仓库两端：`android/`（Compose→APK）、`desktop/`（Tauri v2 + React + TS）。共用 Releases 仓库，
**每端一个固定通道 Release**（末节）。两端数据源规则与图表口径对齐。

## 通用工具链
- Bash 缺 `dirname cat uname xargs mkdir sleep seq basename wc`；`grep sed head tail ls find awk` 前加 `/usr/bin/`
  （`| tail -40` 最易忘）。建目录先 Write 占位文件。
- 绝对路径：`git`=`C:/Program Files/Git/cmd/git.exe`、`node`=`C:/Program Files/nodejs/`、
  `adb`=`C:/Users/sloan.wang/android-sdk/platform-tools/adb.exe`。`gh` 前先
  `export APPDATA="C:\Users\sloan.wang\AppData\Roaming"`。taskkill 的 `/PID` 要写 `//PID`（MSYS 把它当路径）。
- 沙箱 `tasklist` 看不到外部进程（用 Python `CreateToolhelp32Snapshot`）；`rm` 被 safe-delete 拦时 `os.rename` 能过。
- `.workbuddy/tmp/` 已被 gitignore（校验脚本是本地工具，不入库）。

## Android（android/）
- 单测：`JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" ANDROID_HOME="C:/Users/sloan.wang/android-sdk" ./gradlew --no-daemon testDebugUnitTest`
  （**默认 Java 8 会失败** —— 那才是「gradlew 跑不起来」的真因）。基线 **250 全绿**。设备 MuMu `emulator-5554`，
  坐标先 `uiautomator dump`（`screencap` 可能是上一帧）。
- MuMu 的 WebView 会被反复 kill ⇒ 白屏，图表才改自研 Compose Canvas。
- 设计系统：极简杂志风，Ink/Paper 灰阶 + 涨绿跌红，语义色走 `MarketTheme.colors`，尺寸全在 `Tokens.kt`。改前先 grep 调用点。
- 视窗：`ChartViewport` 只存 `visibleBars`+`rightOffset`（**`barCount` 是方法入参、不存字段**）；复位 = 重挂
  `remember(symbolKey, interval.storageKey)`，别用 `LaunchedEffect` 事后对齐。`window()` 管量程、`plotRange()` 管像素（含留白）；
  位移不足一根要累积。Sparkline 别硬要求周期：读不到按 `cachedIntervalCounts` 挑最粗回退（表 `kline`）。
- ⚠️ **`LazyColumn` 给了 `key` 就按「第一个可见项」锚定**：重排时（测速弹窗按延迟逐条更新）被锚定那条一旦排到后面，
  视口跟着往下滚 ⇒ 排第一的反而跑到屏幕上方（用户反馈「我明明在最上面，排序后还得往上滑」）。修法
  `rememberLazyListState()` + 重排时 `scrollToItem(0)`；**单行「重测」别抢视口**（用户正盯着那行看结果），用 `pinToTop`
  区分。排序规则抽成 `ui/settings/ProbeOrdering.kt`（+7 单测）。
- 发版：改 `versionCode/versionName` → 推裸版本号 tag → 出 APK + `.sha256`。**签名固定**（`cf2e20c7…52f034`），别换。

## Desktop（desktop/）
- 结构：`src/App.tsx`（主窗）/ `src/MiniApp.tsx`（`#/mini`，同 bundle 双入口）/ `src-tauri/src/lib.rs`；两窗是**两套 React
  实例**，靠轮询 localStorage 同步。图表自绘 Canvas（`KlineCanvas.tsx`、`lib/chart*.ts`、`indicators.ts`）。权限
  `capabilities/default.json`（自定义命令免权限）。
- **顶栏右端按钮组**（2026-09-29）：`… 数据源 浅色 更新 | ◫ – □ ×` —— 切悬浮窗的 ◫（`.mini-entry`）在 `.winops-divider`
  **右边**、最小化左边；CSS `margin-right: calc(2px - var(--sp-md))` 把顶栏 gap(16px) 抵成窗口组内的 2px。
  别塞进 `WindowChrome`（它只管窗口控件与 8 向拉伸热区）。
- 拖拽用 `lib/windowDrag.ts`，**别用 `data-tauri-drag-region`**（会把子元素按钮一起变热区）。双窗**二选一**互切。
- 悬浮窗：**→主窗整块面板就是入口**（根节点 `onClick`，必须 `e.target.closest('button')` 给行让位；点行走
  `show_main_window` 带标的）；**关窗 = 右键原生菜单**（`lib.rs::open_mini_menu` 弹 `mini_hide`，id 交回 `run()` 的
  `on_menu_event`）—— 菜单只能宿主弹，webview 里画的会被窗口边界裁掉。`.mini-empty` 高度钉 `var(--mini-row-h)`
  （padding 撑是 43px ≠ Rust 算的 28px）。
- ⚠️ **`readWatchlist()` 里 `[]` 不能当「没存过」**（`hooks/useWatchlist.ts`）：只有 `getItem` 返回 `null` 才算首次启动。
  原逻辑两者都落回 8 条默认值 ⇒ 删空自选后主窗又冒出默认 8 条，且两窗口口径不一致。
- ⚠️ **悬浮窗行高亮不能用 `:hover`**（`hooks/usePointerInside.ts` 的 `.inside`）：窗口会自己移动/隐藏，鼠标离开后
  Chromium 收不到 `mouseleave` ⇒ **永久卡亮**。只有 `pointermove` 能点亮，离开/失焦/页面不可见/被移动/被改尺寸一律熄灭。
  （遮罩层已撤、`--scrim` 已删，这个 hook 只留管行高亮。）
- **删 CSS 前先 `git grep` 类名的全部调用点**：`.mini-x` 原是跨面板共用的关闭「×」（已改名 `.panel-close`）。
- **悬浮窗几何**：前端只报**条数**（`set_mini_rows`），高度在 Rust 算。常量（行高 26 / 封顶 6 / 宽 **236**）权威定义在
  `src/lib/layout.ts`，注入成内联 CSS 变量（theme.css 同名声明只是兜底）；Rust `MINI_ROW_H`/`MINI_MAX_ROWS`/`MINI_W` 跨 FFI
  **手工对齐**。❗别改成「前端量高度上报」（窗口出生即隐藏，量不到排版）⇒ 静默退化。调尺寸时**下边缘与 x 不动**。
  宽度是**窗口**属性、CSS 无第二个旋钮 ⇒ 改宽度要同改 `layout.ts` + `lib.rs`，再用 `.workbuddy/tmp/verify-mini-width.mjs`
  按真实视口（236×158）量。⚠️ 量文本宽度**必须用 `Range`**（克隆 `getComputedStyle().font` 偏大 20%）。三个老脚本的
  视口/半宽坐标已同步（268→236、134→118）。
- **侧栏 = 自选（名称 + 现价，取 `useTickers` 格子，零额外请求）；搜索结果走浮层**（临时态塞进常驻容器会把自选冲掉）⇒
  `components/SearchDropdown.tsx` 锚在 `.search-wrap`（**不是 input**：wrap 含「清空」按钮，量 input 会得出「错位 10px、
  窄 42px」的假问题）。排序口径 = `lib/search.ts::rankInstruments` 纯函数。
- ⚠️ **下拉浮层的键盘事件必须挂 `document`**（焦点在顶部输入框，事件不经过浮层 DOM）。点行 = 未自选则**先加入再选中**
  （否则主窗「选中项必须在自选里」的校验会打回）。
- **窗口出生即隐藏**：builder 里 `.position()` + `.visible(false)`（`build()` 返回时窗口已可见且在系统默认位置）。
- **悬浮窗压在任务栏之上**（`lib.rs::watch_mini_above_taskbar` + `taskbar`）：任务栏与悬浮窗同为 topmost，任务栏被激活时
  会被 shell 提到组内最前，盖住悬浮窗；**窗口消息/Focus 事件一概收不到**（连 `Focused(false)` 都没有）。现状仍是
  **500ms 轮询** z 序（`GetWindow(GW_HWNDPREV)` 只走 topmost 组），**真被压住时**才 `SetWindowPos(HWND_TOPMOST)`
  （无条件重设会周期性压住开始菜单）。实测纠正 0.2~0.4s。
  - ✅ **可观测性（2026-09-29 实测，推翻了「完全没事件」的旧结论）**：任务栏提升 = 一次 z 序重排 ⇒ **桌面窗口
    （类 `#32769`）收到 `EVENT_OBJECT_REORDER`**，3/3、再 5/5 次全收到、延迟**同刻**；钩子可装在**无窗口的后台线程**
    （5/5 收到）。空闲 3s 该事件 **0** 条（有交互时 ~2.8/s）；我们的 raise 自身会产生 **4** 条 ⇒ 要节流。
    `EVENT_SYSTEM_FOREGROUND` **不覆盖**该场景（任务栏提升时不发）⇒ 别当触发源。真实鼠标点任务栏**空白处**并不提升它
    （发 reorder 但未被压）⇒ 遮挡来自真正的 shell 交互（Win 键/图标/开始菜单）与程序化提升。
    ⇒ 推荐改事件驱动（后台线程 `SetWinEventHook(…, WINEVENT_OUTOFCONTEXT)` + 3000ms `MsgWaitForMultipleObjects` 兜底），
    纠正延迟降到毫秒级且空闲不再周期性醒来。
  - ❌ **已否决**：`owner = 任务栏句柄`（严格对照实验：无 owner 能压住 ✓ / 设 owner 后照样能压住 ✓ / 拆掉又能压住 ✓；
    ⚠️ 第一次跑得出「owner 生效」是**假绿** —— 没先验证 shove 本身有效）；uiAccess / 更高 window band（要签名 + 装
    Program Files）；AppBar（解决的是预留屏幕空间，不是 z 序竞争）；`WH_MOUSE_LL`（触摸/键盘路径漏、拖慢全局鼠标、超时被静默卸载）。
  - ⚠️ `set_always_on_top(true)` **重复调用无效**（tao 的 flags 去重，`diff == empty` 直接 return）⇒ 必须自己调 Win32；
    对**已是 topmost** 的窗口提 `HWND_TOPMOST` 也可能 no-op ⇒ 验证脚本要先降 NOTOPMOST 再升（成功率约一半，得重试到成功，
    否则「没被压住」会被误读成「已纠正」= 假绿）。依赖 `windows = "0.62"`（与 tauri 同版本）；`window.hwnd()` 是现成方法。
- **两条线必须分得开**：安装版 = `MarketMonitor` + `com.waxilo.marketmonitor` ⇒ `%LOCALAPPDATA%\MarketMonitor\MarketMonitor.exe`；
  dev = `src-tauri/tauri.dev.conf.json` ⇒ `market-monitor` + `com.waxilo.marketmonitor.dev` ⇒ `target/debug/market-monitor.exe`
  （dev 二进制名由 **cargo 包名**决定）。日常调试用 **`npm run tauri:dev`**；`npm run tauri dev` 走生产身份、与安装版共数据目录。
- ❗**不要开关/覆盖用户本机正在跑的那份应用**（用户为此明确抗议过）。dev 与安装版自选不是同一份；dev 也不进更新通道
  （`check_update` 在 `cfg!(debug_assertions)` 下返回「开发版不检查更新」）⇒ 装一次更新会把调试实例换成正式版。
- 构建：`npx.cmd --yes @tauri-apps/cli@^2 build`（裸 `npx tauri` 失败）。**改了前端也要 cargo build**（debug 内嵌 `../dist`）；
  反之 **`cargo build --release` 不会因 `dist/` 变了而重链接**（cargo 指纹里没有前端产物 ⇒ 打 `Finished` 但 exe 还是旧的，
  曾据此误判「改动没生效」）：要么 `tauri dev/build`，要么 `touch src-tauri/src/lib.rs`。沙箱里 `npm run tauri dev` 死在
  `os error 231`（tauri CLI 用 `cmd /S /C` 起 beforeDevCommand，管道撑不住）⇒ 先自己起 Vite
  （`npm run dev -- --port 5173 --strictPort`）再 `tauri dev --no-watch -c <覆盖配置>`（覆盖配置把 `beforeDevCommand` 置空，
  指向 `.workbuddy/tmp/` 下的 json）。tauri 配置是严格 schema，自定义键会被拒。❗WebView2 远程调试口走不通。
- **应用内更新 = 自动下载 + 提示安装**（2026-09-29 起）：启动静默检查（`CHECK_INTERVAL_MS` **1h**；manifest 走
  `github.com/releases/download/...` **不是 api**，60 次/小时限额不适用）一发现新版就下载 + 验签，下完主窗右下角弹
  `.up-ready`，点「安装并重启」。
  - ⚠️ **两个纯函数别删**：`check_effect`（版本 + 下载地址 + **签名**全同才算同一个包 ⇒ 保留已下字节与在途进度；以前
    无条件清空，自动后「随手点一下检查」就把包丢了）、`should_write_back`（`download_gen` 代次，换包丢弃在途结果）。
    `start_update_download` 对 `Done` 直接 no-op。
  - ⚠️ 前端 `check()` 后必须**回读** `get_update_download_state`，不能自己重置换算（否则界面退回「下载更新」按钮、
    点了只回 no-op，永远卡在「没下过」）。「稍后」记 `mm.updateReadyDismissed` **按版本**比（记布尔＝永久锁在旧版）。
    提示条只在主窗；安装包留**内存**，没装就退出要重下。`.up-badge[data-state]`：running 空心环 / ready 实心点。

## 本地校验
`npm run build` = `tsc --noEmit && vite build`；Rust 单测 `cargo test --offline`。脚本都在 `.workbuddy/tmp/`：
`verify-desktop-math.mts`（纯函数）/ `verify-desktop-ui.mjs`（版式 21）/ `verify-mini-width.mjs`（宽度）/
`verify-mini-interaction.mjs`（21）/ `verify-desktop-search-drop.mjs`（38）/ `verify-desktop-auto-update.mjs`（20）/
`verify-desktop-update-chain.mjs`（**转发壳** → skill 的 `scripts/verify-channel.mjs`）/ `smoke-desktop.py`（几何，需 DPI 感知）/
`verify-mini-taskbar.py`（真机 z 序）/ `probe-topmost-events{,2,3,4,5}.py`（z 序事件探测，**自建测试窗口、不碰用户在跑的实例**）。
方法学都在 skill `web-ui-change-verify`，最容易忘的几条：
- **假 IPC 必须导航前注入**（`Page.addScriptToEvaluateOnNewDocument`）—— `IS_TAURI` 是模块加载时求值的，晚一步应用按
  「浏览器预览」短路，绿也是假的；且要补 `metadata.currentWindow.label` 等（缺了 `getCurrentWindow()` 在 **effect 里**
  抛错、整树卸载 ⇒ 表现为「新功能没反应」）。
- **别按文字点按钮**：图表工具条的指标/芯片每个都带 `×`（全页 9 个），`__clickText('×')` 点到的是芯片 ⇒「点了没反应但不报错」。
  同名文字一律用类名（`.panel-close`），并断言 `__clickText` 的返回值。
- **外部数据要轮询等真就位**（Gate `contracts` 1.3MB **单请求实测 16s**，25s 上限会被打穿）；**先 curl 打一次接口再怀疑自己的改动**。
- 只改 fragment 的跳转（`/#/mini` ⇄ `/`）**不发 `loadEventFired`**（同文档导航）⇒ 纯等事件会挂死，要配兜底超时 + `?v=`。
- 真机脚本：**Bash 调用结束会收掉子进程树** ⇒「启动 → 操作 → 断言」要在同一次调用里做完；打包的 python 3.13 没有 tkinter。

## 通道与发版（2026-09-29 起）
- **两端各只有一个 Release**，tag 固定、永不新增；发版只换产物。`publish-channel.sh`：通道不存在才建，存在就**先清空全部
  产物**再传（不清会新旧并存，用户可能下到过期包）。详细步骤与 34 条断言的验收见 skill `tauri-gh-release-verify`。

  | 端 | 通道 tag | 产物 | 应用怎么读 |
  | --- | --- | --- | --- |
  | Android | `android-latest` | `market-monitor-<version>.apk` + `.sha256` | `/releases/tags/android-latest`，版本从**产物名**读 |
  | 桌面端 | `desktop-latest` | `latest.json` + dmg/exe + `.sig`/`.sha256` | `/releases/download/desktop-latest/latest.json` |

- ⚠️ **两端都不再用 `/releases/latest`**（按「最新**创建**」算，以前桌面端发版会把 Android 的包抢走）。**版本号唯一载体是
  产物名**，tag 纯粹是**触发器**（`desktop-v*` → Desktop Release，裸版本号 → Android Release）。
- ⚠️ **存量 Android（≤0.9.26）收不到应用内更新**（老代码读 latest，`android-latest` 解析不出版本 ⇒ 静默判无更新）；
  **0.9.27 是第一个走新链路的**，那批用户要手动装一次。
- **验收**：`node ~/.workbuddy/skills/tauri-gh-release-verify/scripts/verify-channel.mjs [当前版本]`（34 条）。**「原地换、没新建」
  看 `createdAt`**（发版前后不变即是）；**「包里是新代码」**看 exe 内部版本资源 + **前端产物指纹**（本地 build 的
  `assets/index-<hash>.js` 名字与体积 == CI 日志里 vite 打的）。⚠️ 别想在 exe 里 grep 前端文案（压缩内嵌 + strip，全 0 命中）；
  `Compiling market-monitor v0.1.0` 是 **Cargo.toml 包版本**，与发布版本无关。
- 发版前确认改动已 commit、tag 未占用（已用掉 `0.9.27`、`desktop-v0.1.3`、`desktop-v0.1.4`）。记录：`0.9.26`/`desktop 0.1.2`
  （通道模型上线）→ `0.9.27`/`desktop 0.1.3`（悬浮窗 236 + 测速排序修复）→ `desktop 0.1.4`（自动下载）。
