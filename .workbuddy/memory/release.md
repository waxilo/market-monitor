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
  **0.1.7 形态**：usage 例 0.1.7 / 基线 0.1.6），每次发版 sed 生成新副本再跑 —— 2026-09-30 发 0.1.11 实测要
  sed **六处**：`0.1.7→新版`、`0.1.6→上一版`、`/tmp/mm-before→/tmp/mm-before-011`、`/tmp/mm-rel-before.json→…-011.json`、
  `/tmp/mm-after→/tmp/mm-after-011`、`pub_date` 断言的日期 `2026-09-29→2026-09-30`（这条不改必红）；
  基线 rel json 只需要 `{id, created_at}` 两个字段（`jq '{id, created_at}'` 从旧文件切）。`gh release download`
  不在 git 目录里跑要加 `-R waxilo/market-monitor`。2026-10-01 发 0.1.12 同形态（副本 `verify-channel-local-0112.py`：
  `0.1.11→0.1.12`、`0.1.10→0.1.11`、三处 `/tmp` 路径、`pub_date` 日期；PASS 38/38 一次过）。
  2026-10-02 发 0.1.13 仍同形态（副本 `verify-channel-local-0113.py`：`0.1.11_→0.1.12_`（基线文件名）、
  三处 `/tmp` 路径 `0112→0113`、`pub_date` 日期 `2026-10-01→2026-10-02`、docstring 示例；PASS 38/38 一次过，
  run 36962803603 三 job 全绿）。
  2026-10-03 发 0.1.14：sed 四条（`0.1.13→0.1.14`（含 docstring）、基线文件名 `0.1.12_→0.1.13_`、
  三处 `/tmp` 路径 `0113→0114`）——**日期未改**（本机凌晨发版，pub_date 仍在 UTC 前一天 10-02）；
  首跑三产物被截断，副本 fetch 加 `--max-time 300 --retry 3 --retry-all-errors` 后 PASS 38/38；
  run 37035696005 三 job 全绿。
  2026-10-03 当晚发 0.1.15：同形 sed 四条（`0.1.14→0.1.15`（含 docstring）、基线文件名 `0.1.13_→0.1.14_`、
  三处 `/tmp` 路径 `0114→0115`）；日期仍未改（pub_date 连续第二版落 UTC 10-02）；PASS 38/38 一次过，
  run 37044580470 三 job 全绿。
  2026-10-07 发 0.1.16：同形 sed 四条（`0.1.15→0.1.16`（含 docstring）、基线文件名 `0.1.14_→0.1.15_`、
  三处 `/tmp` 路径 `0115→0116`）**外加日期 `2026-10-02→2026-10-07`** —— 这次是下午发版（UTC 06 时同日），
  pub_date 断言必须现推 UTC 日（0114/0115 凌晨发版才不改）；PASS 38/38 一次过，run 37579714383 三 job 全绿。
  ⚠️ **`tauri-gh-release-verify` / `android-gh-release-verify` 两个 skill 本机（Mac）没装**（apksigner 核验在 Windows 机上做），
  别照第 6 行去 `~/.workbuddy/skills/` 找 —— 找不到。
  Mac 侧 `aapt2 dump badging` 用 Gradle transform 缓存里的可执行件：
  `~/.gradle/caches/8.14.3/transforms/b6f0e74b481e850c7d7638bffbb667ea/transformed/aapt2-8.13.2-14304508-osx/aapt2`（2026-10-03 实测可用）。
  - 脚本自带「直连失败改走本机代理 `127.0.0.1:7897`」的兜底（发 0.1.7 时 TLS handshake timeout 反复出现，直连与代理谁通就用谁）；
    **发版前先抓上一版基线**（`created_at` + 两个 `.sig` + 体积清单）到 `/tmp/mm-before/`，覆盖后就再也拿不到了
    （0.1.7/0.1.8 两次都照做兑现；⚠️ Windows 原生 Python 不认 `/tmp`，用 `cygpath -w` 换成实际路径）。
- ⚠️ **curl 断流截断**（2026-10-03 实测）：HTTP 200 已回、连接中途断 ⇒ 落盘文件 60~92% 大小、无报错。
  桌面脚本会把「下载完成」判 PASS、再在「字节 == API 体积」判 FAIL；自写 fetch 不比体积则完全无感。
  大文件补下用 `-C - --retry-all-errors`（asset API 支持 Range）；0114 副本 fetch 已内置
  `--max-time 300 --retry 3 --retry-all-errors`。
- ⚠️ **SSH 推送会「假死」**：连接看起来建立、进程 3 分钟 0.01s CPU（死连接）⇒ `kill` 后**单次**重推即成功（2.8s）。
  判据是 `ps -o etime,time` 的 CPU 时间不涨，别一直等。
- **发版红了的补救**：`release` job 是 `needs: build`，任一平台构建挂 ⇒ 通道**不被污染**（仍是上一版，实测）；
  改完重推即可 —— `git push` 不通时走 refs API 改 tag（skill `tauri-gh-release-verify` 第五节第 8 条）。
