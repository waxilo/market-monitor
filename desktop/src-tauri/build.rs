fn main() {
  // tauri-build 只为 tauri.conf.json / capabilities 声明 rerun-if-changed，
  // 而 `bundle.frontendDist`（../dist）与 `bundle.icon`（icons/）都**不在其中**。
  // 后果：只改前端 / 只换图标时 build script 不重跑 ——
  //   · 前端产物（内嵌进 debug exe 的那份）不更新 ⇒ 打 Finished 但界面还是旧的
  //   · OUT_DIR/resource.lib 停留旧图标 ⇒ exe 里的图标不变
  //   · app crate 也不重编 ⇒ generate_context! 展开出的窗口图标同样是旧的
  // 而 cargo 照样打印 Finished，看上去"构建成功"。
  // 显式声明这两处，改完它们 cargo 会重跑本脚本并重编 app crate。
  println!("cargo:rerun-if-changed=../dist");
  println!("cargo:rerun-if-changed=icons");

  tauri_build::build()
}
