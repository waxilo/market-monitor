# market-monitor 项目长期笔记

## 环境 / 构建 / 验证

### Gradle 绕过方案（gradlew 脚本跑不了：缺 `uname` 等 POSIX 工具）
```bash
cd "C:/Users/sloan.wang/Documents/Code/Tauri/market-monitor" && \
"C:/Program Files/Android/Android Studio/jbr/bin/java.exe" \
  -classpath "C:/Users/sloan.wang/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/lib/gradle-launcher-8.14.3.jar" \
  org.gradle.launcher.GradleMain :app:assembleDebug :app:testDebugUnitTest --console=plain --offline
```
- JDK 用 Android Studio 自带 JBR（21）；系统只有 JDK 8 不够用。Android SDK：`C:/Users/sloan.wang/android-sdk`。
- 单测基线：**146 个全绿**（2026-09-20）。
- 汇总结果（`awk` 不可用，用 python）：
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

### Bash 工具缺的 POSIX 工具（用到就 `command not found`）
`dirname` `cat` `uname` `xargs` `mkdir` `sleep` `seq` `basename` `wc`；`grep`/`sed`/`head`/`tail`/`ls`/`find`/`awk` 要加 `/usr/bin/` 前缀。
- 循环别写 `for i in $(seq 1 N)`，用**字面列表** `for i in 1 2 3 ... N`。
- 建目录先 Write 一个占位文件（没有 `mkdir`）。
- **`adb` 不在 PATH**：`C:/Users/sloan.wang/android-sdk/platform-tools/adb.exe`。
- **`git` 不在 PATH**：`C:/Program Files/Git/cmd/git.exe`（路径含空格，**别赋给变量再 `$G` 展开**，会 `C:/Program: No such file`，每次写完整引号路径）。
- 读 `adb shell`/`cmd` 回显别走 PowerShell（stdout 可能静默空，像「结果为空」）。
- `Dp.toPx()` 是 `Density` 扩展：非 `DrawScope`/非 `with(density)` 里写 `12.dp.toPx(density)` 报 Unresolved reference，必须 `with(density) { 12.dp.toPx() }`。

### 真机 / 模拟器
- 设备：MuMu `emulator-5554`（`127.0.0.1:16384` 是同一台另一 transport）。1080x1920 / 480dpi ⇒ 密度 3.0、屏高 640dp。
- 安装+启动：
  `adb -s emulator-5554 install -r -d app/build/outputs/apk/debug/app-debug.apk`
  `adb -s emulator-5554 shell monkey -p com.waxilo.marketmonitor -c android.intent.category.LAUNCHER 1`
- **坐标不要写死**（inset/AnimatedBanner 会位移列表行），先 `uiautomator dump` 读实时 bounds。
- **`exec-out screencap` 可能返回上一帧**：判断「在哪一页」以 `uiautomator dump` 的文本节点为准。
- **MuMu 已知限制**：系统 WebView v110 的沙箱渲染进程反复被 kill ⇒ 任何 WebView 方案都会白屏（`webview_multiprocess 0` 无效）。这是换掉 WebView 的直接原因。

## UI 设计系统（极简杂志风 minimal editorial）
- 只用 Ink/Paper 中性灰阶 + 涨绿跌红两种信号色，**不引入第三种强调色**。
- 自定义语义色（涨跌、flash、wash、hairline）走 `MarketTheme.colors`（`LocalMarketColors`），**刻意不用 M3 colorScheme** —— M3 语义槽位表达不了「涨/跌/闪动」。
- 数字、代码、overline 用等宽字体；自然语言用系统 sans。
- 分隔线用 `Rule`，**不要逐行 divider**；分组靠 `Section`/`SectionOverline`。
- 间距/圆角/动效时长/字号全走 `Tokens.kt`（`Spacing`/`Radius`/`Motion`/`FontSize`），不写魔法数字。
- 组件统一从 `ui/common/CommonUi.kt` 取用；`MarketTheme.colors` 是唯一颜色入口。

