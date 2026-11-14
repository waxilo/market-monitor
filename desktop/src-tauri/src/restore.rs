//! 主窗的强制恢复（Windows）。
//!
//! `tao` 的 `set_minimized(false)` / Tauri 的 `unminimize()` 在内部会先看窗口状态标志
//! —— 标志一旦与真实状态不一致（本机实测过 `GetWindowPlacement` 报 `SW_SHOWNORMAL`、
//! 实际却还带着 `WS_MINIMIZE`），那次调用就是空操作：`show()` 也只是 `SW_SHOW`，
//! 最小化的窗口按原状态显示，屏幕上什么都没有。
//!
//! 所以几何丢失时再补一刀纯 Win32 的 `SW_RESTORE`（顺便把前台要回来）。平台差异按项目
//! 规矩收在本模块：入口平台无关，Win32 关在 `cfg(windows)` 里。

#[cfg(windows)]
pub(crate) fn force_restore(window: &tauri::WebviewWindow) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{
        SetForegroundWindow, ShowWindow, SW_RESTORE,
    };

    let Ok(hwnd) = window.hwnd() else {
        return;
    };
    unsafe {
        // 失败（窗口已销毁等）静默忽略：调用方随后还会做一次几何复位。
        let _ = ShowWindow(HWND(hwnd.0), SW_RESTORE);
        let _ = SetForegroundWindow(HWND(hwnd.0));
    }
}

#[cfg(not(windows))]
pub(crate) fn force_restore(_window: &tauri::WebviewWindow) {}
