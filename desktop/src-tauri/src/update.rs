//! 应用内更新：GitHub Releases 取桌面端产物 → 校验 SHA-256 → 拉起安装器。
//!
//! 与 Android 侧同名模块一一对应（UpdateMirror / VersionCompare / GithubReleaseApi /
//! UpdateCenter），只有两处是平台逼出来的差异：
//! 1. 发现方式：Android 用 `/releases/latest`，而本仓库的 latest 会落在 Android 包上，
//!    所以桌面端改成拉列表再按 `desktop-v` 前缀筛最新正式版；
//! 2. 产物与安装：APK 换成 `*-setup.exe`（NSIS），下载完由用户点「安装并退出」，
//!    不自动关闭应用。

use std::cmp::Ordering;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager};

const OWNER: &str = "waxilo";
const REPO: &str = "market-monitor";
const API_BASE: &str = "https://api.github.com";
/// 桌面端发布走独立 tag 命名空间，与 Android 的 `v*`/`X.Y.Z` 分开。
const RELEASE_PREFIX: &str = "desktop-v";
/// GitHub 强制要求 User-Agent，缺失直接 403。
const USER_AGENT: &str = "market-monitor-desktop";
const API_TIMEOUT: Duration = Duration::from_secs(30);
/// 下载按「每次读写」计时：连接或读取停顿超过它就换源，而不是给整个包设总时限。
const IO_TIMEOUT: Duration = Duration::from_secs(60);
const PROGRESS_INTERVAL: Duration = Duration::from_millis(200);
const MAIN: &str = "main";
const EVENT: &str = "update-download";

// ── 加速站 ──────────────────────────────────────────────────────────────

