//! One shared HTTP client. The header set matters: a bare request to Facebook
//! returns an HTTP 400 "Sorry, something went wrong" page rather than the feed,
//! so every request carries a full desktop-Chrome fingerprint.

use crate::model::MagpieError;
use once_cell::sync::Lazy;
use std::time::Duration;

pub const UA: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 \
                      (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

static CLIENT: Lazy<reqwest::blocking::Client> = Lazy::new(|| {
    reqwest::blocking::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(60))
        .redirect(reqwest::redirect::Policy::limited(10))
        .build()
        .expect("http client")
});

fn net<E: std::fmt::Display>(e: E) -> MagpieError {
    MagpieError::Network { msg: e.to_string() }
}

/// GET a page as a signed-in desktop browser would.
pub fn get_page(url: &str, cookie: &str) -> Result<String, MagpieError> {
    let mut req = CLIENT
        .get(url)
        .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("accept-language", "en-US,en;q=0.9")
        .header("sec-ch-ua", "\"Chromium\";v=\"141\", \"Not?A_Brand\";v=\"24\"")
        .header("sec-ch-ua-mobile", "?0")
        .header("sec-ch-ua-platform", "\"Linux\"")
        .header("sec-fetch-dest", "document")
        .header("sec-fetch-mode", "navigate")
        .header("sec-fetch-site", "none")
        .header("sec-fetch-user", "?1")
        .header("upgrade-insecure-requests", "1");
    if !cookie.is_empty() {
        req = req.header("cookie", cookie);
    }
    let resp = req.send().map_err(net)?;
    let status = resp.status();
    let body = resp.text().map_err(net)?;
    if !status.is_success() {
        return Err(MagpieError::Network {
            msg: format!("HTTP {status}"),
        });
    }
    Ok(body)
}

/// Exact byte size without pulling the body.
///
/// Tries HEAD first, then a one-byte ranged GET: Google Drive's download
/// endpoint answers HEAD with no usable Content-Length, but does report the
/// full size in `Content-Range` for a range request.
pub fn content_length(url: &str, cookie: &str) -> Option<u64> {
    if let Some(n) = head_length(url, cookie) {
        return Some(n);
    }
    range_length(url, cookie)
}

fn head_length(url: &str, cookie: &str) -> Option<u64> {
    let mut req = CLIENT.head(url).header("accept", "*/*");
    if !cookie.is_empty() {
        req = req.header("cookie", cookie);
    }
    let resp = req.send().ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let n: u64 = resp
        .headers()
        .get(reqwest::header::CONTENT_LENGTH)?
        .to_str()
        .ok()?
        .parse()
        .ok()?;
    (n > 0).then_some(n)
}

fn range_length(url: &str, cookie: &str) -> Option<u64> {
    let mut req = CLIENT
        .get(url)
        .header("accept", "*/*")
        .header("range", "bytes=0-0");
    if !cookie.is_empty() {
        req = req.header("cookie", cookie);
    }
    let resp = req.send().ok()?;
    // "bytes 0-0/123456789" -> 123456789
    let cr = resp
        .headers()
        .get(reqwest::header::CONTENT_RANGE)?
        .to_str()
        .ok()?;
    cr.rsplit('/').next()?.trim().parse().ok()
}
