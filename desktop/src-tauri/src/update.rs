//! 应用内更新：固定更新通道（desktop-latest）+ 加速站 + minisign 签名自验。
//!
//! 发现与版本比较交给官方 tauri-plugin-updater（读固定通道里的 `latest.json`，
//! 按平台给下载地址与签名），本模块负责它不管的三件事：
//! 1. 加速站：manifest 与安装包都按「所选站 → 直连 → 其他站」的候选链依次尝试；
//! 2. 签名自验：下完先用 minisign 公钥验一遍，验过才交给 `Update::install`；
//! 3. **自动下载**的状态机：前端一查到新版就自动发起下载（不用用户点），
//!    所以重复检查不能把已下好的包或在途进度清掉（见 `check_effect`），
//!    换包时还得靠代次把在途结果丢掉（见 `should_write_back`）。
//!
//! 安装语义由插件负责：Windows 拉起 NSIS 后自行退出进程；macOS 原地替换 .app、
//! 需要调用方重启 —— 所以 `install_update` 里 install 之后必接一次 `app.restart()`。

use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64::Engine;
use minisign_verify::{PublicKey, Signature};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::{Update, UpdaterExt};
use url::Url;

/// 更新通道：固定 tag，CI 每次发版覆盖同一个 release 里的 latest.json。
const MANIFEST_URL: &str =
    "https://github.com/waxilo/market-monitor/releases/download/desktop-latest/latest.json";
/// 通道 Release 页：手动下载的兜底入口（应用内装不了时才让用户点它）。
const CHANNEL_PAGE: &str = "https://github.com/waxilo/market-monitor/releases/tag/desktop-latest";
const USER_AGENT: &str = "market-monitor-desktop";
/// 检查更新：manifest 只有 1KB，给整个请求设总时限就够。
const CHECK_TIMEOUT: Duration = Duration::from_secs(30);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// 下载按「每次读写」计时：连接或读取停顿超过它就换源，而不是给整个包设总时限 ——
/// 几十 MB 的安装包在慢网下也得让它下完。
const READ_TIMEOUT: Duration = Duration::from_secs(60);
const PROGRESS_INTERVAL: Duration = Duration::from_millis(200);
const MAIN: &str = "main";
const EVENT: &str = "update-download";

// ── 加速站 ──────────────────────────────────────────────────────────────

/// 更新加速站。语义是「拼在原始地址前」，由代理转发到 GitHub。
///
/// 候选地址一律成链而不是单点：这类公益代理寿命以周计，链上只要有一个通，
/// 用户就拿到包。manifest 与安装包都走同一份候选链，且**直连排在所选站之后**——
/// 开 VPN 时 GitHub 反而更容易按出口 IP 拒绝（共享机房 IP 被限流），
/// 此时换一个加速站比直连更靠谱。
#[derive(Clone, Copy, PartialEq, Eq)]
enum Mirror {
    Native,
    GhProxyCom,
    GhfastTop,
    GhproxyNet,
    GhProxyOrg,
}

impl Mirror {
    const ALL: [Mirror; 5] = [
        Mirror::Native,
        Mirror::GhProxyCom,
        Mirror::GhfastTop,
        Mirror::GhproxyNet,
        Mirror::GhProxyOrg,
    ];