/// 更新加速站。语义是「拼在原始地址前」，由代理转发到 GitHub。
///
/// 候选地址一律成链而不是单点：这类公益代理寿命以周计，链上只要有一个通，
/// 用户就拿到包。`api_capable` 记录「这家是否中转 api.github.com」——实测只有
/// 部分代理放开，代不了 API 的站放进检查更新的候选里只会白跑一趟 403。
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

    fn api_capable(self) -> bool {
        matches!(self, Mirror::GhProxyCom | Mirror::GhProxyOrg)
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

    /// 检查更新的候选：所选站（仅当它代 API）→ 直连 → 其他代 API 的站。
    ///
    /// 直连排在所选站之后：**开 VPN 时 api.github.com 反而更容易 403**——
    /// VPN 出口是共享机房 IP，GitHub 按 IP 限流甚至直接拒绝，而资产下载不受影响。
    fn api_chain(self, path: &str) -> Vec<String> {
        let direct = format!("{}{}", API_BASE.trim_end_matches('/'), path);
        let mut urls = Vec::new();
        if self.api_capable() {
            urls.push(self.accelerated(&direct));
        }
        urls.push(direct.clone());
        for mirror in Self::ALL
            .into_iter()
            .filter(|m| m.api_capable() && *m != self)
        {
            urls.push(mirror.accelerated(&direct));
        }
        dedup(urls)
    }

    /// 下载产物 / 校验值的候选：所选站 → 直连 → 其他站。
    fn asset_chain(self, url: &str) -> Vec<String> {
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

// ── 版本比较 ────────────────────────────────────────────────────────────

#[derive(Clone, Debug)]
struct SemVer {
    major: u64,
    minor: u64,
    patch: u64,
    /// 预发布标识，如 "beta.1"；正式版为空串。
    pre: String,
}

/// 兼容 `v1.2.3` / `desktop-v1.2.3` / `1.2` / `1.2.3-beta.1+build5`。解析失败返回 None。
fn parse_semver(raw: &str) -> Option<SemVer> {
    let mut text = raw.trim();
    if let Some(rest) = text.strip_prefix('v') {
        text = rest;
    }
    if let Some(rest) = text.strip_prefix('V') {
        text = rest;
    }
    if text.is_empty() {
        return None;
    }
    if let Some(index) = text.find('+') {
        text = &text[..index];
    }
    let mut pre = String::new();
    if let Some(index) = text.find('-') {
        pre = text[index + 1..].to_string();
        text = &text[..index];
    }
    let parts: Vec<&str> = text.split('.').map(|part| part.trim()).collect();
    if parts
        .iter()
        .any(|part| !part.is_empty() && !part.chars().all(|c| c.is_ascii_digit()))
    {
        return None;
    }
    let num = |index: usize| -> u64 {
        parts
            .get(index)
            .and_then(|part| part.parse::<u64>().ok())
            .unwrap_or(0)
    };
    Some(SemVer {
        major: num(0),
        minor: num(1),
        patch: num(2),
        pre,
    })
}

fn compare_semver(a: &SemVer, b: &SemVer) -> Ordering {
    let core = a
        .major
        .cmp(&b.major)
        .then_with(|| a.minor.cmp(&b.minor))
        .then_with(|| a.patch.cmp(&b.patch));
    if core != Ordering::Equal {
        return core;
    }
    compare_pre_release(&a.pre, &b.pre)
}

fn compare_pre_release(a: &str, b: &str) -> Ordering {
    if a == b {
        return Ordering::Equal;
    }
    if a.is_empty() {
        return Ordering::Greater; // 正式版大于任何预发布版
    }
    if b.is_empty() {
        return Ordering::Less;
    }
    let left: Vec<&str> = a.split('.').collect();
    let right: Vec<&str> = b.split('.').collect();
    for index in 0..left.len().max(right.len()) {
        // 前缀相同时，标识符段少者更小（1.0.0-alpha < 1.0.0-alpha.1）
        let (Some(l), Some(r)) = (left.get(index), right.get(index)) else {
            return if left.get(index).is_none() {
                Ordering::Less
            } else {
                Ordering::Greater
            };
        };
        let result = compare_identifier(l, r);
        if result != Ordering::Equal {
            return result;
        }
    }
    Ordering::Equal
}

fn compare_identifier(left: &str, right: &str) -> Ordering {
    match (left.parse::<u64>(), right.parse::<u64>()) {
        (Ok(l), Ok(r)) => l.cmp(&r),
        (Ok(_), Err(_)) => Ordering::Less, // 数字标识符小于字母标识符
        (Err(_), Ok(_)) => Ordering::Greater,
        (Err(_), Err(_)) => left.cmp(right),
    }
}

/// 最新版本 tag 是否比当前版本新（解析失败一律视为无更新，避免误弹提示）。
fn has_update(latest_tag: &str, current_version: &str) -> bool {
    match (parse_semver(latest_tag), parse_semver(current_version)) {
        (Some(latest), Some(current)) => compare_semver(&latest, &current) == Ordering::Greater,
        _ => false,
    }
}

/// tag `desktop-v1.2.3` → 版本号 `1.2.3`（比较与展示都走这里，不能直接拿 tag 比）。
fn release_version(tag: &str) -> &str {
    tag.strip_prefix(RELEASE_PREFIX).unwrap_or(tag)
}

// ── GitHub 数据模型 ─────────────────────────────────────────────────────

#[derive(Deserialize)]
struct GhAsset {
    name: String,
    browser_download_url: String,
    #[serde(default)]
    size: i64,
    /// GitHub 侧的 `sha256:<hex>`，可能缺失（老发布或非 GitHub 托管）。
    #[serde(default)]
    digest: Option<String>,
}

#[derive(Deserialize)]
struct GhRelease {
    tag_name: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    published_at: Option<String>,
    #[serde(default)]
    html_url: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    assets: Vec<GhAsset>,
}

#[derive(Clone)]
struct AssetRef {
    name: String,
    url: String,
    size: i64,
}

/// 一次检查的完整结果（对应 Android 的 UpdateInfo）。
#[derive(Clone)]
struct Resolved {
    current_version: String,
    /// 去掉 `desktop-v` 前缀的 tag，用于展示。
    latest_version: String,
    release_name: String,
    notes: String,
    published_at: Option<String>,
    page_url: String,
    asset: Option<AssetRef>,
    expected_sha256: Option<String>,
}

impl Resolved {
    /// 只有拿到可信校验值才走应用内下载安装：产物必须验证摘要，
    /// 无法验证时退回浏览器打开 Release 页，由用户自行判断。
    fn can_install(&self) -> bool {
        self.asset.as_ref().map(|a| !a.url.is_empty()).unwrap_or(false)
            && self.expected_sha256.is_some()
    }

    fn file_name(&self) -> String {
        self.asset
            .as_ref()
            .map(|a| a.name.clone())
            .filter(|name| !name.is_empty())
            .unwrap_or_else(|| format!("market-monitor-{}-setup.exe", self.latest_version))
    }

    fn to_dto(&self) -> UpdateInfoDto {
        UpdateInfoDto {
            current_version: self.current_version.clone(),
            latest_version: self.latest_version.clone(),
            release_name: self.release_name.clone(),
            notes: self.notes.clone(),
            published_at: self.published_at.clone(),
            page_url: self.page_url.clone(),
            asset_name: self.file_name(),
            asset_size: self.asset.as_ref().map(|a| a.size).unwrap_or(0),
            can_install: self.can_install(),
            expected_sha256: self.expected_sha256.clone(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfoDto {
    current_version: String,
    latest_version: String,
    release_name: String,
    notes: String,
    published_at: Option<String>,
    page_url: String,
    asset_name: String,
    asset_size: i64,
    can_install: bool,
    expected_sha256: Option<String>,
}

/// 下载进度与结果。`progress` 为 0..1，-1 表示总量未知（服务端没给 Content-Length）。
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
    Done {
        path: String,
    },
    Failed {
        message: String,
    },
}

/// 应用级更新状态：检查结果与下载进度都留在这里而不是前端，
/// 主窗收起进托盘、切页面回来都得能读到「已经在下载 / 已经下好」。
#[derive(Default)]
pub struct UpdateShared(Mutex<Inner>);

#[derive(Default)]
struct Inner {
    release: Option<Resolved>,
    download: DlState,
    downloading: bool,
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

/// 检查更新。返回 null 表示没有可用更新（含仓库里还没有桌面端发布）。
#[tauri::command]
pub async fn check_update(app: AppHandle, mirror: String) -> Result<Option<UpdateInfoDto>, String> {
    let mirror = Mirror::from_key(&mirror);
    let current = app.package_info().version.to_string();
    let resolved = tauri::async_runtime::spawn_blocking(move || resolve(mirror, &current))
        .await
        .map_err(|e| format!("检查更新失败：{e}"))??;

    {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            inner.release = resolved.clone();
            // 换了版本，上一轮下好的包就不能再拿来装
            inner.download = DlState::Idle;
            inner.downloading = false;
        });
    }
    Ok(resolved.as_ref().map(Resolved::to_dto))
}

#[tauri::command]
pub fn start_update_download(app: AppHandle, mirror: String) -> Result<(), String> {
    let release = {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            if inner.downloading {
                return Ok(None); // 按钮可能被连点，第二次只会把进度写乱
            }
            let Some(release) = inner.release.clone() else {
                return Err("请先检查更新".to_string());
            };
            if !release.can_install() {
                return Err("未获得可信校验值，请从 Release 页面下载".to_string());
            }
            inner.downloading = true;
            inner.download = DlState::Running {
                progress: -1.0,
                downloaded: 0,
                total: 0,
            };
            Ok(Some(release))
        })?
    };
    let Some(release) = release else {
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
    std::thread::spawn(move || run_download(handle, release, mirror));
    Ok(())
}

#[tauri::command]
pub fn get_update_download_state(app: AppHandle) -> DlState {
    app.state::<UpdateShared>()
        .with(|inner| inner.download.clone())
}

/// 拉起 NSIS 安装器，随后退出本进程让安装器接管文件替换。
#[tauri::command]
pub fn launch_update_installer(app: AppHandle) -> Result<(), String> {
    let path = {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| match &inner.download {
            DlState::Done { path } => Ok(PathBuf::from(path)),
            _ => Err("安装包尚未下载完成".to_string()),
        })?
    };
    if !path.is_file() {
        return Err("安装包已被删除，请重新下载".to_string());
    }
    std::process::Command::new(&path)
        .spawn()
        .map_err(|e| format!("拉起安装器失败：{e}"))?;

    let handle = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(900));
        handle.exit(0);
    });
    Ok(())
}

