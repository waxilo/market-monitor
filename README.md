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
| 合约行情多接口（Aster/币安系/OKX/Bybit/Bitget/Gate/MEXC/Hyperliquid，设置页弹窗并行检测后点选） | `data/remote/dialect`、`ui/settings` | 已实现；切换接口会清合约缓存并重同步交易对 |
| 我的仓位（币安签名接口 + API 凭据） | 原 `ui/positions` | **已移除**（含 `BinanceSigner`、`EncryptedBinanceCredentialStore` 与底栏第四个 tab）|
| 价格预警（通知栏 + 前台保活 + 消息中心） | `data/alert`、`ui/alerts` | 已实现，待真机验证 |
| Webhook 推送（端点加密存储 + 模板 + 补发） | `data/remote/WebhookSender`、`ui/webhook` | 已实现，待真机验证 |
| 设置页与应用内更新 | `ui/settings`、`domain/repository/UpdateRepository` | 已实现；更新读取路径见下 |
| 桌面端（窄侧栏导航 + 自绘 K 线、托盘常驻、可自撑高度的迷你悬浮窗、自绘标题栏、应用内更新） | `desktop/src`、`desktop/src-tauri` | 已实现；发版流程见下 |

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

桌面端（Tauri v2 + Vite/React，行情直连 Gate 现货/永续）：

```bash
cd desktop
npm install
npm run dev                         # 浏览器预览：只有界面（无托盘/悬浮窗/更新，Tauri API 短路）
npx tauri dev                       # 真实桌面窗口
npm run build                       # 前端类型检查 + 打包（CI 也会跑）
RUSTUP_TOOLCHAIN=stable-aarch64-apple-darwin cargo test --manifest-path src-tauri/Cargo.toml --lib
npx tauri build                     # 本机出安装包（macOS 需要签名身份，见「桌面端发版流程」）
```

不在本地做发布构建：APK 与桌面端安装包都由 GitHub Actions 产出（见下）。
本地 `tauri build` 只用于自检与冒烟，产物留在 `src-tauri/target/`，不要拿它顶掉 `/Applications` 里正在用的那份。

### 桌面端交互约定（改之前先看这几条）

- **主窗与悬浮窗是二选一，不是两个开关**：主窗顶栏的图标、悬浮窗遮罩里的图标都表示「换到另一个」，
  两侧命令分别是 `switch_to_mini` / `switch_to_main`；悬浮窗点某一行走同一条路（主窗起来、悬浮窗收起）。
- **悬浮窗没有常驻标题栏**：鼠标移上去才浮出遮罩层（市场标签 + 两个图标按钮）；
  遮罩 `pointer-events: none`、只让按钮收事件，所以 hover 时行点选照旧。
- **悬浮窗高度 = 自选条数**：`min(条数, 5) × 32px + 2px 边框`，超过 5 条封顶并在行区内滚动。
  前端只把**条数**报给 Rust（`set_mini_rows`），几何在 Rust 侧算（`MINI_ROW_H` / `MINI_MAX_ROWS`，
  与 theme.css 的 `--mini-row-h` / `--mini-max-rows` 一一对应）。别改成「前端量高度上报」：
  悬浮窗出生即隐藏，隐藏窗口量不到排版、`getComputedStyle` 也读不到那两个字面量，
  上报会静默退化成「没变化」。Rust 调窗口时**保持下边缘与 x 不动**（右下角是它的锚点）。
- **拖窗口自己实现**（`lib/windowDrag.ts`），不要用 `data-tauri-drag-region`：那个属性标在哪个元素上，
  就把它内部的按钮一起变成拖拽热区（表现为「点按钮在拖窗口」）。顶栏空白区按下即拖、双击最大化；
  悬浮窗要**按住 200ms** 才拖 —— 行本身是按钮，点选不能被拖拽吃掉。
- **图表纵向手势 = 平移价格刻度**（`PriceView` 的 `zoom`/`pan` 两个意图量），缩放只留给 Shift+滚轮；
  平移收口按**几何重叠**（可视区与数据区至少重叠 `min(可视跨度, 数据跨度) × 25%`），不按位移比例 ——
  后者在放大 12 倍时几乎拖不动，缩小时又能把 K 线整屏拖出去。

## 当前版本

| 项 | 值 |
| --- | --- |
| versionName | `0.9.25` |
| versionCode | `37` |
| 最新 tag | `0.9.25` |
| 安装包 | GitHub Release `<tag>` 的 `market-monitor-<tag>.apk`（CI 用**固定 release 密钥**签名，见下），边车 `<apk>.sha256` |