    fn key(self) -> &'static str {
        match self {
            Mirror::Native => "native",
            Mirror::GhProxyCom => "gh-proxy.com",
            Mirror::GhfastTop => "ghfast.top",
            Mirror::GhproxyNet => "ghproxy.net",
            Mirror::GhProxyOrg => "gh-proxy.org",
        }
    }

    fn label(self) -> &'static str {
        match self {
            Mirror::Native => "GitHub 原生",
            Mirror::GhProxyCom => "gh-proxy.com",
            Mirror::GhfastTop => "ghfast.top",
            Mirror::GhproxyNet => "ghproxy.net",
            Mirror::GhProxyOrg => "gh-proxy.org",
        }
    }

    /// 空串表示直连 GitHub。
    fn prefix(self) -> &'static str {
        match self {
            Mirror::Native => "",
            Mirror::GhProxyCom => "https://gh-proxy.com/",
            Mirror::GhfastTop => "https://ghfast.top/",
            Mirror::GhproxyNet => "https://ghproxy.net/",
            Mirror::GhProxyOrg => "https://gh-proxy.org/",
        }
    }

    /// 解析存下来的值。也认前缀：旧 Android 版本让用户手填过前缀，
    /// 对不上的自定义前缀只能放弃（已改为内置下拉）。
    fn from_key(raw: &str) -> Mirror {
        let value = raw.trim();
        if let Some(mirror) = Self::ALL.into_iter().find(|m| m.key() == value) {
            return mirror;
        }
        if value.is_empty() {
            return Mirror::Native;
        }
        let normalized = value.trim_end_matches('/');
        Self::ALL
            .into_iter()
            .find(|m| !m.prefix().is_empty() && m.prefix().trim_end_matches('/') == normalized)
            .unwrap_or(Mirror::Native)
    }

    fn accelerated(self, url: &str) -> String {
        if self.prefix().is_empty() {
            return url.to_string();
        }
        format!("{}/{}", self.prefix().trim_end_matches('/'), url)
    }

    /// 候选链：所选站 → 直连 → 其他站。manifest 与安装包共用一个实现。
    fn chain(self, url: &str) -> Vec<String> {
        let mut urls = vec![self.accelerated(url)];
        if self != Mirror::Native {
            urls.push(url.to_string());
        }
        for mirror in Self::ALL
            .into_iter()
            .filter(|m| *m != Mirror::Native && *m != self)
        {
            urls.push(mirror.accelerated(url));
        }
        dedup(urls)
    }
}

fn dedup(urls: Vec<String>) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    urls.into_iter().filter(|u| seen.insert(u.clone())).collect()
}

// ── 状态与 DTO ──────────────────────────────────────────────────────────

/// 下载进度与结果。`progress` 为 0..1，-1 表示总量未知（服务端没给 Content-Length）。
/// 安装包字节留在 `UpdateShared` 里，所以 `Done` 不带路径。
#[derive(Serialize, Clone, Default)]
#[serde(tag = "state", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum DlState {
    #[default]
    Idle,
    Running {
        progress: f64,
        downloaded: u64,
        total: u64,
    },
    Done,
    Failed {
        message: String,
    },
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfoDto {
    current_version: String,
    latest_version: String,
    notes: String,
    published_at: Option<String>,
    page_url: String,
}

/// 应用级更新状态：检查结果、下载进度与安装包字节都留在这里而不是前端，
/// 主窗收起进托盘、切页面回来都得能读到「已经在下载 / 已经下好」。
#[derive(Default)]
pub struct UpdateShared(Mutex<Inner>);

#[derive(Default)]
struct Inner {
    /// 插件返回的更新句柄，`install` 要用（含下载地址、签名、解包路径）。
    update: Option<Update>,
    download: DlState,
    downloading: bool,
    /// 下好且验签通过的安装包字节；重新检查后作废。
    bytes: Vec<u8>,
    /// 下载代次：每换一次包（或清空状态）就 +1，在途下载带着自己那代跑，
    /// 回来时代次对不上就丢弃结果 —— 否则换包后旧包的字节会盖掉新状态。
    download_gen: u64,
}

/// 安装包身份：版本 + 下载地址 + 签名。三者全同才算「还是那个包」。
/// 只比版本不够 —— 换了加速站或 CI 重发同名产物时，后两者会变。
fn release_id(update: &Update) -> String {
    format!(
        "{}|{}|{}",
        update.version, update.download_url, update.signature
    )
}

/// 新一轮检查对「已下好的包 / 在途下载」的影响。
#[derive(Debug, PartialEq, Eq)]
enum CheckEffect {
    /// 还是同一个包：保留字节与进度。
    Keep,
    /// 换包、首次、或已经查不到更新：清空；在途下载靠代次作废。
    Reset,
}

