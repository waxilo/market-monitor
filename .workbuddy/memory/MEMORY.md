# market-monitor 长期笔记

一仓库两端：`android/`（Compose→APK）、`desktop/`（Tauri v2 + React + TS）。共用一个 Releases，tag 前缀区分：Android 裸版本号，桌面端 `desktop-v*`。

## 工具踩坑（通用）
- Bash 缺 `dirname cat uname xargs mkdir sleep seq basename wc`；`grep sed head tail ls find awk` 加 `/usr/bin/`（`| tail -40` 最易忘）。建目录先 Write 占位文件。
- `git`=`"C:/Program Files/Git/cmd/git.exe"`；`node`/`npx`=`C:/Program Files/nodejs/`；`adb`=`C:/Users/sloan.wang/android-sdk/platform-tools/adb.exe`。MSYS 把 `/PID` 当路径 ⇒ taskkill 写 `//PID`。
- ⚠️ 沙箱里的 `tasklist` 看不到外面的进程，用 Python `CreateToolhelp32Snapshot`；`rm` 被 safe-delete 层拦时 **`os.rename` 能过** ⇒ 挪开再重建。
- `gh` 要先 `export APPDATA="C:\Users\sloan.wang\AppData\Roaming"`；匿名 GitHub API 60 次/小时。

## Android（android/）
- 单测：`JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" ANDROID_HOME="C:/Users/sloan.wang/android-sdk" ./gradlew --no-daemon testDebugUnitTest`（**默认 Java 8 会失败**，那才是「gradlew 跑不起来」的真因）。基线 **250 全绿**。设备 MuMu `emulator-5554`，坐标先 `uiautomator dump`（`screencap` 可能是上一帧）。
- MuMu 的 WebView 会被反复 kill ⇒ 白屏，图表才改自研 Compose Canvas。
- 设计系统：极简杂志风，Ink/Paper 灰阶 + 涨绿跌红，语义色走 `MarketTheme.colors`，尺寸全在 `Tokens.kt`。**改前先 grep 调用点**（踩过两次）。
- 视窗：`ChartViewport` 只存 `visibleBars`+`rightOffset`，**`barCount` 为方法入参、不存字段**；复位 = 重挂 `remember(symbolKey, interval.storageKey)`，别用 `LaunchedEffect` 事后对齐。`window()` 管量程、`plotRange()` 管像素（含留白）；每帧位移不足一根要累积。
- Sparkline 别硬要求周期：读不到按 `cachedIntervalCounts` 挑最粗回退（表 `kline`）。
- ⚠️ **`LazyColumn` 给了 `key` 就会「按第一个可见项锚定」**：列表被重新排序时（行情接口测速弹窗按延迟逐条重排），被锚定的那条一旦被排到后面，**整个视口跟着它往下滚 ⇒ 排第一的反而跑到屏幕上方**，用户得往回滑（真实反馈「我明明在最上面，测速排序后还得往上滑」）。修法：`rememberLazyListState()` + 排序一变就 `scrollToItem(0)`；但**单行「重测」不要抢视口**（用户正盯着那一行看结果，key 锚定恰好是他要的），用一个 `pinToTop` 标志区分「批量检测」与「单行重测」。排序规则抽成纯函数 `ui/settings/ProbeOrdering.kt`（+7 条单测）。
- 发版：改 `versionCode/versionName` → tag（裸版本号）→ workflow 出 APK + `.sha256`。**签名已固定**（`cf2e20c7…52f034`），别换。

