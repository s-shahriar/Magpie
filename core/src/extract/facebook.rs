//! Facebook extractor.
//!
//! yt-dlp's `facebook` extractor cannot read current group pages (it reports
//! "No video formats found" on a permalink and "Cannot parse data" on
//! `/watch/?v=`), so Magpie reads the page itself.
//!
//! Facebook embeds every DASH rendition in the HTML as a `"base_url"` JSON
//! string. Each of those URLs carries an `efg` query parameter: base64 JSON
//! holding the authoritative `video_id`, `vencode_tag`, `bitrate` and
//! `duration_s`. That metadata — not the order things appear in — is what
//! identifies a rendition, because a group page routinely holds half a dozen
//! unrelated videos (sidebar reels) alongside the one that was asked for.

use crate::http;
use crate::model::{MagpieError, MediaInfo, Rendition};
use base64::{engine::general_purpose::STANDARD_NO_PAD, Engine};
use once_cell::sync::Lazy;
use regex::Regex;
use serde::Deserialize;

/// Matches a JSON string value, honouring backslash escapes so that the
/// capture stops at the real closing quote.
static BASE_URL: Lazy<Regex> =
    Lazy::new(|| Regex::new(r#""base_url":"((?:[^"\\]|\\.)*)""#).unwrap());
static EFG: Lazy<Regex> = Lazy::new(|| Regex::new(r"[?&]efg=([^&]+)").unwrap());
static OG_TITLE: Lazy<Regex> =
    Lazy::new(|| Regex::new(r#"<meta property="og:title" content="([^"]*)""#).unwrap());
static OG_IMAGE: Lazy<Regex> =
    Lazy::new(|| Regex::new(r#"<meta property="og:image" content="([^"]*)""#).unwrap());

#[derive(Deserialize)]
struct Efg {
    video_id: Option<serde_json::Value>,
    vencode_tag: Option<String>,
    bitrate: Option<u64>,
    duration_s: Option<f64>,
}

struct Found {
    pos: usize,
    video_id: String,
    tag: String,
    bitrate: u64,
    duration: u64,
    url: String,
}

pub fn matches(url: &str) -> bool {
    url.contains("facebook.com") || url.contains("fb.watch") || url.contains("fb.com")
}

pub fn probe(url: &str, cookie: &str) -> Result<MediaInfo, MagpieError> {
    let page = http::get_page(url, cookie)?;

    // A login wall is short and has no player payload; say so plainly rather
    // than reporting "no media".
    if page.len() < 50_000 && !page.contains("base_url") {
        return Err(MagpieError::AuthRequired {
            service: "Facebook".into(),
        });
    }

    let found = collect(&page);
    if found.is_empty() {
        return Err(MagpieError::NoMedia);
    }

    // The post's own video is the first one laid out in the document; the rest
    // are recommendations rendered after it.
    let target = found
        .iter()
        .min_by_key(|f| f.pos)
        .map(|f| f.video_id.clone())
        .unwrap();

    let mine: Vec<&Found> = found.iter().filter(|f| f.video_id == target).collect();
    let duration = mine.iter().map(|f| f.duration).max().unwrap_or(0);

    let mut info = MediaInfo {
        source: "facebook".into(),
        media_id: target.clone(),
        title: OG_TITLE
            .captures(&page)
            .map(|c| unescape_html(&c[1]))
            .filter(|t| !t.is_empty())
            .unwrap_or_else(|| format!("facebook-{target}")),
        duration_secs: duration,
        thumbnail: OG_IMAGE.captures(&page).map(|c| unescape_html(&c[1])),
        video: Vec::new(),
        audio: Vec::new(),
        muxed: false,
    };

    // One entry per vencode_tag: the same rendition is repeated many times
    // across the page (once per DASH segment template).
    let mut seen: Vec<String> = Vec::new();
    for f in mine {
        if seen.contains(&f.tag) {
            continue;
        }
        seen.push(f.tag.clone());
        let is_audio = f.tag.contains("audio");
        let r = Rendition {
            id: f.tag.clone(),
            label: if is_audio {
                "audio".into()
            } else {
                label_for(f.bitrate)
            },
            kind: if is_audio { "audio".into() } else { "video".into() },
            width: None,
            height: None,
            bitrate: f.bitrate,
            approx_bytes: None,
            exact_size: false,
            mime: None,
            codec: None,
            url: f.url.clone(),
        };
        if is_audio {
            info.audio.push(r);
        } else {
            info.video.push(r);
        }
    }

    if info.video.is_empty() {
        return Err(MagpieError::NoMedia);
    }
    info.estimate_sizes();
    Ok(info)
}

fn collect(page: &str) -> Vec<Found> {
    let mut out = Vec::new();
    for m in BASE_URL.captures_iter(page) {
        let whole = m.get(0).unwrap();
        let raw = &m[1];

        // Unescape as JSON, not by hand. Replacing only `\/` leaves `\uXXXX`
        // sequences intact, which corrupts the base64 in `efg` and silently
        // drops roughly two thirds of the renditions — including, in practice,
        // the smallest one, so "lowest quality" would resolve to the largest.
        let url: String = match serde_json::from_str(&format!("\"{raw}\"")) {
            Ok(u) => u,
            Err(_) => continue,
        };
        if !url.starts_with("http") {
            continue;
        }
        let Some(efg_raw) = EFG.captures(&url).map(|c| c[1].to_string()) else {
            continue;
        };
        let decoded = percent_encoding::percent_decode_str(&efg_raw)
            .decode_utf8_lossy()
            .to_string();
        let trimmed = decoded.trim_end_matches('=');
        let Ok(bytes) = STANDARD_NO_PAD.decode(trimmed) else {
            continue;
        };
        let Ok(efg) = serde_json::from_slice::<Efg>(&bytes) else {
            continue;
        };
        let (Some(vid), Some(tag)) = (efg.video_id, efg.vencode_tag) else {
            continue;
        };
        out.push(Found {
            pos: whole.start(),
            video_id: match vid {
                serde_json::Value::Number(n) => n.to_string(),
                serde_json::Value::String(s) => s,
                _ => continue,
            },
            tag,
            bitrate: efg.bitrate.unwrap_or(0),
            duration: efg.duration_s.unwrap_or(0.0) as u64,
            url,
        });
    }
    out
}

/// Facebook does not give pixel dimensions in `efg`, so the quality label is
/// derived from bitrate. Rough, but it orders the list correctly and the exact
/// size is shown beside it.
fn label_for(bitrate: u64) -> String {
    match bitrate {
        0 => "video".into(),
        b if b < 300_000 => "low".into(),
        b if b < 700_000 => "SD".into(),
        b if b < 1_600_000 => "HD".into(),
        _ => "Full HD".into(),
    }
}

fn unescape_html(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
