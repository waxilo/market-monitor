//! z 序相关的纯 Win32 操作。
//!
//! 只有类名匹配这类纯逻辑能离线单测 —— `is_buried_by_taskbar` 要有真窗口才跑得起来，
//! 真机判据在 `.workbuddy/tmp/verify-mini-taskbar.py`（判 z 序而不是截图）。

use windows::Win32::Foundation::HWND;
use windows::Win32::UI::WindowsAndMessaging::{
    GetClassNameW, GetWindow, GetWindowLongPtrW, IsWindowVisible, SetWindowPos, GWL_EXSTYLE,
    GW_HWNDPREV, HWND_TOPMOST, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, WS_EX_TOPMOST,
};

/// 任务栏的窗口类名：主屏一个，副屏每个屏各一条。
const TASKBAR_CLASSES: [&str; 2] = ["Shell_TrayWnd", "Shell_SecondaryTrayWnd"];

/// 沿 z 序往上最多走这么多步。
///
/// 正常情况下 topmost 组里只有个位数窗口，几步就到底或碰到非 topmost 了；
/// 给个上限纯粹是为了「z 序链异常/自引用」时也能退出，不至于把守护线程转死。
const MAX_STEPS: usize = 64;

fn class_name(hwnd: HWND) -> String {
    let mut buf = [0u16; 64];
    let len = unsafe { GetClassNameW(hwnd, &mut buf) };
    if len <= 0 {
        return String::new();
    }
    String::from_utf16_lossy(&buf[..len as usize])
}

/// 类名比对单独拎出来，好让它能被离线单测覆盖（`is_taskbar` 要真窗口，测不了）。
fn matches_taskbar_class(class: &str) -> bool {
    TASKBAR_CLASSES
        .iter()
        .any(|known| class.eq_ignore_ascii_case(known))
}

fn is_taskbar(hwnd: HWND) -> bool {
    matches_taskbar_class(&class_name(hwnd))
}

pub fn is_visible(hwnd: HWND) -> bool {
    unsafe { IsWindowVisible(hwnd).as_bool() }
}

/// 悬浮窗当前是否被任务栏压着。
///
/// 沿 z 序往上（`GW_HWNDPREV`）**只看同一个 topmost 组**：topmost 窗口永远排在所有
/// 非 topmost 之上，所以碰到第一个非 topmost 窗口就可以停 —— 它上面的都压不住我们。
/// （`GetWindow` 取不到上一个窗口就是已经到顶，同样结束。）
pub fn is_buried_by_taskbar(hwnd: HWND) -> bool {
    let mut cursor = hwnd;
    for _ in 0..MAX_STEPS {
        let Ok(above) = (unsafe { GetWindow(cursor, GW_HWNDPREV) }) else {
            return false;
        };
        let ex_style = unsafe { GetWindowLongPtrW(above, GWL_EXSTYLE) } as u32;
        if ex_style & WS_EX_TOPMOST.0 == 0 {
            return false;
        }
        if is_taskbar(above) {
            return true;
        }
        cursor = above;
    }
    false
}

/// 把窗口重新提到 topmost 组最前。
///
/// **不能**用 `set_always_on_top(true)`：tao 对窗口 flags 做了去重
/// （`WindowState::set_window_flags` 里 `diff == empty` 就直接 return），
/// `ALWAYS_ON_TOP` 早已置位 ⇒ 重复调用根本走不到 `SetWindowPos`，什么都不会发生。
pub fn raise(hwnd: HWND) {
    unsafe {
        let _ = SetWindowPos(
            hwnd,
            Some(HWND_TOPMOST),
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn taskbar_class_matches_both_forms() {
        assert!(matches_taskbar_class("Shell_TrayWnd"));
        assert!(matches_taskbar_class("Shell_SecondaryTrayWnd"));
        // 类名大小写不敏感
        assert!(matches_taskbar_class("shell_traywnd"));
    }

    #[test]
    fn taskbar_class_rejects_lookalikes() {
        assert!(!matches_taskbar_class(""));
        assert!(!matches_taskbar_class("Shell_TrayWndX"));
        assert!(!matches_taskbar_class("TrayWnd"));
        assert!(!matches_taskbar_class("Progman"));
    }
}