/// ⚠️ 这条规则是「自动下载」能不能成立的前提。
///
/// 以前检查一次就无条件清空，是因为下载纯手动、重新检查必然意味着「重新决定要不要下」。
/// 现在发现新版会**自动**开下，而检查会更频繁地发生（启动检查 + 用户手动点）——
/// 若无条件清空，用户随手点一下「检查更新」就会把已经下好的包丢掉，
/// 或者把正在下的进度清成 idle（在途任务回来又被 `downloading == false` 挡住），
/// 表现是「进度条跑到一半自己没了」。
fn check_effect(previous: Option<&str>, next: Option<&str>) -> CheckEffect {
    match (previous, next) {
        (Some(previous), Some(next)) if previous == next => CheckEffect::Keep,
        _ => CheckEffect::Reset,
    }
}

/// 在途下载的结果要不要写回：代次对不上说明期间已经换了包，结果必须丢。
fn should_write_back(run_gen: u64, state_gen: u64) -> bool {
    run_gen == state_gen
}

impl UpdateShared {
    /// 锁只在闭包内短暂持有：下载回调与命令处理会交叉访问，
    /// 各自取一份拷贝出去就够，避免把 guard 带出作用域。
    fn with<T>(&self, action: impl FnOnce(&mut Inner) -> T) -> T {
        let mut inner = self.0.lock().unwrap_or_else(|e| e.into_inner());
        action(&mut inner)
    }
}

// ── 命令 ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct MirrorDto {
    key: &'static str,
    label: &'static str,
}

#[tauri::command]
pub fn update_mirrors() -> Vec<MirrorDto> {
    Mirror::ALL
        .into_iter()
        .map(|mirror| MirrorDto {
            key: mirror.key(),
            label: mirror.label(),
        })
        .collect()
}

#[tauri::command]
pub fn app_version(app: AppHandle) -> String {
    app.package_info().version.to_string()
}

/// 检查更新：候选链按顺序试 manifest，拿到的版本比当前新才返回内容。
/// 返回 null 表示已是最新（含通道里还没有比当前更新的版本）。
#[tauri::command]
pub async fn check_update(app: AppHandle, mirror: String) -> Result<Option<UpdateInfoDto>, String> {
    // dev 构建不参与更新通道。它跑的是 `target/debug/market-monitor.exe`（身份
    // `com.waxilo.marketmonitor.dev`），而通道里发的是正式包 —— 允许它就等于
    // 让「调试实例」原地变成「正式安装版」，两条线从此串在一起。
    if cfg!(debug_assertions) {
        return Err("开发版不检查更新".to_string());
    }

    let mirror = Mirror::from_key(&mirror);
    let chain = mirror.chain(MANIFEST_URL);
    let endpoints: Vec<Url> = chain
        .iter()
        .map(|url| Url::parse(url).map_err(|e| format!("更新地址无效：{e}")))
        .collect::<Result<_, _>>()?;

    let updater = app
        .updater_builder()
        .endpoints(endpoints)
        .map_err(|e| format!("更新通道配置无效：{e}"))?
        .timeout(CHECK_TIMEOUT)
        .build()
        .map_err(|e| format!("更新检查初始化失败：{e}"))?;
    let update = updater
        .check()
        .await
        .map_err(|e| check_failed(&e, chain.len()))?;

    let dto = update.as_ref().map(to_dto);
    let next_id = update.as_ref().map(release_id);
    {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            let previous_id = inner.update.as_ref().map(release_id);
            if check_effect(previous_id.as_deref(), next_id.as_deref()) == CheckEffect::Reset {
                inner.download = DlState::Idle;
                inner.downloading = false;
                inner.bytes.clear();
                inner.download_gen = inner.download_gen.wrapping_add(1);
            }
            // 句柄总是换成最新的（更新说明、发布日期可能变了），
            // 但上面判为 Keep 时已下好的字节与在途进度都留着。
            inner.update = update;
        });
    }
    Ok(dto)
}