## 代码约定
- 会参与重组的 data class 加 `@Immutable`。
- LazyColumn 必须给 `contentType` + `items(key=...)`，动效用 `Modifier.animateItem()`。
- 列表关联查询先建 `HashMap` 索引，别在循环里 `firstOrNull`/`filter`（犯过两次 O(n·m)）。

## 图表：只用自研 Compose 画布
- 详情页图表 = `ui/chart/KlineChart.kt`（Compose Canvas）+ `ChartSeries`（算什么）+ `ChartViewport`（看哪几根）+ `ChartGeo`（几何分区）。
- **`KlineChartWebView.kt` 与 `app/src/main/assets/` 已删除**，不要重新引入 WebView。
- ❗**改动前先 `grep -rn "XXX(" app/src/main/java --include=*.kt` 确认调用点**，别假设「文件存在 = 在用」（历史两次踩坑：完整 Compose 图表零调用点；更新下载能力全实现但 UI 无入口）。
- 单测基线：**180 个全绿**（2026-09-20）。

### 指标开关的产品语义
- **MA 允许全关 = 裸 K 图**，不要加「至少保留一条」兜底。
- **副图多选、允许全不选**；**不设「不显示」选项**（空集本身就是「无」）。
- 副图持久化为**逗号分隔枚举名**（`AppSettings.subPaneKeys`），空串=不显示；存名字不存序号。
- 多选后副图顺序按**枚举声明顺序**归一化，避免随点击先后跳动。
- 每块副图**各自算量程**（`ChartSeries.subRange`），MACD 与 VOL 量级不能共用刻度。
- `ChartGeo` 用 `subCount` + `subTopOf(index)` 支持 N 块副图（等分剩余高度）。

### 画布布局：轴标签与图例边界
`ChartGeo` 纵向：**图例带 `legendHeightPx` → 主图 `mainHeightPx` → N 块副图 → 时间轴 `timeAxisHeightPx`**。
- 主图绘制一律用 `geo.mainTopPx`，**不要再写 `0f`**。
- ⚠️ `yOf(fraction, top, height)` 只认「相对顶边的比例」：**改了 top 必须同步改 range 判断**（`y in mainTopPx..mainTopPx+mainHeightPx`），漏一处线条画进图例。
- `LEGEND_HEIGHT_DP = 62f` 与图例行数**强耦合**（4 行 labelSmall），加/删行必须同步改。
- **轴标签通用规则**：① 用 `rememberTextMeasurer()` 量实际宽高，别按字符数猜；② 相邻间距 `< minGap` 就**丢掉后一条**（唯一能同时防重叠+防越界的手段）；③ 两端夹进绘图区，**夹完必须重判间距**，被夹回来的首条贴住第二条要整条丢弃（出现过 `17:1817:23` 贴死）。
- 时间轴标签按**中心锚定**（`x - labelWidth/2`），不是左边缘。
- 落点数学抽成 **`ChartModel.timeLabelPlacements(...)`**（纯函数 + 单测）。**新增轴标签逻辑沿用此模式**：留在 `@Composable` 里就只能靠截图肉眼看，回归了也不知道。

### 手势与纵向刻度
语义：单指横=平移时间轴；单指纵=平移价格刻度；双指=横轴缩放+纵轴缩放；长按=十字光标。
1. **纵向平移按几何重叠收口，不能只按比例钳位移**。`ValueRange.panLimit(base, currentSpan)` 保证可视区与数据区重叠 >= `min(可视跨度,数据跨度) * MIN_VISIBLE_SHARE(0.25)`。❌ 只按比例钳 → 能把 K 线整屏拖出去。⚠️ **钳累积量本身，不要只钳渲染结果**（只钳渲染 ⇒ `pricePan` 涨到离谱、反向拖先走空行程，表现为「反向拖没反应」）。
2. **图表纵向手势必须抢占父级滚动**（详情页是 `Column.verticalScroll`）：`awaitEachGesture` 里 `awaitFirstDown` 后**立刻 `first.consume()`**，每次 `awaitPointerEvent` 后**无条件 `pressed.forEach { it.consume() }`**。❌ 不抢占 → 纵向拖动有时调刻度有时翻页。
3. **越界内容必须 `clipRect`**：`ValueRange.toFraction` 只钳到 `[-0.5,1.5]`。主图与**每块副图各自 `clipRect`**；**十字光标不裁**。
- 纵向缩放以**中位价**为不动点（`ValueRange.center`）；量程钳到原量程 `[0.08, 12]` 倍。
- 纵向**平移按未缩放的原始量程**折算，不是当前量程。
- **换标的/换周期都复位 `priceZoom` + `pricePan`** —— 做法是 `remember(symbolKey, interval.storageKey)`，**不是 effect**（见「视窗状态模型」）。
- 手势判定抽在 **`ui/chart/ChartGesture.kt`**（纯函数+单测）：`axisLock(accumX, accumY, threshold): Axis?`（`null` = 未定性，是必需中间态）+ `pinchFactor(previous, current)`（单帧比值钳 `[0.5,2]`）。手势 bug 截图难复现，纯函数断言一目了然。
- `adb shell input` **不支持多点触控**，双指手势只能靠 `ChartGesture` 单测 + 真机人工确认。

