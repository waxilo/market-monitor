//! 系统级（全局）快捷键：注册/换键/录制期间挂起，以及「主窗 ⇄ 悬浮窗」那一下的判定。
//!
//! **为什么判定在 Rust、不在网页里**：全局热键的意义就是「窗口没焦点也能按」，而主窗收进
//! 托盘时它的网页是隐藏的、随时可能被 WebView2 节流 —— 按键必须由宿主收、由宿主切窗口。
//! 网页只负责把落盘的和弦**推**上来（`localStorage` 跨不过 FFI，Rust 读不到它）。
//!
//! **一次只保留一把键**：`set_global_shortcut` 先 `unregister_all` 再注册，所以换键不会把
//! 上一把还占着的组合键留在系统里；传 `null` 就是「这把先摘掉」—— 设置面板录制新组合键
//! 期间走的就是这条路（系统级的键会被**吃掉**，网页的 keydown 根本收不到，用户按老键位
//! 不但录不上，还会顺手把窗口切走）。
//!
//! **同一把键重复下发必须空转**：网页每次启动、每次热重载都会重新下发一次。走一遍
//! 「先摘再挂」不仅多出一个空窗期，插件的 `unregister_all` 还会**先把账本清空**再去摘系统键
//! （`std::mem::take` 在前、`RegisterHotKey` 的逆操作在后，那一步失败也没人回滚）——
//! 于是账本说「没了」、系统里还占着，下一次 `register` 直接 `ERROR_HOTKEY_ALREADY_REGISTERED`，
//! 好好的全局键就这么永久失效了。所以这里既跳过重复下发，也在注册失败时显式摘一次那把键重试。
//!
//! 注册失败（那把键被别的程序占了）不静默 —— 状态和原因一并回给前端，前端据此退回
//! 「只在窗口里生效」的网页监听（`hooks/useShortcut`），并在设置面板里说清楚现在是哪种情况。

use serde::Serialize;
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutEvent, ShortcutState};

/// 状态变化时广播给**两个窗口**：网页据此决定自己那条 `useShortcut` 还要不要绑。
/// 只有主窗会推和弦，悬浮窗纯靠这条事件跟上「系统接管了没有」。
pub const STATE_EVENT: &str = "global-shortcut-state";

#[derive(Default)]
pub struct Shared(Mutex<Store>);

#[derive(Default)]
struct Store {
    /// 最近一次要的那把键（**不管注册成没成**）。权威在网页的 localStorage：
    /// 摘掉或换键都由前端重新下发，这里留一份只为把状态回读给它显示。
    desired: Option<String>,
    registered: bool,
    error: Option<String>,
}

/// 回给前端的状态（`Status` 同时是事件 payload，所以字段用 camelCase）。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    /// 系统级是否已生效。
    pub registered: bool,
    /// 当前这把键的 accelerator 写法（`Alt+D`）；没设置过则是空串。
    pub accelerator: String,
    /// 注册失败的原因（生效时为 `None`）。
    pub error: Option<String>,
}

/// 换成这把键；`None` = 摘掉全局键，只留窗口内那条监听。
///
/// 摘掉这条路是给设置面板录制新组合键用的：系统级的键会被**吃掉**，网页的 keydown
/// 根本收不到，用户按老键位不但录不上，还会顺手把窗口切走。录完/取消由前端把
/// localStorage 里那把现读出来再下发一次（键的权威在那边，所以这里不留「挂回去」的副本）。
#[tauri::command]
pub fn set_global_shortcut(app: AppHandle, accelerator: Option<String>) -> Status {
    let handle = app.clone();
    with_store(&app, move |store| apply(&handle, store, accelerator))
}

/// 只读状态：两个窗口起来时各问一次，之后再靠 `STATE_EVENT` 跟新。
#[tauri::command]
pub fn global_shortcut_status(app: AppHandle) -> Status {
    with_store(&app, |store| snapshot(store))
}

fn with_store<T>(app: &AppHandle, f: impl FnOnce(&mut Store) -> T) -> T {
    let shared = app.state::<Shared>();
    let mut store = shared.0.lock().expect("global key store poisoned");
    f(&mut store)
}

fn apply(app: &AppHandle, store: &mut Store, accelerator: Option<String>) -> Status {
    let manager = app.global_shortcut();
    // 同一把键、系统里也还认账 = 空转（理由见模块头：重复「先摘再挂」会把键摘没）。
    if store.registered
        && store.desired == accelerator
        && accelerator
            .as_deref()
            .is_some_and(|a| manager.is_registered(a))
    {
        return snapshot(store);
    }
    // 先清空：换键的语义是「只保留这一把」，上一把不该继续占着系统。
    let _ = manager.unregister_all();
    store.registered = false;
    let Some(accelerator) = accelerator else {
        store.desired = None;
        store.error = None;
        return publish(app, store);
    };
    store.desired = Some(accelerator.clone());
    let mut result = manager.on_shortcut(accelerator.as_str(), toggle_handler);
    if result.is_err() {
        // 失败最常见的原因是「我们自己上一把还占着系统」：`unregister_all` 会先把插件的账本
        // 清空再去摘系统键，那一步失败也没人回滚，于是下一次注册直接撞 1409。
        // 显式摘掉这一把再试一次，别让一次竞态变成永久失效。
        let _ = manager.unregister(accelerator.as_str());
        result = manager.on_shortcut(accelerator.as_str(), toggle_handler);
    }
    store.error = result.err().map(|e| e.to_string());
    store.registered = store.error.is_none();
    publish(app, store)
}

/// 热键回调：跑在 `global-hotkey` 自己的线程上，所以窗口显隐要交回主线程（Windows 的要求）。
fn toggle_handler(app: &AppHandle, _shortcut: &Shortcut, event: ShortcutEvent) {
    if event.state != ShortcutState::Pressed {
        return; // 松开那一下不算第二下
    }
    let main_thread = app.clone();
    let _ = app.run_on_main_thread(move || toggle_view(&main_thread));
}

fn snapshot(store: &Store) -> Status {
    Status {
        registered: store.registered,
        accelerator: store.desired.clone().unwrap_or_default(),
        error: store.error.clone(),
    }
}

fn publish(app: &AppHandle, store: &Store) -> Status {
    let status = snapshot(store);
    let _ = app.emit(STATE_EVENT, status.clone());
    status
}

/// 快捷键那一下：悬浮窗在眼前就换回主窗，否则收成悬浮窗。
///
/// 「两个都隐藏」（从托盘全关掉）时也走悬浮窗那支 —— 已拍板：这键的用途是一眼看到行情，
/// 要全功能还有托盘菜单。
fn toggle_view(app: &AppHandle) {
    let mini_visible = app
        .get_webview_window(crate::MINI)
        .and_then(|w| w.is_visible().ok())
        .unwrap_or(false);
    if mini_visible {
        crate::to_main(app);
    } else {
        crate::to_mini(app);
    }
}