#[tauri::command]
pub fn start_update_download(app: AppHandle, mirror: String) -> Result<(), String> {
    let started = {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            // 已在下载 / 已经下好：不再重下一次。前者挡住按钮连点（第二次会把进度写乱），
            // 后者挡住「自动下载 + 手动再点一次」把已经验过签的字节清掉。
            if inner.downloading || matches!(inner.download, DlState::Done) {
                return Ok(None);
            }
            let Some(update) = inner.update.clone() else {
                return Err("请先检查更新".to_string());
            };
            inner.downloading = true;
            inner.download = DlState::Running {
                progress: -1.0,
                downloaded: 0,
                total: 0,
            };
            inner.bytes.clear();
            Ok(Some((update, inner.download_gen)))
        })?
    };
    let Some((update, gen)) = started else {
        return Ok(());
    };

    let initial = DlState::Running {
        progress: -1.0,
        downloaded: 0,
        total: 0,
    };
    let _ = app.emit_to(MAIN, EVENT, initial);

    let mirror = Mirror::from_key(&mirror);
    let handle = app.clone();
    tauri::async_runtime::spawn(async move { run_download(handle, update, mirror, gen).await });
    Ok(())
}

#[tauri::command]
pub fn get_update_download_state(app: AppHandle) -> DlState {
    app.state::<UpdateShared>()
        .with(|inner| inner.download.clone())
}

/// 应用内安装并重启。Windows 侧 `install` 拉起 NSIS 后自行退出进程（走不到下一行）；
/// macOS 侧是原地替换 .app，装完必须重启才跑得上新版本。
#[tauri::command]
pub async fn install_update(app: AppHandle) -> Result<(), String> {
    let (update, bytes) = {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| match inner.update.clone() {
            Some(update) if !inner.bytes.is_empty() => {
                Ok((update, std::mem::take(&mut inner.bytes)))
            }
            _ => Err("安装包尚未下载完成".to_string()),
        })?
    };
    tauri::async_runtime::spawn_blocking(move || update.install(&bytes))
        .await
        .map_err(|e| format!("安装失败：{e}"))?
        .map_err(|e| format!("安装失败：{e}"))?;
    app.restart();
}

#[tauri::command]
pub fn open_url(url: String) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err("只允许打开 https 链接".to_string());
    }
    // Windows 走 rundll32（直接吃 URL，不经过 shell 解析），macOS 走 open
    let program = if cfg!(target_os = "windows") {
        "rundll32.exe"
    } else if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    let mut command = std::process::Command::new(program);
    if cfg!(target_os = "windows") {
        command.arg("url.dll,FileProtocolHandler");
    }
    command
        .arg(&url)
        .spawn()
        .map_err(|e| format!("打开浏览器失败：{e}"))?;
    Ok(())
}

// ── 实现 ────────────────────────────────────────────────────────────────

fn to_dto(update: &Update) -> UpdateInfoDto {
    UpdateInfoDto {
        current_version: update.current_version.clone(),
        latest_version: update.version.clone(),
        notes: update.body.clone().unwrap_or_default(),
        // manifest 的 pub_date 原样带出去，前端只截日期
        published_at: update
            .raw_json
            .get("pub_date")
            .and_then(|value| value.as_str())
            .map(str::to_string),
        page_url: CHANNEL_PAGE.to_string(),
    }
}

/// 插件对「通道拿不到 / manifest 解析不了」只给一个 `ReleaseNotFound`，
/// 用户看到的得是「试了几个源、可以换站重试」这种能照做的话。
fn check_failed(err: &tauri_plugin_updater::Error, sources: usize) -> String {
    match err {
        tauri_plugin_updater::Error::ReleaseNotFound => format!(
            "检查更新失败：拿不到更新通道（已依次尝试 {sources} 个源）。开 VPN 时 GitHub 会按出口 IP 拒绝，换个加速站或关掉 VPN 再试"
        ),
        other => format!("检查更新失败：{other}"),
    }
}

/// 更新公钥（minisign 公钥文件的 base64，由发布流程写进 tauri.conf.json）。
/// 与插件读的是同一处配置，避免两处公钥对不上。
fn release_pubkey(app: &AppHandle) -> Result<String, String> {
    #[derive(Deserialize)]
    struct UpdaterCfg {
        pubkey: String,
    }

    let raw = app
        .config()
        .plugins
        .0
        .get("updater")
        .cloned()
        .ok_or_else(|| "更新插件未配置（tauri.conf.json 的 plugins.updater）".to_string())?;
    let config: UpdaterCfg =
        serde_json::from_value(raw).map_err(|e| format!("更新配置无效：{e}"))?;
    if config.pubkey.trim().is_empty() {
        return Err("更新公钥未配置".to_string());
    }
    Ok(config.pubkey)
}

