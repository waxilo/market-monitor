# 桌面端（desktop/，Tauri v2 + React + TS）

索引见 `MEMORY.md`。

## 结构
`src/App.tsx`（主窗）/ `src/MiniApp.tsx`（`#/mini`，同 bundle 双入口）/ `src-tauri/src/lib.rs`；两窗是**两套 React
实例**，靠轮询 localStorage 同步。图表自绘 Canvas（`KlineCanvas.tsx`、`lib/chart*.ts`、`indicators.ts`）。权限
`capabilities/default.json`（自定义命令免权限）。

## 顶栏与交互
- 顶栏右端：`数据源 设置 | ◫ – □ ×`（◫ = `.mini-entry`，在 `.winops-divider` 右边；CSS
  `margin-right: calc(2px - var(--sp-md))` 把顶栏 gap 抵成窗口组内的 2px）。别塞进 `WindowChrome`（只管窗口控件与拉伸热区）。
  ❗**2026-09-29 收敛：主题切换与「更新」不再是顶栏按钮，一起收进了「设置」**（新按钮 class 仍是
  `icon-btn up-entry` + `.up-badge` 圆点 —— 特意沿用旧类名，好让 `verify-desktop-auto-update.mjs` 不动）。
  **「数据源」留在顶栏**：它是「当前在用哪家」的选择器，不是设置项；`verify-desktop-settings.mjs` 有一条正面断言防它被顺手删掉。
- 顶栏左端（brand 之后）= 侧栏开合开关 `.list-toggle`（`icons.tsx::SidebarIcon open={listOpen}`，收起=竖线+填充小方块、
  展开=方框+竖分隔线），状态落盘 `mm.listOpen`。收起 = `<main>` 加 `.list-collapsed` ⇒ `--list-w: 0px`，
  **列表不卸载**（只列宽归零）⇒ 切回来不丢滚动位置。
- **顶栏「数据源」按钮在**（`.toolbar` 里 status 之后）：点开 = `SourcePanel.tsx`，打开即**并行探测**全部
  候选、按延迟排序、点一行即切换并关窗。别当成「下拉菜单」——它是居中弹窗。
  ❗**用户要的是「去掉弹窗里的滚动条」，不是去掉入口**（2026-09-29 明确纠正过一次：先把整个入口删了，是理解错）。
- ⚠️ **弹窗别用纵向长列表**：候选有 **12 条**（币安一家 5 个镜像），两行式行高 ×12 + 两段说明 ≈ **685px**，而弹窗上限是
  `calc(100vh - 72px)`（默认窗 748px、**最小窗 1024×680 下只有 608px**）⇒ 125%/150% 缩放的笔记本上必然溢出，
  右侧挂一条常显的经典滚动条（占 15px 布局宽）。现在 `.src-grid` 是**两列卡片**、单卡 ~49px ⇒ 整个弹窗 **472px**，
  最小窗余量 136px。**同一条规则也适用于 `IntervalPanel`**（15 行曾顶到 608 上限、同样 15px 滚动条 ⇒ 现已两列、525px）。
- ⚠️ **顶栏是 `nowrap`，窗口窄下来总得有人让步**：让步落在搜索框身上：`.search-wrap { min-width: 120px }` +
  `.search-field input { min-width: 0 }`（input 默认 `min-width: auto` = 内容宽 ≈180px，是它挡住了收缩）。
  `.status` 也要 `white-space: nowrap`，否则「已连接」会断成两行。
  （原先的退让候选 `.market-pill` 0.1.11 起已不在顶栏，见「侧栏与搜索」。）
- **0.1.11：搜索框落在整条顶栏的正中**（用户点名）。机制 = 顶栏分三块：`.tb-side`（brand + 侧栏开关）+
  `.search-wrap` + `.tb-side.right`（状态/数据源/悬浮窗/设置/窗口组），两组侧翼 `flex: 1 1 0` **等分余量**
  ⇒ 搜索框自然落在整条顶栏的中点（不是「剩下的空档里居中」）。搜索框 `flex: 0 1 380px` —— 380 是
  「1280 下右组（自然宽 ≈415）不被压 + 两侧仍严格等宽」的最大值（400 时右组差 8px ⇒ 偏 6px，实测过）。
  ⚠️ **`.icon-btn` 必须 `white-space: nowrap`**：分组后按钮被 flex 压到自然宽以下时，中文标签
  （「数据源/悬浮窗/设置」）会**折成两行**把顶栏撑到 61px（1024 下 78px）—— 第一版就踩了。
  同理 `.winops-divider` 要 `flex: none`（否则被压到 0 宽）。
  实测：1280 中心 = 640、1600 中心 = 800（**精确相等**）；1024（最小窗）右组顶到自己的 min-content（≈415）
  ⇒ 搜索框左移 ~100px（中心 412），但不重叠、不折行、单行 44px 高 —— 接受。
- 拖拽用 `lib/windowDrag.ts`，**别用 `data-tauri-drag-region`**（会把子元素按钮一起变热区）。双窗**二选一**互切。
  ❗**「搬窗口」的判据是位移不是时长**（`lib/dragThreshold.ts`，死区 `DRAG_THRESHOLD_PX = 4`，取 4 是为了与
  Windows 的 `SM_CXDRAG` 同值）：按下只记起点，指针走出死区才 `startDragging()`；**按住再久、原地不动都不搬**，
  抬手照发 click（悬浮窗的行是按钮，点选语义必须完整）。移动/抬手监听挂在 **window 捕获阶段**（`setPointerCapture`
  会把 click 一起重定向、`pointerleave` 会在「往外拖」时误取消，两者都不能用）。
  `blankOnly: true`（主窗顶栏：按钮/输入框上按下永不拖）vs `false`（悬浮窗整块都能拖）。
  - **顶栏双击最大化**（`dblClickMaximize: true`，只主窗开）认的是浏览器 `dblclick` 事件，不是第二次按下上的
    `e.detail === 2`。❗0.1.8 前者的坑：`detail===2` 要求两次按下落在**同一元素**，顶栏是十几个并排 span/button，
    两下偏一两个像素就换了元素、计数从头开始 ⇒「双击毫无反应」。`dblclick` 由浏览器按时间窗+位移配对好再投给
    共同祖先，没这毛病。配套 `draggedPair`：搬窗手势（按下-拖走 ×2）常被配成一次双击，靠它把随之的 `dblclick`
    一起放过，否则拖完窗口顺手就最大化；复位点只有一个 = 新一轮那下没被吞的 `click`（`detail===1`），
    所以「拖过一次」不会把之后的正常双击一起堵死。`onClickCapture` 现在两件事：吞拖拽补发的 click + 复位 `draggedPair`。
  - ⚠️ **窗口句柄 `winRef` 两条路径都得懒建**（0.1.9 修的回归，`6c37fad`）：0.1.8 把双击改成认 `dblclick` 时，
    `getCurrentWindow()` 的赋值只剩在 `onDoubleClick` 里（未开 `dblClickMaximize` 的窗口还在那句开头就 `return`），
    而拖动那条写的是 `winRef.current?.startDragging()` ⇒ 悬浮窗（只传 `blankOnly:false`，永远走不到双击）句柄恒 `null`，
    **可选链把整次拖动静默吞掉 = 彻底拖不动**；主窗也要先双击最大化过一次，之后拖动才「神秘恢复」。
    现在两处统一 `(winRef.current ??= getCurrentWindow())`。
    ❗**发版验收口径要跟着改**：0.1.8 那次只重验双击、没重跑 `verify-mini-drag.mjs`（42 条），回归就这么发出去了。
    本次改用内嵌浏览器挂假宿主（`__TAURI_INTERNALS__.invoke` 收命令名）断言 `plugin:window|start_dragging`：
    8 条正向（含 4px 死区边界、死区内 click 仍送达、拖过的双下不最大化）**＋ A/B 反向** —— 把那一行退回旧写法后
    悬浮窗 0 次调用，才证明探针真抓得住这个缺陷（只跑正向见绿就发，等于没验）。
  ⚠️ **别用「按住 N 百 ms 才拖」**：定时器与指针动没动无关，等于想拖必须先原地干等，且按下即移时前段位移被整段丢掉
  （先愣一下再跳着跟）；反向还会「快速拖一下被当成点击把窗口切走」。教训：`verify-mini-interaction.mjs` 里
  断言「按住 300ms → 拖窗口」的两条**是过期断言**，按新语义按住不动本来就该是点击 —— 改测试而不是改回产品。
- 悬浮窗：**→主窗整块面板就是入口**（根节点 `onClick`，必须 `e.target.closest('button')` 给行让位；点行走
  `show_main_window` 带标的）；**关窗 = 右键原生菜单**（`lib.rs::open_mini_menu` 弹 `mini_hide`，id 交回 `run()` 的
  `on_menu_event`）—— 菜单只能宿主弹，webview 里画的会被窗口边界裁掉。
- ⚠️ **`readWatchlist()` 里 `[]` 不能当「没存过」**（`hooks/useWatchlist.ts`）：只有 `getItem` 返回 `null` 才算首次启动，
  否则删空自选后主窗又冒出默认 8 条，且两个窗口口径不一致。
- **0.1.11：出厂自选收成「现货 / 永续 各 BTC、ETH」四条**（`DEFAULT_WATCHLIST`）。光改默认值对老装机没用
  （「存过就是真相」，旧列表原样留着）⇒ `seedOnce()` 用新 key `mm.watchlistSeeded` 做**一次性替换**：没有记号
  就无条件把 `mm.watchlist` 覆写成出厂名单、再落上记号，之后永不再动 ⇒ 用户自己加回来的标的不会被二次清掉。
  `readWatchlist` 入口调用（两窗同口径）；storage 拿不到时什么都不写、读的那头走默认值。
