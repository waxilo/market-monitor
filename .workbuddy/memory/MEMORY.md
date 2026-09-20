# market-monitor 项目长期笔记

## 构建 / 验证方式（重要）
本机 Bash 工具缺少 `dirname`/`cat`/`uname`/`xargs` 等 POSIX 工具，**gradlew 脚本无法直接运行**（报 `uname: command not found`）。
统一绕过方案 —— 直接用 Android Studio 的 JBR java 调 GradleMain：

```bash
cd "C:/Users/sloan.wang/Documents/Code/Tauri/market-monitor" && \
"C:/Program Files/Android/Android Studio/jbr/bin/java.exe" \
  -classpath "C:/Users/sloan.wang/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/lib/gradle-launcher-8.14.3.jar" \
  org.gradle.launcher.GradleMain :app:assembleDebug :app:testDebugUnitTest --console=plain --offline
```

- JDK：Android Studio 自带 JBR（JDK 21），系统只有 JDK 8，不够用。
- Android SDK：`C:/Users/sloan.wang/android-sdk`。
- 汇总单测结果（`awk` 在本机 Bash 里**不可用**，用 python 代替）：
  ```bash
  "C:/Users/sloan.wang/.workbuddy/binaries/python/versions/3.13.12/python.exe" -c "
  import glob,re
  t=s=f=e=0
  for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
      h=open(p,encoding='utf-8').read(2000)
      m=re.search(r'tests=\"(\d+)\" skipped=\"(\d+)\" failures=\"(\d+)\" errors=\"(\d+)\"',h)
      if m: t+=int(m.group(1)); s+=int(m.group(2)); f+=int(m.group(3)); e+=int(m.group(4))
  print('tests=%d skipped=%d failures=%d errors=%d'%(t,s,f,e))"
  ```
- 基线：146 个单测，全绿（2026-09-20）。
- `Dp.toPx()` 是 `Density` 扩展：非 `DrawScope`/非 `with(density)` 上下文里写 `12.dp.toPx(density)` 会**报 Unresolved reference**，必须 `with(density) { 12.dp.toPx() }`。
- **adb 不在 PATH 上**：绝对路径 `C:/Users/sloan.wang/android-sdk/platform-tools/adb.exe`（见 `local.properties` 的 `sdk.dir`）。Bash 工具里裸 `adb`/`ls`/`sleep` 都 `command not found`。
- **本机 Bash 工具缺失的 POSIX 工具清单**（用到就报 `command not found`）：`dirname`、`cat`、`uname`、`xargs`、`mkdir`、`sleep`、`seq`、`basename`、`wc`。`grep`/`sed`/`head`/`tail`/`ls`/`find`/`awk` 需加 `/usr/bin/` 前缀才可用。
  - 循环不要写 `for i in $(seq 1 N)`，改写**字面列表** `for i in 1 2 3 ... N`。
- **`git` 也不在 PATH 上**：`C:/Program Files/Git/cmd/git.exe`（注意路径含空格，**别把它赋给变量再用 `$G` 展开** —— 会 `C:/Program: No such file or directory`，每次都写完整引号路径）。
- 用 `adb shell`/`cmd` 读回显的活别走 PowerShell（stdout 可能静默空，长得像「结果为空」）。

## UI 设计系统（2026-09-20 确立）
风格：**极简杂志风 (minimal editorial)**。核心规则：
- 只用 Ink/Paper 中性灰阶 + 涨绿跌红两种信号色，**不再引入第三种强调色**做装饰。
- 自定义语义色（涨跌、flash、wash、hairline 等）走 `MarketTheme.colors`（`LocalMarketColors`），**刻意不用 M3 `colorScheme`** —— M3 的语义槽位表达不了「涨/跌/闪动」这类业务概念。
- 数字、代码、overline 一律等宽字体；自然语言用系统 sans。这是编辑风的骨架。
- 分隔线用 `Rule`，**不要逐行画 divider**（性能 + 视觉噪音）；分组靠 `Section` / `SectionOverline` 建立层级。
- 间距/圆角/动效时长/字号全部走 `Tokens.kt`（`Spacing`/`Radius`/`Motion`/`FontSize`），不写魔法数字。
- 组件统一从 `ui/common/CommonUi.kt` 取用，不要在页面里另起一套。
- `MarketTheme.colors` 是唯一的颜色入口。

