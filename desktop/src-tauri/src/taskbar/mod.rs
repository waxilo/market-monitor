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

/// 认领悬浮窗：窗口创建（或重建）后调用，之后守护只跟它的 HWND 打交道。
///
/// 句柄怎么从窗口对象上取是**平台相关**的（`WebviewWindow::hwnd()` 只有 Windows 目标有），
/// 所以按平台各给一份实现，调用点不必知道这件事 —— 若把那个 `hwnd()` 调用留在调用点，
/// 非 Windows 目标会直接编挂（macOS job 上实测过：`no method named hwnd`）。
#[cfg(windows)]
pub fn claim(window: &tauri::WebviewWindow) {
    if let Ok(hwnd) = window.hwnd() {
        guard::set_mini_hwnd(hwnd.0 as isize);
    }
}

#[cfg(not(windows))]
pub fn claim(_window: &tauri::WebviewWindow) {}
