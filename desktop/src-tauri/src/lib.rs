mod market;
mod update;

use tauri::{
    menu::{ContextMenu, Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindowBuilder,
    Window, WindowEvent,
};

const MAIN: &str = "main";
const MINI: &str = "mini";
/// 悬浮窗右键菜单里「隐藏」那一条的 id（菜单在 `open_mini_menu` 里建，
/// 语义统一在 `run()` 的全局 `on_menu_event` 里处理）。
const MENU_MINI_HIDE: &str = "mini_hide";
/// 面板宽度**必须与 `src/lib/layout.ts` 的 `MINI_WIDTH` 一致**（同上：跨 FFI 只能手工同步）。
/// 这里还决定窗口的横向落点（右边缘贴屏，见本文件下方的定位逻辑）。
const MINI_W: f64 = 236.0;
/// 行高与封顶行数**必须与 `src/lib/layout.ts` 的 `MINI_ROW_HEIGHT` / `MINI_MAX_ROWS`
/// 一致**：那边是权威定义（前端渲染行高也用它），这边管真实窗口高度，
/// 跨 FFI 没法共享常量，只能手工同步。theme.css 里的同名声明只是兜底默认值。
/// 封顶之外的行靠行区内部滚动看。
const MINI_ROW_H: f64 = 26.0;
const MINI_MAX_ROWS: u32 = 6;
/// 面板上下各 1px 边框。
const MINI_CHROME_H: f64 = 2.0;
/// 预建时的高度 = 封顶条数的高度，所以「自选 ≥ 6 条」的用户一上来就是对的尺寸。
const MINI_H: f64 = MINI_MAX_ROWS as f64 * MINI_ROW_H + MINI_CHROME_H;
/// 窗口高度的下界/上界（逻辑像素），兜住前端传来的离谱条数。
const MINI_MIN_H: f64 = MINI_ROW_H + MINI_CHROME_H;
const MINI_MAX_H: f64 = 166.0;
/// 距屏幕右侧 / 下侧的边距（逻辑像素）。
const MINI_MARGIN_X: f64 = 24.0;
const MINI_MARGIN_Y: f64 = 96.0;

fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN) {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn hide_mini(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MINI) {
        let _ = window.hide();
    }
}

/// 悬浮窗锚点：当前显示器右下角，返回逻辑像素坐标。
///
/// 逐级回退（主窗所在屏 → 主显示器 → 任意显示器 → 固定边距），**任何一级都不能返回 `None`**：
/// 「位置没设上」的后果是窗口停在系统给的默认位置（本机实测是 (49,49) 物理像素的左上角），
/// 比返回一个近似值糟糕得多。
fn mini_anchor(app: &AppHandle) -> (f64, f64) {
    let monitor = app
        .get_webview_window(MAIN)
        .and_then(|w| w.current_monitor().ok().flatten())
        .or_else(|| app.primary_monitor().ok().flatten())
        .or_else(|| {
            app.available_monitors()
                .ok()
                .and_then(|list| list.into_iter().next())
        });
    match monitor {
        Some(monitor) => {
            let size = monitor.size().to_logical::<f64>(monitor.scale_factor());
            (
                size.width - MINI_W - MINI_MARGIN_X,
                size.height - MINI_H - MINI_MARGIN_Y,
            )
        }
        None => (MINI_MARGIN_X, MINI_MARGIN_Y),
    }
}