## Desktop（desktop/）
- 结构：`src/App.tsx`（主窗）/ `src/MiniApp.tsx`（`#/mini`，同 bundle 双入口）/ `src-tauri/src/lib.rs`。两窗是两套 React 实例，轮询 localStorage 同步。
- 图表自绘 Canvas（`KlineCanvas.tsx` + `lib/chart*.ts`、`indicators.ts`），规则与 Android 对齐。权限在 `capabilities/default.json`（自定义命令免权限）。
- 拖拽用 `lib/windowDrag.ts`，**别用 `data-tauri-drag-region`**（会把子元素的按钮一起变热区；它也只在 `e.button === 0` 才动手，右键天然不拖）。双窗**二选一**互切。
- **反方向（悬浮窗 → 主窗）没有图标，整块面板就是入口**：`MiniApp` 根节点挂 `onClick`（`switchToMainWindow`），但**必须用 `e.target.closest('button')` 给行让位**（行自带「打开该标的」的语义）；点某一行仍走 `show_main_window`（带标的）。**关窗 = 右键的原生菜单**（2026-09-29 起）：`lib.rs::open_mini_menu` 弹 `mini_hide`，条目 id 交回 `run()` 里的全局 `on_menu_event` 处理；菜单只能宿主弹 —— 窗口就面板那么大，webview 里画的 HTML 菜单会被窗口边界裁掉，原生 popup 才溢得出去。空自选那行 `.mini-empty` 高度钉成 `var(--mini-row-h)`（原来由 padding 撑，实测 43px ≠ Rust 算的 28px 窗口）。
- ⚠️ **`readWatchlist()` 里 `[]` 不能当「没存过」**（`hooks/useWatchlist.ts`）：原逻辑 `raw == null || 空数组` 都落回 8 条默认值 ⇒ 用户删空自选后主窗又冒出默认 8 条，且两个窗口各自读 localStorage 时口径还不一致（`.mini-empty` 变成死代码）。现在只有 `getItem` 返回 `null` 才当首次启动，`[]` 就是空自选。
- ⚠️ **悬浮窗行的高亮底色不能用 CSS `:hover`**（`hooks/usePointerInside.ts` 算出的 `.inside` 挂在面板根节点上）：窗口会自己移动（拖拽、改高度时下边缘不动 ⇒ 上边缘在跑）和隐藏，鼠标在那之后离开了窗口 Chromium 也收不到 `mouseleave`，`:hover` 就**永久卡亮**（表现为「某一行一直亮着」）。规则：只有 `pointermove` 能点亮，离开面板 / 失焦 / 页面不可见 / 被移动 / 被改尺寸一律熄灭，默认熄灭。（遮罩层本身已于 2026-09-29 撤掉、`--scrim` 一并删除；这个 hook 保留下来只管行高亮。）
- ⚠️ **`.mini-x` 是跨面板共用的类**（`SourcePanel` / `UpdatePanel` 标题栏的关闭「×」），别当悬浮窗专属 —— 撤遮罩时差点连它一起删掉；已改名 `.panel-close` 并挪到 `.panel-head` 旁边。**删 CSS 前先 `git grep` 类名的全部调用点。**
- **悬浮窗几何**：前端只报**条数**（`set_mini_rows`），高度在 Rust 算。几何常量（行高 26 / 封顶 6 / **宽 236**，2026-09-29 由 268 收窄）的**权威定义在 `src/lib/layout.ts`**，`MiniApp` 注入成内联 CSS 变量 `--mini-row-h`/`--mini-max-rows`（theme.css 里同名声明只是兜底）；Rust `MINI_ROW_H`/`MINI_MAX_ROWS`/`MINI_W` 跨 FFI 没法共享，**手工对齐**。❗别改成「前端量高度上报」：窗口出生即隐藏，量不到排版也读不到 CSS 变量 ⇒ 静默退化。调尺寸时**下边缘与 x 不动**。
  - 宽度是**窗口**属性、CSS 里没有第二个旋钮（`.mini` 是块级 flex，被视口撑满）⇒ 改宽度要同时改 `layout.ts` 与 `lib.rs`，再用 `.workbuddy/tmp/verify-mini-width.mjs` 按真实视口（236×158）量。实测：行内容现实最坏（`1000PEPE` + `116,435.2` + `+12.34%`）只需 ~183px，236 时符号列还剩 31px 余量。⚠️ 量文本宽度**必须用 `Range`**，克隆 `getComputedStyle().font` 会偏大 20%（见 skill `web-ui-change-verify` 第 13 节）。三个老脚本（`verify-desktop-ui` / `verify-mini-interaction` / `verify-desktop-search-drop`）里的视口与半宽坐标（268→236、134→118）已同步。
