//! 价格告警的宿主侧半边：只管**节拍**和**弹通知**，判定一律在前端。
//!
//! 为什么判定不放这里：告警线存在前端 localStorage（`mm.priceLines`），取价要过
//! `lib/dialects.ts` 那套六家盘口的 URL 构造与响应归一化。搬进 Rust 就得把这两样重写一遍，
//! 而它们本来只该有一份。所以宿主每 5s 敲一下主窗（`alert-tick`），前端自己拉价、
//! 自己判上破/下破，判出来了再调 `notify_price_alert` 把系统通知弹出去。
//!
//! 为什么节拍非要由宿主给：主窗点关闭是「收进托盘」，窗口一隐藏，WebView2 就把网页的
//! JS 定时器当后台页节流（一分钟一跑都不奇怪），前端自己的 `setInterval` 在常驻场景不可信。
//! 宿主的事件投递走窗口消息循环，不在这条节流路径上 —— 这正是「托盘常驻也提醒」的落点。

use std::time::Duration;

use tauri::{AppHandle, Emitter};

/// 主窗标签，与 `lib.rs` 的 `MAIN` 是同一个值（跨模块没法共享常量，只能重复字面量）。
const MAIN: &str = "main";
/// 告警节拍。行情按秒在动，5s 既能保证「一穿就报」不漏，也不至于变成轮询风暴。
const TICK: Duration = Duration::from_secs(5);

/// 起告警节拍线程：setup 里调用一次，进程活着就一直敲。
pub fn start(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(TICK);
        // 前端还没挂上监听（启动头几帧）就丢掉这一拍：下一拍 5s 后就到，没必要排队。
        let _ = app.emit_to(MAIN, "alert-tick", ());
    });
}

/// 把**进程**的通知身份认领成 app 自己的 bundle identifier。setup 里、发第一条通知之前调用，
/// 两个平台各有各的「不认领会出什么岔子」，都不是可选项。
///
/// ## Windows（AUMID）
/// Windows 判断「这条 toast 该不该弹横幅」看的是**进程 AUMID 与已注册 AUMID 是否一致**，
/// 光在 toast 上写 `app_id` 不够。从开始菜单/任务栏启动时系统会自动带上快捷方式里那个
/// （安装包写的就是 bundle identifier），所以安装版没事；但 `cargo run` / `tauri dev` 起的进程，
/// 默认 AUMID 是 exe 自己的路径（没注册过）⇒ 通知被系统收下（注册表里
/// `LastNotificationAddedTime` 会跳）、屏幕上就是不弹横幅（2026-09-30 实测）。
/// 认领之后 dev 与安装版走同一条判定路径，调试时能看到真实效果。
///
/// ## macOS（横幅的归属应用）
/// mac-notification-sys 发**本进程第一条**通知前会先替我们认领一次：拿字面量 `"use_default"`
/// 当应用名跑一段 AppleScript（`get id of application "use_default"`）去问 LaunchServices 要
/// bundle id（0.6.15 `ensure_application_set`）。系统里没有这个 app，macOS 就把
/// 「Where is use_default?」的应用定位选框弹到窗口上，通知卡在它后面（2026-10-07 实测）。
/// 这段查找包在库内 `Once` 里 —— 启动时先替它调一次 `set_application`，Once 被占掉，
/// AppleScript 永远轮不到；横幅的归属应用与图标也才是 MarketMonitor 自己。
/// 认领失败（identifier 没在任何 LaunchServices 里注册过，比如机器上从没装过：dev 机器卸载后）
/// 只是横幅挂到库的兜底名下，不值得让启动失败。
#[cfg(windows)]
pub fn claim_notification_identity(app: &AppHandle) {
    use windows::core::PCWSTR;
    use windows::Win32::UI::Shell::SetCurrentProcessExplicitAppUserModelID;

    let id: Vec<u16> = app
        .config()
        .identifier
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    // 返回值只表示「这次设置成没成功」，设置不上顶多退化成原来的行为，不值得让启动失败。
    unsafe {
        if let Err(e) = SetCurrentProcessExplicitAppUserModelID(PCWSTR(id.as_ptr())) {
            log::warn!("认领 AUMID 失败（系统通知可能不弹横幅）：{e}");
        }
    }
}

#[cfg(target_os = "macos")]
pub fn claim_notification_identity(app: &AppHandle) {
    if let Err(e) = notify_rust::set_application(&app.config().identifier) {
        log::warn!("认领通知应用（bundle identifier）失败，横幅归属可能不对：{e}");
    }
}

/// 其余平台（Linux/Mobile）没有「通知归属」这套，空实现。
#[cfg(not(any(windows, target_os = "macos")))]
pub fn claim_notification_identity(_app: &AppHandle) {}

/// 弹一条系统通知（前端判出穿越后调这里）。
///
/// 文案全由前端给：`title` 已经带上「标的 + 上破/下破 + 价」，宿主不参与措辞，
/// 免得「通知怎么写」在两处各有一份定义。
///
/// `sound`：不给就是**静音** toast（Windows 这条路径上 notify-rust 会连 `<audio>` 一起省掉），
/// 价格告警静悄悄地弹在屏幕角落等于没提醒，所以显式要一次默认提示音。
///
/// Windows 上必须显式给 AUMID：系统只肯显示「AUMID 在开始菜单注册过」的 toast，
/// 而 notify-rust 拿不到 app_id 时退回它内置的 PowerShell AUMID —— 新装 Win11 上那条
/// 快捷方式已经不存在，于是 `show()` 成功返回、屏幕上什么都没有（2026-09-30 实测）。
/// bundle identifier 正是安装包写进开始菜单快捷方式的那个 AUMID，dev 与安装版同一条路径。
#[tauri::command]
pub fn notify_price_alert(app: AppHandle, title: String, body: String) -> Result<(), String> {
    let mut note = notify_rust::Notification::new();
    note.summary(&title).body(&body).sound_name("Default");
    #[cfg(windows)]
    note.app_id(app.config().identifier.as_str());
    #[cfg(not(windows))]
    let _ = app;
    note.show().map(|_| ()).map_err(|e| {
        log::warn!("系统通知弹出失败：{e}");
        e.to_string()
    })
}