- ⚠️ **悬浮窗行高亮不能用 `:hover`**（`hooks/usePointerInside.ts` 的 `.inside`）：窗口会自己移动/隐藏，鼠标离开后
  Chromium 收不到 `mouseleave` ⇒ **永久卡亮**。只有 `pointermove` 能点亮，离开/失焦/页面不可见/被移动/被改尺寸一律熄灭。
- **删 CSS 前先 `git grep` 类名的全部调用点**（`.mini-x` 曾是跨面板共用的关闭「×」，已改名 `.panel-close`）。

## 设置面板与快捷键（2026-09-29 新增）
- 弹窗外壳仍复用 `.up-backdrop` / `.up-panel up-wide` / `.up-head` / `.up-body` / `.panel-close`（Esc 关），
  与「更新」原来是同一个壳：`components/SettingsPanel.tsx` 现在是容器，**内容按块拆开**（`.set-block` +
  `.set-block-body`，`:first-child` 不画上分隔线）——「更新」那坨被提成 `components/UpdateSection.tsx`
  （**只出内容、不含 backdrop/标题/Esc**，面板壳由外层负责）。旧 `UpdatePanel.tsx` **已删**，别再去 import 它。
- 三段：**外观**（主题 segmented，`theme.css` 的 `.set-seg` 与 `.iv-units` 同款）/ **快捷键** /
  **更新**（`<UpdateSection/>`）。主题落盘仍是 `mm.theme`（`App.tsx`），`setTheme` 同时调
  `getCurrentWindow().setTheme(theme)` 把原生窗口主题对齐。
- **0.1.11 出厂浅色**：`mm.theme` 的解析口径 = 「只有存过 `'dark'` 才是深色，其余（没存过 / 坏值）一律浅色」。
  ❗**首帧主题必须由 `desktop/index.html` 里的一段内联脚本在样式生效前定 `data-theme`** —— `:root` 的深色底
  是默认值，等 React 的 effect 才翻会先闪一帧深色（出厂浅色后这一闪看得见）。两处判据必须一致
  （都是 `=== 'dark' ? 'dark' : 'light'`），`vite build` 会原样保留这段内联脚本（dist 里已核过）。
  `tauri.conf.json` 的 `"theme": "Dark"` 不动也行：主窗 `decorations:false` 看不到原生外框，JS 挂载后 `setTheme` 会纠。
- **快捷键模块是零依赖纯函数 + 一个 hook**，改快捷键时只动这两处：
  - `lib/shortcuts.ts`：`Chord {ctrl,alt,shift,meta,key}`；注册表 `SHORTCUTS`（目前只有
    `toggleWindow` = 「主窗 ⇄ 悬浮窗」，**默认 `alt+d`**（2026-10-02 由 `alt+m` 改，用户点名））；
    `serializeChord`/`parseChord`（认不出的返回 null）/
    `usableChord`（**必须带 ctrl/alt/meta**）/`chordRejection`（只有 Shift 要给出「会打不出大写」的理由）/
    `formatChord`（`Alt + D`）/`keyNameOf`（从 `KeyboardEvent.code` 归一：`KeyD`→`d`、`Digit1`→`1`、`F5`→`f5`）/
    `chordOfEvent`/`matchesChord`（**修饰键精确相等**，多按一个 Shift 就不算命中）/`readChord`/`writeChord`/
    `resetChord`/`isDefaultChord`。落盘键 `mm.shortcut.<id>`（`resetChord` 是**删键**，不是写回默认值）。
  - `hooks/useShortcut.ts`：`useShortcut(id, handler, enabled)`。**和弦在按下那一刻才从 localStorage 现读**
    —— 主窗刚改完，悬浮窗下一次按键就是新键，不必等 4s 的自选同步轮询。`e.repeat`/`e.defaultPrevented` 直接放行。
- 录制态：`SettingsPanel` 用**捕获阶段**的 window `keydown`（`stopPropagation` + `preventDefault`），
  Esc 取消、纯修饰键忽略、被拒的键**不结束录制**；面板自身的 Esc 关窗要 `if (!recording)` 让路。
- **两个窗口都要挂**：`App.tsx` 挂 `switchToMiniWindow`，`MiniApp.tsx` 挂 `switchToMainWindow` —— 所以「在哪个窗口里按都行」。
- 验收 `verify-desktop-settings.mjs`（47 条）：含「旧键已死 / 新键生效 / 恢复默认后按钮自己置灰」与
  **精确修饰键匹配**（`Alt+M` 命中 ≠ `Alt+Shift+M` 命中）。⚠️ 该套件的桩必须给
  `get_update_download_state → {state:'idle'}`、`check_update → null`、`plugin:window|is_maximized → false`，
  否则主窗启动期就崩（见「本地校验」里那条「桩不能一律返回 null」）。
- ❗**2026-10-02 默认值 `alt+m` → `alt+d`（用户点名）**：上面「精确修饰键匹配」的 Alt+M 例子随之作废
  （判定逻辑没动，还是修饰键精确相等）；只改默认值、不做迁移 —— 存过自定义和弦的照旧，没存过的吃到新默认。
  改后复核 `.workbuddy/tmp/verify-shortcut-default.mjs` **29 条**（**纯浏览器跑，无需 Tauri 桩**；见「本地校验」）。
- ❗**0.1.8 起这条键是系统级的**（`desktop-v0.1.8`，上面「两个窗口都要挂」的描述相应降级为**兜底层**）：
  - 宿主侧 `src-tauri/src/global_key.rs`（插件 `tauri-plugin-global-shortcut`）收键并切窗——判定必须在 Rust：
    主窗收进托盘时网页被隐藏、随时被 WebView2 节流。窗口显隐要 `run_on_main_thread` 回主线程（Windows 要求）。
  - **和弦权威仍在 localStorage**（`mm.shortcut.toggleWindow`）：Rust 读不到它，由**主窗**经
    `lib/globalKey.ts` → `set_global_shortcut(accelerator)` 推上去；`acceleratorOf` 负责「我们的和弦表示法 →
    宿主 accelerator」翻译（`meta→Super`，认不出的主键返回 null 退回窗口内监听）。悬浮窗只靠
    `global-shortcut-state` 事件跟状态。
  - **一次只保留一把键**（先 unregister 再 register）；⚠️ 同一把键重复下发必须空转——插件 `unregister_all`
    会先清账本再摘系统键，摘失败不回滚，下次注册直接撞 1409、键永久失效。注册失败时显式摘这一把再重试一次。
  - 注册失败**不静默**：状态（registered/accelerator/error）回给前端，两窗据此放开自己那条 `useShortcut`
    兜底，设置面板用 `.set-sub`（失败加 `.bad`）说清现在是「系统级已生效」还是「只在窗口里生效」。
  - **录制新键期间必须先摘掉系统级那把**（`clearGlobalShortcut`）：否则按键被系统吃掉、网页 keydown 收不到，
    不但录不上还会顺手切窗。退出录制（成功/取消/关面板）一律按 localStorage 现读那把重新挂回。
  - 验收注：「按下真的会切窗」这步**没法自动化**（合成按键被拦、不抢前台），只能用
    `RegisterHotKey` 探针（撞 1409 = 已被自家占住，配一把没人用的对照组合键证明探针本身有效）验注册生效，
    手感留给用户手按。

## 悬浮窗几何
前端只报**条数**（`set_mini_rows`），高度在 Rust 算。常量（行高 26 / 封顶 6 / 宽 **236**）权威定义在
`src/lib/layout.ts`，注入成内联 CSS 变量（theme.css 同名声明只是兜底）；Rust 的 `MINI_ROW_H`/`MINI_MAX_ROWS`/`MINI_W`
跨 FFI **手工对齐**。❗别改成「前端量高度上报」：窗口出生即隐藏，量不到排版 ⇒ 静默退化。调尺寸时**下边缘与 x 不动**。
宽度是**窗口**属性、CSS 无第二个旋钮 ⇒ 改宽度要同改 `layout.ts` + `lib.rs`，再用 `verify-mini-width.mjs` 按真实视口
（236×158）量。⚠️ 量文本宽度**必须用 `Range`**（克隆 `getComputedStyle().font` 偏大 20%）。`.mini-empty` 高度钉
`var(--mini-row-h)`（padding 撑是 43px ≠ Rust 算的 28px）。

## 侧栏与搜索
- **0.1.11：市场切换（现货 / 永续合约）从顶栏挪到侧栏顶部** —— `MarketList` 的 `.panel-head.list-head`
  竖排两行：`.market-switch`（两个 `.market-pill`、`flex:1` 等分、选中 `.on`）+ `自选 · N`。
  ❗用户点名「放到左侧菜单栏上面」；副作用是**侧栏收起（`.list-collapsed`）时胶囊跟着宽度归零一起消失**，
  想切市场得先展开侧栏（开合开关就在顶栏左端）。