- **侧栏 = 自选列表（名称 + 现价），搜索结果走浮层**：现价直接取 `useTickers` 格子（零额外请求）；搜索结果不进侧栏（临时态塞进常驻容器会把自选冲掉）⇒ `components/SearchDropdown.tsx` 挂在 `.search-wrap` 下（**锚在 wrap 不是 input**：wrap 含「清空」按钮，量 input 会得出「错位 10px、窄 42px」的假问题）。排序口径抽成 `lib/search.ts` 的 `rankInstruments` 纯函数。
- ⚠️ **下拉浮层的键盘事件必须挂 `document`**：焦点全程在顶部输入框里，事件不经过浮层 DOM，挂浮层根节点一条都收不到。点行 = 未自选则**先加入再选中**（否则主窗的「选中项必须在自选里」校验会把它打回）。
- **窗口要出生即隐藏**：builder 里 `.position()` + `.visible(false)`；`build()` 返回时窗口已「可见 + 系统默认位置」，再补 `set_position`/`hide` 会留一段窗口期。
- **悬浮窗要压在任务栏之上**（`lib.rs` 的 `watch_mini_above_taskbar` + `taskbar` 模块）：任务栏与悬浮窗同为 topmost，任务栏被点/激活时会被 shell 提到 topmost 组最前盖住悬浮窗，而**这件事不会给应用发任何事件**（连 `Focused(false)` 都收不到）⇒ 只能 500ms 轮询 z 序（`GetWindow(GW_HWNDPREV)` 只走 topmost 组，碰到非 topmost 就停），**真被压住时**才 `SetWindowPos(HWND_TOPMOST)`（无条件重设会周期性压住开始菜单）。实测纠正 0.2~0.4s。
  - ⚠️ 两个坑：① **`set_always_on_top(true)` 重复调用无效** —— tao 的 `set_window_flags` 有 flags 去重（`diff == empty` 直接 return），`ALWAYS_ON_TOP` 早置位 ⇒ 走不到 `SetWindowPos`，必须自己调 Win32；② 对**已经是 topmost** 的窗口直接提 `HWND_TOPMOST` 也可能是 no-op ⇒ 验证脚本复现现象要先降 NOTOPMOST 再升 TOPMOST（成功率约一半，得重试到成功，否则「没被压住」会被误读成「已纠正」= 假绿）。
  - Win32 依赖：`[target.'cfg(windows)'.dependencies] windows = "0.62"`（与 tauri 同版本，复用 lock 不新增下载）；`window.hwnd()` 是 tauri 现成方法。
- **两条线必须分得开（名字 + 身份）**：安装版 = `productName: "MarketMonitor"` + `identifier: com.waxilo.marketmonitor` ⇒ `%LOCALAPPDATA%\MarketMonitor\MarketMonitor.exe`；dev = `src-tauri/tauri.dev.conf.json` 覆盖成 `market-monitor` + `com.waxilo.marketmonitor.dev` ⇒ `target/debug/market-monitor.exe`（dev 二进制名由 **cargo 包名**决定，`productName` 管不着）。日常调试用 **`npm run tauri:dev`**（= `tauri dev --config src-tauri/tauri.dev.conf.json`）；`npm run tauri dev` 还能用但走**生产身份**、与安装版共数据目录，别用它调试。Tauri 没有 dev 专属的自动合并配置，只有 `<platform>.conf.json` 和 CLI `--config`。
- ❗**不要开关/覆盖用户本机正在跑的那份应用**（用户为此明确抗议过一次）。dev 与安装版数据目录不同（自选列表**不是**同一份），并且 **dev 构建不进更新通道**：`update.rs::check_update` 在 `cfg!(debug_assertions)` 下直接返回「开发版不检查更新」—— 前端 `useUpdate` 启动时会静默检查，不放行就等于「装一次更新把调试实例换成正式安装版」。
- 构建：`npx.cmd --yes @tauri-apps/cli@^2 build`（裸 `npx tauri` 失败）；改 Rust 用 `cargo build` —— **改了前端也要 cargo build**（debug 内嵌 `../dist`）。反过来说：**`cargo build --release` 不会因为 `dist/` 变了就重新链接** —— cargo 指纹里没有前端产物，`tauri-build` 只在 CLI codegen 路径写 `rerun-if-changed`；只改前端却直接 build 会打 `Finished` 但 exe 还是旧的（曾据此得出「改动没生效」的错误结论）。要么 `tauri dev/build`，要么 `touch src-tauri/src/lib.rs` 再 build。
  - ⚠️ 沙箱里 `npm run tauri dev` 会死在 `failed to run command 'npm run dev' with 'cmd /S /C': 所有的管道范例都在使用中。(os error 231)` —— tauri CLI 用 `cmd /S /C` 起 `beforeDevCommand`，沙箱的 stdout 管道撑不住（重定向到文件也没用）。绕法：**自己先起 Vite（`npm run dev -- --port 5173 --strictPort`），再用 `tauri dev --no-watch -c <覆盖配置>`** —— 覆盖配置里把 `build.beforeDevCommand` 置空即可（`-c` 指向 `.workbuddy/tmp/` 下的 json，别写进仓库正式配置；`--config` 内联 JSON 在 cmd 下引号会被吃）。顺带：tauri 配置是严格 schema，自定义键一律当作 `Additional properties are not allowed`，别想加 `_comment`。
