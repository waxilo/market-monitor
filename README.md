# Market Monitor（行情监控）

币安现货 + USDT-M 合约的行情监控 App：K 线图表、价格预警、Webhook 推送与应用内更新。
只做行情与预警，不碰账户：没有任何签名/私有接口，也不需要填 API Key。
需求与取舍见 [`docs/需求文档.md`](android/docs/需求文档.md)。

## 仓库布局

| 目录 | 内容 |
| --- | --- |
| `android/` | Gradle 工程根（App 端：`app/` 模块、wrapper、`keystore.properties`、`basedata/`、`docs/`） |
| `desktop/` | 桌面端（Tauri v2 + Vite + React + TS），UI 风格对齐 App；托盘常驻 + 迷你悬浮窗 |

## 模块与现状

| 模块 | 位置 | 状态 |
| --- | --- | --- |
| 行情列表 / 自选 / 搜索 | `ui/market`、`ui/search` | 已实现 |
| 详情页与 K 线图表（自研 Canvas） | `ui/detail`、`ui/chart` | 已实现 |
| 数据层（REST 轮询 + Room 缓存；WS 链路已移除） | `data/remote`、`data/local` | 已实现 |
| 合约行情多接口（Aster/币安主域/OKX/Bybit/Bitget/Gate/MEXC/Hyperliquid/HTX/Bitunix，设置页弹窗并行检测后点选，结果整体按延迟排序） | `data/remote/dialect`、`ui/settings` | 已实现；切换接口会清合约缓存并重同步交易对。币安镜像 1/2/3 与老镜像已移除 —— 它们与主域同后端、同生共死，占着席位却不增加容灾能力 |
| 我的仓位（币安签名接口 + API 凭据） | 原 `ui/positions` | **已移除**（含 `BinanceSigner`、`EncryptedBinanceCredentialStore` 与底栏第四个 tab）|
| 价格预警（通知栏 + 前台保活 + 消息中心） | `data/alert`、`ui/alerts` | 已实现，待真机验证 |
| Webhook 推送（端点加密存储 + 模板 + 补发） | `data/remote/WebhookSender`、`ui/webhook` | 已实现，待真机验证 |
| 设置页与应用内更新 | `ui/settings`、`domain/repository/UpdateRepository` | 已实现；更新读取路径见下 |
| 桌面端（窄侧栏导航 + 现价、搜索下拉浮层、自绘 K 线、托盘常驻、可自撑高度的迷你悬浮窗、自绘标题栏、应用内更新） | `desktop/src`、`desktop/src-tauri` | 已实现；发版流程见下 |
| 桌面端合约多数据源（与 App 同一份 12 候选清单，顶栏「数据源」弹窗并行测速后点选（5 秒不响应即判超时），结果整体按延迟排序） | `desktop/src/lib/sources.ts`、`dialects.ts`、`components/SourcePanel.tsx` | 已实现；现货固定走 Gate，选中项存 `mm.futuresSource` |
| 桌面端告警线通知（穿越 → 系统通知 + webhook 推送；设置弹窗「告警通知」页管地址，接 notify_hub 的 hook） | `desktop/src/lib/webhooks.ts`、`hooks/usePriceAlerts.ts`、`components/WebhookSection.tsx` | 已实现；地址存 `mm.webhooks`，只发 https（宿主 `market_request` 只放行 https） |
| 桌面端悬浮窗列表（设置弹窗「悬浮窗」页：现货/永续混搜添加 + 拖动排序，独立于自选） | `desktop/src/components/MiniListSection.tsx`、`hooks/useWatchlist.ts`、`lib/search.ts` | 已实现；存 `mm.miniWatchlist`，没存过的机器首次读取会拷贝一份当前自选当出厂值 |

Android 端的应用内更新读取 `api.github.com/repos/waxilo/market-monitor/releases/latest`，要求**匿名可读**。
仓库已转为 public，匿名请求实测 200，所以这条链路是通的；若日后改回 private，匿名一律 404，
应用内更新会整体失效（届时的出路：只读 token（会被打进 APK，有泄露风险）、或把产物同步到可匿名读的地址）。
数据层按「tag 比较 + `<apk>.sha256` 边车 + 流式校验」实现，改动这条读取路径时保持这三段不变。

桌面端**不复用**这条路径：它读固定更新通道 `desktop-latest` 的 `latest.json`（按平台给地址与 minisign 签名），
因为桌面端产物是 dmg/exe 而 Android 是 APK、且 NSIS 安装与 Android 安装器的语义完全不同（见「桌面端发版流程」）。

## UI 设计系统

风格定调：**极简杂志风**，刻意与「币安黄铺满屏」的零售交易所观感拉开距离。

