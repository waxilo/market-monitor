//! 关掉 WebView2 自带的「浏览器加速键」。
//!
//! 用户反馈（2026-10-26）：在图上按 Alt 会「丢失鼠标」、定位线不再跟随 —— 输入被宿主
//! 那边抢走了，网页一侧再也收不到 pointermove。被抢的就是 WebView2 自带的浏览器按键
//! 处理：Alt 相关那几条（Alt+←/→ 前进后退、Alt+D 聚焦地址栏……）对一个桌面应用毫无意义，
//! 却正好压在「用 Alt 做的组合键」上。整档关掉：
//! `AreBrowserAcceleratorKeysEnabled = false`（属性在 Settings3 上，Win10 起的 WebView2 都有）。
//!
//! 关掉的只有**浏览器**的加速键（刷新 / 缩放 / 前进后退 / 地址栏 / DevTools 快捷键），
//! 应用自己的键一个不动：Ctrl+K 是网页自己监听的，Alt+D 是宿主 `RegisterHotKey` 注册的。
//! 网页层还有一道同义的拦截（`src/lib/altGuard.ts`），两边各管一层：那一道管「网页默认行为」，
//! 这一道管「宿主替浏览器应答」。
//!
//! 属性属于 WebView（不是窗口），创建后设一次即可；失败静默忽略 —— 拿不到设置 API 时
//! 行为退回改动前，不该因此挡住窗口。
//!
//! 平台差异按项目规矩收进本模块：入口平台无关，COM 调用关在 `cfg(windows)` 里。

#[cfg(windows)]
pub fn disable_browser_accelerators(window: &tauri::WebviewWindow) {
    use webview2_com::Microsoft::Web::WebView2::Win32::ICoreWebView2Settings3;
    use windows::core::Interface;

    let _ = window.with_webview(|webview| {
        let controller = webview.controller();
        // COM 调用都要 unsafe；每一步都可能拿不到（老运行时 / 空控制器），失败即放弃
        unsafe {
            let Ok(core) = controller.CoreWebView2() else {
                return;
            };
            let Ok(settings) = core.Settings() else {
                return;
            };
            let Ok(settings3) = settings.cast::<ICoreWebView2Settings3>() else {
                return;
            };
            let _ = settings3.SetAreBrowserAcceleratorKeysEnabled(false);
        }
    });
}

#[cfg(not(windows))]
pub fn disable_browser_accelerators(_window: &tauri::WebviewWindow) {}