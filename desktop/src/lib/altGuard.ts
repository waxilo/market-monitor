/**
 * 吞掉 Alt 的默认动作（2026-10-26 用户）。
 *
 * 现象：在图上按 Alt 会「丢失鼠标」、定位线不跟随 —— Alt 一按下去输入就被宿主截走了，
 * 网页再也收不到 pointermove。这里把浏览器/网页给 Alt 挂的默认行为一律挡掉；
 * 宿主那一侧的同类拦截在 src-tauri 的 `browser_keys` 模块（关 WebView2 的浏览器加速键），
 * 两边各管一层，缺一层都可能漏。
 *
 * ❗只 `preventDefault`，**不是** `stopPropagation`：Alt 作为**修饰键**必须照常工作 ——
 * 全局热键 Alt+D 由宿主注册，设置面板录制组合键也要看得见 Alt；被挡掉的只有「按 Alt
 * 本身会触发的默认动作」。keydown / keyup 都要挡：浏览器对 Alt 的应答是压在抬起上的。
 */
export function installAltGuard(): void {
  const swallow = (e: KeyboardEvent) => {
    if (e.key === 'Alt') e.preventDefault();
  };
  // 捕获阶段挂 window：先于任何业务监听跑，也先于浏览器的默认动作
  window.addEventListener('keydown', swallow, true);
  window.addEventListener('keyup', swallow, true);
}