| 维度 | 约定 |
| --- | --- |
| 色彩 | 只有墨黑/纸白两级中性色撑结构，饱和度全部留给涨跌信号；黄色仅用于「已选中」的极小面积 |
| 描边 | 用 1px 细线（`Rule`）代替卡片阴影，全 App 只有一档描边层级 |
| 分隔 | 不铺 6 层 `surfaceContainer`，只有 `paper` / `surface` / `wash` 三档 |
| 排版 | 等宽体承载所有数字与小标题，系统无衬线承载所有自然语言；靠字重与字距做层次，不引入字体文件 |
| 组件 | `ui/common/CommonUi.kt` 为唯一组件来源；选择器只有两种形态（`SegmentedControl` 互斥 / `FilterChip` 多选） |
| 常量 | 间距、圆角、动效曲线集中在 `ui/theme/Tokens.kt`，页面不得写字面量 |

色彩入口是 `MarketTheme.colors`（自定义 `CompositionLocal`），不直接读 M3 的 `colorScheme`——
M3 的角色命名是给 Material 组件用的，表达不了「发丝线 / 弱化文字」这类本设计系统的概念。

主题模式（跟随系统 / 浅色 / 深色）已接入设置页并持久化（`AppSettings.themeMode`），改动即时生效。


## 本地构建

```bash
cd android
./gradlew :app:assembleDebug        # 需要 JDK 17
./gradlew testDebugUnitTest         # 纯逻辑单测（指标、聚合、判定、模板）
```

桌面端（Tauri v2 + Vite/React，现货走 Gate，合约在 12 个候选接口里测速点选）：

```bash
cd desktop
npm install
npm run dev                         # 浏览器预览：只有界面（无托盘/悬浮窗/更新，Tauri API 短路）
npm run tauri:dev                   # 真实桌面窗口，dev 身份 —— 日常调试用这个（见下）
npm run build                       # 前端类型检查 + 打包（CI 也会跑）
RUSTUP_TOOLCHAIN=stable-aarch64-apple-darwin cargo test --manifest-path src-tauri/Cargo.toml --lib
npx tauri build                     # 本机出安装包（macOS 需要签名身份，见「桌面端发版流程」）
```

不在本地做发布构建：APK 与桌面端安装包都由 GitHub Actions 产出（见下）。
本地 `tauri build` 只用于自检与冒烟，产物留在 `src-tauri/target/`，不要拿它顶掉 `/Applications` 里正在用的那份。

**验证界面改动一律走 `npm run tauri:dev`，不要去关掉/覆盖本机正在跑的那份应用。**

它是 `tauri dev` 加一层 dev 身份覆盖（`src-tauri/tauri.dev.conf.json`），与安装版从**名字到身份**都分开：

| | 二进制 | 身份 `identifier` | 应用数据 / WebView2 目录 |
|---|---|---|---|
| dev（`tauri dev`） | `target/debug/market-monitor.exe` | `com.waxilo.marketmonitor.dev` | `%APPDATA%`/`%LOCALAPPDATA%\com.waxilo.marketmonitor.dev` |
| 安装版（`tauri build`） | `MarketMonitor.exe` | `com.waxilo.marketmonitor` | `%APPDATA%`/`%LOCALAPPDATA%\com.waxilo.marketmonitor` |

（Rust 包名是 `market-monitor`，所以 dev 二进制永远是 `market-monitor.exe`；安装版的名字来自
`tauri.conf.json` 的 `productName: MarketMonitor`。）

两条线互不干扰：数据目录不同（自选列表因此**不是**同一份），**dev 构建也不参与更新通道**
（`update.rs` 的 `check_update` 在 debug 下直接返回「开发版不检查更新」）——否则装上一次更新，
调试实例就被换成正式安装版了。`npm run tauri dev` 仍然可用，但它走生产身份，日常别用。

dev 模式跑的是本地 Vite dev server（`devUrl` 指 `http://localhost:5173`），改前端有 HMR、不用重编译。
反过来，**用 `cargo build` 出的 release 二进制不会因为 `dist/` 变了而重新链接**
—— cargo 的指纹里没有前端产物，`tauri-build` 只在 CLI codegen 路径上写 `rerun-if-changed`；
只改了前端却 `cargo build` 会打印 `Finished` 但 exe 还是旧的（表现为「改动没生效」）。
要么直接 `tauri dev` / `tauri build`，要么先 `touch src-tauri/src/lib.rs` 再 build。

桌面端的行情请求**全走宿主侧** `market_request`（`src-tauri/src/market.rs`），不用 webview 的 `fetch`：
除 Gate、Hyperliquid 与币安现货镜像外，其余接口都不回 `Access-Control-Allow-Origin`，webview 里读到的
一定是 CORS 报错（宿主侧 reqwest 跟随系统代理，路由与浏览器一致）。Rust 只做搬运，周期映射、
字段解析、业务码判定这些方言逻辑都在 `src/lib/dialects.ts`，判定结论与 App 的 `FuturesDialects.kt` 对齐。