### ❗视窗状态模型（2026-09-20 重构，务必按新 API 写）
`ChartViewport` **只存「用户意图」两个字段**：`visibleBars` + `rightOffset`。
**`barCount` 不是字段，是所有方法的显式入参**（`clamp(barCount)` / `window(barCount)` / `plotRange(barCount)` / `pan(d, barCount)` / `zoom(f, anchor, barCount)` / `startIndex(barCount)` / `endIndex(barCount)` / `fractionOf(i, barCount)` / `indexAt(f, barCount)`）。
- 理由：`barCount` 是数据层投影，存字段就必须和 `series.size` 同步，而「靠 effect 事后对齐」永远慢一帧 ⇒ 首屏按 `barCount = 0` 算 ⇒ 窗口空、量程退化 ⇒ **「首帧 K 线挤在一角，切一下周期才恢复」**。改成入参后二者在同一表达式取值，**结构上不可能脱钩**。
- `ChartViewport.initial()` **无参**；`resize()` **已删除**（「来了新蜡烛」= `barCount` 变大，`rightOffset = 0` 自然贴回最新）。
- **复位 = 重建 `remember`**：`viewport`/`crosshair`/`priceZoom`/`pricePan`/`barPanRemainder`/`pinchRemainder` 全挂 `remember(symbolKey, interval.storageKey)`。`KlineChart` 里**没有任何 `LaunchedEffect`**（三条对齐/复位 effect 已删）。
- ⚠️ **新增「A 必须跟随 B」的状态时别用 `LaunchedEffect` 事后对齐**；把 A 做成 B 的派生量，或挂同一组 `remember` key。`remember { mutableStateOf(f(x)) }` 在 x 首帧为空时是陷阱（定值后不自更新）。
- 首帧守卫：`ChartGeo.measured`（`size > 0`）+ `isUsable`，`KlineChart` 里 `val isReady = barCount > 0 && geo.isUsable` 统一把关绘制/轴标签/图例。**别再拿 `plotWidthPx <= 1f` 当哨兵**（那是 `max(1f,…)` 的权宜兜底值）。