侧栏 = 自选（名称 + 现价，取 `useTickers` 格子，零额外请求）；搜索结果走浮层（临时态塞进常驻容器会把自选冲掉）⇒
`components/SearchDropdown.tsx` 锚在 `.search-wrap`（**不是 input**：wrap 含「清空」按钮，量 input 会得出「错位 10px、
窄 42px」的假问题）。排序口径 = `lib/search.ts::rankInstruments` 纯函数。⚠️ 浮层键盘事件必须挂 `document`（焦点全程在
顶部输入框，事件不经过浮层 DOM）。
- **0.1.11：自选三个入口的分工**（用户点名；口径改过一次：先要「行内常显 ×」，随即又要求撤掉 ×、
  改「右键移除自选」）：① **行右键菜单**「从自选移除」（`MarketList` 里的 `RowMenu`，复用 `.chart-menu`
  壳与行为 —— 贴边翻转、点外面/Esc/滚轮收起、做完即收；单条 danger、aside 显示标的）——
  行上**不再挂任何按钮**；② 详情页标题旁的 ☆/★（`.star-btn`，`watched` = 当前标的是否在自选里，
  `App::toggleWatch` 复用 add/remove）；③ 搜索结果行内的 ＋/✓（`.row-add`，判重按 symbol）。
  ❗**整行点击 = 只查看，不进自选**（0.1.11 起）：`SearchDropdown::pick` 不再调 `onAdd`。为此 `App` 的选中校验
  从「必须在自选里」放宽成**只要求 `selected.market === market`** ⇒ 「搜到直接看图、不入列表」成立，且正在看的
  条目被行内 × / ☆ 删掉时**图不动**（真正需要回落的只剩换市场：`marketItems[0]` 或 null）。
**窗口出生即隐藏**：builder 里 `.position()` + `.visible(false)`（`build()` 返回时窗口已可见且在系统默认位置）。

## 图表纵向几何（`src/lib/chartLayout.ts`）
纵向几何的**唯一出处**：各段常量（`AXIS_W`/`TIME_AXIS_H`/`READOUT_LINE`/`READOUT_PAD`/`SUB_PANE_H`/
`SUB_READOUT_H`/`MAIN_MIN`）+ `chartHeights(availH, readoutRows, subPaneCount)` 纯函数。
`KlineCanvas` 只消费它，**别再在组件里手写第二份**（曾经分叉过，见下）。

规则（2026-09-29 收敛为一条）：**主图吃掉所有剩余空间**（下限 240，**无上限**），副图区刚性（每块固定 110px）。
默认窗口 1280×820 ⇒ 可视 652px：裸 K 线 = 读数带 18 + 主图 **616** + 时间轴 18 = 652；
3 块副图 = 18 + **286** + 330 + 18 = **652（正好放下、不滚动）**；4 块副图（主图已到下限 240）溢出 64px；
最小窗口 1280×680（可视 512）+ 3 块副图溢出 94px。
本文件**零依赖**（clamp 已不再需要），这样 `.mts` 单测能用 Node 直接跑（Node 的 ESM 解析不接受无扩展名相对导入）。

> ❗**0.1.10**：默认纵向留白 `chartMath.ts::DEFAULT_PADDING` 从 `0.06` 调到 `0.20`
> （蜡烛占主图高约 `1/(1+2×0.20)` ≈ 71%）—— 0.06 时最长影线几乎顶到边框，每换一个标的都要手动 Shift+滚轮缩一次。

- ❗**坑一（同日修）：主图有个 420 的上限**，`mainH = clamp(…, 240, 420)`。上限会**抢走本该让给别人的空间**，
  也会在**没人跟它抢时留白**：裸 K 线（没选任何指标）画布只有 18+420+18 = **456px**，可视区 652 ⇒ 图表区底下
  空 **196px**（1600×1000 下空 376px）。⇒ 上限**删掉**，只留 `MAIN_MIN`；不变量改成
  「放得下就填满（`totalH == avail && overflow == 0`）、放不下才如实溢出」。
- ❗**坑二（同日修）：`mainH` 的公式漏减了副图高度** ——
  `clamp(availH - readoutH - TIME_AXIS_H, …)` ⇒ 主图先吃满 420，副图再往下叠 ⇒ 总高 786 > 可视 652。
  第三块副图（RSI）落在滚动区下方**可见 0%**，而 `.chart-scroll` 的滚动条是被**刻意隐藏**的
  （`scrollbar-width: none` + `::-webkit-scrollbar{width:0;display:none}`，还有一条「滚动条也是版面上的线」的理由）
  ⇒ 用户看到的是「选了三个副图只显示两个」，且没有任何可滚的提示。
  **窗口若够高（1600×1000，可视 832）就三块全可见** ⇒ 这不是「副图数量上限」。
  修法是把副图高度**作为参数**纳入分配（不是就地补一行）：`mainH = max(240, avail - readout - n×SUB_PANE_H - TIME_AXIS_H)`。
- ⚠️ `availH` 来自 `ResizeObserver` 观察 `.chart-scroll` 的 **clientHeight**（容器高度，不受画布内容影响）
  ⇒ 主图让位不会形成循环。若哪天改成观察内容高度就会自激。
- ⚠️ 别给 `.chart-scroll` 恢复滚动条来「解决」这类问题 —— 那是设计决定；正确做法是让内容适配可视区。
- 真机验证：`verify-chart-layout.mts`（11 条纯函数，含「渲染 == 公式」与 0~6 块×300~1200 的**填满/溢出**不变量）、
  `verify-sub-panes.mjs`（**真实渲染** 28 条，跑「裸 K 线 / 3 块 / 4 块 / 大窗口 / 最小窗口」五个场景，量
  canvas 的 style.height 与 `.chart-scroll` 的 clientHeight，逐块算可见比例 + 截图）。
  ⚠️ 周期条里加控件会改这条链上的可视高（新按钮按通用 `.icon-btn` 尺寸比 chip 高 5px ⇒ 可视区少 5px），
  所以 `.interval-bar .icon-btn` 单独收了字号与内边距。

## 指标与副图的选择（0.1.11 起持久化）
- `lib/indicatorPrefs.ts`：`mm.indicators` = `{ ma: number[]; boll: boolean; panes: SubPaneKind[] }`。
  `parseIndicatorPrefs` 按支持集过滤（`MA_CHOICES` / `SUB_PANE_KINDS`，去重排序）、认不出的静默丢 ——
  与 `lib/intervals.ts` 同一套「解析即过滤」。`ChartView` 里**一个** `prefs` state 管三样（挂载读一次 +
  effect 落盘），别再拆回三个 useState。
- 语义没变：MA chip 切 `prefs.ma`、BOLL 切 `prefs.boll`、副图组切 `prefs.panes`；panes 按 `SUB_PANE_KINDS`
  目录顺序存，不按点击顺序。出厂 = **裸 K 线**（一个都不叠）；这是「怎么看图」与标的无关 ⇒ 全局一份。

## 界面文案（0.1.11 起全中文）
- 用户要求「页面上所有参数用中文」，但**通用技术指标名保留英文**（MA5/MA10/…、BOLL、MACD、RSI、KDJ、
  DIF/DEA —— 用户在示例文案里自己拍的「基本全中文」）。已改：K 线明细带 `开 / 高 / 低 / 收`
  （`KlineCanvas.tsx::drawCandleRow`，`较上根 ±x%` 不变）；概览四块 `24小时最高 / 24小时最低 /
  24小时成交额 / 24小时走势`；标的后缀 `永续 / 现货`；副图 `成交量`（`SUB_PANE_LABEL.VOLUME`，读数
  `成交量: …`）；周期用短单位（见「K 线周期」）。
- ⚠️ 改这些文案会牵动验收脚本的**文本断言**（画布上的字要靠 `fillText` 探针收，见「本地校验」）。

## K 线周期（`src/lib/intervals.ts` = 目录唯一出处）

- ❗**自定义周期只能「聚合」出来**：各家周期是一张固定白名单（实测 `45m`/`2m`/`7h`/`2M` 一律被拒），
  表外的值由**能整除它的最粗原生周期**拼（`aggregate.ts`），因子最小 ⇒ 请求根数最少。
  ⇒ 周期条上的 chip **不再置灰**（原先是「当前源不支持」就灰掉），只在 `title` 里说明怎么拼的。
- `IntervalPanel` **只有一组**（已选），也是**两列网格**，行数减半才装得进弹窗（见「顶栏与交互」里那条弹窗高度规则）。
  ❗**2026-09-29 用户要求去掉两样东西**：① 行上的**拖拽条/抓取把手**（`.iv-grip` 及其 hover/dragging 样式已从
  theme.css 删除，`RowInner` 里也不再渲染）；② 整个 **「未选」分类**（`rest` 列表与 `INTERVAL_CATALOG` 的 import 全删）。
  现在「添加周期」是**唯一**入口，且位置挪到已选列表**下方**。断言见 `verify-intervals-ui.mjs` 的
  `groupCount === 1` / 页面上不出现「未选」/ `gripCount === 0`。
- **已选是「整行直接拖」**（2026-09-29 改）：没有拖拽把手按钮，整行 `cursor: grab` + 画出来的点阵把手
  （`radial-gradient`，别用 `⠿` 这种盲文字符）。超过 4px 才算拖，否则纯点击会把顺序抖一下。
  行上有按钮时 `if (target.closest('button')) return`，别把点击吃成拖拽。
  - **幽灵要 portal 到 body + `position: fixed`**：行是整格宽、抓点常落在左半边 ⇒「贴在指针下」
    必然探出面板右边缘一大截；而 `.up-body` 是滚动容器（`overflow-y: auto` ⇒ 横轴也被算成 `auto`），
    留在里面就被裁掉三分之一**还多出一条横向滚动条**（截图为证）。原地留一个虚线空格
    （`.iv-row.slot`，内容 `visibility: hidden`）⇒ 网格不塌、落点判定与 FLIP 都不用特判。
  - **不要给幽灵加 `scale`**：缩放会挪走 transform 基准点，「抓点钉在指针下」是按左上角算的。
  - **挤动动效 = FLIP**：改列表前按**视觉** rect 拍快照 → 提交后（useLayoutEffect）先 `cancel()` 掉
    自己上一轮的动画再量新位置 → `el.animate` 200ms。先 cancel 才能「从半路接着走」；
    只 cancel 带 `anim.id === 'iv-shift'` 的，别连行上的 CSS 过渡一起掐。被拖那行（空格）**也要动画**。
  - 松手让幽灵 `transition: transform` 飞回格子，别凭空消失；清 state 前用 ref 比对兜住「组件已卸载/又拖了一次」。