产物的文件名从 `0.2.2` 起定为 `market-monitor-<tag>.apk`（`release.yml` 里先 `cp` 再上传；`gh` 的
`本地文件#远端名` 写法不生效，用 API 给产物改名又会留下旧的下载路径，所以只能从上传时就定名）。
应用内更新按 `releases/latest` 的 tag 与 `versionName` 比较，因此**发版前务必确认 versionName 严格大于线上最新 tag**。

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
   创建同名 GitHub Release。应用内更新读取 `releases/latest` 的 tag（**不带 `v` 前缀**）与 `versionName` 比较，
   所以优先打裸版本号 tag。
5. 若推送 tag 后没看到 Release 运行，手动补一次：`gh workflow run release.yml --ref <tag>`。
6. 发版前先确认 tag 不存在（`git tag -l`），并确认要发的改动**已经提交** —— 曾出现「线上 tag 已存在、而本地改动全未提交」的组合，那会误以为改动已发布。

## 桌面端发版流程

| 项 | 值 |
| --- | --- |
| version | `0.1.0`（`desktop/src-tauri/tauri.conf.json`，应用内比较的就是它） |
| 更新通道 | 固定 tag `desktop-latest` 的 Release（标为 pre-release），应用只读它的 `latest.json` |
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

3. `Desktop Release` workflow 两端并行构建，再**重建** `desktop-latest`（tag 固定，里面的文件每次覆盖）：

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

| 文件 | 用途 |
| --- | --- |
| `market-monitor_<v>_macos.dmg` | macOS 安装包（arm64，自签名：首次安装要在「系统设置 → 隐私与安全性」点『仍要打开』，或先 `xattr -dr com.apple.quarantine`） |
| `market-monitor_<v>_macos.app.tar.gz` + `.sig` | macOS 应用内更新用 |
| `market-monitor_<v>_windows.exe` | Windows 安装包（NSIS，x64） |
| `market-monitor_<v>_windows.exe.sig` | Windows 应用内更新用 |
| `latest.json` | 通道清单：版本 + 各平台的下载地址与 minisign 签名 |

### 签名与凭据（都不入库）

| 凭据 | 本机位置 | GitHub Secret |
| --- | --- | --- |
| updater minisign 私钥 | `~/.market-monitor-signing/updater.key`（公钥已内嵌 `plugins.updater.pubkey`） | `TAURI_SIGNING_PRIVATE_KEY` |
| macOS 代码签名叶证书 | `~/.market-monitor-signing/leaf.p12`（含私钥与根 CA） | `MACOS_CERT_P12_MARKETMONITOR`（base64） |
| 叶证书 p12 密码 | `~/.market-monitor-signing/ci-cert-password.txt` | `MACOS_CERT_PASSWORD_MARKETMONITOR` |
| 根 CA（与 ai-assistant 共用） | `~/.traework-signing/`（本机已受信任） | —（CI 从 p12 里抽出来临时信任） |

重新签发叶证书：`CA_DIR="${HOME}/.traework-signing" bash desktop/scripts/make-signing-cert.sh`。
换机器：把 `~/.traework-signing` 与 `~/.market-monitor-signing` 整目录拷过去，重跑上面这条命令完成导入。
⚠️ **不要用脚本的 A 模式重造 CA**：macOS 把信任记在代码身份（锚到根证书哈希）上，换 CA 等于换身份。

### 两个一次性注意

⚠️ **Windows 0.1.0（旧机制）要手动装一次下一版**：0.1.0 的更新是「扫 `desktop-v*` 找 `-setup.exe` + `.sha256` 边车」，
而新链路只发固定通道 `desktop-latest`，所以那批用户不会再收到应用内提示；装一次 ≥0.1.1 之后就走新链路了。
（想让旧版本自动迁移也可以：在 workflow 里额外发一个 `desktop-v<版本>` release，旧版本就会照旧发现它 ——
代价是它会把仓库的 `/releases/latest` 抢走，Android 的更新发现会静默失效一轮，见下条。）

⚠️ **`/releases/latest` 的抢占**：GitHub 的 latest 取「最新创建的非 draft、非预发布 Release」。
新链路的通道 release 标了 **pre-release**，所以它不再占 latest；现存的一次性影响来自 `desktop-v0.1.0`
（今天创建的正式 release），它会把 latest 从 Android 的包上抢走。Android 端拿到 `desktop-v0.1.0` 这类 tag 时，
`VersionCompare.parse` 因 `desktop` 段不是纯数字而返回 null，按「解析失败一律视为无更新」处理 ——
**不会误弹提示、也不会装错包，但那段窗口期 Android 收不到更新提示**，直到下一次 Android 发版把 latest 抢回来。
要让两端彻底互不干扰，就把 Android 的发现方式也改成「拉 `releases?per_page=100` 再按**裸版本号**筛」。

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
