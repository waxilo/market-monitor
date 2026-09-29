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
- 已用掉的 tag：`0.9.27`、`desktop-v0.1.3`、`desktop-v0.1.4`。发版记录：`0.9.26`/`desktop 0.1.2`（通道模型上线）→
  `0.9.27`/`desktop 0.1.3`（悬浮窗 236 + 测速排序修复）→ `desktop 0.1.4`（发现新版自动下载 + 下完提示安装）。