- ⚠️ **两列网格里的拖拽落点不能只比 Y**：同一视觉行并排两格、纵向位置完全相同。
  基准取**每格中心**、按阅读顺序比（纵向中心差 < 半格高 ⇒ 比横坐标，否则比纵坐标），
  且**两侧都用「包含」** ⇒ 判据其实是**中点边界**：指针在某格右/下半 ⇒ 落在它后面，左/上半 ⇒ 落在它前面
  （所以「拖到第 2 格中心」= 落在索引 **2**，不是 1；写断言必须按这条来）。
  被拖的那行要排除在判定之外（列表是拖到哪就实时重排到哪，算进去会来回抖）。
  ⚠️ 判定一律用 **`offsetLeft/offsetTop`**，不要用 `getBoundingClientRect()` —— 其它行正在播挤动动画，
  rect 是**动画中途**的值，用它判会来回抖。
- **自定义周期 = 数值 + 单位（分/时/天/月/年）**，不再让用户手打 `45m` 这种字符串
  （`composeInterval(value, suffix)` 取代了旧的 `parseIntervalInput`）。单位表 `INTERVAL_UNITS`。
  口径写死：**月 = 30 天**（对齐各家最粗原生码 `30d`）、**年 = 12 月 = 360 天** ⇒
  `MAX_INTERVAL_MINUTES` 从 43200 抬到 **518400（1 年）**，`LADDER_UNITS` 顶部加 `y`/年。
  「12 月」与「1 年」必然合成同一个 `1y`（和「7 天」→ `1w` 同一条规范化）。`2y` 会被 `minutesOf` 拒掉（越界）。
目录 = **各数据源能力的并集** 15 个（1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M），
由用户在「周期」弹窗里挑、排（`components/IntervalPanel.tsx`：已选整行拖排序/`×` 删、下方「添加周期」用数值+单位造、恢复默认），
存 `localStorage mm.intervals`（**有序数组**，`parseIntervals` 过滤认不出的 id ⇒ 老值不会弄崩界面）。

- **0.1.11 周期文案改短单位**：`intervalLabel`（`15m→15分`、`1h→1时`、`1w→1周`、`1M→1月`，`y→年`；认不出的
  原样返回，**不做大小写归一**），周期条 chip / `IntervalPanel` 行 / 聚合 tag（`聚合 15分 × 3`）全走它。
  `intervalNote` = 「短名没说清同一件事才给第二行」：`90分 → 1 小时 30 分钟`，单成分周期返回 `''`
  （`15分` 旁再写「15 分钟」= 说两遍，用户要求砍重复）。`minutesLabel` 保留（完整读数；`intervalNote` 与
  `composeInterval` 的报错文案在用）。

- 用户改的是**列表与顺序**，不是能力：`supportedIntervals(market)` 才是「这家能不能用」——
  永续看当前方言的 `intervalLadder`（不另立一份支持清单，两份必然分叉），现货（Gate）全支持。
  周期条上**不支持的 chip 置灰 `disabled`**；当前周期若变成「不在列表里或不被支持」，
  由一条 effect **回落到列表里第一个可用的**（不回落的后果：`fetchKlines` 直接抛 ⇒ 图是空的而 chip 还亮着）。
- ⚠️ **`1m` 与 `1M` 只差大小写** ⇒ 任何比较都不许做大小写归一化（`1M` = 月线）。
- ❗实测纠正：Gate 的 ladder 原本漏了 `12h`（代码不认 ⇒ 界面把它当「不支持」置灰，实际可用）。
  逐周期打过 `/futures/usdt/candlesticks` 补上 `3m/12h/3d/1M`（`30d` = 1M）；
  **对照**：`7h`/`2m`/`bogus` 一律 400 ⇒ 200 是真的支持，不是宽容取整。
  Gate 现货同理全支持 15 个，只有两个名字不同（`1w`→`7d`、`1M`→`30d`）。
- 验收：`verify-intervals.mts`（18 条纯函数：目录/坏存储值/增删排序/按 ladder 翻译/回落）、
  `verify-intervals-ui.mjs`（36 条真实渲染：默认 8 个 → 添加 2h → 上移 → 关弹窗周期条跟着变 → 刷新仍在 →
  点了 2h 后**拦 fetch 断言真的请求了 `interval=2h`** → 预置 MEXC 源断言 12h 置灰 + 当前周期回落 → 恢复默认）。

## 价格告警（`src-tauri/src/alert.rs` + `hooks/usePriceAlerts.ts`，2026-09-30 新增）
宿主只出**节拍**与**通知通道**两样，其余（告警线、取价、穿越判定、文案）全在前端 —— 线存在 localStorage、
取价要过 `lib/dialects.ts` 那六家盘口的 URL 构造与解析，判定挪去 Rust 必然分叉。
- **节拍必须在宿主**：主窗点关闭是收进托盘，窗口一隐藏 WebView2 就把网页定时器当后台页节流 ⇒ 前端轮询会漏报。
  `alert::start()` 起线程每 **5s** `emit_to("main", "alert-tick", ())`；实测隐藏期间 13 拍间隔 `5000±1ms`。
- **只在主窗挂** `usePriceAlerts`（App.tsx）：悬浮窗是第二份 React 实例，两边都跑会一次穿越弹两条通知。
- `judgeTick` 是纯函数（`lib/priceAlerts.ts`）：首拍**静默建档**（不报开局就在线外的那些线），之后只在**真穿越**时报；
  线自带 `alert` 开关，未开的不参与判定。`busy` 重入锁：慢网里一拍没跑完就跳过下一拍，免得同一穿越报两遍。
- **通知走 notify-rust 直连**（不是 `tauri-plugin-notification`）：后者把 `show()` 的 Result 丢掉，
  Windows 上弹不出来也**静默不报错**；且不显式给 AUMID 时 dev 进程的通知会被系统收下、**横幅不弹**
  （Windows 比的是「进程 AUMID == 已注册 AUMID」）。现在显式 `app_id(identifier)` + `setup` 里
  `SetCurrentProcessExplicitAppUserModelID(bundle identifier)` ⇒ dev 与安装版同一条路径，错误如实回抛。
- 文案按方向分「上破/下破」（`usePriceAlerts.notify()`），宿主不参与措辞。
- ⚠️ 右键**一律弹自绘菜单**（`ChartMenu.tsx`，见 0.1.10 更新），「加告警线」是菜单第一项。

## 悬浮窗显示列表独立（`mm.miniWatchlist`，2026-09-30 新增）
悬浮窗显示的是**自选的一个子集**，与自选列表同表混排但**单独一份存储 + 单独排序**（数据在 `useWatchlist`，
UI 在 `MiniListPanel.tsx`，拖拽排序抽成 `hooks/useDragSort.ts`）。
- 两条拖拽语义**不同**：侧栏（自选）只在本市场内动；悬浮窗弹窗里是**整行跨市场混排**排序。
- 进 `mm.miniWatchlist` 的键与自选同形（`市场:标的`），删自选时同步剔掉。

## 画线（`src/lib/priceLines.ts` + `src/lib/trendLines.ts` + `hooks/useDrawings.ts`）
> ❗**0.1.10 更新**：右键**一律弹 `ChartMenu`**（空白处「设成告警线 / 画一条水平线」、线上「取消这条告警 /
> 删掉这条线」、直线上「删掉这条直线」、纵向刻度被拖过再加「复位纵向刻度」），下面的「不弹自绘菜单」已作废。
> 菜单能自绘是因为**主窗有 1024×680 的底**、画布不是整个窗口，贴边翻得回来；悬浮窗那边仍只能走原生 popup
> （就一块面板，浮层必被窗口边界裁掉）。

> ❗**0.1.10 更新**：指标/副图/画图**另起一行**挪到周期行**下面**（`ChartView` 里第二个 `.interval-bar`，
> 两行都在 `.bar-stack` 内），仍然每行只展开当前一组；挪行是为了让「选周期」和「选指标」在视觉上分开。
> 图表纵向几何（上文的 `chartHeights`）因此多了一条工具条的高度 —— 改工具条高度等于改可视高。

> ❗**0.1.11 更新**（2026-09-30 用户拍板「移除画图和后面的选择，直接在右键菜单里做，右键菜单也添加清空」）：
> **工具条上的「画图」整组拿掉**（`直线` / `锁定` / `清空 N` 三个 chip + 手势提示全删），第二行只剩
> 指标 / 副图 / 条件性的 `告警 N` 读数（`.bar-label.alert`，只说「有几条告警线在盯着」）。
> 所有增删改一律走**右键菜单**（`ChartMenu.tsx` 是画线上唯一入口）：空白 = 设成告警线 / 画一条水平线 /
> **从这里画直线**；线身 = 设(取消)告警 / 删掉这条线；直线线身 = 删掉这条直线；一律附一条
> `清空全部画线`（右侧报总数，一条都没时不出现）。⇒ **画布对已有的线是只读的**：没有抓取、没有拖动，
> 光标不再有 grab/move/ns-resize（只剩「复位小标」给 pointer），`mode` 只剩 `'none' | 'pan'`。
> 起笔 = 菜单里「从这里画直线」把键点落成 `TrendSeed{anchor,seq}`（**seq 递增**才好重复触发同一点），
> 之后鼠标动 = 预览虚线、左键落第二点成线、Esc / 右键撤销这一笔（右键撤销排在「线身命中」之后 ——
> 落在线上时右键仍是操作那条线）。「从这里画直线」不再需要「画线开关」这个状态，`drawTool` / `trendArmed` 全删。
> 验收 `.workbuddy/tmp/verify-draw-menu.mjs`（**无头 Chrome，PASS 71/71**：工具条无「画图」字样、
> 空白菜单三条、单击/拖拽画布不落线、预览/Esc/两点成线、**按在线上拖 = 平移画布且落盘逐字节不变**、
> 刷新落盘、清空两种线一起清）。`movePriceLine` / `moveTrendLineBy` / `moveTrendAnchor` 三个写操作
> 从 `useDrawings` 摘掉（库里的纯函数留着，`.mts` 验收还在用）。
> ⚠️ 右键菜单是**自绘 HTML**（`createPortal` 到 body、`position: fixed`、z 62）：主窗有 1024×680 的底，
> 贴边能翻回来；悬浮窗那边才非得走原生 popup（那块面板一整块就是窗口）。