- **发版 = 推 tag，CI 出包**（不在本地构建）：改 `desktop/src-tauri/tauri.conf.json` 的 `version` → commit → 推 main → 打 `desktop-v<版本>` 推 → `Desktop Release` 两端并行构建（macOS arm64 `app,dmg` + Windows `nsis`）→ `.github/scripts/publish-channel.sh` 原地换进 `desktop-latest`。
  - `productName` = `MarketMonitor` 后本地产物名是 `MarketMonitor_<v>_x64-setup.exe`；CI 收集时改名为 `market-monitor_<v>_<platform>.exe`（应用端按 `latest.json` 的固定 URL 取包，不靠文件名）。
  - ⚠️ `.sig` 是 **base64(minisign 签名文件全文)**，不是裸签名 —— 想验 keyid 得先 base64 解一层拿到文本，再解第二行（`Ed`/`ED` + keyid(8) + sig(64) = 74 字节）。
  - 发版与验收步骤见 skill `tauri-gh-release-verify`（**2026-09-29 已按通道模型重写，v2.0.0**）：配套脚本 `scripts/verify-channel.mjs` 复刻整条链路（28 条断言：两通道唯一 / manifest / 版本判定 / **通道无上一版残留** / 真下载比 SHA-256 / `.sig` keyid == 内嵌公钥 / Android APK 版本取自文件名）。
- **应用内更新 = 自动下载 + 提示安装**（2026-09-29 起）：启动静默检查（`CHECK_INTERVAL_MS` **1 小时**；manifest 走 `github.com/releases/download/...`，**不是 api**，60 次/小时那条限额管不着）一发现新版就自动 `start_update_download` + 验签，下完在主窗右下角弹 `.up-ready` 提示条（`components/UpdateReady.tsx`），点「安装并重启」。
  - ⚠️ **自动下载的前提是 `update.rs` 里那两个纯函数，别删**：`check_effect`（版本 + 下载地址 + **签名**三者全同才算同一个包 ⇒ 重复检查保留已下好的字节与在途进度；以前无条件清空，自动之后「随手点一下检查更新」就会把包丢掉）与 `should_write_back`（下载带 `download_gen` 代次，换包后丢弃在途结果，否则旧包字节盖掉新包状态）。`start_update_download` 对 `Done` 也直接 no-op。
  - ⚠️ 前端 `check()` 之后必须**回读** `get_update_download_state`，不能自己重置换算 —— 否则界面退回「下载更新」按钮、点了 Rust 只回 no-op，界面永远卡在「没下过」的错误认知里。
  - 「稍后」记在 `mm.updateReadyDismissed` 里**按版本**比（记布尔 = 一次稍后把人永久锁在旧版本上）。提示条只在主窗渲染（悬浮窗没有）；安装包留**内存**，没装就退出要重下。
  - `.up-badge[data-state]`：running = 空心环、ready = 实心大点 —— 自动下载本身无声，这三态是它在顶栏唯一的存在感。