/// 悬浮窗创建：贴当前显示器右下角，且**出生即隐藏**。
///
/// 位置与可见性都必须在 builder 里一次给定：
/// `build()` 之后窗口就已经是「可见 + 系统默认位置」的状态，再补 `set_position` / `hide`
/// 会留下一段窗口期 —— 前端 `ResizeObserver` 若在这段里上报高度，就会把错误的位置当基线，
/// 按「下边缘不动」算出一组漂浮的坐标。`visible(false)` 让它在被唤起前根本不参与枚举。
///
/// 必须在 setup 阶段（启动时）建，运行时再建 WebView2 控制器起不来（0x0 空窗）。
fn create_mini(app: &AppHandle) -> Option<tauri::WebviewWindow> {
    let (x, y) = mini_anchor(app);
    WebviewWindowBuilder::new(app, MINI, WebviewUrl::App("index.html#/mini".into()))
        .title("行情悬浮窗")
        .inner_size(MINI_W, MINI_H)
        .position(x, y)
        .visible(false)
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .shadow(false)
        .transparent(true)
        .build()
        .ok()
}

/// 悬浮窗开关：启动时已预建（隐藏），这里只做显隐切换。
fn toggle_mini(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MINI) {
        if window.is_visible().unwrap_or(false) {
            let _ = window.hide();
        } else {
            let _ = window.show();
            let _ = window.set_focus();
        }
    } else {
        // 预建失败时兜底；`create_mini` 出生即隐藏，这里不用再 hide。
        let _ = create_mini(app);
    }
}

/// 唤回主窗；带 symbol/market 时（悬浮窗点行）让主窗同步选中该标的。
/// 主窗起来的同时把悬浮窗收掉 —— 两个窗口是「二选一」的关系，不叠在一起。
#[tauri::command]
fn show_main_window(app: AppHandle, symbol: Option<String>, market: Option<String>) {
    if let (Some(symbol), Some(market)) = (symbol, market) {
        let _ = app.emit_to(
            MAIN,
            "select-symbol",
            serde_json::json!({ "symbol": symbol, "market": market }),
        );
    }
    hide_mini(&app);
    show_main(&app);
}

/// 主窗图标 → 悬浮窗：主窗收起，只剩小块看盘面板（托盘菜单里是同一个开关）。
#[tauri::command]
fn switch_to_mini(app: AppHandle) {
    if let Some(window) = app.get_webview_window(MINI) {
        let _ = window.show();
        let _ = window.set_focus();
    }
    if let Some(window) = app.get_webview_window(MAIN) {
        let _ = window.hide();
    }
}

/// 悬浮窗图标 → 主窗。
#[tauri::command]
fn switch_to_main(app: AppHandle) {
    hide_mini(&app);
    show_main(&app);
}

/// 悬浮窗右键菜单。
///
/// **菜单必须由宿主弹**（原生 popup）：悬浮窗就面板那么大，在 webview 里画一层
/// HTML 菜单立刻会被窗口边界裁掉 —— 原生菜单是独立窗口，能正常溢出到面板外面。
///
/// 条目的语义不写在这儿：id 交回 `run()` 里那个全局 `on_menu_event`，
/// 免得「隐藏悬浮窗」在两处各实现一遍。
#[tauri::command]
fn open_mini_menu(window: Window, app: AppHandle) -> Result<(), String> {
    let hide = MenuItem::with_id(&app, MENU_MINI_HIDE, "隐藏悬浮窗", true, None::<&str>)
        .map_err(|e| e.to_string())?;
    let menu = Menu::with_items(&app, &[&hide]).map_err(|e| e.to_string())?;
    menu.popup(window).map_err(|e| e.to_string())
}

/// 悬浮窗的目标高度（逻辑像素）：`min(条数, 封顶行数) × 行高 + 边框`。
///
/// 空自选也留一行 —— 那一行就是 `.mini-empty` 的「自选为空」提示。
/// 条数由前端给（它才知道自选有几条），几何由这里算：悬浮窗启动时是隐藏窗口，
/// 前端量不到排版，只能报条数。
fn mini_height_for_rows(rows: u32) -> f64 {
    rows.clamp(1, MINI_MAX_ROWS) as f64 * MINI_ROW_H + MINI_CHROME_H
}