> ❗**2026-10-03 更新**（用户拍板：右键菜单加「锁定/解锁全部画线」；**粒度 = 整张图一个开关**（该标的的
> 水平线+直线一起，明确否掉逐条锁）、**默认解锁**）—— 对上面「画布只读」的**有约束回退**：解锁时可拖、
> 锁定时拖不动；锁**只管「拖」**，删线/告警/清空/画新线照旧。
> - `lib/linesLock.ts`：`mm.linesLock` = `Record<string, true>`，**只存 `true`**、键不存在 = 解锁（默认解锁 ⇒
>   老数据零迁移）。`withLinesLock(store, key, locked)` 解锁即删键；作用域与画线同键（`市场:标的`）。
> - `useDrawings`：增 `linesLocked` / `setLinesLocked` + 拖动三 API（`movePriceLine` / `moveTrendBy` /
>   `moveTrendAnchor` 重新接线到库里那三个纯函数）。`ChartMenu`：`drawingCount > 0` 才给这条
>   （没线可锁不出现），文案随 `linesLocked` 翻转；`MENU_H` 208。
> - `KlineCanvas`：`mode` 恢复 `'trend-anchor' | 'trend-body' | 'line'`；按下时抓取优先序
>   把手(6px) → 线身(4px) → 水平线(4px)，**整段（含空闲光标 grab/move/ns-resize）当且仅当 `linesLocked` 跳过**；
>   锁定时落点是「拖画布」（平移照旧）。把手（端点小圆点）只在解锁时画 —— 0.1.12 那条「不给能抓的错觉」就是这条。
>   拖动位移按**当前映射**换算（线身：`timeAtIndex(indexOfX(x))` 之差 + `priceOfY` 之差；端点/水平线：绝对定位），
>   指针坐标先夹进主图（甩出画布锚点不飞）。拖动重画走「state → props → modelRef → 绘制 effect」，拖拽分支不直接 draw。
> - 验收 `npm run build` 绿 + `.workbuddy/tmp/verify-lines-lock-drag.mjs`（**无头 Chrome，36 条全 PASS**）：
>   拖水平线改价落盘 → 锁后无提示/拖不动（pricePan 动了 = 落点退回拖画布）→ 解锁又能拖；直线端点 grab /
>   线身 move / 平移两端 t 同增（增量 = 80px 折算，半根容差）/ 拖端点 a 不动 b 随指针；把手黄色像素差分
>   （解锁 198 / 锁定 127）；锁定期两次拖尝试后双击复位；无页面 JS 报错。
> ❗**右键落点先钉十字光标**（2026-10-02）：`onContextMenu` 里先把 `crossRef.current` 钉到这一下右键的
> `{index, y, onBar}`（与 `pointermove` 同一套算法）并 `draw()`，之后才判命中、才 `priceOfY(y)` 算菜单价 ——
> 菜单里的价和图上那枚价格标必须是**同一个 y** 出来的。光标跟着 `pointermove` 走，而右键事件的坐标可能和
> 最后一次移动差一个物理像素（Retina 上就是半个 CSS 像素），这个刻度下足以差出 6 个价格单位
> （菜单 3853.37 / 标 3847.10，用户截图报的就是这个）。验收脚本扩到 **78 条**：第 9 节用合成
> `contextmenu` 事件「hover 一处、落点漂 3px」验价格标跟着落点走、且漂移与不漂移两帧**逐像素相同**。
> ❗**0.1.8 更新**（`desktop-v0.1.8`）：指标条改**四格单选**（周期/指标/副图/画图，`ChartView` 的 `BAR_TABS`），
> 每行只展开当前一组，1280 宽不再折行吃图高。画线工具（水平线/直线）收进「画图」格，进那一格才能动线、
> 离开即退回水平线；**那把全局锁 `mm.priceLineLock` 整个删了** —— 「能不能改线」和「选在哪一格」是同一个开关，
> 留两层反而误导（chip 亮着、图上一片死寂）。所以下文凡提「锁 / 锁定 chip / `locked`」的都已作废。
> 验收 `verify-trend-lines.mts` / `verify-price-lines.mts` 里「锁住后右键/拖拽全不生效」那类断言随之作废，改测
> 「切到『画图』格才动得了线」。
右键主图 = 加/删水平线（**不弹自绘菜单**：只有两个操作，菜单多一次点击，且会被窗口边界裁掉）；
两点直线 = 「直线」chip 进画线模式，图上点两下、或按住直接拖出来（**贯穿全图的直线**，同花顺「直线」工具的口径）。
- `lib/priceLines.ts`（零依赖纯函数）：`parseLineStore` / `addPriceLine`（同价不重复）/ `removePriceLine` /
  `movePriceLine` / `nearestLineWithin(ys, y, tolerance = 4px)`。
  ❗**数组顺序 = 添加顺序，绝不许排序** —— 拖拽期间下标必须稳定。
- `lib/trendLines.ts`（零依赖纯函数，2026-09-29 新增）：`parseTrendStore` / `addTrendLine`（两点完全重合不画）/
  `removeTrendLine` / `moveTrendLineBy`（整体平移）/ `moveTrendAnchor`（拖一端，另一端当支点）/
  `clipLineToRect`（Liang–Barsky，无限直线裁到主图）/ `distanceToLinePx` / `nearestTrendHandle`（6px）/
  `nearestTrendBody`（4px）。**把手容差必须大于线身**：把手是「改角度」的唯一入口，难点中就等于没法编辑。
- ❗直线的锚点是**数据空间**（时间戳 ms + 价格），不是蜡烛下标：序列是滚动的 300 根窗口，下标会漂，
  时间戳不会 —— 刷新/换周期后线还落在同一段行情上。屏幕坐标由 `KlineCanvas.anchorPx` 实时换算，
  **绘制与命中判定共用这一处**（两处各写一份必分叉，`xOf` 就吃过这个亏）。
  配套 `chartMath.ts` 的 `timeIndexOf`/`timeAtIndex`（严格互逆：数据内按相邻两根真实间隔插值 —— 1M 是
  28~31 天、缺根的洞同理；首尾之外按 `intervalMs` 外推 —— 线画到未来/过去都合法）。
- `hooks/useDrawings.ts`（`usePriceLines` 扩成）：`mm.priceLines` + `mm.trendLines`（同一套 `市场:标的` 键）+
  `mm.priceLineLock`（**一把锁管两种线**）。所有写操作走**函数式 setState**（调用方是每 `pointermove` 一次的手势）。
- `KlineCanvas.tsx` 手势机：`type Mode = 'none' | 'pan' | 'line' | 'trend-body' | 'trend-anchor' | 'draw'`。
  - 「直线」chip 点亮（`trendArmed`）时**左键整体让给画线**：平移与抓取都让路。点两下成线（第二点 <8px
    当作空点、保持待定），按住拖 ≥8px 也成线 —— 两种手势同一条收口。Esc / 右键 / 锁住 / 换标的的复位都退模式。
    读数带与时间轴里的点击不算锚点（与「只有主图里能画水平线」同规）；拖动预览甩出主图不受限（取到更远的
    时间/价格，锚点仍良定义）。
  - 未锁时的抓取优先序：端点把手 → 直线线身 → 水平线（= 绘制顺序的逆序）；画线模式的判断在这三者**之前**。
  - 线身平移的位移按**当前映射**换算成数据空间增量（`timeAtIndex(indexOfX(x))` 之差 + `priceOfY` 之差），
    **别自己推「像素 / 格宽 × 周期」**—— 视窗被夹住时那个公式不成立。
  - `onContextMenu` 顺序：锁住 ⇒ 什么都不做；画线模式 ⇒ 退出（模式内右键不删除：左键画/右键删会让同一张
    画布手感分叉）；否则把手 → 直线 → 水平线，都不中才在落点价格新增水平线。`Esc` 挂 document（canvas 不可聚焦）。
  - `onPointerDown` 先 `if (e.button !== 0) return`；副图区 `belowMain(y)` 直接 return（副图全只读，含 `onDblClick`）。
  - `onWheel`：`belowMain(y)` 时 **return 但仍 `preventDefault()`**（不挡的话浏览器去滚 `.chart-scroll`，
    等于「副图滑轮动了主图」）。
  - 空闲光标：命中把手 `grab`、线身 `move`、水平线 `ns-resize`、画线模式 `crosshair`；十字光标**照旧贯穿副图**
    （只读但可照价）。