## 约定
- Compose 里给会参与重组的 data class 加 `@Immutable`，帮助跳过重组。
- LazyColumn 列表：必须给 `contentType` + `items(key=...)`，动效用 `Modifier.animateItem()`。
- 列表关联查询先建 `HashMap` 索引，别在循环里做 `firstOrNull` / `filter`（历史上犯过两次 O(n·m)）。

## 真机 / 模拟器验证
- **设备**：MuMu 模拟器 `emulator-5554`（另有 `127.0.0.1:16384` 是同一台的另一 transport）。1080x1920 / 480dpi ⇒ 密度 3.0、屏高 640dp。
- **安装+启动**：
  `adb -s emulator-5554 install -r -d app/build/outputs/apk/debug/app-debug.apk`
  `adb -s emulator-5554 shell monkey -p com.waxilo.marketmonitor -c android.intent.category.LAUNCHER 1`
- **坐标不要写死**：inset / AnimatedBanner 会让列表行位移，固定坐标时灵时不灵。先 `uiautomator dump` 读实时 bounds。
- **`exec-out screencap` 可能返回上一帧**：判断"当前在哪一页"以 `uiautomator dump` 的文本节点为准，别只看截图。
- **Bash 工具里 `mkdir` / `sleep` 不可用**（`command not found`）；要建目录先 Write 一个占位文件。

## MuMu 已知限制（非代码问题）
- **K 线 WebView 白屏**（**已通过换实现解决，见下**）：容器尺寸正确但内容全白，无 JS 异常。原因是 MuMu 的 `com.android.webview` v110 沙箱渲染进程反复被 `Killing ... (adj 0): isolated not needed`，页面初始化后画不出来。
- 试过 `settings put global webview_multiprocess 0`（强制单进程）**无效**。

## 图表实现：只用自研 Compose 画布，不要用 WebView（2026-09-20 定案）
- 详情页图表走 **`ui/chart/KlineChart.kt`**（自研 Compose Canvas），配 `ChartSeries`（算什么）+ `ChartViewport`（看哪几根）。
- **`KlineChartWebView.kt` 与整个 `app/src/main/assets/`（klinecharts HTML/JS）已删除** —— 不要重新引入 WebView 方案。理由：① 不依赖系统 WebView 与 GL 合成，模拟器/低端机都能画；② 少一套 HTML/JS 资源与 JS↔Kotlin 桥要维护。
- 教训：曾有一个功能完整的 Compose 图表实现**零调用点**，详情页却在用另一个未提交的 WebView WIP。改动前先 `grep` 确认「哪个实现真的在被调用」（`grep -rn "XXX(" app/src/main/java --include=*.kt`），别假设文件存在就是在用。

## 指标开关的产品语义（用户明确要求）
- **MA 允许全关 = 裸 K 图**。不要加「至少保留一条」的兜底 —— 判断形态时叠着均线反而看不清。
- **副图多选、允许全不选**；**不设「不显示」选项**（用枚举值表达「无」会和多选打架，空集本身就是「无」）。
- 副图选择持久化为**逗号分隔的枚举名**（`AppSettings.subPaneKeys`），空串=不显示。存名字不存序号。
- 多选后副图顺序按**枚举声明顺序**归一化，避免随点击先后跳动。
- 每块副图**各自算量程**（`ChartSeries.subRange(pane, start, end)`），MACD 与 VOL 量级差太多不能共用刻度。
- `ChartGeo` 用 `subCount` + `subTopOf(index)` 支持 N 块副图（等分剩余高度），不是单副图的固定 top/height。

