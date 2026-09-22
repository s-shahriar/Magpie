//! Google Drive extractor.
//!
//! Drive marks course material "view only" (download disabled), but the player
//! still has to be served something playable. Two endpoints cover it:
//!
//! * `get_video_info?docid=` — a urlencoded blob carrying `fmt_stream_map`
//!   (`itag|url,…`) and `fmt_list` (`itag/WxH/…`), i.e. the transcoded ladder
//!   the web player itself chooses from. These are progressive MP4s with audio
//!   already muxed in.
//! * `drive.usercontent.google.com/download?...&confirm=t` — the original
//!   uploaded file, which is larger and better than any transcode.

use crate::http;
use crate::model::{MagpieError, MediaInfo, Rendition};
use once_cell::sync::Lazy;
use regex::Regex;
use std::collections::HashMap;

static FILE_ID: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?:/file/d/|[?&]id=|/open\?id=|/uc\?id=)([A-Za-z0-9_-]{20,})").unwrap()
});
static FMT: Lazy<Regex> = Lazy::new(|| Regex::new(r"^(\d+)/(\d+)[xX](\d+)").unwrap());

pub fn matches(url: &str) -> bool {
    url.contains("drive.google.com") || url.contains("drive.usercontent.google.com")
}

pub fn file_id(url: &str) -> Option<String> {
    FILE_ID.captures(url).map(|c| c[1].to_string())
}

pub fn probe(url: &str, cookie: &str) -> Result<MediaInfo, MagpieError> {
    let id = file_id(url).ok_or_else(|| MagpieError::Unsupported { url: url.into() })?;

    let body = http::get_page(
        &format!("https://drive.google.com/get_video_info?docid={id}"),
        cookie,
    )?;
    let kv = parse_query(&body);

    if kv.get("reason").is_some() && kv.get("fmt_stream_map").is_none() {
        // Drive puts the human-readable refusal in `reason`.
        let reason = kv.get("reason").cloned().unwrap_or_default();
        if reason.to_lowercase().contains("sign in") || reason.contains("permission") {
            return Err(MagpieError::AuthRequired {
                service: "Google Drive".into(),
            });
        }
        return Err(MagpieError::Parse { msg: reason });
    }

    let title = kv
        .get("title")
        .cloned()
        .filter(|t| !t.is_empty())
        .unwrap_or_else(|| format!("drive-{id}"));
    let duration = kv
        .get("length_seconds")
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);

    // itag -> (w, h)
    let mut res: HashMap<&str, (u32, u32)> = HashMap::new();
    let fmt_list = kv.get("fmt_list").cloned().unwrap_or_default();
    for part in fmt_list.split(',') {
        if let Some(c) = FMT.captures(part) {
            let (Ok(w), Ok(h)) = (c[2].parse(), c[3].parse()) else {
                continue;
            };
            res.insert(
                Box::leak(c[1].to_string().into_boxed_str()) as &str,
                (w, h),
            );
        }
    }

    let mut video = Vec::new();
    let stream_map = kv.get("fmt_stream_map").cloned().unwrap_or_default();
    for entry in stream_map.split(',') {
        let mut it = entry.splitn(2, '|');
        let (Some(itag), Some(surl)) = (it.next(), it.next()) else {
            continue;
        };
        if surl.is_empty() {
            continue;
        }
        let dims = res.get(itag).copied();
        video.push(Rendition {
            id: format!("itag-{itag}"),
            label: dims
                .map(|(_, h)| format!("{h}p"))
                .unwrap_or_else(|| format!("itag {itag}")),
            kind: "muxed".into(),
            width: dims.map(|d| d.0),
            height: dims.map(|d| d.1),
            bitrate: 0,
            approx_bytes: None,
            exact_size: false,
            mime: Some("video/mp4".into()),
            codec: None,
            url: lowercase_unescape(surl),
        });
    }

    // The original upload. Worth listing even when transcodes exist: for the
    // class recordings this is the only full-resolution copy.
    let source = format!(
        "https://drive.usercontent.google.com/download?id={id}&export=download&confirm=t"
    );
    // Only offered when it can actually be sized: on a view-only file this
    // endpoint serves a refusal page, and listing it would hand the user a
    // download that 403s. (yt-dlp hits the same wall and falls back to itag 37.)
    if let Some(len) = http::content_length(&source, cookie) {
        video.push(Rendition {
            id: "source".into(),
            label: "Original".into(),
            kind: "muxed".into(),
            width: None,
            height: None,
            bitrate: 0,
            approx_bytes: Some(len),
            exact_size: true,
            mime: Some("video/mp4".into()),
            codec: None,
            url: source,
        });
    }

    // Drive gives no bitrate, so the only way to show a real size preview is to
    // ask each rendition. One cheap ranged request each.
    for r in video.iter_mut() {
        if r.approx_bytes.is_none() {
            if let Some(n) = http::content_length(&r.url, cookie) {
                r.approx_bytes = Some(n);
                r.exact_size = true;
            }
        }
    }

    if video.is_empty() {
        return Err(MagpieError::NoMedia);
    }

    let mut info = MediaInfo {
        source: "gdrive".into(),
        media_id: id.clone(),
        title,
        duration_secs: duration,
        thumbnail: Some(format!("https://drive.google.com/thumbnail?id={id}")),
        video,
        audio: Vec::new(),
        muxed: true,
    };
    info.estimate_sizes();
    Ok(info)
}

fn parse_query(body: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for pair in body.split('&') {
        let mut it = pair.splitn(2, '=');
        let (Some(k), Some(v)) = (it.next(), it.next()) else {
            continue;
        };
        let key = percent_encoding::percent_decode_str(&k.replace('+', " "))
            .decode_utf8_lossy()
            .to_string();
        let val = percent_encoding::percent_decode_str(&v.replace('+', " "))
            .decode_utf8_lossy()
            .to_string();
        out.entry(key).or_insert(val);
    }
    out
}

/// Drive escapes URLs with lowercase `&`-style sequences.
fn lowercase_unescape(s: &str) -> String {
    let unified = s.replace("\\u0026", "&").replace("\\/", "/");
    serde_json::from_str::<String>(&format!("\"{}\"", unified.replace('"', "\\\"")))
        .unwrap_or(unified)
}