### 横向平移与右侧留白
**`ChartViewport.rightOffset` = 右端越过最新一根空出的根数**（极易搞反）：
- `0` 贴最新价（默认）；`>0` 最新一根被推向屏幕内偏左、**右侧露出留白**；`<0` 窗口移向更早数据。
- `end = barCount - 1 + rightOffset`；`pan(deltaBars, barCount)` = `rightOffset - round(deltaBars)`（手指右移=看更早=变负）。
- `clamp(barCount)` = `[-(barCount - visible), visible * (1 - MIN_VISIBLE_SHARE)]`；`zoom()` 反推 `O' = newStart + target - barCount`。
- ❌ 旧写法 `end = barCount-1 - rightOffset` + 下界 0 把「看更早」和「右侧留白」混成一个量 ⇒ **最新一根永远顶在最右、往左拖不动**。
- **`window()` vs `plotRange()` 必须分开**：`window()` 夹在 `0..barCount-1`（算量程/取数据）；`plotRange()` 含留白可越界（横向像素定位/网格/时间轴）。所有 `drawXxx` 的 `xOf` 传 `plotRange`，取数据用 `candles.getOrNull(i) ?: continue`。`indexAt()` 结果**夹回 `0..barCount-1`**。⚠️ 两者只差一个符号，易 grep 漏；用「`plotRange().last > barCount-1` 且 `window().last == barCount-1`」单测钉死。
- **每帧位移不足一根必须累积**：`pan()` 内 `roundToInt()` 会把 `deltaPx/slot`（<<1）抹成 0 ⇒「横向完全拖不动」。抽成 **`ChartGesture.accumulateBarPan(remainder, pixelDelta, slotPx): BarPan`**，用 `toInt()` **向零取整**保留符号，凑够一根才提交，零头留 `barPanRemainder`。非法输入→本帧无位移但**保留零头**。零头清零时机：`onGestureStart`、以及挂 key 重建时（换周期/换标的）。
- 留白上限复用 `MIN_VISIBLE_SHARE`：`maxRightBlank(visible) = visible * (1 - MIN_VISIBLE_SHARE)`，常量在 **`ChartViewport.companion`**（`ValueRange.companion` 里是别名引用）。⚠️ 它是 `Float`，Double 乘法要 `* MIN_VISIBLE_SHARE.toDouble()`。

## 列表 Sparkline 的取数回退
- `kline` 表（⚠️ 不是 `klines`；主键 `(market, symbol, intervalKey, openTime)`，列名 `symbol` 不是 `symbolId`）各标的缓存的周期**不一定一样**。
- `MarketRepositoryImpl.recentCloses` **不能硬要求某周期**：读不到就查 `klineDao.cachedIntervalCounts`，挑**最粗的**回退读取。
- ❌ 硬要求 1h → 只有 15m 缓存的标的 Sparkline 空白（用户反馈「列表为什么只显示一个缩略图」）。
- `readCloses(...)` 返回 `List<Double>?`：把「该周期没数据」与「数据点不够（<2 点）」分开判断。

## 发版与应用内更新
### 流程
1. `main` CI 绿 → 改 `app/build.gradle.kts` 的 `versionCode`(+1)/`versionName`(语义化) → 同步 README 版本表。
2. `commit` → `push origin main` → `git tag <版本>` → `push origin <版本>`。
3. tag（`v*` 或裸版本号）触发 `Release` workflow，产 `market-monitor-<tag>.apk` + `.sha256` 边车。
4. ⚠️ **发版前先确认目标 tag 不存在**（差点重复发 `0.4.0`），并确认**本次改动真的 commit 了**（曾出现 tag 已存在而改动全没提交）。
### tag 命名
应用读 `releases/latest` 的 tag（**不带 `v`**）与 `versionName` 比较（`VersionCompare`）⇒ 优先打裸版本号 tag，且 `versionName` 必须严格大于线上最新 tag。
### 更新链路硬约束
- 落盘目录必须是 **`cacheDir/updates/`**（`file_paths.xml` 声明 `cache-path updates/`，换目录 `FileProvider.getUriForFile` 抛 IllegalArgumentException）；`AppContainer.updateDir` 与它强耦合。
- `UpdateInfo.apkName` 是展示名与落盘名的**单一出处**。
- 「安装未知应用」权限**无法静默申请** ⇒ 流程拆成可中断两步：下载/安装。`ApkInstaller.install()` 返回 `Boolean`（false = 没权限，不是失败），`needInstallPermission` 引导去授权，**回来后保留 `downloadedApk` 直接重试不重下**。
- 重新检查更新要清上一轮 `downloadedApk`。
- 验证权限：`adb shell cmd appops get <pkg> REQUEST_INSTALL_PACKAGES`（裸 `adb shell appops` 报 not found，要走 `cmd appops`）。
- 轮询下载进度**别乱点屏幕**（会误触导航），只读文件体积：`adb exec-out run-as <pkg> ls -l cache/updates`。
### 核对脚本
`.workbuddy/tmp/verify-update-chain.mjs <当前版本>`：复刻应用侧逻辑（`releases/latest` → 版本比较 → 找 APK + `.sha256` → 可达性 → 流式下载算 SHA-256 比对）。发版后跑一次确认「会弹提示」+「产物完整」。
### 造「可更新」场景
临时把 `versionCode`/`versionName` 压到低于线上最新 tag（如 `6`/`0.4.1`），构建安装后走完整流程，**验证完务必改回真实版本号并重新构建**。

