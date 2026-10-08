//! 悬浮窗的 DWM 焦点边框。
//!
//! Windows 11 会给**持有焦点的前台顶层窗口**描一圈 1px 的 DWM 边框：主窗切到悬浮窗时
//! 悬浮窗成为前台，这圈黑边就描在透明圆角胶囊外面，要等用户点别处失焦才消失
//! （用户截图报的就是它）。靠「不抢焦点」修不掉 —— `ShowWindow(SW_SHOW)` 本身就会
//! 激活窗口，tao 的 `show()` 走的就是它。
//!
//! 正解：把悬浮窗这圈系统边框**永久关掉**（`DWMWA_BORDER_COLOR = DWMWA_COLOR_NONE`）。
//! 悬浮窗自己的胶囊描边由 CSS 画，系统这圈边没有存在价值；焦点行为（键盘快捷键兜底、
//! `awake` 的 Focused 判据）不受影响。该属性是 Win11 专属 API，Win10 上调用失败即忽略
//! （Win10 对无边框窗口本就不描这圈边）。属性跟窗口生命周期一致，创建时设一次即可。
//!
//! 平台差异按项目规矩收进本模块：入口平台无关，Win32 关在 `cfg(windows)` 里
//! （`hwnd()` 只有 Windows 目标有，直接留在调用点会把 macOS job 编红）。

#[cfg(windows)]
pub fn clear_focus_border(window: &tauri::WebviewWindow) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{DwmSetWindowAttribute, DWMWA_BORDER_COLOR};

    /// DWM 边框颜色常量：「不画边框」（即 DWMWA_COLOR_NONE）。
    const DWMWA_COLOR_NONE: u32 = 0xFFFF_FFFE;

    let Ok(hwnd) = window.hwnd() else {
        return;
    };
    let color = DWMWA_COLOR_NONE;
    unsafe {
        // 失败（Win10 没这个属性）静默忽略：那边没有要关的边。
        let _ = DwmSetWindowAttribute(
            HWND(hwnd.0),
            DWMWA_BORDER_COLOR,
            &color as *const u32 as *const core::ffi::c_void,
            core::mem::size_of::<u32>() as u32,
        );
    }
}

#[cfg(not(windows))]
pub fn clear_focus_border(_window: &tauri::WebviewWindow) {}
