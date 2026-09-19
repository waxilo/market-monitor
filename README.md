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
| 设置页与应用内更新 | `ui/settings`（待建）、`domain/repository/UpdateRepository` | 开发中 |

## 本地构建

```bash
./gradlew :app:assembleDebug        # 需要 JDK 17
./gradlew testDebugUnitTest         # 纯逻辑单测（指标、聚合、判定、模板）
```

不在本地做发布构建：APK 一律由 GitHub Actions 产出（见下）。

## 当前版本

| 项 | 值 |
| --- | --- |
| versionName | `0.2.0` |
| versionCode | `2` |
| 最新 tag | `0.2.0` |
| 安装包 | GitHub Release `0.2.0` 的 `market-monitor-0.2.0.apk`（debug 签名） |

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

4. `Release` workflow 由 tag 触发，构建 debug APK、算 SHA-256，创建同名 GitHub Release。
   应用内更新读取 `releases/latest` 的 tag（**不带 `v` 前缀**）与 `versionName` 比较。

签名当前沿用 debug keystore，保证覆盖安装可用；正式签名接入时改 `release.yml`，
Secrets 注入，不动版本号约定。