## ✅ 签名已固定（2026-09-20 修复完成）

原问题：`app/build.gradle.kts` 无 `signingConfig`，`release.yml` 跑 `assembleDebug` ⇒ 用 Gradle 自动生成的 debug keystore，而 GitHub runner 是一次性的，**每次发版一把新钥匙**。实测：
```
0.5.0 → ad9473d7086fd239adb54550cec0543d702f6c15f8e0033dca647921d85cb55b
0.6.0 → 99c7af161c47f8d3bc612e1f1d169a4d33beda36ffafb324d1dbb15249fc3691
覆盖安装 → INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match
```
**修法（已落地）**：正式 release keystore + Secrets 注入 + `assembleRelease`。

### 固定发布密钥
- 证书 SHA-256 指纹：**`cf2e20c74d1edd4d1fb290afee3b68ed1a18e20ac70a89e4ab5c43ec2d52f034`**
- alias `market-monitor`，PKCS12，有效期 30 年；口令见 `.workbuddy/signing/`（该目录不入库）
- 本地备份：`.workbuddy/signing/release.keystore` + `.base64`；CI 侧：Secrets `KEYSTORE_BASE64`/`KEY_ALIAS`/`KEYSTORE_PASSWORD`/`KEY_PASSWORD`（已写入）
- ⚠️ **密钥丢失 = 所有用户只能卸载重装**。别换签名。

### 接线方式（照这个写，别改回去）
- `app/build.gradle.kts` 里 `resolveReleaseKeystore()`：先读 CI 环境变量 `KEYSTORE_FILE`/`KEY_ALIAS`/`KEYSTORE_PASSWORD`/`KEY_PASSWORD`，再读仓库根 `keystore.properties`（已 gitignore）；**都拿不到返回 null ⇒ 降级 debug 签名**（本地开发/单测不该被生产密钥卡住）。
- 类型必须是 **`com.android.build.api.dsl.ApkSigningConfig`**（不是 `SigningConfig`，后者会报 `ApkSigningConfig? expected`）。
- `release.yml`：decode `KEYSTORE_BASE64` → `$RUNNER_TEMP/signing/release.keystore` → 写 `$GITHUB_ENV` 的 `KEYSTORE_FILE` → `assembleRelease` → 指纹断言 → 产物 `app-release.apk`。
- ❗**AGP 默认只做 v2/v3 签名，APK 里没有 `META-INF/*.RSA`** ⇒ 校验指纹**只能用 `apksigner verify --print-certs`**；`keytool -printcert -jarfile` 会报「不是已签名的 jar 文件」。`apksigner` 在 `$ANDROID_HOME/build-tools/<ver>/apksigner`。
- `release.yml` 里用 `grep 'certificate SHA-256 digest' | sed 's/.*digest: *//' | tr -d ':\r' | tr 'A-Z' 'a-z'` 提指纹（本机实测 MATCH）。

### 本地离线构建
- 本机 `--offline` 跑 `assembleRelease` 会因 `lint-gradle` 未缓存失败 ⇒ 加 `-x lintVitalAnalyzeRelease -x lintVitalRelease`（**CI 上别加**，CI 有网）。
- 真机验证更新：`adb uninstall` → 装新签名包 → 再 `adb install -r` 同包（模拟覆盖）⇒ 应报 `Success`。实测已验证通过。
- MuMu 的 adb 连接会反复 `device offline`，用 `adb reconnect offline` + `wait-for-device` 恢复；且**一条 bat 里连续跑多步 adb 最稳**（分多次调用容易撞上 offline）。
- ⚠️ **别用「本地 debug 包」去装官方 Release 包**（签名天然不同，会误报成「更新功能坏了」）。