- 绘制（**0.1.12 起三线分色**：「当前价格 灰 / 告警 红 / 水平线+直线 黄」，用户点名）：
  - 水平线/直线 = 黄 `--line-mark: #f0b90b`（**两主题同值**，深浅底都读得出；原来的 dark `#8b9bff` /
    light `#4b5bd6` 已撤）。水平线 `[5,3]` 虚线，画在**最新价虚线之前**；其价格标也画在最新价标**之前**，
    同高时被最新价盖住。直线**实线**（同色系不同线型：虚线读作「一个价位」、实线读作「一条斜率」），
    两锚点决定方向、向两端延伸到主图边界裁掉；端点小圆点只在未锁时画（锁就是「别再动它」，不给能抓的错觉）。
  - **告警水平线 = 红 `--down`** `[1,3]` 点线（底色徽标 + `--paper` 字）；工具条「告警 N」读数同色。
    ⚠️ 红与跌 K 同色 ⇒ **画布内扫色判不了告警线**（验收改扫右轴徽标带 `plotW+3` 起 30px，只有它在那儿是红的）。
  - **最新价线 = 灰 `--muted`（palette 的 `label`）** `[4,4]` 虚线：0.1.12 起不表态方向（原来是涨绿/跌红）。
  - 价位徽标**底色跟随线色**；**黄徽标配 `--on-accent` 深字**（`palette.onAccent` 新字段——黄底配 `--paper`
    字在浅色主题下读不出来）；告警/现价徽标仍 `--paper` 字。
- UI（指标条末尾）：`画线` 标签（说明进 `title`）+ `直线` chip（锁住时禁用）+ `锁定/已锁` chip +
  `清空 N` chip（**两种线合起来计数**）。
  ❗这两个控件是必要的：锁持久化在 `mm.priceLineLock`，没有可点入口用户找不到怎么解锁 / 怎么清理。
  ❗**1280 宽下指标条只剩 ~30px 富余**（内容 ~1015 / 可用 ~1046，侧栏展开时）：标签一律要短、说明进 `title`。
  本轮加「直线」chip 时就是差这 40px 折成了两行（画布 600 → 628 高），最后把「副图 · 可多选」压成「副图」才收住。
- `modelRef` 扩到 `{ series, heights, interval, tickSize, priceLines, trendLines, linesLocked, trendArmed,
  onAddLine, onRemoveLine, onMoveLine, onAddTrend, onRemoveTrend, onMoveTrendBy, onMoveTrendAnchor, onDisarmTrend }`，
  同步 effect **不写依赖数组**且声明在绘制 effect **之前**（事件监听只在挂载时绑定，不能拖拽中途解绑）。
- ❗`ResizeObserver` 必须同时存**宽和高**：`const [box, setBox] = useState({ w: 0, h: 0 })`。只存高时「只改宽度」
  会让 `setState` bail out ⇒ canvas 位图不重排、被 CSS 拉糊（侧栏收起正是这种「只改宽度」）。
- 验收：`verify-price-lines.mts`（16 条纯函数）、`verify-chart-interaction.mjs`（43 条真实渲染，
  含副图只读配主图对照、`attrW === clientW × dpr` 锁位图同步、刷新后线还在）；
  **两点直线**：`verify-trend-lines.mts`（**140 条纯函数**：坏存储值、增删、平移/绕端点转、裁剪含水平/垂直/陡线/
  擦角点/整线在外、命中容差边界、时间换算往返含缺口与单根、100 点单调性扫描）+
  `verify-trend-lines-ui.mjs`（**68 条真实渲染**：点两下成线、按住拖成线、拖线身（像素位移 = 拖拽距离）、
  拖端点（线仍穿过另一端）、右键删、锁住后右键/拖拽全不生效、清空两种线、刷新后锚点逐字节相同、
  平移/缩放后锚点不变、待定 + Esc 取消、画线模式内右键只退出不删；像素判据 = 线色像素数 + 列增量 +
  **左右边缘条带都有差异**（贯穿全图，不是 A-B 线段））。两个脚本都靠预注入 fetch 桩喂确定性 K 线，不碰网络。

## 十字光标（`lib/chartMath.ts::overDataAt`，2026-09-29 新增）
❗**竖线只在「真压在某根蜡烛上」时才画；视窗右侧的留白里不画竖线**（横线与右上角价格标**照旧**画，
读数不受影响）。用户的原话是「鼠标放在最新 K 线的右侧，最新 K 线上的竖线不该展示」。

- 根因：视窗右侧有留白（`Viewport.rightOffset`，默认 3 根），那段位置**没有蜡烛**，但 `indexAt` 会把
  结果**夹回最后一根** ⇒ 竖线画在最新那根上，看上去像选中了它。**不能靠改 `indexAt` 修**：它被
  读数、价格标、横向线共用，夹回是它的正确语义。
- 所以单加一个**纯几何判据**：`overDataAt(v, fraction, barCount)` = 浮点下标落在
  `[-0.5, barCount - 1 + 0.5]`（一根蜡烛占 `[中心-半格, 中心+半格]`，判据与画法同源）。
  夹 `fraction` 到 `[0,1]` 与 `indexAt` 一致 ⇒ 落到右侧价格刻度列上也判 false。
- `KlineCanvas` 的 `crossRef` 因此多了 `onBar: boolean`，只在 `onBar` 为真时 `vline(...)`。
  `fraction = px / plotW`（**注意是相对绘图区宽度**，不是 canvas 宽度——右边还有价格刻度列）。
- 验收：`verify-desktop-math.mts` 里 6 条**边界**断言（半格处分界、`-1`/`2` 夹成 `0`/`1`、
  与独立复算一致的 100 点扫描性质核对、「true ⇒ indexAt 给的必是离 raw 不超过半格的那根」）；
  `verify-chart-interaction.mjs` ⑪ 用**像素列增量**验真实渲染（压在蜡烛上时该列增量 ≥ 200px、
  挪回留白后增量归 0）——**「挪回去又消失」这条必须有**，否则只是「画上去没擦」。

## 前台不息屏（`src-tauri/src/awake.rs`，2026-09-30 新增）

- 口径（用户拍板）：**任一窗口有焦点才算前台** —— 主窗/悬浮窗谁在前台都压住息屏；全丢焦点（切去别的应用）
  就松开，按系统原本的时间息屏。「窗口只摊着但没焦点」不算。
- 调用点只有一个：`run()` 里 `on_window_event` 的 `WindowEvent::Focused(_)` → `awake::refresh(app)`（幂等）。
  它必须在事件循环线程上跑：macOS 那支要读 `NSWindow.isKeyWindow()`（AppKit 只许主线程碰）。
- macOS：IOKit 断言 `IOPMAssertionCreateWithName(PreventUserIdleDisplaySleep)`。语义（本机 SDK `IOPMLib.h`）：
  屏幕不因空闲关闭，且**屏幕压着时系统也不会 idle sleep**。`IOPMAssertionRelease` 松开；断言归属进程、
  任何线程都能建/销（`static HELD: Mutex<Option<AssertionId>>` 防重复建）。失败只 `log::warn`，退化成照常息屏。
  验收 = `awake::imp::tests::display_sleep_assertion_round_trip`（建 → `/usr/bin/pmset -g assertions` 里按**自己 pid**
  查到这条 → 松开 → 查不到）。⚠️ 直接链 IOKit：没有官方 Tauri 插件干这件事；`core-foundation = "0.10"`
  复用已锁版本（0.10.1，不拉新树）。单测与实现同进程 ⇒ pmset 查自己 pid 就是真凭据。
- Windows：`SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED)` 压住、
  只留 `ES_CONTINUOUS` 松开。❗这个状态是**线程态**的：压住与松开必须同一条线程，所以养一条常驻线程靠
  `mpsc` 收请求（`static TX: OnceLock<Sender<bool>>`），谁调 `set` 都只是发消息。少写 `ES_SYSTEM_REQUIRED`
  机器照样能睡（屏幕一样会黑）。新依赖 feature = `Win32_System_Power`。
- 其它平台空实现（本项目只发 Windows 安装包；macOS 是 dev 机上的顺手实现）。

## 悬浮窗压在任务栏之上（`src-tauri/src/taskbar/`）
`mod.rs` + `zorder.rs`（纯 Win32）+ `guard.rs`（事件源）。任务栏与悬浮窗同为 topmost，任务栏被激活时会被 shell 提到
topmost 组最前盖住悬浮窗，而**窗口消息/Focus 事件一概收不到**（连 `Focused(false)` 都没有）。

- ✅ **2026-09-29 已从「500ms 轮询 z 序」改成事件驱动**：`SetWinEventHook(EVENT_OBJECT_REORDER, …,
  WINEVENT_OUTOFCONTEXT)` + 后台线程 `MsgWaitForMultipleObjects(2000ms, QS_ALLINPUT)` 循环（顺带当兜底，钩子失效也不瞎）；
  **回调是空实现**（事件本身就把循环唤醒，在里面做重活会拖住消息派发）；每轮按 可见 → `is_buried_by_taskbar`（沿
  `GW_HWNDPREV` 只走 topmost 组）→ 100ms 节流 → `raise` 处理。句柄由 `create_mini` 经 `taskbar::claim(&window)` 认领，
  守护线程只碰 HWND + 纯 Win32、**不回主线程**。真机纠正延迟 **1.0~1.9ms**（旧版 200~400ms）。
  ⚠️ `SetWinEventHook` 返回 `HWINEVENTHOOK` 而非 `Result` ⇒ 判失败用 `hook.0.is_null()`。
- ❗**取句柄是平台相关的**：`WebviewWindow::hwnd()` **只有 Windows 目标有** ⇒ 那句必须关在 `#[cfg(windows)]` 里。
  曾把它直接写在 `create_mini` 里 ⇒ Windows job 编得过、macOS job 报 `error[E0599] no method named hwnd`，
  发版 CI 直接红（**只跑本机 Windows 的 `cargo check` 看不出来**）。现在封装成 `taskbar::claim(&window)`：
  Windows 版内部取句柄、非 Windows 空实现，调用点不出现 `cfg`。本机**无法**交叉 check macOS
  （缺 C 编译器 `cc`，某依赖的 build script 要它）⇒ 平台相关改动只能靠 CI 的 macOS job 验。