#[tauri::command]
pub fn open_url(url: String) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err("只允许打开 https 链接".to_string());
    }
    // rundll32 直接吃 URL，不经过 shell 解析
    std::process::Command::new("rundll32.exe")
        .args(["url.dll,FileProtocolHandler", &url])
        .spawn()
        .map_err(|e| format!("打开浏览器失败：{e}"))?;
    Ok(())
}

// ── 实现 ────────────────────────────────────────────────────────────────

fn build_client(timeout: Duration) -> Result<Client, String> {
    Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(Duration::from_secs(15))
        .timeout(timeout)
        .build()
        .map_err(|e| format!("网络客户端初始化失败：{e}"))
}

fn resolve(mirror: Mirror, current_version: &str) -> Result<Option<Resolved>, String> {
    let client = build_client(API_TIMEOUT)?;
    let path = format!("/repos/{OWNER}/{REPO}/releases?per_page=100");
    let text = first_ok(&client, &mirror.api_chain(&path), "检查更新", true)?;
    let releases: Vec<GhRelease> =
        serde_json::from_str(&text).map_err(|e| format!("解析发布列表失败：{e}"))?;

    let Some(release) = releases
        .into_iter()
        .find(|r| !r.draft && !r.prerelease && r.tag_name.starts_with(RELEASE_PREFIX))
    else {
        // 仓库里还没发过桌面端版本：不是错误，只是没有更新可给
        return Ok(None);
    };
    if !has_update(release_version(&release.tag_name), current_version) {
        return Ok(None);
    }

    let latest_version = release_version(&release.tag_name).to_string();
    let picked = pick_asset(&release.assets);
    let asset = picked.map(|a| AssetRef {
        name: a.name.clone(),
        url: a.browser_download_url.clone(),
        size: a.size,
    });
    // 优先用 GitHub 的 digest；缺失时读发布流程产出的 `.sha256` 边车，
    // 两个都拿不到就只给 Release 页入口（can_install = false）
    let expected = match picked.and_then(|a| a.digest.as_deref()).and_then(parse_digest) {
        Some(hex) => Some(hex),
        None => match &asset {
            Some(a) => sidecar_sha256(&client, mirror, &release.assets, &a.name).unwrap_or(None),
            None => None,
        },
    };
    let page_url = release
        .html_url
        .clone()
        .filter(|url| !url.is_empty())
        .unwrap_or_else(|| {
            format!(
                "https://github.com/{OWNER}/{REPO}/releases/tag/{}",
                release.tag_name
            )
        });

    Ok(Some(Resolved {
        current_version: current_version.to_string(),
        latest_version,
        release_name: release
            .name
            .clone()
            .filter(|name| !name.is_empty())
            .unwrap_or_else(|| release.tag_name.clone()),
        notes: release.body.clone().unwrap_or_default(),
        published_at: release.published_at.clone(),
        page_url,
        asset,
        expected_sha256: expected,
    }))
}