async fn run_download(app: AppHandle, update: Update, mirror: Mirror, gen: u64) {
    let state = match download_and_verify(&app, &update, mirror, gen).await {
        Ok(bytes) => {
            let shared = app.state::<UpdateShared>();
            shared.with(|inner| {
                if should_write_back(gen, inner.download_gen) {
                    inner.bytes = bytes;
                }
            });
            DlState::Done
        }
        Err(message) => DlState::Failed { message },
    };
    let mut publish = false;
    {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            // 期间换过包：这一轮结论整体作废，别把新包的状态盖成旧包的结果。
            if !should_write_back(gen, inner.download_gen) {
                return;
            }
            inner.downloading = false;
            inner.download = state.clone();
            publish = true;
        });
    }
    if publish {
        let _ = app.emit_to(MAIN, EVENT, state);
    }
}

/// 依次尝试候选地址，返回第一个「下全且验签通过」的字节。
async fn download_and_verify(
    app: &AppHandle,
    update: &Update,
    mirror: Mirror,
    gen: u64,
) -> Result<Vec<u8>, String> {
    let pubkey = release_pubkey(app)?;
    let client = reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(READ_TIMEOUT)
        .build()
        .map_err(|e| format!("网络客户端初始化失败：{e}"))?;

    let urls = mirror.chain(update.download_url.as_str());
    let mut last: Option<String> = None;
    for url in &urls {
        let bytes = match download_once(&client, url, app, gen).await {
            Ok(bytes) => bytes,
            Err(e) => {
                last = Some(e);
                continue;
            }
        };
        // 验签不过也换源：可能只是这一站把包传坏了；没验过的字节绝不交付
        if let Err(e) = verify_signature(&bytes, &update.signature, &pubkey) {
            last = Some(e);
            continue;
        }
        return Ok(bytes);
    }
    let tail = if urls.len() > 1 {
        format!("（已依次尝试 {} 个源）", urls.len())
    } else {
        String::new()
    };
    Err(format!(
        "下载失败：{}{tail}",
        last.unwrap_or_else(|| "无可用地址".to_string())
    ))
}

/// 流式下载进内存并回报进度（安装包要整块交给 `Update::install`）。
async fn download_once(
    client: &reqwest::Client,
    url: &str,
    app: &AppHandle,
    gen: u64,
) -> Result<Vec<u8>, String> {
    let mut response = client
        .get(url)
        .header(reqwest::header::ACCEPT, "application/octet-stream")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!("HTTP {}", status.as_u16()));
    }

    let total = response.content_length().unwrap_or(0);
    let mut bytes: Vec<u8> = Vec::new();
    let mut downloaded: u64 = 0;
    let mut last_emit = Instant::now();
    publish_progress(app, 0, total, gen);
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|e| format!("读取下载数据失败：{e}"))?
    {
        bytes.extend_from_slice(&chunk);
        downloaded += chunk.len() as u64;
        if last_emit.elapsed() >= PROGRESS_INTERVAL {
            last_emit = Instant::now();
            publish_progress(app, downloaded, total, gen);
        }
    }
    if bytes.is_empty() {
        return Err("下载内容为空".to_string());
    }
    Ok(bytes)
}

/// minisign 验签，与插件内部（`Update::download`）用同一套实现和公钥格式：
/// 公钥与签名都是「base64 包着 minisign 文本」。
fn verify_signature(data: &[u8], release_signature: &str, pub_key: &str) -> Result<(), String> {
    fn decode(value: &str) -> Result<String, String> {
        let raw = base64::engine::general_purpose::STANDARD
            .decode(value.trim())
            .map_err(|e| format!("更新签名不是有效的 base64：{e}"))?;
        String::from_utf8(raw).map_err(|_| "更新签名不是有效的文本".to_string())
    }

    let public_key =
        PublicKey::decode(&decode(pub_key)?).map_err(|e| format!("更新公钥无效：{e}"))?;
    let signature = Signature::decode(&decode(release_signature)?)
        .map_err(|e| format!("更新签名无效：{e}"))?;
    public_key.verify(data, &signature, true).map_err(|_| {
        "安装包签名校验失败，已丢弃下载（包可能被中间环节篡改），请换个加速站重试".to_string()
    })
}