### 桌面端交互约定（改之前先看这几条）

- **主窗与悬浮窗是二选一，不是两个开关**：主窗顶栏的图标表示「收成悬浮窗」（`switch_to_mini`）。
  反方向没有图标：**悬浮窗整块面板就是入口**（左键）—— 点行 = `show_main_window`（主窗起来并选中该
  标的），点空白/空状态 = `switch_to_main`（只切过去）。所以 `MiniApp` 在面板根节点上有 `onClick`，
  且必须用 `closest('button')` 给行让位：行自带更具体的语义。
- **悬浮窗没有常驻标题栏，也没有遮罩层**：整块面板都是价格。**隐藏走右键的原生菜单**
  （`lib.rs` 的 `open_mini_menu` → `hide_mini`），菜单条目 id 交回 `run()` 里的全局
  `on_menu_event` 处理。菜单只能由宿主弹：窗口就面板那么大，在 webview 里画 HTML 菜单
  会被窗口自己的边界裁掉，原生 popup 是独立窗口才能溢出到面板外面。右键**不改变左键语义**
  （`click` 不响应右键；`useWindowDrag` 在 `button !== 0` 就返回，也不会开始拖拽）。
- **行的高亮底色不用 CSS `:hover`**（`hooks/usePointerInside.ts`）：这个窗口会自己移动
  （拖拽、按条数改高度时下边缘不动 ⇒ 上边缘在跑）和隐藏，鼠标位置在那之后离开窗口时
  Chromium 收不到 `mouseleave`，`:hover` 就永久卡在亮着 —— 表现为「某一行一直亮着」。
  规则只有一条：**只有指针自己的事件能点亮，任何能证明「指针位置已失效」的信号
  （离开面板 / 窗口失焦 / 页面不可见 / 被移动 / 被改尺寸）一律熄灭**，默认熄灭。
  hook 算出的 `.inside` 挂在面板根节点上，`theme.css` 里写的是 `.mini.inside .mini-row:hover`。
- **悬浮窗高度 = 列表条数**：`min(条数, 6) × 26px + 2px 边框`，超过 6 条封顶并在行区内滚动。
  前端只把**条数**报给 Rust（`set_mini_rows`），几何在 Rust 侧算（`MINI_ROW_H` / `MINI_MAX_ROWS`）。
  这两个值的**权威定义在 `desktop/src/lib/layout.ts`**（`MINI_ROW_HEIGHT` / `MINI_MAX_ROWS`），
  由 `MiniApp` 注入成内联 CSS 变量 `--mini-row-h` / `--mini-max-rows`（theme.css 里同名声明只是兜底默认值）；
  Rust 侧跨 FFI 没法共享常量，只能与 layout.ts 手工对齐。别改成「前端量高度上报」：
  悬浮窗出生即隐藏，隐藏窗口量不到排版、`getComputedStyle` 也读不到那两个字面量，
  上报会静默退化成「没变化」。Rust 调窗口时**保持下边缘与 x 不动**（右下角是它的锚点）。
- **悬浮窗列表 = 设置里的独立一份**（设置 → 悬浮窗，`components/MiniListSection.tsx`）：0.1.16 起
  不再挂在自选下面 —— 页面是**左右两栏**（左「显示中」列表、右「搜索添加」）；搜索是现货/永续
  **混搜**（结果行带市场胶囊；排序口径是 `lib/search.ts` 的 `rankAcrossMarkets`），键盘 ↑↓ 高亮、
  Enter 加高亮那条（没高亮时加第一条未加入的）、拖动排序，存 `mm.miniWatchlist`。没存过的机器
  第一次读会把当前自选**拷一份**当出厂值（`readMiniWatchlist`），拷完两边各自增删、互不影响；
  主窗轮询会把「正在看但不是自选」的那一条加进来（App 的 `tickItems`）—— 悬浮窗点行切过来时
  得能看到实时价。
- **悬浮窗要压在任务栏之上**（`lib.rs` 的 `watch_mini_above_taskbar` + `taskbar` 模块）：
  任务栏与悬浮窗同为 topmost 窗口，任务栏被点击/激活时会被 shell 提到 topmost 组最前、盖住悬浮窗；
  而这件事**不会给应用发任何事件**（用户点的是任务栏，我们连 `Focused(false)` 都收不到），
  所以只能 500ms 轮询 z 序、**真被任务栏压住时**才用 `SetWindowPos(HWND_TOPMOST)` 提回去
  （无条件重设会周期性压住开始菜单等其它 topmost 窗口）。两个别踩的坑：
  ① `set_always_on_top(true)` **重复调用无效** —— tao 对窗口 flags 做了去重
  （`set_window_flags` 里 `diff == empty` 直接 return），`ALWAYS_ON_TOP` 早已置位，根本走不到
  `SetWindowPos`，所以这里直接调 Win32；② 对已经是 topmost 的窗口直接提 `HWND_TOPMOST`
  也可能是 no-op，**验证脚本复现现象时要先降级再升级**（`.workbuddy/tmp/verify-mini-taskbar.py`），
  不然时灵时不灵，验证就成了掷骰子。