/// NSIS 安装包；多产物时优先 x64。
fn pick_asset(assets: &[GhAsset]) -> Option<&GhAsset> {
    let setups: Vec<&GhAsset> = assets
        .iter()
        .filter(|a| a.name.to_ascii_lowercase().ends_with("-setup.exe"))
        .collect();
    if setups.len() == 1 {
        return Some(setups[0]);
    }
    setups
        .iter()
        .find(|a| a.name.to_ascii_lowercase().contains("x64"))
        .copied()
        .or_else(|| setups.first().copied())
}

/// `sha256:<hex>` → 纯小写十六进制。
fn parse_digest(digest: &str) -> Option<String> {
    digest
        .split(':')
        .nth(1)
        .map(|hex| hex.trim().to_ascii_lowercase())
        .filter(|hex| hex.len() >= 32)
}

/// 读取 `<安装包>.sha256` 边车，取第一个空白前的十六进制串。
fn sidecar_sha256(
    client: &Client,
    mirror: Mirror,
    assets: &[GhAsset],
    asset_name: &str,
) -> Result<Option<String>, String> {
    let wanted = format!("{asset_name}.sha256");
    let sidecar = assets
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case(&wanted))
        .or_else(|| {
            assets
                .iter()
                .find(|a| a.name.to_ascii_lowercase().ends_with(".sha256"))
        });
    let Some(sidecar) = sidecar else {
        return Ok(None);
    };
    let text = first_ok(
        client,
        &mirror.asset_chain(&sidecar.browser_download_url),
        "读取校验值",
        false,
    )?;
    Ok(text
        .split_whitespace()
        .next()
        .filter(|token| token.len() >= 32)
        .map(|token| token.to_ascii_lowercase()))
}