fn publish_progress(app: &AppHandle, downloaded: u64, total: u64, gen: u64) {
    let progress = if total > 0 {
        downloaded as f64 / total as f64
    } else {
        -1.0
    };
    let state = DlState::Running {
        progress,
        downloaded,
        total,
    };
    let mut publish = false;
    {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            if inner.downloading && should_write_back(gen, inner.download_gen) {
                inner.download = state.clone();
                publish = true;
            }
        });
    }
    if publish {
        let _ = app.emit_to(MAIN, EVENT, state);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest_url() -> &'static str {
        MANIFEST_URL
    }

    #[test]
    fn chain_starts_from_selection_and_stays_deduped() {
        let url = manifest_url();
        let native = Mirror::Native.chain(url);
        assert_eq!(native[0], url); // 直连就用原地址
        assert_eq!(native.len(), 5); // 直连 + 四个加速站

        let proxied = Mirror::GhfastTop.chain(url);
        assert_eq!(proxied[0], format!("https://ghfast.top/{url}"));
        assert_eq!(proxied[1], url); // 非原生时直连排第二
        assert_eq!(proxied.len(), 5); // 所选站已计入，其余站补位
        assert_eq!(
            proxied.iter().filter(|u| u.as_str() == url).count(),
            1,
            "直连不能重复出现"
        );
    }

    #[test]
    fn mirror_keys_tolerate_legacy_prefix_values() {
        assert!(matches!(Mirror::from_key("gh-proxy.com"), Mirror::GhProxyCom));
        assert!(matches!(Mirror::from_key("https://ghfast.top/"), Mirror::GhfastTop));
        assert!(matches!(Mirror::from_key(""), Mirror::Native));
        assert!(matches!(Mirror::from_key("https://example.com/"), Mirror::Native));
    }

    /// 全部源都不可达时，报错要说清「试了几个源」，而不是只丢一个 HTTP 码。
    #[test]
    fn check_failure_mentions_source_count() {
        let message = check_failed(&tauri_plugin_updater::Error::ReleaseNotFound, 5);
        assert!(message.contains("5 个源"), "{message}");
    }

    /// 同一个包重复检查 ⇒ 已下好的字节留着。
    /// 自动下载是常态，用户随手点一下「检查更新」不能把下好的包丢掉。
    #[test]
    fn same_release_keeps_downloaded_bytes() {
        let id = "0.1.3|https://example.com/a.exe|sig";
        assert_eq!(check_effect(Some(id), Some(id)), CheckEffect::Keep);
    }

    /// 换了包 / 首次查到 / 变成没有更新 ⇒ 一律清空。
    #[test]
    fn different_or_missing_release_resets_download() {
        assert_eq!(
            check_effect(Some("0.1.3|a|s"), Some("0.1.4|a|s")),
            CheckEffect::Reset,
            "换了版本"
        );
        assert_eq!(
            check_effect(Some("0.1.3|a|s"), Some("0.1.3|b|s")),
            CheckEffect::Reset,
            "换了下载地址（换了加速站 / CI 重发同名产物）"
        );
        assert_eq!(
            check_effect(Some("0.1.3|a|s"), Some("0.1.3|a|t")),
            CheckEffect::Reset,
            "换了签名"
        );
        assert_eq!(
            check_effect(None, Some("0.1.3|a|s")),
            CheckEffect::Reset,
            "首次查到"
        );
        assert_eq!(
            check_effect(Some("0.1.3|a|s"), None),
            CheckEffect::Reset,
            "已经查不到更新"
        );
    }

    /// 在途下载带着自己那代跑，回来时代次对不上就丢结果 ——
    /// 否则换包后旧包的字节会盖掉新包的状态。
    #[test]
    fn stale_download_run_is_dropped() {
        assert!(should_write_back(3, 3));
        assert!(!should_write_back(3, 4), "期间换过包 ⇒ 结果作废");
    }
}