## 图表画布布局：轴标签与图例的边界规则（2026-09-20 定案）
`ChartGeo` 的纵向分区自上而下：**图例带 `legendHeightPx` → 主图 `mainHeightPx` → N 块副图 → 时间轴 `timeAxisHeightPx`**。
- 主图绘制一律用 `geo.mainTopPx`（= `plotTopPx` = `legendHeightPx`）作为顶边，**不要再写 `0f`**。
- ⚠️ `yOf(fraction, top, height)` 只认「相对顶边的比例」：**改了 top 就必须同步改 range 判断**（`y in mainTopPx..mainTopPx+mainHeightPx`），漏一处线条就画进图例里。
- `LEGEND_HEIGHT_DP = 62f` 与图例行数**强耦合**（4 行 labelSmall）。加/删图例行必须同步改，否则图例又压到蜡烛上。
- **轴标签的通用规则（三类轴都适用）**：
  1. 用 `rememberTextMeasurer()` 量出实际宽/高，**不要按字符数猜**；
  2. 相邻标签间距 `< minGap` 就**丢掉后一条**，不要硬画 —— 这是唯一能同时防重叠和防越界的手段；
  3. 两端**夹进绘图区**，但**夹完必须重新判间距**：被夹回来的首条若贴住第二条，要整条丢弃（这里踩过坑，出现 `17:1817:23` 贴死）。
- 时间轴标签按**中心锚定**（`x - labelWidth/2`），不是左边缘。
- 这段落点数学已抽成 **`ChartModel.timeLabelPlacements(centersPx, labelWidthPx, plotWidthPx, minGapPx, step)`**（纯函数、有单测）。**新增轴标签逻辑请沿用这个模式**：留在 `@Composable` 里依赖 `TextMeasurer`，就只能靠截图肉眼看，回归了也不知道。

## 图表手势与纵向刻度（2026-09-20 定案）
手势语义：**单指横向** = 平移时间轴；**单指纵向** = 平移价格刻度；**双指** = 横轴缩放时间 + 纵轴缩放刻度；**长按** = 十字光标。

### 三条必须遵守的硬规则
1. **纵向平移必须按几何重叠收口，不能只按比例钳位移。**
   `ValueRange.panLimit(base, currentSpan)` 保证可视区与数据区重叠 >= `min(可视跨度, 数据跨度) * MIN_VISIBLE_SHARE(=0.25)`。
   ❌ 只按比例钳（曾用 `PAN_LIMIT = 1.2f`）→ 用户能把 K 线整屏拖出去，画面只剩网格且无路可回。
   ⚠️ **钳累积量本身，不要只钳渲染结果** —— 只钳渲染的话 `pricePan` 会一路涨到离谱，反向拖要先走完空行程，表现为「反向拖完全没反应」（踩过）。
2. **图表纵向手势必须抢占父级滚动。** 详情页整体是 `Column.verticalScroll`，图表是子节点。
   `awaitEachGesture` 里 `awaitFirstDown` 后**立刻 `first.consume()`**，且每次 `awaitPointerEvent` 后**无条件 `pressed.forEach { it.consume() }`**。
   ❌ 不抢占 → 纵向拖动有时调刻度、有时翻页，看起来「时灵时不灵」。
3. **越界内容必须 `clipRect`。** `ValueRange.toFraction` 只钳到 `[-0.5, 1.5]`，缩放/平移后蜡烛仍会画到 pane 外（窜进图例/时间轴）。
   主图与**每块副图各自 `clipRect`**；**十字光标不裁**（需跨 pane 跟随）。

### 其他约定
- 纵向缩放以**中位价**为不动点（`ValueRange.center`），否则一缩放整张图跑掉；量程钳到原量程的 `[0.08, 12]` 倍。
- 纵向**平移**按**未缩放的原始量程**折算，不是当前量程（否则放大 10 倍后拖同样距离飞出去 10 倍）。
- **换标的 / 换周期都要复位 `priceZoom` + `pricePan`**（`KlineChart(symbolKey = ...)` 传 `state.id.storageKey`）。平移量是相对比例，跨标的毫无可比性。
- 手势判定抽在 **`ui/chart/ChartGesture.kt`**（纯函数、有单测）：`axisLock(accumX, accumY, threshold): Axis?`（`null` = 还没定性，是必需中间态）+ `pinchFactor(previous, current)`（单帧比值钳 `[0.5, 2]`，防指头抬起/落下的跳变）。**新增手势逻辑请沿用这个模式**：手势错了表现为「斜着拖同时平移又缩放」，截图难复现，纯函数断言一目了然。
- `adb shell input` **不支持多点触控**，双指手势在模拟器上无法注入 —— 用 `ChartGesture` 单测覆盖，真机人工确认。