- ✅ **可观测性（实测，推翻了旧笔记「完全没事件」的结论）**：任务栏提升 = 一次 z 序重排 ⇒ **桌面窗口（类 `#32769`）
  收到 `EVENT_OBJECT_REORDER`**（3/3、再 5/5 次全收到，延迟同刻）；钩子可装在**无窗口的后台线程**（5/5 收到）。空闲 3s
  该事件 **0** 条（有交互时 ~2.8/s）；我们的 raise 自身会产生 **4** 条 ⇒ 要节流。`EVENT_SYSTEM_FOREGROUND` **不覆盖**该
  场景（任务栏提升时不发）⇒ 别当触发源。真实鼠标点任务栏**空白处**并不提升它（发 reorder 但未被压）⇒ 遮挡来自真正的
  shell 交互（Win 键/图标/开始菜单）与程序化提升。
- ❌ **已否决**：`owner = 任务栏句柄`（严格对照实验：无 owner 能压住 ✓ / 设 owner 后照样能压住 ✓ / 拆掉又能压住 ✓；
  ⚠️ 第一次跑得出「owner 生效」是**假绿** —— 没先验证 shove 本身有效）；uiAccess / 更高 window band（要签名 + 装
  Program Files）；AppBar（解决的是预留屏幕空间，不是 z 序竞争）；`WH_MOUSE_LL`（触摸/键盘路径漏、拖慢全局鼠标、
  超时被静默卸载）。
- ⚠️ `set_always_on_top(true)` **重复调用无效**（tao 的 `set_window_flags` 有 flags 去重，`diff == empty` 直接 return）
  ⇒ 必须自己调 Win32；对**已是 topmost** 的窗口提 `HWND_TOPMOST` 也可能 no-op ⇒ 验证脚本要先降 NOTOPMOST 再升
  （成功率约一半，得重试到成功，否则「没被压住」会被误读成「已纠正」= 假绿）。依赖 `windows = "0.62"`（与 tauri 同版本）。

## 身份与构建
- **两条线必须分得开**：安装版 = `com.waxilo.marketmonitor` ⇒
  `%LOCALAPPDATA%\MarketMonitor\market-monitor.exe`（⚠️ 实测 exe 名是**小写**的 `market-monitor.exe`，而 Windows 的
  `taskkill /IM` **不区分大小写** ⇒ 会连用户正在跑的实例一起杀，真机脚本一律**按 pid** 收尾、按 pid 认领窗口）；
  dev = `tauri.dev.conf.json` ⇒ `market-monitor` + `com.waxilo.marketmonitor.dev` ⇒ `target/debug/market-monitor.exe`
  （dev 二进制名由 **cargo 包名**决定）。日常调试用 **`npm run tauri:dev`**；`npm run tauri dev` 走生产身份、与安装版共数据目录。
- ❗**不要开关/覆盖用户本机正在跑的那份应用**（用户明确抗议过）。dev 与安装版自选不是同一份；dev 也不进更新通道
  （`check_update` 在 `cfg!(debug_assertions)` 下返回「开发版不检查更新」）⇒ 装一次更新会把调试实例换成正式版。
- 构建：`npx.cmd --yes @tauri-apps/cli@^2 build`（裸 `npx tauri` 失败）。**改了前端也要 cargo build**（debug 内嵌 `../dist`）；
  反之 **`cargo build --release` 不会因 `dist/` 变了而重链接**（cargo 指纹里没有前端产物 ⇒ 打 `Finished` 但 exe 还是旧的，
  曾据此误判「改动没生效」）：要么 `tauri dev/build`，要么 `touch src-tauri/src/lib.rs`。
  ⚠️ **`icons/` 同病**：`tauri-build` 只给 tauri.conf.json / capabilities 声明 rerun-if-changed，**不含 `bundle.icon`**
  ⇒ 单独换图标时 build script 不重跑、`resource.lib` 停旧图、app crate 也不重编。已在 `src-tauri/build.rs` 里
  `println!("cargo:rerun-if-changed=icons")` 修掉（指目录即可，cargo 会递归）。
- 沙箱里 `npm run tauri dev` 死在 `os error 231`（tauri CLI 用 `cmd /S /C` 起 beforeDevCommand，管道撑不住）⇒ 先自己起
  Vite（`npm run dev -- --port 5173 --strictPort`）再 `tauri dev --no-watch -c <覆盖配置>`（覆盖配置把 `beforeDevCommand`
  置空，放 `.workbuddy/tmp/`，别写进仓库）。tauri 配置是严格 schema，自定义键会被拒。❗WebView2 远程调试口走不通。
- **真机验证要用 dev 身份构建**（数据目录与安装版分开，不碰用户那份）：
  `tauri build --debug --no-bundle -c src-tauri/tauri.dev.conf.json -c ../.workbuddy/tmp/tauri-test-build.json`
  （后者只有 `{"build":{"beforeBuildCommand":""}}`）。

## 应用内更新 = 自动下载 + 提示安装
启动静默检查（`CHECK_INTERVAL_MS` **1h**；manifest 走 `github.com/releases/download/...` **不是 api**，60 次/小时限额
不适用）一发现新版就下载 + 验签，下完主窗右下角弹 `.up-ready`（`components/UpdateReady.tsx`），点「安装并重启」。
- ⚠️ **两个纯函数别删**：`check_effect`（版本 + 下载地址 + **签名**全同才算同一个包 ⇒ 保留已下字节与在途进度；以前无条件
  清空，自动后「随手点一下检查」就把包丢了）、`should_write_back`（`download_gen` 代次，换包丢弃在途结果）。
  `start_update_download` 对 `Done` 直接 no-op。
- ⚠️ 前端 `check()` 后必须**回读** `get_update_download_state`，不能自己重置换算（否则界面退回「下载更新」按钮、点了只回
  no-op，永远卡在「没下过」）。「稍后」记 `mm.updateReadyDismissed` **按版本**比（记布尔＝永久锁在旧版）。提示条只在主窗；
  安装包留**内存**，没装就退出要重下。`.up-badge[data-state]`：running 空心环 / ready 实心点。

## 本地校验
`npm run build` = `tsc --noEmit && vite build`；Rust 单测 `cargo test --offline`（**11 条**）。
**2026-09-29 全绿口径**（纯函数 104 + 真实渲染 362 + Rust 11）：
纯函数（Node 直接跑 `.mts`）：`verify-desktop-math.mts` **18**（价格量程 + 十字光标 `overDataAt`）/
`verify-chart-layout.mts` **11** / `verify-drag-threshold.mts` **22**（位移死区边界）/
`verify-intervals.mts` **37** / `verify-price-lines.mts` **16**。
真实渲染（无头 Chrome + CDP）：`verify-desktop-settings.mjs` **47**（设置三段 + 快捷键录制全流程）/
`verify-desktop-ui.mjs` **22** / `verify-intervals-ui.mjs` **53**（周期弹窗与置灰）/
`verify-chart-interaction.mjs` **49**（含十字光标竖线有/无对照）/ `verify-source-panel.mjs` **29** /
`verify-desktop-search-drop.mjs` **38** / `verify-desktop-auto-update.mjs` **20** /
`verify-mini-interaction.mjs` **22** / `verify-mini-drag.mjs` **42**（真手势位移判据）/
`verify-mini-width.mjs` **12** / `verify-sub-panes.mjs` **28**（纵向空间分配，五场景）。
**2026-10-02 新增**：`verify-shortcut-default.mjs` **29**（快捷键默认值改 `alt+d`：上半纯函数 —— 默认和弦 /
`formatChord`+`acceleratorOf` 翻译链 / Alt+M 事件作废；下半无头 Chrome —— 面板显示「Alt + D」且「恢复默认」
置灰、窗口内 Alt+D 被吃掉而 Alt+M 不再命中、录制成 `alt+shift+d` 后新键生效老键让位、恢复默认删落盘键且
Alt+D 立刻复活。**纯浏览器跑，不碰 Tauri 桩**，可作后续快捷键改动的基线）。
**2026-10-03 新增**：`verify-lines-lock-drag.mjs` **36**（画线拖动 + 右键锁：拖水平线改价落盘 → 锁定后
悬停无提示/拖不动（落点退回拖画布）→ 解锁恢复；直线端点 grab / 线身 move / 平移与拖端点的落盘语义；
把手黄色像素差分验「锁定时把手消失」）。靠预注入 fetch 桩喂确定性 15m K 线，不碰网络。
**2026-09-30 新增**：`verify-draw-menu.mjs` **78**（右键菜单画线 + 画布只读 + 第 9 节右键落点钉光标；
**0.1.12 起支持** `VERIFY_THEME=light` 跑浅色，浅色截图带 `-light` 后缀，主题经 `addInitScript` 传参）/
`verify-zh-prefs.mjs` **95**（0.1.11 第一批：出厂浅色 + 自选迁移 + 侧栏市场胶囊 + 中文文案 + 周期面板 +
指标持久化 + 坏值容错 + 老机器迁移「自己加回的不会被二次清」；第二批：**顶栏几何**（1280/1600 搜索中心 =
顶栏中点 ±1、左右两组等宽、1024 不重叠不折行、`.grow` 与顶栏胶囊计数为 0）+ **行上无按钮 + 行右键菜单**
（菜单内容/Esc 收起不动数据/点条目真的移除）+ **搜索行只查看不进自选**（点行后 `mm.watchlist` 逐字节不变、
星标 ☆、侧栏无选中行）→ **☆/★ 往返**（加入/移除、侧栏跟着多/少一行、正在看的图不动）→ 行内 ＋ 仍能加
→ 右键移除正在看的条目、图仍不动）。
- ❗**画布上的文字没有 DOM**：断 K 线明细（`开/高/低/收`）、读数带（`MA5: …`）这类文案要打
  `CanvasRenderingContext2D.prototype.fillText` 把文本收进 `window.__texts`（配 `__textsClear()` 分阶段读），
  在 init 脚本里注入。
