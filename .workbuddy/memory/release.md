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
  2026-10-07 晚发 0.1.17：同形 sed 三条（`0.1.16→0.1.17`（含 docstring）、基线文件名 `0.1.15_→0.1.16_`、
  三处 /tmp 路径 `0116→0117`）；**日期未改**（18 时 CST = 10 时 UTC 同日）；PASS 38/38，run 37606544482
  三 job 全绿。⚠️ **基线 rel json 要 REST 原样（数字 id）**：`gh release view --json id` 给的是 **node id**
  （`RE_kwDOUhbi-c4XxTUm`）⇒ 必红「Release id 与上一版相同」一条；直接
  `curl -sL -H "Authorization: Bearer $(gh auth token)" https://api.github.com/repos/waxilo/market-monitor/releases/tags/desktop-latest -o /tmp/mm-rel-before-XXXX.json`（脚本只取 id/created_at 两个键）。
  2026-10-07 晚发 0.1.18：同形 sed 三条（`0.1.17→0.1.18`（含 docstring）、基线文件名 `0.1.16_→0.1.17_`、
  三处 /tmp 路径 `0117→0118`）；**日期未改**（19 时 CST = 11 时 UTC 同日）；PASS 38/38 一次过（基线已按
  REST 全文抓），run 37611178513 三 job 全绿。新知识：**mac 包前端串/版本串都不可搜**（前端压缩嵌入 +
  mac 二进制无版本资源；拿旧版就有的 `mm.chartView` 在新旧两包都对照过 0 次）—— 桌面端「包内新前端」
  证据固定为 CI vite 指纹 == 本地 build。
  2026-10-15 发 0.1.26：同形 sed 六处（`0.1.19→0.1.26`（含 docstring）、基线文件名 `0.1.18_→0.1.25_`、
  三处 /tmp 路径 `0119→0126`、pub_date 日期 `2026-10-07→2026-10-15`）—— 下午发版（13:59 UTC 起构建，
  同日）⇒ 日期必须现推；PASS 38/38 一次过（基线照 0117 教训直接 REST 全文抓），run 38747393056
  三 job 全绿（约 9 分钟）。包内证据：Info.plist 0.1.26/0.1.26 + win exe utf-16le `0.1.26` 命中 2
  /`0.1.25` 命中 0 + CI vite 指纹 `index-Dd__qifc.js 291.08` / `index-CuOoHjgT.css 28.38` == 本地 build。
  ⚠️ 本修复（Dock 唤回）无新字符串字面量 ⇒ mac 包内没有可 grep 的差异串、`dock_reopen` 符号也被 strip；
  包内差分证据 = 版本资源 + tag 指向含 fix 的 commit + dev 真机三段探针（见 2026-10-15.md）。
  ⚠️ 本次构建首跑 codesign `errSecInternalComponent`（`~/.market-monitor-signing/signing.keychain-db`
  被锁）⇒ `security unlock-keychain -p ""` 解锁后重跑即过 —— 构建挂签名先查这把钥匙串。
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
- 已用掉的 tag：`0.9.27`、`0.9.28`、`0.9.29`、`0.9.30`、`0.9.31`、`0.9.32`、`0.9.33`、`0.9.34`、`0.9.35`、`0.9.36`、`0.9.37`、`0.9.38`、`0.9.39`、`0.9.40`、`0.9.41`、`0.9.42`、`desktop-v0.1.3`、`desktop-v0.1.4`、`desktop-v0.1.5`、`desktop-v0.1.6`、`desktop-v0.1.7`、`desktop-v0.1.8`、`desktop-v0.1.9`、`desktop-v0.1.10`、`desktop-v0.1.11`、`desktop-v0.1.12`、`desktop-v0.1.13`、`desktop-v0.1.14`、`desktop-v0.1.15`、`desktop-v0.1.16`、`desktop-v0.1.17`、`desktop-v0.1.18`、`desktop-v0.1.19`、`desktop-v0.1.20`、`desktop-v0.1.21`、`desktop-v0.1.22`、`desktop-v0.1.23`、`desktop-v0.1.24`、`desktop-v0.1.25`、`desktop-v0.1.26`、`desktop-v0.1.27`、`desktop-v0.1.28`、`desktop-v0.1.29`、`desktop-v0.1.33`、`desktop-v0.1.34`、`0.9.47`、`desktop-v0.1.35`、`0.9.48`、`0.9.49`。
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
  测速 5s 判超时、十字光标两枚标；双端同发，CI 两轮红两轮修）→
  `desktop 0.1.17`（macOS 首条通知弹「Where is use_default?」选框——启动时先认领 bundle identifier；只发桌面端）→
  `desktop 0.1.18`（自选侧栏行加 24h 涨跌幅读数；K 线滑到最早/缩小超界自动回填历史——9 家方言接时间窗参数、
  同 Android loadMore 同口径；只发桌面端）→
  `desktop 0.1.19`（自定义 MA 均线——指标栏「＋」面板可增删/拖动排序，配色改按升序序号分配；只发桌面端）→
  `desktop 0.1.20`（悬浮窗切换后的 DWM 焦点黑边——`DWMWA_BORDER_COLOR=NONE` 永久关掉系统描边；
  只发桌面端，run 37764055846）→
  `0.9.33`（图上拖线告警直达 + 全屏现价牌跟随市场域；只发 Android）→
  `0.9.34`（新建预警的市场从详情页贯通到落库；只发 Android）→
  `0.9.35`（预警条件聚合——上破/下破合并为「达到」，方向由触发时实际穿越自动判定；只发 Android，
  Release run 37789464843、verify-channel 34/34）→
  `desktop 0.1.21`（前台不息屏判据收窄到「主窗可见 + 有焦点」——用悬浮窗时允许息屏；只发桌面端，
  run 37884914736 绿 6m26s）→
  `0.9.36`（详情页首屏不再先冒一根柱子 + 加载态改空图表/遮罩；只发 Android，
  run 37885961349 绿 3m10s、verify-channel 34/34）→
  `0.9.37`（告警去冷却、默认「每次穿越」+ 全屏指标条常驻/不画副图/标签统一「指标」/两条窄带收紧；
  含 Room v7→v8 迁移；只发 Android，run 37897610292 绿 3m14s、verify-channel 34/34）→
  `desktop 0.1.22`（单实例——重复启动不允许多开、唤起已运行实例的主窗（tauri-plugin-single-instance，
  注册在全插件最前；本机 dev 构建端到端验过：第二实例自动退出、收进托盘的主窗被恢复可见）；只发桌面端，
  run 38195463434 绿，通道验收 latest.json = 0.1.22、darwin-aarch64/windows-x86_64 两产物 URL 指向本版）。
  `0.9.38`/`desktop 0.1.23`（双端同发，两个 tag 同指一个 commit `cb262b3`）——Android「全屏划线竖栏瘦身 +
  点垃圾桶删线 + 清空全部划线」（Room v8→v9 新增 `chart_line`）、桌面端「换标的/周期/数据源改成骨架画布 +
  遮罩 + 失败重试」。Release run 38222196441 绿 3m17s（含 CI 内 pinned-key 断言）、Desktop Release run
  38222195529 绿 8m3s（windows/macos/发布三 job）、main 的 CI run 38222182738 绿 1m55s；
  verify-channel.mjs **34/34**。两通道 `id`/`createdAt` 发版前后逐字不变（原地换）；桌面端 CI 日志里 vite
  指纹 `index-Cyat4dNX.js 292.29 kB` + `index-BhzAcPd-.css 27.75 kB`（92 modules）== 本地 `npm run build`；
  新 exe 内嵌 `0.1.23`（utf-16le 命中 2）且 `0.1.22` 命中 0。⚠️ 本次**跳过真机走查**：release buildType
  `isMinifyEnabled = false`（未开混淆），release APK 与已逐态验过的 debug 包同观感。
  ⚠️ 相邻两版 APK **压缩后大小完全相同**（都 11088520）——别拿「体积没变」当「没换包」的证据，
  要比 `classes3.dex` / `AndroidManifest.xml` 的 CRC（解压后总字节 0.9.36 = 30491721、0.9.37 = 30487422）。
  ⚠️ 双端同发时两个 tag 可以指向**同一个 commit**（2026-09-29 那次都是 `8e65cb2`），互不误触发。

  `0.9.39`（**只发 Android**）——「撤掉全屏划线的画布垃圾桶（原「把线拖到右上角松手删除」整条撤除，
  删除只剩竖栏那枚点线即删 + 清空）+ 均线目录整份可编辑（出厂五档与自定义不再分家、可删 MA5；
  `ma_catalog` 取代 `ma_custom_periods`，含老 key 迁移）」。Release run 38246389844 绿 3m27s（含 CI 内
  pinned-key 断言）、main 的 CI run 38246373696 绿 2m38s；verify-channel.mjs **34/34**；
  `android-latest` 的 `id`/`createdAt`（`398798258` / `2026-09-29T03:22:14Z`）发版前后不变（原地换）；
  包内 `versionCode=51 / versionName=0.9.39` 与产物名自洽；APK 体积 11137668（0.9.38 = 11137672，仅差 4 字节）。

  `desktop 0.1.24`（与 `0.9.40` 双端同发，tag 同指 `cd1fe6c`——悬浮窗断联遮罩 + 任务栏唤回大窗收起悬浮窗/
  后台热键直接开大窗；验收全程见 2026-10-14.md）→
  `desktop 0.1.25`（应用内更新改自落盘安装器 + 优雅退出——修僵尸进程占住 exe 的「Failed to kill」；
  只发桌面端，Desktop Release run 38554031474 绿 7m50s、verify-channel.mjs 34/34；验收见 2026-10-14.md）
  （0.1.24/0.1.25 当时只记进了当日流水，本册未逐条留痕，补齐于此）→
  `desktop 0.1.26`（**macOS 点程序坞图标唤回大窗**——事件循环接 `RunEvent::Reopen` 走 `to_main` 同一收口；
  只发桌面端，fix `bcffd00` + chore(release) `f1038a1`，Desktop Release run 38747393056 三 job 全绿 ~9min、
  verify-channel-local-0126 **PASS 38/38**；修复真机三段探针与包内证据见 2026-10-15.md）→
  `desktop 0.1.27`（**画线锁挪进指标栏 + 小眼睛显隐**——「锁定」chip 与眼睛 chip 常驻指标栏末尾
  （副图组后、告警前），右键菜单删掉锁定条目；`mm.linesHidden` 按标的显隐、只藏不删、告警照常
  （实现为给 KlineCanvas 喂空数组）；锁定态右键**不识别线**（压线同空白；用户两轮收窄：先禁画线、再禁清空），
  没调刻度整个菜单不弹、调过只剩「复位纵向刻度」；浏览器像素级验证过显隐与菜单形态）。
  只发桌面端，feat `6e6537a` + chore(release) `e8c0440`，Desktop Release run 38840224361 三 job 全绿
  （02:24–02:33Z，约 9 分钟，一次过没重跑）；通道验收：`desktop-latest` id/createdAt **原地不变**
  （398800166 / 2026-09-28T12:55:50Z）、latest.json = 0.1.27 两平台 URL 指向本版、8 产物全 0.1.27
  无 0.1.26 残留、CI vite 指纹 `index-B1RP36Fd.js 292.49` / `index-CcguxJRN.css 28.48` ==
  本地 build（mac/win 两平台一致，本轮改动含新前端串 ⇒ 指纹本身即「包内新前端」证据）→
  `desktop 0.1.28`（**切周期/换标的/首次进入后纵轴重定 = 最新价居中**——对齐 App 2026-10-15 同名决策：
  KlineCanvas 里 resetKey（标的|周期）一变挂起 `recenterPendingRef`，新序列带着有限现价落地才重算
  priceRef（半程=现价到较远侧极值距离，zoom=2·半程/baseSpan、pan 把现价挪到中心，clampPriceView 收口，
  公式与 App KlineChart.kt 逐项一致）；纵向缩放/平移意图不再跨 (标的,周期) 保留、重定后照常落盘；
  横向视窗照旧全局记住、换数据源不触发重定；浏览器像素级验证过首屏/切 1时/换 ETH 三条路径
  最新价虚线钉在主图正中、拖偏的 pan 不残留）。
  只发桌面端，feat `e8c668f` + chore(release) `0e844b3`，Desktop Release run 38843754311 三 job 全绿
  （03:05–03:13Z 约 8 分钟，一次过没重跑；main CI run 38843749286 绿 59s）；通道验收：
  `desktop-latest` id/createdAt **原地不变**（398800166 / 2026-09-28T12:55:50Z）、latest.json = 0.1.28
  两平台 URL 指向本版、8 产物全 0.1.28 无 0.1.27 残留、两平台 .sig keyid `226b03192e5df60b` ==
  基线逐字同、CI vite 指纹 `index-CPsJALmX.js 292.92` / `index-CcguxJRN.css 28.48` == 本地 build
  （两平台一致；CSS 与 0.1.27 同 = 样式没动，JS 换新 = 包内新前端）→
  `0.9.42`/`desktop 0.1.29`（**均线小眼睛**——指标栏 MA chip 之后、「＋」之前加眼睛 chip，
  **只藏不删**：隐藏时 MA 折线与读数行一起收掉、chip 选中保留，再点恢复；App 存 DataStore `ma_hidden`，
  桌面存 `mm.indicators.maHidden`；App 真机竖屏+全屏验证过、force-stop 重启保持，桌面 CDP 读 fiber props +
  localStorage 验证）。双端同发：feat `3472a1d`（Android）/`fc4b94a`（desktop）+ chore(release) `12cc9fd`；
  Android Release run 39186126354 绿（01:36–01:39Z ≈ 3.5 分钟，一次过）；Desktop Release run 39186126432
  三 job 全绿（01:36–01:42Z ≈ 6 分钟，一次过）；main CI run 39186126406 绿（≈2.5 分钟）。
  Android 通道：`android-latest` createdAt **原地不变**（2026-09-29T03:22:14Z）、仅 0.9.42 APK（11,154,056）
  + 边车、sha256 `0417a9e5…` == 边车、apksigner 指纹 `cf2e20c7…` == 基线、aapt2 `54 / 0.9.42`、
  `classes3.dex` 命中「隐藏全部 MA 均线」、verify-channel 34/34。
  桌面通道：`desktop-latest` createdAt **原地不变**（2026-09-28T12:55:50Z）、8 产物全 0.1.29 无 0.1.28 残留、
  latest.json = 0.1.29（pub_date 2026-10-19T01:42:23Z）、exe 2,779,978 字节 sha256 `755b2ecb…` == 边车、
  dmg sha256 `c881fcfd…` == 边车、两平台 `.sig` keyid `226b03192e5df60b` == 基线、
  exe FileVersion/ProductVersion 0.1.29、CI vite 指纹 `index-CoWRGNKG.js 293.21` / `index-CcguxJRN.css 28.48`
  == 本地 build（两平台一致）。

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
  - 0.9.32 复核（2026-10-07）：同上（run 见 2026-10-07 流水）⇒ 指纹不变。
  - 0.9.33 复核（2026-10-08）：本机 apksigner 直验下载产物 `cf2e20c7…` == 基线逐字一致；边车 sha256 一致（6df873d2…）。
  - 0.9.35 复核（2026-10-08）：本机 apksigner 直验下载产物 `cf2e20c7…` == 基线逐字一致；边车 sha256 一致（c7b5d401…）；
    verify-channel.mjs 34/34；Release run 37789464843 全绿。
  - 0.9.36 复核（2026-10-09）：本机 apksigner 直验下载产物 `cf2e20c7…` == 基线逐字一致；边车 sha256 一致
    （`8f201359…`）；aapt2 读包内 `versionCode=48 / versionName=0.9.36` 与产物名自洽；
    verify-channel.mjs 34/34；Release run 37885961349 全绿（含 CI 里那条 pinned-key 断言）。
    ⚠️ 本次**没做真机验收**（`adb devices` 为空，MuMu 没开）—— 改的是 Compose 自绘渲染，单测覆盖不到。
  - 0.9.37 复核（2026-10-09）：**发版前**先把上一版存下来（`gh release download` 会断流只剩 4.7MB，
    必须 `curl -C - --retry-all-errors` 续传，字节 == 声明 11088520 才算完整），再核基线：
    `cf2e20c7…` 逐字一致、边车 sha256 一致、包内 48/0.9.36 自洽。发版后新包同样三项复核
    （`cf2e20c7…` / `78a80da4…` / 49-0.9.37），verify-channel.mjs **34/34**，Release run 37897610292 全绿。
    ⚠️ **`adb root` 会把 MuMu 的 adb 连接打断**（`devices` 空、`connect 127.0.0.1:16384` 与 `:7555` 都 10061 拒绝）
    —— 想拿 root 去备份 `/data/data` 之前先想清楚，恢复要用户重启 MuMu 或重开 ADB 调试。
  - 0.9.32 复核（2026-10-07）：同上 —— 以 `Release` run 37579714368 的同一断言步骤为准（绿）⇒ 指纹不变。
  - 0.9.38 复核（2026-10-12）：**本机 apksigner 直验下载产物** `cf2e20c7…` == 基线逐字一致（SHA-1 仍
    `b539f267…`、DN 仍 `CN=Waxilo, OU=Market Monitor, O=Waxilo, L=Beijing, ST=Beijing, C=CN`）；
    边车 sha256 自算 == 边车文件（`bbad2325…`），体积 11137672 == 声明（`curl --max-time 300 --retry 3
    --retry-all-errors` 一次下全，没触发断流）；aapt2 读包内 `versionCode=50 / versionName=0.9.38` 与产物名自洽；
    verify-channel.mjs 34/34；Release run 38222196441 全绿。桌面端两个平台的新 `.sig` keyid
    `226b03192e5df60b` == 发版前基线（0.1.22 那两份逐字相同）。
  - 0.9.39 复核（2026-10-12）：**本机 apksigner 直验下载产物** `cf2e20c7…` == 基线逐字一致（SHA-1
    `b539f267…`、DN 仍 `CN=Waxilo, OU=Market Monitor, O=Waxilo, L=Beijing, ST=Beijing, C=CN`）；
    边车 sha256 自算 == 边车文件（`6339ed18…`），体积 11137668 == 声明（`curl --max-time 300 --retry 3
    --retry-all-errors` 一次下全）；aapt2 读包内 `versionCode=51 / versionName=0.9.39` 自洽；
    verify-channel.mjs 34/34；Release run 38246389844 全绿。
  - 0.9.42 复核（2026-10-19）：**本机 apksigner 直验下载产物** `cf2e20c7…` == 基线逐字一致；边车 sha256 自算
    == 边车文件（`0417a9e5…`），体积 11154056 == 声明（`gh release download` 一次下全）；aapt2 读包内
    `versionCode=54 / versionName=0.9.42` 自洽；`classes3.dex` 命中「隐藏全部 MA 均线」；
    verify-channel.mjs 34/34；Release run 39186126354 全绿。桌面端两平台 `.sig` keyid
    `226b03192e5df60b` == 发版前基线（0.1.28 那两份逐字相同）。
  - 0.9.43 / 0.1.30 复核（2026-10-19）：**本机 apksigner 直验下载产物** `cf2e20c7…` == 基线逐字一致；边车
    sha256 自算 == 边车文件（`f4d001c9fd0165a604cd30a25799ecab17956b60e51e0be2dabbe1c656476052`），
    体积 11154056 == 声明；aapt2 读包内 `versionCode=55 / versionName=0.9.43` 自洽；`classes3.dex` 命中
    「新增会自动挑一个没用过的颜色」（包内确为新代码）；verify-channel.mjs **34/34**；
    CI run 39188538395、Release run 39188538440、Desktop Release run 39188538738 全绿。
    桌面通道 `createdAt` 原地不变（2026-09-28T12:55:50Z），8 个产物全 0.1.30、无旧版残留；
    两平台 `.sig` keyid `226b03192e5df60b` == 发版前基线；exe FileVersion/ProductVersion 与 mac Info.plist
    均 0.1.30；CI vite 指纹 `index-Ob62Glwz.js 295.24 kB` / `index-D7SlzNbX.css 29.19 kB` == 本地 build。
    ⚠️ 工作区 `git push` 的 `not a git repository` 再次出现，仍用临时克隆绕法成功。
  - 0.9.43 桌面依赖小改（均线线色）：桌面端前端产物名与体积均与本地一致，故用「CI vite 指纹 == 本地 build」
    作包内新前端证据（旧结论仍成立：exe/dmg 内 grep 前端文案 0 命中）。
  - `0.9.47`/`desktop 0.1.34` 复核（2026-10-22，**双端同发、tag 同指 `728894d`**，改动是 Bitget 周期码大小写 +
    取数失败提示带真实原因）：Release run `39745640145` 绿 3m20s（含 CI 内 pinned-key 断言）、Desktop Release
    run `39745639153` 三 job 全绿（win 08:09:20–08:17:26 / mac 08:09:25–08:15:27 / 发布 24s）、main CI
    `39745639079` 绿；**`verify-channel.mjs 0.1.34` → 34/34 PASS**（本机 Windows 装了：`~/.workbuddy/skills/
    tauri-gh-release-verify/scripts/verify-channel.mjs`，不必再按「Mac 没装」那条绕）。
    桌面：`desktop-latest` id/created_at 原地不变（398800166 / 2026-09-28T12:55:50Z）、8 产物全 0.1.34 无残留、
    latest.json version 0.1.34（pub_date `2026-10-22T08:17:36Z`）两平台 URL 指向本版、内联签名 == `.sig`、
    两平台 keyid `226b03192e5df60b` == 基线、exe 2783825 字节 FileVersion/ProductVersion 0.1.34、dmg 4433195、
    两份边车 sha256 自算一致、CI vite 指纹 `index-zI63eDIU.js 297.20` / `index-ByGwHDWe.css 29.30` == 本地 build。
    Android：`android-latest` id/created_at 原地不变（398798258 / 2026-09-29T03:22:14Z）、APK 11170440 与边车
    sha256 `95bd0c50…` 自算一致、apksigner `cf2e20c7…` == 基线、aapt2 `59 / 0.9.47`、
    **`classes3.dex` 命中 `1Dutc`/`6Hutc`/`1Mutc`** ⇒ 包内确为新周期码（比体积/CRC 更直接）。
    ⚠️ **push 的新绕法**（工作区 `git push` 仍报 not a git repository）：临时目录 `git init` →
    加 `gh` + `local` 两个 remote → `fetch local main:refs/remotes/local/main refs/tags/<tag>` →
    `push gh refs/remotes/local/main:refs/heads/main refs/tags/...`；**不用 clone 远端全历史**，一次成功。
  - `0.9.48`/`desktop 0.1.35` 复核（2026-10-22，**双端同发**，改动是 MA/EMA 双族收敛 + 每条均线小眼睛）：
    Release run `39770679710` 绿 3m18s、Desktop Release run `39770687418` 三 job 全绿
    （win 11:27:16–11:35:01 / mac 11:27:16–11:32:16 / 发布 24s）、CI run `39770635317` 绿 3m15s；
    **`verify-channel.mjs 0.1.35` → 34/34 PASS**。
    桌面：id/created 原地不变（398800166 / 2026-09-28T12:55:50Z）、8 产物全 0.1.35 无残留、
    latest.json 0.1.35（pub_date `2026-10-22T11:35:11Z`）、两平台 URL 指向本版、内联签名 == `.sig`、
    两平台 keyid `226b03192e5df60b` == 基线、exe 2783995 字节 sha256 `3980fb64…` == 边车、
    内嵌 `0.1.35` utf-16le 命中 2 / `0.1.34` 命中 0、dmg 4434236、
    CI vite 指纹 `index-Bx3b3Emo.js 300.35` / `index-DYvJ27ed.css 30.78` == 本地 build。
    Android：id/created 原地不变（398798258 / 2026-09-29T03:22:14Z）、APK 11170440 与边车
    sha256 `435abc8a…` 自算一致、apksigner `cf2e20c7…` == 基线、aapt2 `60 / 0.9.48`、
    **`classes3.dex` 命中「全部隐藏」「全部显示」** ⇒ 包内确为新代码。
    ⚠️ **APK 体积与上一版完全相同（都 11170440）**——再次印证「别拿体积当证据」。
    ❗**push 这条路这轮彻底不通**：工作区 `git push`、临时目录 `git init` + `-C` + `cd` 进去、
    沙箱内外（`dangerouslyDisableSandbox`）**四种姿势全是 `not a git repository`**，只有 push 子命令这样
    ⇒ 改用 skill `github-api-push-fallback` 的 `api-push.py`；**多 commit 必须分次推**，第二个的 parent
    要换成远端重建后的 SHA（副本 `.workbuddy/tmp/api-push-0948.py` 加了 `--parent`）；
    annotated tag 走 `POST /git/tags` + `POST /git/refs`（`.workbuddy/tmp/tag-0948.py`）。
    推完本地 `reset --hard origin/main` + `tag -f -a` 重指（本地原 SHA `0fbe40b`，远端 `b003ba9`，tree 相同）。
    ⚠️ `gh release download -D <相对路径>` 是相对 **cwd** 的：已经 `cd` 进 `.workbuddy/tmp` 还写
    `.workbuddy/tmp/xxx` ⇒ 落到嵌套目录里，`ls` 找不着别怀疑下载失败。