- **搜索结果是浮层，不进侧栏**（`components/SearchDropdown.tsx`）：侧栏只管自选（它是常驻的，
  内容要稳定），搜索结果是**临时态**、由输入驱动 —— 塞进侧栏会在输入时把自选冲掉，清空才恢复。
  浮层锚在 `.search-wrap`（搜索框 +「清空」按钮那一整块），不是锚在框体上。
  键盘 ↑↓/Enter/Esc 挂在 **document** 上而不是浮层根节点：焦点一直在顶部搜索框里，
  事件不经过浮层的 DOM，挂根节点一条都收不到。排序口径在 `lib/search.ts` 的 `rankInstruments`（纯函数）。
- **两个搜索框都只收英文，选着中文输入法也不弹候选词**（顶栏全市场搜索、设置 → 悬浮窗的搜索添加，
  0.1.16 起）：框体不是 `<input>`，而是自绘的 `components/EnglishField.tsx`（非编辑的
  `div[role="textbox"]`）—— **焦点在非编辑元素上，输入法不会启动**：敲拼音不弹候选词、字母直接进
  （Chromium / WebKit 只给可编辑元素建输入法上下文，Windows 与 macOS 同机制；候选窗是系统层的、
  网页侧没有标准能关，这是唯一出路）。字符集口径仍收在 `lib/search.ts` 的 `englishOnly` ——
  只留英文字母 / 数字 / 下划线，中文、全角、空格、标点进不来。框内行为全自绘：画在文字流里的
  1px 光标、选区高亮（`--sel`）、←→ / Home End（↑↓ 也映射为它俩）/ Ctrl+A C X V / 双击选全 /
  拖选 / 点击定位；粘贴走隐藏 sink 接原生 `paste`（不依赖剪贴板权限）。几何照旧输入框抄：
  高 26px、两处同位同宽。浮层开着时 ↑↓ 归列表翻高亮、光标不动（App 的 onKeyDown 抢先
  preventDefault，见上面那条）。改一处别忘另一处（两框同一套）。
- **侧栏行 = 名称 + 现价**（`MarketList.tsx`）：现价直接取 `useTickers` 的格子 —— 那份轮询本来
  就在为图表跑，显示它是零额外代价。搜索结果那档**不给价格**：它没有现成的逐标的轮询源，
  硬要显示就得把主窗的全量快照扩成常开，得不偿失。
- **拖窗口自己实现**（`lib/windowDrag.ts`），不要用 `data-tauri-drag-region`：那个属性标在哪个元素上，
  就把它内部的按钮一起变成拖拽热区（表现为「点按钮在拖窗口」）。顶栏空白区按下即拖、双击最大化；
  悬浮窗要**按住 200ms** 才拖 —— 行本身是按钮，点选不能被拖拽吃掉。
- **图表纵向手势 = 平移价格刻度**（`PriceView` 的 `zoom`/`pan` 两个意图量），缩放只留给 Shift+滚轮；
  平移收口按**几何重叠**（可视区与数据区至少重叠 `min(可视跨度, 数据跨度) × 25%`），不按位移比例 ——
  后者在放大 12 倍时几乎拖不动，缩小时又能把 K 线整屏拖出去。
- **十字光标两条线各带一枚标**（两端同规，0.1.16 起）：横线在右侧刻度列标**价格**，竖线在时间轴带
  标**日期**（那根 K 线的开线时间，格式随周期：日线 `YYYY-MM-DD`、日内 `MM-DD HH:mm`）。日期标只在
  竖线**真落在蜡烛上**时出现（与竖线同一判据，右侧留白里 `indexAt` 会夹回最后一根、标出来的是假日期）；
  贴着左右边缘时以竖线为心中、再夹进绘图区 —— 否则会切掉半个字。**App 端松手后十字光标保持展示**
  （长按拖动松手即停在那根上），要清掉它得在画布上另做动作：平移/双指缩放/轻点。
- **持仓量副图是永续专属、且只有四家源有历史**（币安同构 / OKX / Bybit / Gate，0.1.16 起）：其余源
  与现货页的 OI chip 置灰并写明原因 —— 桌面有 hover，`disabled` + 悬停提示；App 没有 hover，置灰
  但仍可点，点一下弹同一句原因。四家原生口径在方言层统一折成美元名义值（Bybit 只给基础币数量，
  对齐时按该根收盘价折），取数周期 = 不超过图周期的最大原生周期、对齐 = 按蜡烛时间前向填充
  （不插值）；取不到就整块不画 —— 不画空板、不弹错。
