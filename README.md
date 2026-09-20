# Market Monitor（行情监控）

币安现货 + USDT-M 合约的行情监控 App：K 线图表、价格预警、Webhook 推送与应用内更新。
需求与取舍见 [`docs/需求文档.md`](docs/需求文档.md)。

## 模块与现状

| 模块 | 位置 | 状态 |
| --- | --- | --- |
| 行情列表 / 自选 / 搜索 | `ui/market`、`ui/search` | 已实现 |
| 详情页与 K 线图表（自研 Canvas） | `ui/detail`、`ui/chart` | 已实现 |
| 数据层（REST + WS + Room 缓存） | `data/remote`、`data/local` | 已实现 |
| 价格预警（通知栏 + 前台保活 + 消息中心） | `data/alert`、`ui/alerts` | 已实现，待真机验证 |
| Webhook 推送（端点加密存储 + 模板 + 补发） | `data/remote/WebhookSender`、`ui/webhook` | 已实现，待真机验证 |
| 设置页与应用内更新 | `ui/settings`、`domain/repository/UpdateRepository` | 已实现；更新读取路径见下 |

应用内更新读取 `api.github.com/repos/waxilo/market-monitor/releases/latest`，要求**匿名可读**。
仓库已转为 public，匿名请求实测 200，所以这条链路是通的；若日后改回 private，匿名一律 404，
应用内更新会整体失效（届时的出路：只读 token（会被打进 APK，有泄露风险）、或把产物同步到可匿名读的地址）。
数据层按「tag 比较 + `<apk>.sha256` 边车 + 流式校验」实现，改动这条读取路径时保持这三段不变。

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
./gradlew :app:assembleDebug        # 需要 JDK 17
./gradlew testDebugUnitTest         # 纯逻辑单测（指标、聚合、判定、模板）
```

不在本地做发布构建：APK 一律由 GitHub Actions 产出（见下）。

## 当前版本

| 项 | 值 |
| --- | --- |
| versionName | `0.7.0` |
| versionCode | `9` |
| 最新 tag | `0.7.0` |
| 安装包 | GitHub Release `<tag>` 的 `market-monitor-<tag>.apk`（当前为 CI 随机 debug 签名，见下方签名问题），边车 `<apk>.sha256` |

产物的文件名从 `0.2.2` 起定为 `market-monitor-<tag>.apk`（`release.yml` 里先 `cp` 再上传；`gh` 的
`本地文件#远端名` 写法不生效，用 API 给产物改名又会留下旧的下载路径，所以只能从上传时就定名）。
应用内更新按 `releases/latest` 的 tag 与 `versionName` 比较，因此**发版前务必确认 versionName 严格大于线上最新 tag**。

## 发版流程

版本号只在两处：`app/build.gradle.kts` 的 `versionCode` / `versionName`，以及本文件的「当前版本」表。

1. 确认 `main` 的 CI（`CI` workflow）为绿：单测通过且能产出 debug APK。
2. 改 `app/build.gradle.kts`：`versionCode` +1、`versionName` 按语义化递增，同步更新本文件的版本表。
3. 提交并发版：

```bash
git commit -am "chore(release): v0.2.0"
git tag 0.2.0
git push origin main 0.2.0
```

4. `Release` workflow 由 tag 触发（`v*` 与裸版本号都能触发），构建 debug APK、算 SHA-256，
   创建同名 GitHub Release。应用内更新读取 `releases/latest` 的 tag（**不带 `v` 前缀**）与 `versionName` 比较，
   所以优先打裸版本号 tag。
5. 若推送 tag 后没看到 Release 运行，手动补一次：`gh workflow run release.yml --ref <tag>`。
6. 发版前先确认 tag 不存在（`git tag -l`），并确认要发的改动**已经提交** —— 曾出现「线上 tag 已存在、而本地改动全未提交」的组合，那会误以为改动已发布。

### ⚠️ 已知阻塞问题：签名未固定，应用内更新装不上

`app/build.gradle.kts` 目前**没有配置 `signingConfig`**，`release.yml` 直接用 `./gradlew assembleDebug`，
即依赖 Gradle 自动生成的 debug keystore。而 GitHub runner 是一次性的，**每次发版都会生成一把新的密钥**，
于是相邻两个 Release 的签名并不相同：

```
0.5.0 → SHA-256 ad9473d7086fd239adb54550cec0543d702f6c15f8e0033dca647921d85cb55b
0.6.0 → SHA-256 99c7af161c47f8d3bc612e1f1d169a4d33beda36ffafb324d1dbb15249fc3691
```

后果是覆盖安装必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`，
**真实用户无法通过应用内更新升级，只能卸载重装**。

> 这个问题的隐蔽之处在于：「检查更新」提示一直正常（`releases/latest` + `versionName` 比较是另一套机制），
> 只有走到安装那一步才暴露。

修复方向（二选一，需一次定死）：

1. **固定 debug keystore**：把一把 `debug.keystore` 入库（或存为 Secret），在 `build.gradle.kts` 里显式指定 `signingConfigs.debug.storeFile`。
2. **改用正式 release keystore**（推荐）：keystore base64 存入 Secrets（`KEYSTORE_BASE64` / `KEY_ALIAS` / `KEYSTORE_PASSWORD` / `KEY_PASSWORD`），
   `release.yml` 解码后在 `release` buildType 挂上 `signingConfig`，构建 `assembleRelease`。

⚠️ **签名一旦更换，线上已发布的老版本（≤ 0.5.0）就再也无法应用内升级**，那批用户必须卸载重装。
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