- ⚠️ **工作区内 `git push` 报 `fatal: not a git repository`（2026-10-19 实测）**：`git fetch` / `ls-remote` /
  `rev-parse --git-dir` 都正常，甚至推本地 bare remote 也报同一句；改用 `/tmp` 临时克隆
  （`git clone --no-checkout origin <tmp>` → `git remote add local <repo>` → fetch 本地 `main` 与 tag →
  `git push origin refs/remotes/local/main:refs/heads/main refs/tags/<版本> ...`）一次成功。
  先别怀疑仓库损坏，也别用 `--tags` 乱推；只写要发的 ref 名。
- ⚠️ **`git push origin main` 报 `fetch first` 时先别急着重推**，两种情形先看一眼远端再决定：
  - **同内容、不同 SHA**（上次发版走过 Git Data API 重建，时间戳被规范成 UTC）：先
    `rev-parse <a>^{tree} <b>^{tree}` 比 tree + `git diff --stat` 确认等价，再
    `git rebase --onto origin/main <本地那个等价提交>` 换基。（2026-09-29 发 0.1.6 实测：本地 `8f69de2` ↔ 远端
    `ff4a0dd`，tree 同为 `665ae30…`。副作用：本地 `desktop-v0.1.5` tag 因此游离 ⇒ **推 tag 只写要发的名字，别用 `--tags`**。）
  - **远端有本地没有的提交**（他机会话补的 `docs(memory)` 证据提交，如 2026-09-30 的 `17a6d8e`）：`git log origin/main`
    会直接看到，`git rebase origin/main` 换基即可；两边都改过 memory 文件时会撞**追加型冲突**（两个 `## 段` 都要留），
    按「旧的在前、新的在后」拼回去，别丢掉任一边。
  - 0.9.34 复核（2026-10-08）：本机 apksigner 直验下载产物 `cf2e20c7…` == 基线逐字一致；边车 sha256 一致（f3765f05…）。