- **「已连接」状态与「数据源」按钮已合并**（顶栏 `.src-entry`，0.1.16 起）：两者本来就是同一个
  系统的两面，点开都进数据源弹窗。常态安静（只写 `● 数据源`），连接中/断线才报字
  （`● 断线 · 数据源`，圆点转红）；悬停提示保留「当前数据源 + 连接态」两样信息。
- **换合约数据源 = 换盘口，缓存与视图一起作废**：弹窗里的 `setFuturesSource` 只改选择并广播，
  清缓存与重取靠两条线 —— `api.ts` 在模块加载时注册的 `onFuturesSourceChange`（清 `instrumentCache`），
  以及各取数 hook 的 `useSourceKey(market)` 依赖（现货恒为 `''`）。别把源写进 symbol：
  自选/搜索/图表一律用 Gate 形态的 `BTC_USDT`，`BTCUSDT`、`BTC-USDT-SWAP`、`BTC` 这些只出现在方言层内部。
  悬浮窗是另一套 React 实例，只在它那 4s 的 localStorage 轮询里跟进（记得保留 `syncFuturesSource()`）。
- **数据源测速 5 秒不响应即判超时**（两端同口径，0.1.16 起）：只掐探测 —— 探测请求自带 5s 时限
  （桌面：`lib/sources.ts` 的 `PROBE_TIMEOUT_MS` → `httpRequest(…)` 第四参 → 宿主 `market_request`
  的 `timeout_ms`（reqwest 请求级覆盖）；App：`probeFutures` 的 `withTimeoutOrNull`），到点那一行
  直接落「连接超时」，不再挂到数据请求的时限上。K 线这类数据请求不吃这个口径（桌面仍 20s、App 仍 30s）。
- **设置弹窗 = 90% 固定画布 + 左栏分类 / 右栏内容**（`components/SettingsPanel.tsx`）：类别只会越挂越多
  （外观 / 悬浮窗 / 快捷键 / 告警通知 / 更新），一列竖着摆摆不下；固定尺寸顺带保证换分类时框不跳大小。
  更新包已下好时默认落在「更新」页 —— 顶栏那枚圆点写着「点这里进去安装」。告警通知页的 webhook
  是**火忘式**发送（`lib/webhooks.ts` 的 `sendAlertWebhook`）：告警线穿越时同步调用、不 await，
  对端慢或挂掉不许拖住 5s 的判定节拍；**不做补发**（隔夜补发一串过时的穿越比少一条更糟），
  结果落回行底的「上次发送」，失败标红。只发 https、只认 `POST {"message": …}`
  （notify_hub 的 hook 约定，标题由那边 key 名定），地址空着/不合规的行不参与发送。

## 当前版本

| 项 | 值 |
| --- | --- |
| versionName | `0.9.32` |
| versionCode | `44` |
| 更新通道 | 固定 tag `android-latest` 的 Release（**只有这一个**，发新版只换里面的产物） |
| 安装包 | 通道 Release 里的 `market-monitor-<version>.apk`（CI 用**固定 release 密钥**签名，见下），边车 `<apk>.sha256` |

产物名 `market-monitor-<version>[-<abi>].apk` 是**版本号的唯一载体**：通道 tag 恒定不变、不带版本，
App 用 `VersionCompare.versionFromAssetName` 从文件名里读版本（`release.yml` 里先 `cp` 再上传；
`gh` 的 `本地文件#远端名` 写法不生效，用 API 给产物改名又会留下旧的下载路径，所以只能从上传时就定名）。
因此**发版前务必确认 `versionName` 严格大于通道里现存的版本**；workflow 里还有一条断言：
tag 版本必须与 `build.gradle.kts` 的 `versionName` 一致，不一致直接红。

## 发版流程

版本号只在两处：`android/app/build.gradle.kts` 的 `versionCode` / `versionName`，以及本文件的「当前版本」表。

1. 确认 `main` 的 CI（`CI` workflow）为绿：单测通过且能产出 debug APK。
2. 改 `android/app/build.gradle.kts`：`versionCode` +1、`versionName` 按语义化递增，同步更新本文件的版本表。
3. 提交并发版：

```bash
git commit -am "chore(release): v0.2.0"
git tag 0.2.0
git push origin main 0.2.0
```

4. `Release` workflow 由 tag 触发（`v*` 与裸版本号都能触发），解码签名密钥后构建 **release APK**、算 SHA-256，
   再把产物**原地换进固定通道** `android-latest`。tag 只是**触发器**，不再充当发布名，也**不会新建 Release**（见下节）。