## 列表 Sparkline 的取数回退（2026-09-20 定案）
- `kline` 表（⚠️ 不是 `klines`；主键 `(market, symbol, intervalKey, openTime)`，列名 `symbol` 不是 `symbolId`）里各标的缓存的周期**不一定一样** —— BTC 可能只有 `o:1h`，ETH 只有 `o:15m`/`o:1m`。
- `MarketRepositoryImpl.recentCloses` **不能硬要求某个周期**：读不到就 `klineDao.cachedIntervalCounts` 查已缓存周期，挑**最粗的那个**回退读取。
- ❌ 曾经的写法硬要求 1h → 只有 15m 缓存的标的 Sparkline 直接空白（用户反馈「列表为什么只显示一个缩略图」）。
- `readCloses(...)` 返回 `List<Double>?`：把「该周期没数据」与「数据点不够（<2 点画不出线）」分开判断。

## 横向平移与右侧留白（2026-09-20 定案）

### `ChartViewport.rightOffset` 的语义（极易搞反，务必照抄）
`rightOffset` = **右端越过最新一根空出的根数**：
- `= 0` 贴最新价（默认）；
- `> 0` 最新一根被推向屏幕内偏左，**右侧露出留白**（用户明确要的行为）；
- `< 0` 窗口移向更早数据（`|offset|` 根历史移出右边界之外）。

⇒ `end = barCount - 1 + rightOffset`；`pan(deltaBars)` = `rightOffset - round(deltaBars)`（手指右移 = 看更早 = 变负）。
⇒ `clamp()` 区间 = `[-(barCount - visible), visible * (1 - MIN_VISIBLE_SHARE)]`。
⇒ `zoom()` 反推 offset：`O' = newStart + target - barCount`。
❌ 旧实现是 `end = barCount - 1 - rightOffset` + 下界 0 —— 那等于把「看更早」和「右侧留白」混成一个量，导致**最新一根永远顶在最右边、往左拖不动**（用户实测反馈）。

### `window()` vs `plotRange()` 必须分开
允许越界后，「索引安全」与「几何定位」不再是同一件事：
- `window()`：夹在 `0..barCount-1`，给**算量程**与**取数据**用。
- `plotRange()`：含留白，可越出序列末根，给**横向像素定位 / 网格 / 时间轴**用。
- 所有 `drawXxx` 的 `xOf` 一律传 `plotRange`；取数据一律 `candles.getOrNull(i) ?: continue` / `bars.getOrNull(i) ?: continue`。
- `indexAt()` 结果**夹回 `0..barCount-1`**（留白区点按应返回末根，别返回越界下标）。
- ⚠️ 两者只差一个符号，`grep` 时极易看漏一处；用「`plotRange().last > barCount-1` 且 `window().last == barCount-1`」的单测钉死。

### 每帧位移不足一根 → 必须累积
- `pan()` 内部 `roundToInt()` 会把每帧 `deltaPx / slot`（通常 << 1）抹成 0 ⇒ 表现为「横向完全拖不动」。
- 抽成纯函数 **`ChartGesture.accumulateBarPan(remainder, pixelDelta, slotPx): BarPan(bars, remainder)`**，用 **`toInt()` 向零取整**（保留符号），凑够一根才提交，零头留在 `barPanRemainder`。
- 非法输入（slot 非正/非有限、delta 非有限）→ 本帧无位移但**保留既有零头**。
- 零头清零时机：`onGestureStart`（手势按下）、换周期、换标的。
- 新蜡烛到达用 `resize(size, keepRightOffset = true)`：用户可能正把最新一根推向左边，不该被弹回最右端。

### 留白上限复用 `MIN_VISIBLE_SHARE`
`maxRightBlank(visible) = visible * (1 - MIN_VISIBLE_SHARE)`，与纵向 `panLimit` 同一套「不许拖成空屏」原则。
该常量已上移到 **`ChartViewport.companion`**（`ValueRange.companion` 里是别名引用），两个方向共用。
⚠️ 它是 `Float`，`ValueRange.panLimit` 做 Double 乘法时要 `* MIN_VISIBLE_SHARE.toDouble()`。

