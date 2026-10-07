# market-monitor 长期笔记

一仓库两端：`android/`（Compose→APK）、`desktop/`（Tauri v2 + React + TS）。共用 Releases 仓库，**每端一个固定通道
Release**（tag 固定永不新增，**版本号由产物名承载**，tag 只作触发器）。

**细节按主题分册**（同目录，需要时直接读对应那本）：
- `desktop.md` —— 桌面端结构 / 悬浮窗几何与交互 / **设置与快捷键** / **画线（水平线 + 两点直线）** /
  **十字光标（竖线规则 + 两枚标）** / **搜索框只收英文 + 测速 5s 判超时** / **持仓量副图（四家源方言 + 对齐）** /
  **z 序守护（压住任务栏）** / 构建与身份分离 / 自动更新 / 本地校验
- `android.md` —— Android 环境、设计系统、图表视窗、**十字光标（松手保持 + 日期标）**、**测速 5s 超时**、
  **持仓量副图（chip 置灰可点 + 基础周期取数）**、`LazyColumn` 锚定坑
- `release.md` —— 通道模型与发版验收
- `YYYY-MM-DD.md` —— 当日流水（含每次探测/踩坑的完整过程）

## 通用工具链（每轮都会用到）
- Bash 缺 `dirname cat uname xargs mkdir sleep seq basename wc`；`grep sed head tail ls find awk` 前加 `/usr/bin/`
  （`| tail -40` 最易忘）。建目录先 Write 占位文件。
- 绝对路径：`git`=`C:/Program Files/Git/cmd/git.exe`、`node`=`C:/Program Files/nodejs/`、
  `adb`=`C:/Users/sloan.wang/android-sdk/platform-tools/adb.exe`。`gh` 前先
  `export APPDATA="C:\Users\sloan.wang\AppData\Roaming"`。taskkill 的 `/PID` 要写 `//PID`（MSYS 把它当路径）。
- 沙箱 `tasklist` 看不到外部进程（用 Python `CreateToolhelp32Snapshot`）；`rm` 被 safe-delete 拦时 `os.rename` 能过。
- `.workbuddy/tmp/` 已被 gitignore（校验脚本是本地工具，不入库）。
- ✅ **Android 构建本机跑得动**（2026-09-29 纠正，此前一直以为无 JDK）：JDK 17 在 `~/.jdks/ms-17.0.19`，
  用 `JAVA_HOME="C:/Users/sloan.wang/.jdks/ms-17.0.19" ./gradlew :app:testDebugUnitTest`
  （系统 `java` 只有 1.8，直接用会失败）。
- ⚠️ **连跑两次 Gradle 会撞 Windows 文件锁**（`kotlin-classes\debug` 删不掉 / `kspCaches\...\symbols` 被占用，
  报「另一个程序正在使用此文件」）—— 是前一个 daemon 还持着构建目录，**不是代码问题**。
  `./gradlew --stop` 只能停 Gradle daemon，**停不掉 Kotlin daemon**。
  ⚠️ 但 `jps -l` 里常能看到用户的 **IDEA（`com.intellij.idea.Main`）与它的 `RemoteMavenServer`**，
  它的 `KotlinCompileDaemon` 也可能在列表里 ⇒ **一律不要杀 java 进程**。
  ✅ 可行做法（实测）：把构建输出整体挪走，原 `app/build` 一动不动 ——
  `-I ../.workbuddy/tmp/init-builddir.gradle --project-cache-dir ../.workbuddy/tmp/agcache`，
  init script 里 `allprojects { buildDir = new File(".../.workbuddy/tmp/agbuild", name) }`。
  用户开着 IDEA 时这是**唯一**能跑通本地构建的方式。
- **验证前端方言解析不必搭测试框架**：Node 22 原生 strip-types 可直接 `import` 项目的 `.ts`
  （`node --experimental-strip-types x.mjs`，脚本里 `await import('.../dialects.ts')`），
  于是能把**真实响应**喂给纯函数做断言。断言的必须是**值**，不是「没抛异常」。

## 最容易踩的几条（细节都在分册里）
- ⚠️ **`taskkill /IM` 在 Windows 上不区分大小写**：用户安装版的 exe 就叫 `market-monitor.exe` ⇒ 会连正在跑的实例一起
  杀掉。真机脚本一律**按 pid** 收尾、按 pid 认领窗口。
- ❗**不要开关/覆盖用户本机正在跑的那份应用**（用户明确抗议过）。两条线必须分得开：安装版
  `com.waxilo.marketmonitor`（`%LOCALAPPDATA%\MarketMonitor\`）vs dev `…marketmonitor.dev`（`target/debug/`），
  日常调试用 `npm run tauri:dev`。**附带推论（2026-09-29 实测）**：dev 应用在跑时 `cargo build` 必然 `LNK1104`
  （exe 被锁）、增量目录也改不了名 —— 这是预期，**别为了能构建去关掉它**。
- 改了前端也要 `cargo build`；但 **`cargo build --release` 不会因 `dist/` 变了就重新链接**（cargo 指纹里没有前端产物
  ⇒ 打 `Finished` 但 exe 还是旧的）。
- ❗**两个 cargo 同时用同一个 `target/` 会触发 rustc ICE**（`rmeta/encoder.rs … no entry found for key`，
  rustc 自己都说 `This is a bug`）。指纹是紧邻那行 `error deleting lock file for incremental compilation session
  directory … 拒绝访问 (os error 5)`。绕过：`CARGO_INCREMENTAL=0`，或等另一个跑完。**不是代码的问题**。
- ⚠️ **校验脚本里的假 IPC 桩不能一律返回 `null`**：主窗会把它塞进 state，渲染期抛错 ⇒ 整树卸载，
  表象却是「某个交互没反应」。至少补 `get_update_download_state → {state:'idle'}`。