5. 若推送 tag 后没看到 workflow 运行，手动补一次：`gh workflow run release.yml --ref main`。
6. 发版前确认要发的改动**已经提交** —— 曾出现「tag 已推、而本地改动全未提交」的组合，那会误以为改动已发布。

## Release 只有一个（两个端各一个）

每个端**只有一个 Release**，tag 固定不变；发新版只替换里面的产物，不新建 Release：

| 端 | 通道 tag | 里面有什么 | 应用怎么读 |
| --- | --- | --- | --- |
| Android | `android-latest` | `market-monitor-<version>.apk` + `.sha256` | `GET /repos/<o>/<r>/releases/tags/android-latest`，版本从产物名里取 |
| 桌面端 | `desktop-latest` | `latest.json` + 各平台 dmg/exe 及其 `.sig` / `.sha256` | 直接下 `/releases/download/desktop-latest/latest.json` |

两端共用 `.github/scripts/publish-channel.sh`：**通道不存在才创建**，存在就先清掉上一次的全部产物再传新的
—— 产物名带版本号（`market-monitor_0.1.1_windows.exe` → 下一版名字就变了），`gh release upload --clobber`
只覆盖同名文件，不清就会新旧并存、用户可能下到过期包。

⚠️ **两端都不再用 `/releases/latest`**：GitHub 的 latest 是「最新**创建**的非 draft、非预发布 Release」，
与 tag 语义无关 —— 以前 Android 靠它找包，桌面端一发版就会把它抢走（Android 于是静默判「无更新」）。
各自读固定通道 tag 之后，这个互相抢占从根上没有了。

⚠️ **历史 tag 仍在**：`0.9.x` / `desktop-v0.1.x` 这些 tag 只是没有对应的 Release 了。
`desktop-v*` 推上去照样触发 `Desktop Release`，裸版本号照样触发 `Release`，两边都只是**触发器**。

⚠️ **Android 0.9.27 要手动装一次**：现存版本（≤0.9.26）读的是 `/releases/latest`，而它现在返回的是固定通道
`android-latest`（tag 不带版本号）⇒ 老代码解析不出版本，判「无更新」（不误弹、也不装错包，只是收不到提示）。
手动装一次 0.9.27（第一个带新发现机制的版本）之后就回到新链路了。

## 桌面端发版流程

| 项 | 值 |
| --- | --- |
| version | `0.1.16`（`desktop/src-tauri/tauri.conf.json`，应用内比较的就是它） |
| 更新通道 | 固定 tag `desktop-latest` 的 Release（**只有这一个**，发新版只换里面的产物），应用只读它的 `latest.json` |
| 产物 | `market-monitor_<version>_macos.dmg`、`market-monitor_<version>_windows.exe`（各带 `.sha256`） |
| 发版方式 | 打 tag `desktop-v<版本>` 推送触发 `Desktop Release`；也可在 Actions 里手动 dispatch |

桌面端与 Android 共用同一个仓库的 Releases，用 **`desktop-v` 前缀的 tag** 与 Android 的裸版本号区分开：
`desktop-v*` 既不匹配 Android `release.yml` 的 `v*`/`[0-9]*`，桌面端自己也不走 `/releases/latest`。

### 发版三步

1. 改 `desktop/src-tauri/tauri.conf.json` 的 `version`（应用内比较的就是它，不是 Cargo.toml），提交。
2. 打 tag 推送（前缀不对工作流不触发）：

```bash
git tag desktop-v0.2.0
git push origin main desktop-v0.2.0
```

3. `Desktop Release` workflow 两端并行构建，再**原地换进** `desktop-latest`（tag 固定：先清掉上一次的产物再传新的，**不新建 Release**）：

| 平台 | 构建内容 | 发布产物 |
| --- | --- | --- |
| macOS arm64 | `app,dmg` | `market-monitor_<v>_macos.dmg` + `market-monitor_<v>_macos.app.tar.gz`(+`.sig`) |
| Windows x64 | `nsis` | `market-monitor_<v>_windows.exe`(+`.sig`) |

产物一律改名为 `market-monitor_<版本>_<平台>.*`（`.sig` 跟着产物一起改名），另给安装包算 `.sha256` 边车
供手动下载核对 —— 应用内不读它，读的是 minisign 签名。

### 更新链路

检查更新与下载都在 Rust 侧（`desktop/src-tauri/src/update.rs`）：候选链为 **所选加速站 → 直连 → 其他站**，
两者都走这条链；下完先用内嵌公钥验 minisign 签名，验过才交给官方插件安装
（Windows 拉起 NSIS 后应用自己退出，macOS 原地替换 `.app` 再重启）。
内置 5 个加速站（GitHub 原生 / gh-proxy.com / ghfast.top / ghproxy.net / gh-proxy.org），更新弹窗里可切换；
开 VPN 时 GitHub 会按出口 IP 拒绝，检查失败换个加速站即可。

