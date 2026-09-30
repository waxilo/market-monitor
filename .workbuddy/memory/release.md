# 通道模型与发版

索引见 `MEMORY.md`。

- **两端各只有一个 Release**，tag 固定、永不新增；发版只换里面的产物。`publish-channel.sh`：通道不存在才建，存在就
  **先清空上一次的全部产物**再传（不清会新旧并存，用户可能下到过期包）。详细步骤与 34 条断言的验收见 skill
  `tauri-gh-release-verify`；Android 侧见 skill `android-gh-release-verify`。

  | 端 | 通道 tag | 产物 | 应用怎么读 |
  | --- | --- | --- | --- |
  | Android | `android-latest` | `market-monitor-<version>.apk` + `.sha256` | `/releases/tags/android-latest`，版本从**产物名**读 |
  | 桌面端 | `desktop-latest` | `latest.json` + dmg/exe + `.sig`/`.sha256` | `/releases/download/desktop-latest/latest.json` |

- ⚠️ **两端都不再用 `/releases/latest`**（它按「最新**创建**」算、与 tag 语义无关，以前桌面端一发版就把 Android 的包抢走）。
- ⚠️ **版本号唯一载体是产物名**，tag 纯粹是**触发器**（`desktop-v*` → Desktop Release，裸版本号 → Android Release）。
- ⚠️ **存量 Android（≤0.9.26）收不到应用内更新**（老代码读 latest，拿到 `android-latest` 解析不出版本 ⇒ 静默判无更新、
  也不装错包）；**0.9.27 是第一个走新链路的**，那批用户得手动装一次（已写进 README）。
- **一条命令验收**：`node ~/.workbuddy/skills/tauri-gh-release-verify/scripts/verify-channel.mjs [当前版本]`（34 条断言）。
  - **「原地换、没新建」看 `createdAt`**（发版前后不变即是）。
  - **「包里是新代码」**看 exe 内部版本资源 + **前端产物指纹**（本地 `npm run build` 的 `assets/index-<hash>.js` 名字与体积
    == CI 日志里 vite 打的）。⚠️ 别想在 exe 里 grep 前端文案（dist 压缩内嵌 + 符号 strip，全 0 命中）；
    `Compiling market-monitor v0.1.0` 是 **Cargo.toml 包版本**，与发布版本无关。
  - ⚠️ `.sig` 是 **base64(minisign 签名文件全文)**，不是裸签名 —— 验 keyid 要先 base64 解一层拿到文本，再解第二行
    （`Ed`/`ED` + keyid(8) + sig(64) = 74 字节）。
- ⚠️ **`github.com` 不通时官方 `verify-channel.mjs` 会在拉 `latest.json` 那步 `ConnectTimeoutError`**（走 api 的前 2 条
  断言仍能过）；但 **`gh release download desktop-latest`（asset API + objects.githubusercontent.com）照样能下** ⇒
  用 `.workbuddy/tmp/verify-channel-local.py` 做**本机核对**（2026-09-29 发 0.1.7 起是 **38 条**，PASS 38/38：体积 /
  sha256 边车 / **逐平台** keyid 与上一版逐字节比 / manifest 内联签名 == `.sig` 文件内容 / URL 版本号 / 无旧版残留；
  2026-09-30 发 0.1.8 同样 PASS 38/38 = 脚本 31 条 + 基线对比 7 条）。⚠️ 脚本里版本号**写死**（仓库旁那份停在
  0.1.5 形态），每次发版 sed 生成新副本（`0.1.5→新版`、`0.1.4→上一版`）再跑；`gh release download` 不在 git
  目录里跑要加 `-R waxilo/market-monitor`。
  ⚠️ **`tauri-gh-release-verify` / `android-gh-release-verify` 两个 skill 本机（Mac）没装**（apksigner 核验在 Windows 机上做），
  别照第 6 行去 `~/.workbuddy/skills/` 找 —— 找不到。
  - 脚本自带「直连失败改走本机代理 `127.0.0.1:7897`」的兜底（发 0.1.7 时 TLS handshake timeout 反复出现，直连与代理谁通就用谁）；
    **发版前先抓上一版基线**（`created_at` + 两个 `.sig` + 体积清单）到 `/tmp/mm-before/`，覆盖后就再也拿不到了
    （0.1.7/0.1.8 两次都照做兑现；⚠️ Windows 原生 Python 不认 `/tmp`，用 `cygpath -w` 换成实际路径）。