- 已用掉的 tag：`0.9.27`、`0.9.28`、`0.9.29`、`0.9.30`、`0.9.31`、`0.9.32`、`desktop-v0.1.3`、`desktop-v0.1.4`、`desktop-v0.1.5`、`desktop-v0.1.6`、`desktop-v0.1.7`、`desktop-v0.1.8`、`desktop-v0.1.9`、`desktop-v0.1.10`、`desktop-v0.1.11`、`desktop-v0.1.12`、`desktop-v0.1.13`、`desktop-v0.1.14`、`desktop-v0.1.15`、`desktop-v0.1.16`。
  发版记录：`0.9.26`/`desktop 0.1.2`（通道模型上线）→ `0.9.27`/`desktop 0.1.3`（悬浮窗 236 + 测速排序修复）→
  `desktop 0.1.4`（发现新版自动下载 + 下完提示安装）→ `desktop 0.1.5`（悬浮窗置顶改事件驱动，纠正延迟 ms 级）→
  `0.9.28`/`desktop 0.1.6`（行情来源扩建 HTX/Bitunix、币安系 6→2；桌面端图标重做 + 面板重构）→
  `desktop 0.1.7`（两点画直线/趋势线 + 指标条 1280 折行修复；只发桌面端）→
  `desktop 0.1.8`（系统级全局热键 + 顶栏双击最大化改认 dblclick + 工具条四格单选、画线锁删除；只发桌面端）→
  `desktop 0.1.9`（悬浮窗拖不动的回归修复；只发桌面端）→
  `desktop 0.1.10`（价格告警线 + 到价系统通知、悬浮窗独立混合列表、指标/副图/画图另起一行、默认纵向留白 6%→20%；只发桌面端）→
  `desktop 0.1.11`（画线/删自选改右键菜单、前台不息屏、默认浅色+全中文、搜索居中、自选与指标持久化；只发桌面端）→
  `desktop 0.1.12`（图上三线分色——现价灰 `--muted` / 告警红 `--down` / 画线黄 `--line-mark`；只发桌面端）→
  `desktop 0.1.13`（右键落点钉十字光标——菜单价与价格标同源、快捷键默认 alt+m→alt+d；只发桌面端）→
  `0.9.29`（全屏现价牌——读数带右端常驻大号现价；只发 Android）→
  `0.9.30`/`desktop 0.1.14`（K 线视角全局持久化——缩放/位移跨周期、跨重启记住；双端同发）→
  `0.9.31`/`desktop 0.1.15`（短序列 K 线靠右留白——窗口恒宽/实体等比/缩放解锁 + 桌面端画线可拖、右键锁定/解锁全部画线；双端同发）→
  `0.9.32`/`desktop 0.1.16`（一整批：设置左右分栏 + 告警 webhook、悬浮窗独立列表、OI 副图（四家源）、自绘英文搜索框、
  测速 5s 判超时、十字光标两枚标；双端同发，CI 两轮红两轮修）。
  ⚠️ 双端同发时两个 tag 可以指向**同一个 commit**（2026-09-29 那次都是 `8e65cb2`），互不误触发。

- ❗**Android 签名基线**（通道模型下拿不到上一版 APK，所以把指纹存在这儿；每次发版后必核）：
  `market-monitor-0.9.28.apk` 的签名证书
  **SHA-256 = `cf2e20c74d1edd4d1fb290afee3b68ed1a18e20ac70a89e4ab5c43ec2d52f034`**
  （DN `CN=Waxilo, OU=Market Monitor, O=Waxilo, L=Beijing, ST=Beijing, C=CN`，SHA-1 `b539f267…`）
  ⇒ 走的是**正式 release keystore**，不是 CI 自生成的 debug keystore（那种每次发版都换密钥）。
  核法：下载新 APK 后跑
  `JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" "C:/Users/sloan.wang/android-sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs <apk>`，
  指纹**必须与上面逐字一致** —— 不一致 = 覆盖安装必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，那批用户只能卸载重装。
  - 0.9.29 复核（2026-10-02）：Mac 侧**无 JDK 跑不了 apksigner**，以 `Release` run 37023057982 的
    「Verify signature is consistent with the pinned key」步骤为准（CI 里 apksigner 逐字比对同一指纹、绿）
    ⇒ 指纹不变。
  - 0.9.30 复核（2026-10-03）：同 0.9.29 —— 以 `Release` run 37035695601 的同一断言步骤为准（绿）⇒ 指纹不变。
  - 0.9.31 复核（2026-10-03）：同上 —— 以 `Release` run 37044579950 的同一断言步骤为准（绿）⇒ 指纹不变。
  - 0.9.32 复核（2026-10-07）：同上 —— 以 `Release` run 37579714368 的同一断言步骤为准（绿）⇒ 指纹不变。

- ⚠️ **`git push origin main` 报 `fetch first` 时先别急着重推**，两种情形先看一眼远端再决定：
  - **同内容、不同 SHA**（上次发版走过 Git Data API 重建，时间戳被规范成 UTC）：先
    `rev-parse <a>^{tree} <b>^{tree}` 比 tree + `git diff --stat` 确认等价，再
    `git rebase --onto origin/main <本地那个等价提交>` 换基。（2026-09-29 发 0.1.6 实测：本地 `8f69de2` ↔ 远端
    `ff4a0dd`，tree 同为 `665ae30…`。副作用：本地 `desktop-v0.1.5` tag 因此游离 ⇒ **推 tag 只写要发的名字，别用 `--tags`**。）
  - **远端有本地没有的提交**（他机会话补的 `docs(memory)` 证据提交，如 2026-09-30 的 `17a6d8e`）：`git log origin/main`
    会直接看到，`git rebase origin/main` 换基即可；两边都改过 memory 文件时会撞**追加型冲突**（两个 `## 段` 都要留），
    按「旧的在前、新的在后」拼回去，别丢掉任一边。