- ⚠️ **脚本之间会互相踩出厂默认**：0.1.11 起 `mm.theme` 缺省是浅色（颜色断言写死深色的脚本要显式预置
  `'dark'`），`mm.watchlist` 缺 `mm.watchlistSeeded` 时会被一次性迁移覆写（自己预置自选的脚本要补 `'1'`）。
  `verify-draw-menu.mjs` 两处都已补。
`verify-source-panel.mjs` 的反面控制：不加 `--hide-scrollbars` + 注入 2000px 内容验证探针能测出 15px。
其它工具：`check-exe-icon.py`（**exe 内嵌图标 vs ico 逐图 sha256 对账**，`--locate` 看字节落在哪个段）/
`icon-lab.html`·`icon-preview.html`（图标候选对比 / 真实产物预览）/
`verify-desktop-update-chain.mjs`（**转发壳** → skill 的 `scripts/verify-channel.mjs`）/ `smoke-desktop.py`（几何，需 DPI 感知）/
`verify-mini-taskbar.py`（真机 z 序，**referee 对照**）/ `probe-topmost-events{,2,3,4,5}.py`（z 序事件探测，
**自建测试窗口、不碰用户的实例**）/ `list-mm-procs.py`（收尾查遗留进程）。方法学在 skill `web-ui-change-verify`：
- **假 IPC 必须导航前注入**（`Page.addScriptToEvaluateOnNewDocument`）—— `IS_TAURI` 是模块加载时求值的，晚一步应用按
  「浏览器预览」短路，绿也是假的；且要补 `metadata.currentWindow.label` 等（缺了 `getCurrentWindow()` 在 **effect 里**
  抛错、整树卸载 ⇒ 表现为「新功能没反应」）。
- ❗**桩的 `invoke` 绝不能一律 `Promise.resolve(null)`**（2026-09-29 实测两次踩同一个坑）：主窗挂载时
  `useUpdate` 会 `setDownload(await fetchDownloadState())`，拿到 `null` 之后 `download.state` 在**渲染期**抛错
  ⇒ React 整树卸载 ⇒ `.toolbar` 消失。表现极具误导性：**看起来像「顶栏拖不动窗口」**（`verify-mini-drag` 阶段 C
  一直报 `start_dragging 0 次`），实际是页面已经空了。要按命令给最小可用默认值：
  `get_update_download_state → {state:'idle'}`（缺它必崩）、`check_update → null`、`plugin:window|is_maximized → false`。
  **并且要正面钉一条**「等 500ms 后 `.toolbar .mini-entry` 仍在」——「出现过」不等于「还在」。
- ❗**跨导航的记账要在 `navigate()` 里自动 harvest**：`__all` 活在页面里、一次导航就清零，靠「阶段边界手工 concat」
  必然会漏（`verify-mini-drag` 曾漏掉整个阶段 B，于是总账「`switch_to_main` 恰好 2 次」静默报了 0 次）。
  另外**形状要统一**：页面里的 `__all` 存 `{cmd,args}`、各阶段 `READ` 出来的却是 `cmd` 字符串数组，
  混着拼会让 `filter(c => c === 'x')` 全不中（总账报 0/1，而不是报错）。
- **别按文字点按钮**：图表工具条的指标/芯片每个都带 `×`（全页 9 个），`__clickText('×')` 点到的是芯片 ⇒「点了没反应但
  不报错」。同名文字一律用类名（`.panel-close`），并断言 `__clickText` 的返回值。
- **外部数据要轮询等真就位**（Gate `contracts` 1.3MB **单请求实测 16s**，25s 上限会被打穿）；
  **先 curl 打一次接口再怀疑自己的改动**。
- 只改 fragment 的跳转（`/#/mini` ⇄ `/`）**不发 `loadEventFired`**（同文档导航）⇒ 纯等事件会**永久挂死**
  （`verify-mini-drag` 曾在阶段 C 卡到被 SIGTERM）。最省事的办法是**每次导航都加一个唯一 query**（`?_nav=N`）强制
  真文档加载，而不是在每个阶段记得配 `?v=`；路由只看 hash，query 本来就是缓存刷新用的。
- 真机脚本：**Bash 调用结束会收掉子进程树** ⇒「启动 → 操作 → 断言」要在同一次调用里做完；打包的 python 3.13 没有 tkinter。
- ❗**`cargo test`/`cargo build` 撞上「另一个 cargo 在同一个 `target/` 里跑」会触发 rustc 的 ICE**
  （2026-09-29 实测）：报 `thread 'rustc' panicked … rustc_metadata/src/rmeta/encoder.rs … no entry found for key`
  + `error: the compiler unexpectedly panicked. This is a bug`。**判据是紧挨着的那行**
  `warning: error deleting lock file for incremental compilation session directory … 拒绝访问 (os error 5)`
  —— 增量会话目录被两个 cargo 抢，缓存写坏。绕过：`CARGO_INCREMENTAL=0 cargo test --offline`（实测全过），
  或让并发的那个先跑完（本次就是这么恢复的，随后默认设置也全过）。**这不是我们代码的问题，别去改代码**。
- ⚠️ **用户本机的 dev 应用正在跑时，`cargo build` 一定是 `LNK1104`**（`cannot open file '…\deps\market_monitor.exe'`
  —— 正在运行的 exe 被锁）**且增量目录改不了名**（`WinError 5`）。这是预期而非缺陷；判据是进程树
  `cargo → cargo → market-monitor.exe`。**不要为了能构建去关掉它**（见「身份与构建」那条用户抗议）。
  另：增量目录里**新建/删除文件不受影响、只有目录改名被拒**，别据此误判成 ACL 问题。

## 应用图标（两端同一套几何）
- 母版 `desktop/app-icon.svg`（1024、四周透明）：深色圆角方 + **金色圆币 + 币内上扬折线箭头**，
  金色就是应用自己的 `--accent: #f0b90b`。**只用渐变、不用滤镜** —— `tauri icon` 走 resvg，滤镜支持不全。
- 桌面端 `npm run icon`（`scripts/gen-icons.mjs`）：`tauri icon` **直接吃 SVG**，一次出 ico / icns / 32·64·128·128@2x /
  Windows Store 方块图。⚠️ 它还会**顺带**在 `src-tauri/icons/` 下建 `android/`、`ios/` —— 本仓 Android 是独立
  Gradle 工程、图标走矢量重绘，那两份永远是死文件，所以脚本生成完就地删掉，别让它进仓库。
- Android：`drawable/ic_launcher_foreground.xml` 用 `<group scale=0.093168 translate=6.298>` 把 1024 空间搬到 108dp，
  **圆币直径恰 60dp**（缩放系数就是 60/644）⇒ 落在 66dp 安全区内，圆形/方形/水滴遮罩都不会切边。
  背景换成 `@drawable/ic_launcher_background`（`aapt:attr` 渐变 —— `@color` 给不了渐变）⇒ `values/colors.xml` 已删。
  另有 `ic_launcher_monochrome.xml`（Android 13+ 主题图标）：单色下金/暗对比没了，只能换成「币缘 + 箭头」纯轮廓。
- 验收三板斧：① Python 解 `.ico`/`.icns` 容器核条目与尺寸；② Android `aapt2 compile --dir`（秒级）+
  `assembleDebug` 真建（**本机必须 `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`**，
  默认 JVM 是 Java 8 会被 AGP 8.13 直接拒）；③ `icon-preview.png` 把 16/24/32/48 摆到深/浅任务栏，
  并叠圆形遮罩与 66dp 安全区肉眼过一遍。
- ⚠️ **Windows 上 exe 资源是图标的唯一来源**：`tauri-codegen` 对 `Target::Windows` emit 的不是 `include_bytes!`
  那张 PNG，而是 `default_window_icon_from_app_icon_resource()` —— **运行时去读 exe 自己的 ICON 资源**
  （其它平台才内嵌 PNG；本仓托盘 `TrayIconBuilder::icon(app.default_window_icon())` 也走这条）。
  ⇒ Explorer / 任务栏 / Alt-Tab / 托盘 是同一份，一处没换就处处是旧图；反之验好这一份就全验好了。
  ⇒ **实测踩过**：换完图标 `cargo check` 照样 `Finished`，exe 里 6 张全是旧的（首轮探测 0/6）。修好 build.rs 后
  重建 → 6/6 PASS。**唯一可靠的判据是把 exe 的 `RT_ICON` 抠出来跟 ico 逐图 sha256 对账**（脚本
  `.workbuddy/tmp/check-exe-icon.py`，`--locate` 还能看字节落在哪个段）；只看 exe 时间戳不够 —— 时间戳旧必是旧的，
  时间戳新也可能是旧的。⚠️ 该脚本**必须手工解 PE 目录**：ctypes 的 `EnumResourceNamesW` 回调在 Python 3.13 上会
  `Fatal Python error: _PyThreadState_Attach` 直接崩；另 PE32 的 DataDirectory 偏移是 `0x60`、PE32+ 是 `0x70`，写反了
  会拿到 `rsrc_rva=0`。
- 换图标后**必须重链接才会进 exe**（见上「身份与构建」的 icons 指纹坑，已在 build.rs 修掉）；本机 dev exe 是
  `target/debug/market-monitor.exe`（cargo 包名），**不是 `app-*`** —— 找 build script 产物目录要按 crate 名找
  `target/debug/build/market-monitor-*/out/output`，`app-*` 是另一个包的。