## 发版与应用内更新（2026-09-20 定案）

### 发版流程
1. `main` 的 CI 绿 → 改 `app/build.gradle.kts` 的 `versionCode`(+1) / `versionName`(语义化递增) → 同步 README 版本表。
2. `git commit` → `git push origin main` → `git tag <版本>` → `git push origin <版本>`。
3. tag 触发 `Release` workflow（`v*` 与裸版本号都可），产出 `market-monitor-<tag>.apk` + `.sha256` 边车。
4. ⚠️ **发版前必须先 `git log`/`gh release list` 确认目标 tag 不存在** —— 曾差点重复发一个已存在的 `0.4.0`。也要确认「本次要发的改动真的提交了」（曾出现线上 tag 已存在、而所有新改动都还没 commit 的情况）。

### tag 命名约定（影响更新判断）
应用读 `releases/latest` 的 tag（**不带 `v` 前缀**）与 `versionName` 比较（`VersionCompare`）⇒ **优先打裸版本号 tag**，且 `versionName` 必须严格大于线上最新 tag。

### ⚠️ 判断「某能力是否可用」必须找调用点，不能看到实现就认为在跑
- 已知案例一：自研 Compose 图表实现完整但**零调用点**，详情页在用另一个 WebView WIP。
- 已知案例二：`UpdateRepository.download()` + `ApkInstaller` 全实现好了，但 `SettingsViewModel` 只调 `checkManually` ⇒ UI 能「发现新版本」却**没有任何下载/安装入口**，整套 SHA-256 校验下载是死代码。
- ⇒ `grep -rn "XXX(" app/src/main/java --include=*.kt` 确认调用点，是这类工作的固定前置步骤。

### 更新链路的硬约束
- 落盘目录必须是 **`cacheDir/updates/`**：`file_paths.xml` 里声明的就是 `cache-path updates/`，换目录 `FileProvider.getUriForFile` 直接抛 `IllegalArgumentException`。`AppContainer.updateDir` 与它强耦合。
- `UpdateInfo.apkName` 是展示名与落盘名的**单一出处**，别在两处各写一份兜底逻辑。
- 「安装未知应用」权限**无法应用内静默申请**（必须跳系统设置）⇒ 流程拆成可中断的两步：下载 / 安装。`ApkInstaller.install()` 返回 `Boolean`（false = 没权限，不是失败），`needInstallPermission` 状态引导去授权，**回来后保留 `downloadedApk` 直接重试，不重下**。
- 重新检查更新时要清掉上一轮 `downloadedApk`（版本可能变了，旧的不能拿来装）。
- 验证「权限是否真的授了」：`adb shell cmd appops get <pkg> REQUEST_INSTALL_PACKAGES`（直接调 `adb shell appops` 是 `inaccessible or not found`，要走 `cmd appops`）。

### 更新链路核对脚本（免真机即可验证大半）
`.workbuddy/tmp/verify-update-chain.mjs <当前版本>`：复刻应用侧逻辑 —— `releases/latest` → 版本比较 → 找 APK + `.sha256` 边车 → 可达性 → 流式下载算 SHA-256 比对。发版后跑一次可确认「会弹更新提示」+「产物完整」。

### 装机验证的坑：签名不一致 ≠ 流程有 bug
本地 debug 包用**本机 debug keystore**，CI 产物用 **CI 环境的 debug keystore**（两份不同证书）⇒ 在已装官方包的设备上覆盖安装会报「软件包与现有软件包存在冲突」，这是 Android 的预期行为。
要复现完整安装，要么先卸载设备上的应用，要么让本地包签名与线上一致。**别把这当成更新功能坏了。**

### 造「可更新」场景的做法
临时把 `versionCode`/`versionName` 压到低于线上最新 tag（如 `6`/`0.4.1`），构建安装后即可对线上新版走完整流程。**验证完务必改回真实版本号并重新构建**。

### 轮询下载进度时不要乱点屏幕
边轮询边 `adb shell input tap` 会误触导航、打断观察窗口。**轮询只读文件体积**：`adb exec-out run-as <pkg> ls -l cache/updates`。