struct FetchError {
    code: Option<u16>,
    message: String,
}

fn get_text(client: &Client, url: &str, api: bool) -> Result<String, FetchError> {
    let accept = if api {
        "application/vnd.github+json"
    } else {
        "application/octet-stream"
    };
    let mut request = client.get(url).header(reqwest::header::ACCEPT, accept);
    if api {
        request = request.header("X-GitHub-Api-Version", "2022-11-28");
    }
    let response = request.send().map_err(|e| FetchError {
        code: None,
        message: e.to_string(),
    })?;
    let status = response.status();
    if !status.is_success() {
        let hint = if status.as_u16() == 403 {
            "（可能被限流或拒绝该出口 IP）"
        } else {
            ""
        };
        return Err(FetchError {
            code: Some(status.as_u16()),
            message: format!("HTTP {}{hint}", status.as_u16()),
        });
    }
    response.text().map_err(|e| FetchError {
        code: None,
        message: e.to_string(),
    })
}

/// 依次尝试候选地址，第一个成功即用。
/// 全失败时把最后一个错误抛回去并说明试过几个源——只报 403 用户不知道该干什么。
fn first_ok(client: &Client, urls: &[String], what: &str, api: bool) -> Result<String, String> {
    let mut last: Option<FetchError> = None;
    for url in urls {
        match get_text(client, url, api) {
            Ok(text) => return Ok(text),
            // 404 是「这个文件本来就没有」（比如发布没带 .sha256 边车），
            // 换一家只会再 404 一次，直接停手让调用方按缺失处理
            Err(e) if e.code == Some(404) => {
                return Err(format!("{what}失败：{}", e.message));
            }
            Err(e) => last = Some(e),
        }
    }
    let tail = if urls.len() > 1 {
        format!("（已依次尝试 {} 个源）", urls.len())
    } else {
        String::new()
    };
    Err(format!(
        "{what}失败：{}{tail}",
        last.map(|e| e.message)
            .unwrap_or_else(|| "无可用地址".to_string())
    ))
}

fn run_download(app: AppHandle, release: Resolved, mirror: Mirror) {
    let state = match download_release(&release, mirror, &|done, total| {
        publish_progress(&app, done, total);
    }) {
        Ok(path) => DlState::Done {
            path: path.display().to_string(),
        },
        Err(message) => DlState::Failed { message },
    };
    {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            inner.downloading = false;
            inner.download = state.clone();
        });
    }
    let _ = app.emit_to(MAIN, EVENT, state);
}