- ⚠️ **SSH 推送会「假死」**：连接看起来建立、进程 3 分钟 0.01s CPU（死连接）⇒ `kill` 后**单次**重推即成功（2.8s）。
  判据是 `ps -o etime,time` 的 CPU 时间不涨，别一直等。
- **发版红了的补救**：`release` job 是 `needs: build`，任一平台构建挂 ⇒ 通道**不被污染**（仍是上一版，实测）；
  改完重推即可 —— `git push` 不通时走 refs API 改 tag（skill `tauri-gh-release-verify` 第五节第 8 条）。
- 已用掉的 tag：`0.9.27`、`0.9.28`、`desktop-v0.1.3`、`desktop-v0.1.4`、`desktop-v0.1.5`、`desktop-v0.1.6`、`desktop-v0.1.7`、`desktop-v0.1.8`。
  发版记录：`0.9.26`/`desktop 0.1.2`（通道模型上线）→ `0.9.27`/`desktop 0.1.3`（悬浮窗 236 + 测速排序修复）→
  `desktop 0.1.4`（发现新版自动下载 + 下完提示安装）→ `desktop 0.1.5`（悬浮窗置顶改事件驱动，纠正延迟 ms 级）→
  `0.9.28`/`desktop 0.1.6`（行情来源扩建 HTX/Bitunix、币安系 6→2；桌面端图标重做 + 面板重构）→
  `desktop 0.1.7`（两点画直线/趋势线 + 指标条 1280 折行修复；只发桌面端）→
  `desktop 0.1.8`（系统级全局热键 + 顶栏双击最大化改认 dblclick + 工具条四格单选、画线锁删除；只发桌面端）。
  ⚠️ 双端同发时两个 tag 可以指向**同一个 commit**（2026-09-29 那次都是 `8e65cb2`），互不误触发。

- ❗**Android 签名基线**（通道模型下拿不到上一版 APK，所以把指纹存在这儿；每次发版后必核）：
  `market-monitor-0.9.28.apk` 的签名证书
  **SHA-256 = `cf2e20c74d1edd4d1fb290afee3b68ed1a18e20ac70a89e4ab5c43ec2d52f034`**
  （DN `CN=Waxilo, OU=Market Monitor, O=Waxilo, L=Beijing, ST=Beijing, C=CN`，SHA-1 `b539f267…`）
  ⇒ 走的是**正式 release keystore**，不是 CI 自生成的 debug keystore（那种每次发版都换密钥）。
  核法：下载新 APK 后跑
  `JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" "C:/Users/sloan.wang/android-sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs <apk>`，
  指纹**必须与上面逐字一致** —— 不一致 = 覆盖安装必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，那批用户只能卸载重装。

- ⚠️ **`git push origin main` 报 `fetch first` 时先别急着重推**：上次发版若走过 Git Data API 重建，
  远端那个提交与本地是**同内容、不同 SHA**（时间戳被规范成 UTC）。先
  `rev-parse <a>^{tree} <b>^{tree}` 比 tree + `git diff --stat` 确认等价，再
  `git rebase --onto origin/main <本地那个等价提交>` 换基。（2026-09-29 发 0.1.6 实测：本地 `8f69de2` ↔ 远端
  `ff4a0dd`，tree 同为 `665ae30…`。副作用：本地 `desktop-v0.1.5` tag 因此游离 ⇒ **推 tag 只写要发的名字，别用 `--tags`**。）