**自动下载**（`0.1.4` 起）：应用启动时静默检查一次（节流 1 小时），**一发现新版本就立刻在后台下载并验签**，
不用点「下载更新」；下完在主窗右下角弹一条「新版本已就绪」，点「安装并重启」即可。
「稍后」按**版本**记（`mm.updateReadyDismissed`）—— 同一版不再烦你，下一版必须重新提示
（否则一次「稍后」等于把人永久锁在旧版本上）。

两条需要知道的边界：

- 下载与验签全程在 Rust 侧、与界面无生命周期耦合，主窗收进托盘也照样下完；
  但**安装包是留在内存里的**，没装就退出应用的话下次要重下（现在会自动重下，所以只是费一次流量）。
- 同一个包**重复「检查更新」不会把已下好的包丢掉**（`update.rs::check_effect`：版本 + 下载地址 + 签名
  三者全同即视为同一个包），所以随手点一下检查不会把进度条清成零。
  换了版本/换了产物则清空，并用代次（`should_write_back`）把换包前那次在途下载的结果丢掉。

| 文件 | 用途 |
| --- | --- |
| `market-monitor_<v>_macos.dmg` | macOS 安装包（arm64，自签名：首次安装要在「系统设置 → 隐私与安全性」点『仍要打开』，或先 `xattr -dr com.apple.quarantine`） |
| `market-monitor_<v>_macos.app.tar.gz` + `.sig` | macOS 应用内更新用 |
| `market-monitor_<v>_windows.exe` | Windows 安装包（NSIS，x64） |
| `market-monitor_<v>_windows.exe.sig` | Windows 应用内更新用 |
| `latest.json` | 通道清单：版本 + 各平台的下载地址与 minisign 签名 |

发完版怎么验（不是「看一眼 Release 在不在」，而是把应用侧判定重跑一遍并**真的下载**）：

```bash
node "C:/Users/sloan.wang/.workbuddy/skills/tauri-gh-release-verify/scripts/verify-channel.mjs"       # 应判「已是最新」
node "C:/Users/sloan.wang/.workbuddy/skills/tauri-gh-release-verify/scripts/verify-channel.mjs" 0.1.1 # 应判「有更新」
```

34 条断言覆盖：发布列表恰好两个通道、manifest 可取且两平台齐全、版本判定、
**通道里没有上一版残留产物**、安装包真下载比对 SHA-256 边车、
**两个平台**的 `.sig` 解出的 keyid == 内嵌公钥 keyid（且通道里的 `.sig` 文件与 manifest 内联签名一致）、
Android 通道的 APK 版本能从文件名读出来。失败即非 0 退出码。
（`.workbuddy/tmp/verify-desktop-update-chain.mjs` 是它的转发壳，在仓库根目录跑等价。）

### 签名与凭据（都不入库）

| 凭据 | 本机位置 | GitHub Secret |
| --- | --- | --- |
| updater minisign 私钥 | `~/.market-monitor-signing/updater.key`（公钥已内嵌 `plugins.updater.pubkey`） | `TAURI_SIGNING_PRIVATE_KEY` |
| macOS 代码签名叶证书 | `~/.market-monitor-signing/leaf.p12`（含私钥与根 CA） | `MACOS_CERT_P12_MARKETMONITOR`（base64） |
| 叶证书 p12 密码 | `~/.market-monitor-signing/ci-cert-password.txt` | `MACOS_CERT_PASSWORD_MARKETMONITOR` |
| 根 CA（与 ai-assistant 共用） | `~/.traework-signing/`（本机已受信任） | —（CI 从 p12 里抽出来临时信任） |

重新签发叶证书：`CA_DIR="${HOME}/.traework-signing" bash desktop/scripts/make-signing-cert.sh`。
⚠️ **重签 = 换身份**：macOS 的 designated requirement 钉的是**叶证书**哈希（本机实测，不是根证书），
而脚本每执行一次就出一张新叶证书 —— 系统会把新包当新应用（权限重新授权），且必须把新的
`ci-cert-p12.b64` 重配成 Secret，否则 CI 与本机出的包在系统眼里是两个应用。没有必要时不要重签。
换机器：把 `~/.traework-signing` 与 `~/.market-monitor-signing` 整目录拷过去，重跑上面这条命令完成导入
（用的是同一张叶证书，身份不变）。
⚠️ **不要用脚本的 A 模式重造 CA** —— 同理，换 CA 也等于换身份。

### 一次性注意

⚠️ **Windows 0.1.0（旧机制）要手动装一次下一版**：0.1.0 的更新是「扫 `desktop-v*` 找 `-setup.exe` + `.sha256` 边车」，
而 0.1.1 起只认固定通道 `desktop-latest` 的 `latest.json`，所以那批用户不会再收到应用内提示；
装一次 ≥0.1.1 之后就走新链路了。