fn download_release(
    release: &Resolved,
    mirror: Mirror,
    on_progress: &dyn Fn(u64, u64),
) -> Result<PathBuf, String> {
    let asset = release
        .asset
        .as_ref()
        .ok_or_else(|| "该版本没有可安装的产物".to_string())?;
    let expected = release
        .expected_sha256
        .as_deref()
        .ok_or_else(|| "未获得可信校验值，请从 Release 页面下载".to_string())?;

    let dir = std::env::temp_dir().join("market-monitor-updates");
    std::fs::create_dir_all(&dir).map_err(|e| format!("创建下载目录失败：{e}"))?;
    let target = dir.join(safe_file_name(&asset.name));

    let client = build_client(IO_TIMEOUT)?;
    let urls = mirror.asset_chain(&asset.url);
    let mut last: Option<String> = None;
    for url in &urls {
        match download_once(&client, url, &target, expected, on_progress) {
            Ok(()) => return Ok(target),
            Err(e) => {
                last = Some(e);
                // 换下一个源：半截文件不能留给安装器
                let _ = std::fs::remove_file(&target);
            }
        }
    }
    Err(format!(
        "下载失败：{}（已依次尝试 {} 个源）",
        last.unwrap_or_else(|| "无可用地址".to_string()),
        urls.len()
    ))
}

/// 流式下载并边写边算 SHA-256。校验不通过即删文件——半包或被替换的包都不能交付。
fn download_once(
    client: &Client,
    url: &str,
    target: &Path,
    expected: &str,
    on_progress: &dyn Fn(u64, u64),
) -> Result<(), String> {
    let mut response = client
        .get(url)
        .header(reqwest::header::ACCEPT, "application/octet-stream")
        .send()
        .map_err(|e| e.to_string())?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!("HTTP {}", status.as_u16()));
    }

    let total = response.content_length().unwrap_or(0);
    let mut file = std::fs::File::create(target).map_err(|e| format!("写入下载文件失败：{e}"))?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    let mut downloaded: u64 = 0;
    let mut last_emit = Instant::now();

    loop {
        let read = response
            .read(&mut buffer)
            .map_err(|e| format!("读取下载数据失败：{e}"))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
        file.write_all(&buffer[..read])
            .map_err(|e| format!("写入下载文件失败：{e}"))?;
        downloaded += read as u64;
        if last_emit.elapsed() >= PROGRESS_INTERVAL {
            last_emit = Instant::now();
            on_progress(downloaded, total);
        }
    }
    file.flush().map_err(|e| format!("写入下载文件失败：{e}"))?;
    drop(file);

    let actual = format!("{:x}", hasher.finalize());
    if !actual.eq_ignore_ascii_case(expected) {
        return Err("安装包校验失败，请换个加速站重试".to_string());
    }
    Ok(())
}

fn publish_progress(app: &AppHandle, downloaded: u64, total: u64) {
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
    {
        let shared = app.state::<UpdateShared>();
        shared.with(|inner| {
            if inner.downloading {
                inner.download = state.clone();
            }
        });
    }
    let _ = app.emit_to(MAIN, EVENT, state);
}

