//! 前台不息屏：应用在前台时压住显示器的空闲息屏，退到后台立刻松开。
//!
//! 判据只有一条：**任一窗口有焦点才算前台**（2026-09-30 用户拍板 —— 窗口只摊着但没焦点
//! 不算，切去别的应用打字时该按系统原本的息屏时间来）。判据由 `refresh` 算，
//! 调用点只有一个：`run()` 里 `on_window_event` 的 `Focused`（显隐切换、换窗都会走它）。
//!
//! 平台实现各有一处必须照做的规矩，见下面两个 `imp`：
//! - macOS 是 IOKit 的 PreventUserIdleDisplaySleep 断言。语义（本机 SDK 的 IOPMLib.h）：
//!   屏幕不因空闲关闭，且**屏幕压着时系统也不会 idle sleep** —— 正是「屏幕不息屏」。
//!   断言归属进程，任何线程都能建/销。
//! - Windows 的 `SetThreadExecutionState` 状态是**线程态**的，压住与松开必须落在
//!   同一条线程上，所以专门养一条常驻线程收请求（谁调 `set` 都只是发个消息）。

use tauri::{AppHandle, Manager};

/// 按「有没有窗口聚焦」收放不息屏（幂等）。窗口焦点变化时调用。
///
/// **必须在主线程调用**：macOS 那支要读 `NSWindow.isKeyWindow()`（AppKit 只许主线程碰），
/// 而 `on_window_event` 就在事件循环线程上 —— 别把它接到别的线程上去。
pub fn refresh(app: &AppHandle) {
    let focused = app
        .webview_windows()
        .values()
        .any(|w| w.is_focused().unwrap_or(false));
    imp::set(focused);
}

#[cfg(target_os = "macos")]
mod imp {
    use core_foundation::base::TCFType;
    use core_foundation::string::CFString;
    use std::ffi::c_void;
    use std::sync::Mutex;

    /// `IOPMAssertionID`
    type AssertionId = u32;
    /// `kIOPMAssertionLevelOn`
    const LEVEL_ON: u32 = 255;
    /// `kIOPMAssertionTypePreventUserIdleDisplaySleep` = CFSTR("PreventUserIdleDisplaySleep")。
    const KIND: &str = "PreventUserIdleDisplaySleep";

    #[link(name = "IOKit", kind = "framework")]
    extern "C" {
        fn IOPMAssertionCreateWithName(
            assertion_type: *const c_void,
            assertion_level: u32,
            assertion_name: *const c_void,
            assertion_id: *mut AssertionId,
        ) -> i32;
        fn IOPMAssertionRelease(assertion_id: AssertionId) -> i32;
    }

    /// 当前攥着的那条断言（`None` = 没压）。
    static HELD: Mutex<Option<AssertionId>> = Mutex::new(None);

    pub fn set(active: bool) {
        let mut held = HELD.lock().unwrap_or_else(|e| e.into_inner());
        match (active, *held) {
            (true, None) => {
                let kind = CFString::from_static_string(KIND);
                // 这条名字会出现在 `pmset -g assertions` 里，写清楚是谁压的
                let name = CFString::from_static_string("Market Monitor 看盘中");
                let mut id: AssertionId = 0;
                let result = unsafe {
                    IOPMAssertionCreateWithName(
                        kind.as_concrete_TypeRef() as *const c_void,
                        LEVEL_ON,
                        name.as_concrete_TypeRef() as *const c_void,
                        &mut id,
                    )
                };
                // 失败顶多退化成「照常息屏」，不让它影响别的功能
                if result == 0 {
                    *held = Some(id);
                } else {
                    log::warn!("压住息屏失败（IOReturn {result:#x}）");
                }
            }
            (false, Some(id)) => {
                unsafe { IOPMAssertionRelease(id) };
                *held = None;
            }
            _ => {}
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// 断言真建得起来、真进得去系统的断言表。返回值 0 只说明调用没报错，
        /// 是不是真的被系统记下了由 `pmset` 说话（按自己的 pid 找，别的进程压着不算数）。
        #[test]
        fn display_sleep_assertion_round_trip() {
            set(false);
            set(true);
            assert!(listed_by_pmset(), "pmset 里应能读到自己 pid 的 {KIND}");
            set(false);
            assert!(!listed_by_pmset(), "松开后 pmset 里不应再有");
        }

        fn listed_by_pmset() -> bool {
            let Ok(out) = std::process::Command::new("/usr/bin/pmset")
                .args(["-g", "assertions"])
                .output()
            else {
                return false;
            };
            let text = String::from_utf8_lossy(&out.stdout);
            let pid = format!("pid {}(", std::process::id());
            text.lines()
                .any(|line| line.contains(&pid) && line.contains(KIND))
        }
    }
}

#[cfg(windows)]
mod imp {
    use std::sync::mpsc::{self, Sender};
    use std::sync::OnceLock;
    use windows::Win32::System::Power::{
        SetThreadExecutionState, ES_CONTINUOUS, ES_DISPLAY_REQUIRED, ES_SYSTEM_REQUIRED,
    };

    /// 常驻线程的请求口。线程一退出，线程态的执行状态就跟着没了，所以它得一直活着。
    static TX: OnceLock<Sender<bool>> = OnceLock::new();

    pub fn set(active: bool) {
        let _ = tx().send(active);
    }

    fn tx() -> &'static Sender<bool> {
        TX.get_or_init(|| {
            let (tx, rx) = mpsc::channel::<bool>();
            std::thread::spawn(move || {
                let mut active: Option<bool> = None;
                while let Ok(next) = rx.recv() {
                    if active == Some(next) {
                        continue;
                    }
                    active = Some(next);
                    // ES_CONTINUOUS 让这一档**持续**生效（否则只顶到下一次用户输入），
                    // 只带它、不带别的位 = 撤销本线程之前设的。
                    // ES_DISPLAY_REQUIRED 管屏幕、ES_SYSTEM_REQUIRED 管整机 —— 少了后者，
                    // 机器照样能睡眠，屏幕一样会黑。
                    let flags = if next {
                        ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED
                    } else {
                        ES_CONTINUOUS
                    };
                    unsafe {
                        SetThreadExecutionState(flags);
                    }
                }
            });
            tx
        })
    }
}

/// 其它平台（本项目不发安装包）：空实现，不假装处理了。
#[cfg(not(any(target_os = "macos", windows)))]
mod imp {
    pub fn set(_active: bool) {}
}
