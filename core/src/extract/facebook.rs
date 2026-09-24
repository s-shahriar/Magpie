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
use crate::model::{MagpieError, MediaInfo, Rendition, StreamFacts};
use base64::{engine::general_purpose::STANDARD_NO_PAD, Engine};
use once_cell::sync::Lazy;
use regex::Regex;
use serde::Deserialize;

/// Matches a JSON string value, honouring backslash escapes so that the
/// capture stops at the real closing quote.
static BASE_URL: Lazy<Regex> =
    Lazy::new(|| Regex::new(r#""base_url":"((?:[^"\\]|\\.)*)""#).unwrap());
static EFG: Lazy<Regex> = Lazy::new(|| Regex::new(r"[?&]efg=([^&]+)").unwrap());
/// The progressive MP4s Facebook still ships beside the DASH ladder.
///
/// These are single files with the audio already in them — H.264/AAC, the pair
/// every Android muxer accepts. They matter because a growing number of videos
/// are published with a VP9-only ladder, and VP9 cannot be written into an MP4
/// next to AAC: without this fallback those videos cannot be saved at all.
static PROGRESSIVE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(
        r#""(browser_native_hd_url|browser_native_sd_url|playable_url_quality_hd|playable_url|hd_src_no_ratelimit|sd_src_no_ratelimit|hd_src|sd_src)":"((?:[^"\\]|\\.)*)""#,
    )
    .unwrap()
});
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

/// Reads the metadata Facebook bakes into a stream URL's `efg` parameter.
///
/// The page no longer ships a DASH manifest — video data now arrives through a
/// runtime GraphQL fetch — so URLs are captured from the player's own network
/// traffic instead. Each one still carries `efg`, which identifies the video,
/// the rendition, its bitrate and duration, and that is enough to present a
/// quality list without parsing any page at all.
pub fn describe(url: &str) -> Option<StreamFacts> {
    let efg_raw = EFG.captures(url)?.get(1)?.as_str().to_string();
    let decoded = percent_encoding::percent_decode_str(&efg_raw)
        .decode_utf8_lossy()
        .to_string();
    let bytes = STANDARD_NO_PAD.decode(decoded.trim_end_matches('=')).ok()?;
    let efg: Efg = serde_json::from_slice(&bytes).ok()?;
    let tag = efg.vencode_tag?;
    let video_id = match efg.video_id? {
        serde_json::Value::Number(n) => n.to_string(),
        serde_json::Value::String(s) => s,
        _ => return None,
    };
    let bitrate = efg.bitrate.unwrap_or(0);
    let codec = codec_for(&tag);
    Some(StreamFacts {
        video_id,
        is_audio: tag.contains("audio"),
        label: if tag.contains("audio") { "audio".into() } else { label_for(bitrate) },
        tag,
        bitrate,
        duration_secs: efg.duration_s.unwrap_or(0.0) as u64,
        codec,
    })
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
        // A page whose video the caller may watch always embeds at least one
        // DASH `base_url`. None at all means Facebook served a wall instead,
        // which is a sign-in problem rather than an empty page — and saying so
        // gets the user to the fix instead of a dead end.
        return Err(MagpieError::AuthRequired {
            service: "Facebook".into(),
        });
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
            codec: codec_for(&f.tag),
            url: f.url.clone(),
        };
        if is_audio {
            info.audio.push(r);
        } else {
            info.video.push(r);
        }
    }

    info.video.extend(progressive(&page, cookie));

    if info.video.is_empty() {
        return Err(MagpieError::NoMedia);
    }
    info.estimate_sizes();
    Ok(info)
}

/// Reads the progressive MP4s out of the page.
///
/// Position is the only thing that ties a URL to a video here — unlike a DASH
/// `base_url`, these carry no `efg` — so only the first occurrence of each key
/// is taken. That is the same assumption the DASH path already rests on: the
/// post's own video is laid out before the sidebar reels.
///
/// The size comes from the server rather than from a bitrate estimate, because
/// there is no bitrate to estimate from. A URL that will not answer a size
/// probe is still offered: unlike Drive's view-only `source` download, nothing
/// here is known to be blocked, and on a VP9-only video this row is the only
/// way to get the file at all. It sorts last, as every unsized row does.
fn progressive(page: &str, cookie: &str) -> Vec<Rendition> {
    let mut out: Vec<Rendition> = Vec::new();
    let mut seen_keys: Vec<String> = Vec::new();

    for m in PROGRESSIVE.captures_iter(page) {
        let key = m[1].to_string();
        if seen_keys.contains(&key) {
            continue;
        }
        seen_keys.push(key.clone());

        let Ok(url) = serde_json::from_str::<String>(&format!("\"{}\"", &m[2])) else {
            continue;
        };
        if !url.starts_with("http") {
            continue;
        }
        // The same file is published under several names; one row each.
        if out.iter().any(|r: &Rendition| r.url == url) {
            continue;
        }
        let hd = key.contains("hd");
        let bytes = http::content_length(&url, cookie);
        out.push(Rendition {
            id: format!("progressive-{}", if hd { "hd" } else { "sd" }),
            label: if hd { "HD" } else { "SD" }.into(),
            // "muxed": audio is already in the file, so nothing is merged and
            // the muxer never runs.
            kind: "muxed".into(),
            width: None,
            height: None,
            bitrate: 0,
            approx_bytes: bytes,
            exact_size: bytes.is_some(),
            mime: Some("video/mp4".into()),
            codec: Some("h264".into()),
            url,
        });
    }
    out
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
/// Names the codec from the `vencode_tag`.
///
/// Facebook does not publish a codec field anywhere on the page, but it names
/// the ladder in the tag it encodes each rendition under — `dash_vp9-basic…`,
/// `dash_h264-basic…`. That is the only signal short of downloading the stream
/// and reading its `stsd` box, and the caller needs it *before* it spends a
/// gigabyte: Android's MediaMuxer cannot write VP9 or AV1 into MP4, so a
/// rendition from those ladders can be fetched but never merged with its audio.
///
/// Unknown stays `None` rather than guessing. A caller that filters on this
/// must treat `None` as allowed, or a tag Facebook renames tomorrow takes every
/// rendition down with it.
fn codec_for(tag: &str) -> Option<String> {
    let t = tag.to_ascii_lowercase();
    // Ordered: "av01" and "vp9" are distinctive, "h264"/"avc" is the fallback
    // family, and the audio tags are checked last because a video tag never
    // carries them.
    for (needle, codec) in [
        ("av01", "av1"),
        ("av1", "av1"),
        ("vp09", "vp9"),
        ("vp9", "vp9"),
        ("vp8", "vp8"),
        ("hevc", "hevc"),
        ("h265", "hevc"),
        ("h264", "h264"),
        ("avc", "h264"),
        ("opus", "opus"),
        ("aac", "aac"),
    ] {
        if t.contains(needle) {
            return Some(codec.into());
        }
    }
    None
}

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

#[cfg(test)]
mod tests {
    #[test]
    fn progressive_reads_escaped_urls() {
        let page = r#"{"id":1,"browser_native_hd_url":"https:\/\/nowhere.invalid\/a.mp4?x=1","playable_url":"https:\/\/nowhere.invalid\/b.mp4"}"#;
        let found = super::progressive(page, "");
        assert_eq!(found.len(), 2, "both keys should be read: {found:?}");
        assert_eq!(found[0].url, "https://nowhere.invalid/a.mp4?x=1");
        assert_eq!(found[0].kind, "muxed");
        assert_eq!(found[0].codec.as_deref(), Some("h264"));
    }
}