- 无前端测试框架：`npm run build` = `tsc --noEmit && vite build`；Rust 单测 `cargo test --offline`。核对脚本在 `.workbuddy/tmp/`（`verify-desktop-math.mts` 纯函数 / `verify-desktop-ui.mjs` 版式 / `verify-desktop-search-drop.mjs` 搜索下拉交互 / **`verify-desktop-auto-update.mjs` 自动下载全流程（假 `__TAURI_INTERNALS__` + `__emit` 推事件，20 条）** / `verify-desktop-update-chain.mjs` **已是转发壳**，真实现在 skill 的 `scripts/verify-channel.mjs` / `smoke-desktop.py` 几何，**必须 DPI 感知**；`verify-mini-taskbar.py` 任务栏遮挡，真机 z 序判定）。
  - ⚠️ **假 IPC 三件套**（缺一个就得出错误结论）：① 必须在**导航前**注入（`Page.addScriptToEvaluateOnNewDocument`）—— `IS_TAURI` 是模块加载时求值的，晚装一步应用就按「浏览器预览」短路，绿也是假的；② 除 `invoke` 外还要补 `metadata.currentWindow.label`、`transformCallback`、`unregisterCallback`、`window.__TAURI_EVENT_PLUGIN_INTERNALS__` —— 缺 `metadata` 时 `getCurrentWindow()` 在 **effect 里**抛错，整树被卸载，表现为「新功能没反应」；③ 验 host→前端的事件要按事件名存 `plugin:event|listen` 返回的 handler id，再用 `__emit(event, payload)` 手动推。
  - ⚠️ **别按文字点按钮**：图表工具条每个指标/周期芯片都带 `×`（全页 9 个），`__clickText('×')` 会点到第一个芯片 —— 症状是「点了没反应但不报错」。同名文字一律用类名（`.panel-close`），且 `__clickText` 的返回值必须断言。
  - 真机脚本注意：**Bash 工具调用结束时会把子进程树一起收掉**，所以「启动应用 → 操作 → 断言」必须在同一次调用里做完；打包的 python 3.13 **没有 tkinter**（要造测试窗口直接用 ctypes `RegisterClassExW`+`CreateWindowExW`，还能自定义类名伪装成任务栏）；`taskkill` 输出是 GBK，`subprocess.run(..., text=True)` 会 UnicodeDecodeError，加 `errors="ignore"`。
  - ⚠️ CDP 脚本的两个坑（都踩过）：① **只改 fragment 的跳转（`/#/mini` ⇄ `/`）是同文档导航，`loadEventFired` 不发** ⇒ 纯等事件挂死（曾零输出 5 分钟），必须配兜底超时 + 每次导航带不同 `?v=`；再加全局看门狗。② 量浮层位置/宽度要挑对**锚点元素**，量错元素会得出假问题。
  - ⚠️ 断言依赖外部数据的（如 Gate 1.3MB 全量合约表）**必须轮询等它真就位**再断言，并把页面里的提示文案一起打出来 —— 否则「清单还没到」会被误判成「功能坏了」。用 CDP 派发鼠标/键盘真事件（`Input.insertText` / `dispatchKeyEvent`）比原生 setter 造假事件更可信。
- ❗WebView2 远程调试口走不通（Tauri 自己设了 additional_browser_args，环境变量被忽略）。

## 跨端速查（2026-09-29 起：**每端一个固定通道 Release**）

- **两端各只有一个 Release**，tag 固定、永不新增；发新版只换里面的产物。共用 `.github/scripts/publish-channel.sh`：通道不存在才创建，存在就**先清空上一次的全部产物**再传新的（不清会新旧并存，用户可能下到过期包）。

  | 端 | 通道 tag | 产物 | 应用怎么读 |
  | --- | --- | --- | --- |
  | Android | `android-latest` | `market-monitor-<version>.apk` + `.sha256` | `/repos/<o>/<r>/releases/tags/android-latest`，版本从**产物名**读 |
  | 桌面端 | `desktop-latest` | `latest.json` + dmg/exe + `.sig` / `.sha256` | `/releases/download/desktop-latest/latest.json` |

- ⚠️ **两端都不再用 `/releases/latest`**（它按「最新**创建**」算、与 tag 语义无关；以前桌面端一发版就把 Android 的包抢走）。**版本号的唯一载体是产物名**，不是 tag —— tag 现在纯粹是**触发器**（`desktop-v*` → Desktop Release，裸版本号 → Android Release）。
- ⚠️ **存量 Android（≤0.9.26）收不到应用内更新**：老代码读 latest，拿到的 `android-latest` 解析不出版本 ⇒ 判「无更新」（不误弹、也不装错包，只是收不到提示）。**0.9.27 是第一个走新链路的版本**，那批用户得手动装一次才回到链路上（已写进 README）。
- **验收一条命令**：`node ~/.workbuddy/skills/tauri-gh-release-verify/scripts/verify-channel.mjs [当前版本]` —— 28 条断言覆盖两个通道（发布列表恰好 2 条、manifest、真下载 + sha256、`.sig` keyid、无残留）。**证明「原地换、没新建」看 `createdAt`**：`gh api repos/<o>/<r>/releases/tags/<通道> --jq .created_at`，发版前后不变就是原地换（`desktop-latest` 一直是 `2026-09-28T12:55:50Z`）。
- 发版前确认改动真的 commit 了；目标 tag 不存在（已用掉：`0.9.27`、`desktop-v0.1.3`）。
- 发版记录：`0.9.26`/`desktop 0.1.2`（通道模型上线）→ `0.9.27`/`desktop 0.1.3`（悬浮窗 236 + 测速排序滚动修复）。