/// 悬浮窗按自选条数自撑高度。
///
/// **下边缘不动**：悬浮窗贴在屏幕右下角，「右下角」就是它的锚点，按左上角缩放会让
/// 面板每加一条自选就整体往下挪一次 —— 只有 y 变、x 不变。
///
/// 当前坐标不可信时（`outer_position` 报错，或窗口还没真正映射、给出 (0,0) 这种退化值）
/// 不能用它当锚点，否则按「下边缘不动」算出来的是一组更离谱的坐标；退回监视器右下角。
#[tauri::command]
fn set_mini_rows(app: AppHandle, rows: u32) {
    let Some(window) = app.get_webview_window(MINI) else {
        return;
    };
    let Ok(scale) = window.scale_factor() else {
        return;
    };
    let Ok(size) = window.outer_size() else {
        return;
    };
    let target = (mini_height_for_rows(rows).clamp(MINI_MIN_H, MINI_MAX_H) * scale)
        .round()
        .max(1.0) as u32;
    if target == size.height {
        return;
    }
    let (anchor_x, anchor_y) = mini_anchor(&app);
    let pos = window
        .outer_position()
        .ok()
        .filter(|p| p.x != 0 || p.y != 0)
        .unwrap_or_else(|| {
            PhysicalPosition::new(
                (anchor_x * scale).round() as i32,
                ((anchor_y + MINI_H) * scale).round() as i32,
            )
        });
    let y = (pos.y + size.height as i32 - target as i32).max(0);
    let _ = window.set_size(PhysicalSize::new(size.width, target));
    let _ = window.set_position(PhysicalPosition::new(pos.x, y));
}

/// 悬浮窗不被 Windows 任务栏压住。
///
/// 悬浮窗与任务栏**同为 topmost 窗口**。点击任务栏时系统会把任务栏提到 topmost 组的
/// 最前，于是盖住悬浮窗；而这件事不会给我们发任何事件（用户点的是任务栏，我们的窗口
/// 连激活都没参与），所以只能自己查 z 序、事后纠正。
///
/// 只在「确实被任务栏压住」时才动手：无条件重设 topmost 会周期性地把自己压到
/// 开始菜单等其它 topmost 窗口前面，那比被压住更烦人。
#[cfg(windows)]
fn watch_mini_above_taskbar(app: AppHandle) {
    use std::time::Duration;

    /// 轮询间隔。够短 —— 点完任务栏几乎立刻看到悬浮窗回到最前。
    const TICK: Duration = Duration::from_millis(500);

    std::thread::spawn(move || loop {
        std::thread::sleep(TICK);
        let handle = app.clone();
        // 窗口操作统一回主线程做（Tauri 的窗口线程约定）
        let _ = app.run_on_main_thread(move || {
            let Some(window) = handle.get_webview_window(MINI) else {
                return;
            };
            if !window.is_visible().unwrap_or(false) {
                return;
            }
            let Ok(hwnd) = window.hwnd() else {
                return;
            };
            if taskbar::is_buried_by_taskbar(hwnd) {
                taskbar::raise(hwnd);
            }
        });
    });
}

/// macOS 的 Dock 与 NSWindow 是另一套 level 规则（floating level 本就压不过 Dock），
/// 而本项目只发 Windows 安装包 —— 这里留空实现，不假装处理了。
#[cfg(not(windows))]
fn watch_mini_above_taskbar(_app: AppHandle) {}