## 签名：应用内更新的前提

应用内更新的本质是「新包覆盖安装旧包」，而 Android 只接受**同一把密钥**签出的包。
因此发布密钥必须固定 —— CI 上一次性的 debug keystore 会让每个版本换一把钥匙，
表现为下载完成后安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`。

### 当前配置

密钥不入库，只以 base64 存在仓库 Secrets 里：

| Secret | 含义 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64 |
| `KEY_ALIAS` | 密钥别名（`market-monitor`） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_PASSWORD` | 密钥口令 |

`release.yml` 解码到 runner 临时目录后注入 `KEYSTORE_FILE` 环境变量；
`android/app/build.gradle.kts` 的 `android { signingConfigs }` 从该变量（CI）或 Gradle 工程根 `android/` 下的
`keystore.properties`（本地，已 gitignore）读取，挂在 `release` buildType 上。

**发布密钥证书 SHA-256 指纹**（换密钥必须同步改 `release.yml` 里的断言）：

```
cf2e20c74d1edd4d1fb290afee3b68ed1a18e20ac70a89e4ab5c43ec2d52f034
```

### 设计取舍

- **缺密钥时不报错，降级到 debug 签名**：本地开发和跑单测不该被一把生产密钥卡住。
  发布流水线会显式断言 `KEYSTORE_BASE64` 存在并用指纹校验产物，**签名跑偏在 CI 就红**，不会漏到用户手里。
- **只做 v2 签名**（AGP 默认），因此 APK 里没有 `META-INF/*.RSA`。
  ⚠️ 校验指纹必须用 `apksigner verify --print-certs`，`keytool -printcert -jarfile` 会报「不是已签名的 jar 文件」。

⚠️ **签名一旦更换，线上已发布的老版本就再也无法应用内升级**，那批用户必须卸载重装。
另：本地验证更新功能时，**不要拿本地 debug 包去覆盖官方 Release 包**（签名天然不同，会误判成功能损坏）；
要复现完整安装请先 `adb uninstall`，或让本地包使用与线上相同的密钥。

## 排查搜索问题时容易踩的三个坑

**① 先确认输入框里真的有字。** 空查询时展示的是全量标的按字母序截断的前 80 个
（`0GUSDT / 1000CATUSDT …`），`BTCUSDT` 在 3705 个标的里排第 **144** 名，首屏当然没有。
这**不是**排序 bug。曾因此误判过一次：`adb shell input text` 没落到输入框上，
看到的是空查询首屏，却被当成「搜了 btc 却搜不到比特币」的证据。
判据：读 `uiautomator dump` 里输入框的 `text`，为空就是没输入。

**② 截图/dump 前必须先把列表滚回顶部。** 这条代价最大：用 `input swipe` 翻页找某个标的之后，
**滚动位置会一直保留**，下一次 `uiautomator dump` 读到的是列表中段的行，
却被当成「首屏」用来推断排序 —— 于是「`BTCDOWNUSDT` 排在 `BTCUSDT` 前面」这个结论
其实是读到了中段。做排序验证前先反复 `input swipe y_bottom → y_top` 回到顶部，
或干脆重启应用。

**③ `ticker` 表在模拟器上恒空**（无外网），此时所有 `quoteVolume` 都是 `BigDecimal.ZERO`，
按成交量排序等价于不排序 —— 顺序会掉回字母序，`BTCUSDT` 实测会掉到第 32/35 名。
这正是 `SearchRanking.quotePriority` 必须存在的原因：它不依赖行情。
所以**不要用「模拟器上排得对不对」来验证成交量相关的排序**，那部分在模拟器上永远不生效。

### 排序结论的可信来源

排序是纯逻辑，**在 JVM 单测里断言**，不要靠截图推断。
真机上要确认时，最省事的是打一行日志把 `Take` 之后的前 9 名连同 `baseAsset` 打出来 ——
一次就能看清「算出来的顺序」与「屏幕上的顺序」是否一致：

```kotlin
.also { rows ->
    if (keyword.equals("btc", ignoreCase = true)) {
        Log.i("SEARCHDIAG", "top9=" + rows.take(9).joinToString(",") {
            "${it.id.symbol}(base=${it.baseAsset})" })
    }
}
```

实测输出（0.7.0，`instrument` 3705 行、`ticker` 为空）：
```
kw=btc cands=3705 rows=80
top9=BTCUSDT(base=BTC),BTCUSDC(base=BTC),BTCFDUSD(base=BTC),BTCTUSD(base=BTC),
     BTCEUR(base=BTC),BTCTRY(base=BTC),BTCAEUR(base=BTC),BTCARS(base=BTC),BTCAUD(base=BTC)
```