/// 只取最后一段文件名，避免资产名里带路径分隔符写到别处。
fn safe_file_name(name: &str) -> String {
    Path::new(name)
        .file_name()
        .and_then(|part| part.to_str())
        .filter(|part| !part.is_empty())
        .map(|part| part.to_string())
        .unwrap_or_else(|| "market-monitor-setup.exe".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn asset(name: &str, url: &str, size: i64, digest: Option<&str>) -> GhAsset {
        GhAsset {
            name: name.to_string(),
            browser_download_url: url.to_string(),
            size,
            digest: digest.map(|d| d.to_string()),
        }
    }

    #[test]
    fn semver_compare_follows_semantics() {
        // 桌面端 tag 带 `desktop-v` 前缀，必须先剥掉再比（直接拿 tag 比会永远判「已是最新」）
        assert_eq!(release_version("desktop-v0.9.25"), "0.9.25");
        assert!(has_update(release_version("desktop-v0.9.25"), "0.1.0"));
        assert!(!has_update(release_version("desktop-v0.1.0"), "0.1.0"));
        assert!(has_update("v1.2.3", "1.2.2"));
        assert!(!has_update("v1.2.3", "1.2.3"));
        assert!(!has_update("1.2", "1.2.0")); // 缺段按 0 补
        assert!(has_update("1.10.0", "1.9.9")); // 数字比较，不是字符串
        assert!(!has_update("1.0.0-beta.1", "1.0.0"));
        assert!(has_update("1.0.0", "1.0.0-beta.1"));
        assert!(has_update("1.0.0-alpha.1", "1.0.0-alpha"));
        assert!(!has_update("1.0.0+build5", "1.0.0")); // 忽略构建元数据
        assert!(!has_update("1.0.0-beta.2", "1.0.0-beta.11")); // 数字标识符按数值比
        assert!(!has_update("不是版本号", "1.0.0"));
        assert!(!has_update("1.0.0", "不是版本号"));
    }

    #[test]
    fn api_chain_starts_from_selection_and_stays_deduped() {
        let native = Mirror::Native.api_chain("/repos");
        assert_eq!(native[0], "https://api.github.com/repos");
        assert_eq!(native.len(), 3); // 直连 + 两个代 API 的站

        let proxied = Mirror::GhProxyCom.api_chain("/repos");
        assert_eq!(proxied[0], "https://gh-proxy.com/https://api.github.com/repos");
        assert_eq!(proxied[1], "https://api.github.com/repos"); // 直连第二
        assert_eq!(proxied.len(), 3); // 所选站已计入，另一个 API 站补位
    }

    #[test]
    fn asset_chain_puts_direct_second_for_proxied_mirrors() {
        let url = "https://github.com/waxilo/market-monitor/releases/download/x/a.exe";
        let assets = Mirror::GhfastTop.asset_chain(url);
        assert_eq!(assets[0], format!("https://ghfast.top/{url}"));
        assert_eq!(assets[1], url); // 非原生时直连排第二
        assert_eq!(assets.len(), 5); // 直连 + 四个加速站
        assert_eq!(Mirror::Native.asset_chain(url).len(), 5); // 直连 + 四站
    }

    #[test]
    fn mirror_keys_tolerate_legacy_prefix_values() {
        assert!(matches!(Mirror::from_key("gh-proxy.com"), Mirror::GhProxyCom));
        assert!(matches!(Mirror::from_key("https://ghfast.top/"), Mirror::GhfastTop));
        assert!(matches!(Mirror::from_key(""), Mirror::Native));
        assert!(matches!(Mirror::from_key("https://example.com/"), Mirror::Native));
    }

    #[test]
    fn asset_pick_prefers_x64_setup_and_digest_parses() {
        let assets = vec![
            asset("market-monitor_0.1.0_x64_en-US.msi", "u1", 1, None),
            asset("market-monitor_0.1.0_x64-setup.exe", "u2", 2, Some("sha256:ABC")),
        ];
        assert_eq!(pick_asset(&assets).unwrap().name, "market-monitor_0.1.0_x64-setup.exe");
        assert_eq!(parse_digest("sha256:ABC"), None); // 太短不当校验值
        assert_eq!(parse_digest("sha256:"), None);
        assert_eq!(parse_digest("md5:abc"), None); // 只认 sha256
        let hex = "a".repeat(64);
        assert_eq!(parse_digest(&format!("sha256:{hex}")).as_deref(), Some(hex.as_str()));

        let no_setup = vec![asset("market-monitor_0.1.0_x64_en-US.msi", "u1", 1, None)];
        assert!(pick_asset(&no_setup).is_none());
    }

    #[test]
    fn safe_file_name_keeps_last_segment_only() {
        assert_eq!(safe_file_name("a/b/setup.exe"), "setup.exe");
        assert_eq!(safe_file_name("setup.exe"), "setup.exe");
        assert_eq!(safe_file_name(""), "market-monitor-setup.exe");
    }
}