/// 任务栏遮挡相关的 Win32 调用（`windows` crate 只在 Windows 目标上依赖）。
#[cfg(windows)]
mod taskbar {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetClassNameW, GetWindow, GetWindowLongPtrW, SetWindowPos, GWL_EXSTYLE, GW_HWNDPREV,
        HWND_TOPMOST, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, WS_EX_TOPMOST,
    };

    /// 任务栏的窗口类名：主屏一个，副屏一个（多显示器时每个屏各有一条任务栏）。
    const TASKBAR_CLASSES: [&str; 2] = ["Shell_TrayWnd", "Shell_SecondaryTrayWnd"];

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

    /// 悬浮窗当前是否被任务栏压着。
    ///
    /// 沿 z 序往上（`GW_HWNDPREV`）**只看同一个 topmost 组**：topmost 窗口永远排在所有
    /// 非 topmost 之上，所以碰到第一个非 topmost 窗口就可以停 —— 它上面的都压不住我们。
    /// （`GetWindow` 取不到上一个窗口就是已经到顶，同样结束。）
    pub fn is_buried_by_taskbar(hwnd: HWND) -> bool {
        let mut cursor = hwnd;
        while let Ok(above) = unsafe { GetWindow(cursor, GW_HWNDPREV) } {
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
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 更新通道（endpoints/公钥）来自 tauri.conf.json 的 plugins.updater；
        // 检查与安装细节在 update.rs 里包了一层（加速站 + 验签自验）
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(update::UpdateShared::default())
        .invoke_handler(tauri::generate_handler![
            show_main_window,
            switch_to_mini,
            switch_to_main,
            open_mini_menu,
            set_mini_rows,
            market::market_request,
            update::check_update,
            update::start_update_download,
            update::get_update_download_state,
            update::install_update,
            update::open_url,
            update::update_mirrors,
            update::app_version
        ])
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }

            let show_main_item =
                MenuItem::with_id(app, "show_main", "显示主窗口", true, None::<&str>)?;
            let toggle_mini_item = MenuItem::with_id(
                app,
                "toggle_mini",
                "显示/隐藏悬浮窗",
                true,
                None::<&str>,
            )?;
            let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_main_item, &toggle_mini_item, &quit_item])?;

            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("Market Monitor")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show_main" => show_main(app),
                    "toggle_mini" => toggle_mini(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;

            // 悬浮窗启动即预建（隐藏），从托盘/主窗按钮唤起
            let _ = create_mini(app.handle());

            // 后台看住悬浮窗的 z 序：被任务栏压住就重新置顶（见 watch_mini_above_taskbar）
            watch_mini_above_taskbar(app.handle().clone());

            Ok(())
        })
        // 全局菜单事件：托盘菜单有自己的 `on_menu_event`，这里只接「没有自己的处理者」
        // 的那些菜单 —— 目前就是悬浮窗右键菜单。
        .on_menu_event(|app, event| {
            if event.id.as_ref() == MENU_MINI_HIDE {
                hide_mini(app);
            }
        })
        .on_window_event(|window, event| {
            // 主窗点关闭 = 收进托盘，进程继续跑，悬浮窗照常刷新
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == MAIN {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 钉住产品约定：`min(条数, 6) × 26 + 2`。字面量是照着 theme.css 的
    /// `--mini-row-h` / `--mini-max-rows` 与 src/lib/layout.ts 写的
    /// —— 那两处改了，这里必须一起改。
    #[test]
    fn mini_height_follows_row_count() {
        assert_eq!(mini_height_for_rows(0), 28.0, "空自选也要留一行提示");
        assert_eq!(mini_height_for_rows(1), 28.0);
        assert_eq!(mini_height_for_rows(3), 80.0);
        assert_eq!(mini_height_for_rows(6), 158.0);
        assert_eq!(mini_height_for_rows(8), 158.0, "超过 6 条封顶，靠行区内部滚动");
        assert_eq!(mini_height_for_rows(u32::MAX), 158.0, "离谱条数不能撑爆窗口");
    }

    /// 预建高度必须正好是「封顶条数」的高度：自选 ≥6 条的用户一上来就该是最终尺寸，
    /// 否则首次显示会看到一次跳变；同时它得落在钳位区间内，否则首次 resize 会被削掉。
    #[test]
    fn prebuild_height_is_the_capped_height() {
        assert_eq!(MINI_H, mini_height_for_rows(MINI_MAX_ROWS));
        assert!(MINI_MIN_H <= mini_height_for_rows(1));
        assert!(MINI_H <= MINI_MAX_H);
    }
}
