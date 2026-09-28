//! 行情取数的宿主侧 HTTP 通道。
//!
//! 桌面端最初只取 Gate，是因为只有它能在 webview 里直接 `fetch`（响应带
//! `Access-Control-Allow-Origin`）。多数据源之后这条不成立：实测 Aster、币安合约、
//! OKX、Bybit、Bitget、MEXC 的响应里都没有放行头，webview 的 fetch 一概读不到 ——
//! 取数统一改走宿主侧 reqwest（与更新下载同一条 TLS 栈，`system-proxy` 跟随系统代理，
//! 行为与浏览器一致），前端只拿回状态码与响应体。
//!
//! 这里刻意只做「发请求」：各家的 URL 构造与响应归一化在前端的 lib/dialects.ts
//! （对应 App 的 data/remote/dialect），Rust 侧不感知任何盘口协议。

use std::sync::OnceLock;
use std::time::Duration;

use serde::Serialize;

/// 与更新下载同一个 UA：个别盘口对空 UA 的请求直接 403。
const USER_AGENT: &str = "market-monitor-desktop";
/// 连接阶段 6s 就够下结论：探测盘口可不可达主要卡在这一步（被网络层丢包的域名
/// 会一直等下去），6s 的结论对用户比 30s 的「准确」有用。
const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
/// 单次请求总时限：K 线一次 300 根，慢网直连也得给够。
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Serialize)]
pub struct MarketResponse {
    status: u16,
    body: String,
}

fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            .build()
            .expect("构造行情 HTTP 客户端失败")
    })
}

/// 探测结果要在弹窗里逐行显示，网络栈原文（`error sending request for url …`）
/// 一行放不下也没法照做 —— 归纳成两三个字的结论。
fn short_error(err: &reqwest::Error) -> String {
    if err.is_timeout() {
        return "连接超时".to_string();
    }
    let text = err.to_string();
    let lower = text.to_ascii_lowercase();
    if lower.contains("dns") || lower.contains("lookup address") {
        return "域名解析失败".to_string();
    }
    if lower.contains("certificate") || lower.contains("tls") || lower.contains("ssl") {
        return "TLS 握手失败".to_string();
    }
    if err.is_connect() {
        return "连接失败".to_string();
    }
    text
}

/// 发一次行情请求，原样带回状态码与响应体。
///
/// 非 2xx 不在这里判错：「服务器答了」本身也是连通性证据，探测行要显示 `HTTP 403`
/// 这种具体结论；数据请求与非 2xx 的取舍由前端按用途决定。
#[tauri::command]
pub async fn market_request(
    method: String,
    url: String,
    body: Option<String>,
) -> Result<MarketResponse, String> {
    // 数据源清单是内置的，正常到不了这里；挡一下被注入的明文请求
    if !url.starts_with("https://") {
        return Err("只允许 https 请求".to_string());
    }
    let request = if method.eq_ignore_ascii_case("POST") {
        client()
            .post(&url)
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(body.unwrap_or_default())
    } else {
        client().get(&url)
    };
    let response = request.send().await.map_err(|e| short_error(&e))?;
    let status = response.status().as_u16();
    let body = response.text().await.map_err(|e| short_error(&e))?;
    Ok(MarketResponse { status, body })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 非 https 直接拒绝，不发请求（挡注入的兜底，不依赖网络）。
    #[test]
    fn rejects_plaintext_urls() {
        let err = tauri::async_runtime::block_on(market_request(
            "GET".to_string(),
            "http://api.gateio.ws/api/v4/spot/tickers".to_string(),
            None,
        ))
        .unwrap_err();
        assert!(err.contains("https"), "{err}");
    }
}
