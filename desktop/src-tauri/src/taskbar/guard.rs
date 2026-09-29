//! 悬浮窗 z 序守护：**事件驱动**（z 序重排的 WinEvent）＋ 低频兜底。
//!
//! ## 为什么不是定时轮询
//!
//! 任务栏被点/激活时会被 shell 提到 topmost 组最前。这件事**不给应用发窗口消息**
//! （连 `Focused(false)` 都收不到），但**会发 WinEvent**：任务栏提升自己就是一次 z 序重排，
//! 桌面窗口（类 `#32769`）随即收到 `EVENT_OBJECT_REORDER`。
//!
//! 真机实测（自建 topmost 测试窗口，2026-09-29）：任务栏被顶到最前 3/3、再 5/5 次全部收到，
//! 延迟**同刻**（消息投递毫秒级）；空闲 3s 该事件 0 条、有交互时约 2.8 条/s；
//! 钩子装在**无窗口的后台线程**上也收得到。于是从「每 500ms 醒来看一眼」改成
//! 「z 序一变就被叫醒」：纠正延迟实测 0.2~0.4s → 毫秒级，空闲时不再周期性醒来。
//!
//! ## 两条必须处理的约束
//!
//! - **我们自己的 `raise` 也会产生同事件**（实测一次 raise ⇒ 4 条 `EVENT_OBJECT_REORDER`），
//!   所以既要最小间隔节流，也要靠「每次都重新查当前 z 序」收敛 —— 纠正完再被触发，
//!   也只是查一次、不会再动手。
//! - **钩子失效不能瞎掉**，所以消息循环用带超时的 `MsgWaitForMultipleObjects` 而不是纯阻塞的
//!   `GetMessage`：超时就做一次兜底检查。正常路径永远走不到兜底。
//!
//! 线程只碰两个东西：登记进来的 HWND 和纯 Win32 调用，**不依赖 Tauri 的窗口对象**
//! （那要求回主线程，是把延迟加回去）。

use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::Once;
use std::time::{Duration, Instant};

use windows::Win32::Foundation::HWND;
use windows::Win32::UI::Accessibility::{SetWinEventHook, HWINEVENTHOOK};
use windows::Win32::UI::WindowsAndMessaging::{
    DispatchMessageW, EVENT_OBJECT_REORDER, MSG, MsgWaitForMultipleObjects, PM_REMOVE, PeekMessageW,
    QS_ALLINPUT, TranslateMessage, WINEVENT_OUTOFCONTEXT,
};

use super::zorder;

/// 兜底检查间隔。只在钩子失效时才真的用得上，正常路径完全由事件驱动。
const FALLBACK: Duration = Duration::from_millis(2000);

/// 两次纠正之间的最小间隔。自己 raise 会产生新的 z 序重排事件，没有节流就会自激；
/// 有了它，最坏情况也只是每 100ms 收敛一轮。
const MIN_RAISE_GAP: Duration = Duration::from_millis(100);

/// 窗口句柄在窗口创建/重建时登记进来（见 [`set_mini_hwnd`]）。
static MINI_HWND: AtomicIsize = AtomicIsize::new(0);

static STARTED: Once = Once::new();

pub fn set_mini_hwnd(hwnd: isize) {
    MINI_HWND.store(hwnd, Ordering::Relaxed);
}

/// 启动守护线程（幂等）。
pub fn start() {
    STARTED.call_once(|| {
        std::thread::spawn(run);
    });
}

/// WinEvent 回调。
///
/// 它**什么都不用做**：事件本身就会被投递成线程消息，`MsgWaitForMultipleObjects`
/// 因此醒来、检查一遍当前 z 序。留个空实现是为了有这个函数指针可注册 ——
/// 在回调里做重活会拖住消息派发。
unsafe extern "system" fn on_reorder(
    _hook: HWINEVENTHOOK,
    _event: u32,
    _hwnd: HWND,
    _id_object: i32,
    _id_child: i32,
    _thread: u32,
    _time: u32,
) {
}

fn run() {
    let hook = unsafe {
        SetWinEventHook(
            EVENT_OBJECT_REORDER,
            EVENT_OBJECT_REORDER,
            None,
            Some(on_reorder),
            0,
            0,
            WINEVENT_OUTOFCONTEXT,
        )
    };
    if hook.0.is_null() {
        // 钩子没装上就只剩 2s 兜底 —— 不致命，但要知道自己在退化。
        eprintln!("[zorder] SetWinEventHook 失败，退回低频兜底");
    }

    let mut msg = MSG::default();
    let mut last_raise = Instant::now() - MIN_RAISE_GAP;

    loop {
        unsafe {
            // 有消息（含 WinEvent 派发的）立刻返回；没有就等到超时。
            MsgWaitForMultipleObjects(None, false, FALLBACK.as_millis() as u32, QS_ALLINPUT);
            while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }

        let hwnd = HWND(MINI_HWND.load(Ordering::Relaxed) as *mut _);
        if hwnd.0.is_null() || !zorder::is_visible(hwnd) {
            continue;
        }
        if !zorder::is_buried_by_taskbar(hwnd) {
            continue;
        }
        if last_raise.elapsed() < MIN_RAISE_GAP {
            continue;
        }
        zorder::raise(hwnd);
        last_raise = Instant::now();
    }
}