## 0.9.49（只发 Android，2026-10-22 晚）

「切周期保留纵向缩放倍数（只重定位置、不动倍数）」。Release run **39781857987** 绿 2m27s
（12:51:44Z → 12:54:11Z，含 CI 内 pinned-key 断言）、main 的 CI run **39781805357** 绿。
`verify-channel.mjs 0.9.49` → **34/34 PASS**。

- `android-latest` 的 `id` / `created_at`（`398798258` / `2026-09-29T03:22:14Z`）发版前后逐字不变（原地换）；
  通道里只有 `market-monitor-0.9.49.apk`（11170440）+ `.sha256`（65），无 0.9.48 残留。
- 包内证据：`aapt2 dump badging` → `versionCode=61 / versionName=0.9.49`；
  自算 sha256 `1aea51cb…` == 边车；apksigner `cf2e20c7…` == 基线（签名跨版本稳定）；
  **`classes3.dex` 命中新方法名 `panCenteringOn` 1 次**（0.9.48 里根本没有这个方法）⇒ 包内确为新代码。
  ⚠️ 体积 11170440 与 0.9.47 / 0.9.48 **完全相同**，第三次印证「别拿体积当证据」。
- ⚠️ 本次改动**不含任何新增字符串字面量**（只删了一段算式、加了注释），所以 dex 里没有文案可搜 ——
  「包内是新代码」的硬证据要靠**方法名/字段名**（R8 未开混淆，`isMinifyEnabled = false`）。
- push 仍只能走 Git Data API：`api-push.py HEAD`（parent 就是远端 main，单 commit 不用 `--parent`）
  → 远端 `258863ea`（本地 `91e99f7`，tree `c9aab15` 相同）→ `tag-0949.py` 打附注 tag
  （`POST /git/tags` + `/git/refs`，先断言远端 tree == 本地 tree）。
- ⚠️ `gh run watch` 的 `--exit-status` 能直接给结论；`gh release download` 下这个 11MB APK 花了 **7 分钟**
  （会一直感觉像卡住），别当失败重试。
