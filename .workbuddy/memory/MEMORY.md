# market-monitor 长期笔记

一仓库两端：`android/`（Compose→APK）、`desktop/`（Tauri v2 + React + TS）。共用 Releases 仓库，**每端一个固定通道
Release**（tag 固定永不新增，**版本号由产物名承载**，tag 只作触发器）。

**细节按主题分册**（同目录，需要时直接读对应那本）：
- `desktop.md` —— 桌面端结构 / 悬浮窗几何与交互 / **z 序守护（压住任务栏）** / 构建与身份分离 / 自动更新 / 本地校验
- `android.md` —— Android 环境、设计系统、图表视窗、`LazyColumn` 锚定坑
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

## 三条最容易踩的（细节都在分册里）
- ⚠️ **`taskkill /IM` 在 Windows 上不区分大小写**：用户安装版的 exe 就叫 `market-monitor.exe` ⇒ 会连正在跑的实例一起
  杀掉。真机脚本一律**按 pid** 收尾、按 pid 认领窗口。
- ❗**不要开关/覆盖用户本机正在跑的那份应用**（用户明确抗议过）。两条线必须分得开：安装版
  `com.waxilo.marketmonitor`（`%LOCALAPPDATA%\MarketMonitor\`）vs dev `…marketmonitor.dev`（`target/debug/`），
  日常调试用 `npm run tauri:dev`。
- 改了前端也要 `cargo build`；但 **`cargo build --release` 不会因 `dist/` 变了就重新链接**（cargo 指纹里没有前端产物
  ⇒ 打 `Finished` 但 exe 还是旧的）。
