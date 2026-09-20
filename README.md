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
| versionName | `0.6.0` |
| versionCode | `8` |
| 最新 tag | `0.5.0` |
| 安装包 | GitHub Release `<tag>` 的 `market-monitor-<tag>.apk`（debug 签名），边车 `<apk>.sha256` |

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

签名当前沿用 debug keystore，保证覆盖安装可用；正式签名接入时改 `release.yml`，
Secrets 注入，不动版本号约定。
