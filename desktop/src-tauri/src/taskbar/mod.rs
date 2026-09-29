//! 悬浮窗与 Windows 任务栏的 z 序关系。
//!
//! 悬浮窗和任务栏**同为 topmost 窗口**，任务栏被点/激活时会被 shell 提到 topmost 组最前，
//! 于是盖住悬浮窗。这个模块负责把悬浮窗捞回来。
//!
//! macOS 的 Dock 与 NSWindow 是另一套 level 规则（floating level 本就压不过 Dock），
//! 而本项目只发 Windows 安装包 —— 这里对非 Windows 目标留空实现，不假装处理了。

#[cfg(windows)]
mod guard;
#[cfg(windows)]
mod zorder;

/// 启动 z 序守护（幂等，只作用于 Windows）。
#[cfg(windows)]
pub fn start() {
    guard::start();
}

#[cfg(not(windows))]
pub fn start() {}

/// 登记悬浮窗句柄。窗口创建（或重建）后调用，之后守护只跟这个句柄打交道。
#[cfg(windows)]
pub fn set_mini_hwnd(hwnd: isize) {
    guard::set_mini_hwnd(hwnd);
}

#[cfg(not(windows))]
pub fn set_mini_hwnd(_hwnd: isize) {}
