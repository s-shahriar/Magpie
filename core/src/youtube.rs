//! YouTube, read through yt-dlp.
//!
//! Unlike Facebook and Drive, YouTube is not parsed here from the page. Its
//! player now gates every stream behind a JavaScript challenge, rotates which
//! clients get direct URLs, and pushes the rest onto SABR — and yt-dlp ships a
//! fix for each of those within days. Re-implementing that race in Rust would
//! lose it, so the front ends run yt-dlp (`-J`) and hand its JSON here.
//!
//! What stays in Rust is what the other sources get too: turning the format
//! list into renditions under the house rules, choosing a pair for a quality
//! preset, and reading captions. The Android app and the CLI share all of it.

use crate::model::{Chapter, MagpieError, MediaInfo, Rendition, SubTrack};
use once_cell::sync::Lazy;
use regex::Regex;
use serde_json::Value;

pub const SOURCE: &str = "youtube";

/// Which YouTube player clients yt-dlp asks, in order.
///
/// yt-dlp's default set is refused outright ("Sign in to confirm you're not
/// a bot") on an IP YouTube has flagged — a home connection included — while
/// the embedded-player client still gets the whole ladder, up to 2160p and
/// every dubbed track. `mweb` covers what embedding is disabled for, and the
/// defaults stay last so nothing they would have found is lost.
pub const PLAYER_CLIENTS: &str = "web_embedded,mweb,default";

/// Arguments every front end adds to a yt-dlp read.
pub fn ytdlp_args() -> Vec<String> {
    vec!["--extractor-args".into(), format!("youtube:player_client={PLAYER_CLIENTS}")]
}

/// How many of a channel's uploads are listed, newest first.
pub const CHANNEL_LIMIT: u32 = 200;

static VIDEO_ID: Lazy<Regex> = Lazy::new(|| Regex::new(r"^[A-Za-z0-9_-]{11}$").unwrap());

/// What a YouTube link points at, so the UI knows whether to show a quality
/// picker, a playlist, or both.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct YtLink {
    /// A canonical watch URL when the link names a single video.
    pub video_url: Option<String>,
    /// A URL yt-dlp reads as a list: a playlist, a mix, or a channel's uploads.
    pub playlist_url: Option<String>,
    /// A channel's uploads run to thousands and are paged in one by one, so
    /// the front ends list only the newest of them.
    pub is_channel: bool,
}

/// One entry of a playlist, as yt-dlp's flat listing gives it — enough to
/// show and select, not yet resolved to streams.
#[derive(Debug, Clone, uniffi::Record)]
pub struct YtEntry {
    pub id: String,
    pub url: String,
    pub title: String,
    pub duration_secs: u64,
    pub thumbnail: String,
    /// 1-based position in the list.
    pub index: u32,
    /// False for private, deleted and members-only entries, which stay listed
    /// so the numbering matches YouTube's but cannot be selected.
    pub available: bool,
    /// Search results say whose video it is and how widely it was watched;
    /// a playlist's entries usually do not.
    #[uniffi(default = None)]
    pub channel: Option<String>,
    #[uniffi(default = None)]
    pub views: Option<u64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct YtPlaylist {
    pub id: String,
    pub title: String,
    pub uploader: Option<String>,
    pub entries: Vec<YtEntry>,
}

/// What the user asked for, in the terms a preset can be applied with.
#[derive(Debug, Clone, uniffi::Record)]
pub struct YtPrefs {
    /// "best", "smallest", "audio", or a height cap such as "720".
    pub quality: String,
    /// "compat" prefers H.264 + AAC, which every player opens; "small"
    /// prefers AV1/VP9 + Opus, which are a third smaller at the same height.
    pub codec: String,
    /// Preferred audio language ("en", "bn"…); None means the original track.
    pub audio_lang: Option<String>,
}

/// A video rendition and the audio to merge with it. `video` is None for an
/// audio-only download; `audio` is None when the video already carries sound.
#[derive(Debug, Clone, uniffi::Record)]
pub struct YtPick {
    pub video: Option<Rendition>,
    pub audio: Option<Rendition>,
}

/// Why yt-dlp gave up, sorted into what the UI can do about it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct YtFailure {
    /// "signin" (a session would help), "unavailable", "network", "outdated"
    /// (a newer yt-dlp may fix it), "live", or "other".
    pub kind: String,
    pub message: String,
}

pub fn matches(url: &str) -> bool {
    host_of(url).is_some_and(|h| {
        h == "youtu.be"
            || h == "youtube.com"
            || h.ends_with(".youtube.com")
            || h == "youtube-nocookie.com"
            || h.ends_with(".youtube-nocookie.com")
    })
}

fn host_of(url: &str) -> Option<String> {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let host = rest.split(['/', '?', '#']).next()?.split(':').next()?;
    (!host.is_empty()).then(|| host.to_ascii_lowercase())
}

fn query_param(url: &str, key: &str) -> Option<String> {
    let q = url.split_once('?')?.1.split('#').next()?;
    q.split('&').find_map(|kv| {
        let (k, v) = kv.split_once('=')?;
        (k == key && !v.is_empty()).then(|| {
            percent_encoding::percent_decode_str(v)
                .decode_utf8_lossy()
                .to_string()
        })
    })
}

/// Reads a link without touching the network.
pub fn link(url: &str) -> Option<YtLink> {
    if !matches(url) {
        return None;
    }
    let host = host_of(url)?;
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let path = rest[rest.find('/').unwrap_or(rest.len())..]
        .split(['?', '#'])
        .next()
        .unwrap_or("");
    let segs: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    let watch = |id: &str| format!("https://www.youtube.com/watch?v={id}");
    let list = query_param(url, "list");

    let id = if host == "youtu.be" {
        segs.first().map(|s| s.to_string())
    } else {
        match segs.first().copied() {
            Some("watch") => query_param(url, "v"),
            Some("shorts" | "live" | "embed" | "v" | "e") => segs.get(1).map(|s| s.to_string()),
            _ => None,
        }
    }
    .filter(|id| VIDEO_ID.is_match(id));

    if let Some(id) = id {
        // A video opened from inside a list: the video first, and the list
        // on offer beside it. Mixes ("RD…") have no /playlist page, so the
        // list is read through the watch URL, which yt-dlp also accepts.
        let playlist_url = list.map(|l| format!("{}&list={l}", watch(&id)));
        return Some(YtLink { video_url: Some(watch(&id)), playlist_url, is_channel: false });
    }
    if let Some(l) = list {
        return Some(YtLink {
            video_url: None,
            playlist_url: Some(format!("https://www.youtube.com/playlist?list={l}")),
            is_channel: false,
        });
    }
    // A channel. Its root lists the tabs (Videos, Shorts, Live) as nested
    // playlists, not videos, so it is pointed at the uploads tab instead.
    let channel = match segs.first().copied() {
        Some(s) if s.starts_with('@') => Some(1),
        Some("channel" | "c" | "user") if segs.len() >= 2 => Some(2),
        _ => None,
    };
    if let Some(n) = channel {
        let base = format!("https://www.youtube.com/{}", segs[..n].join("/"));
        let tab = segs.get(n).copied().unwrap_or("videos");
        return Some(YtLink {
            video_url: None,
            playlist_url: Some(format!("{base}/{tab}")),
            is_channel: true,
        });
    }
    None
}

// ---- formats -----------------------------------------------------------

fn s<'a>(v: &'a Value, k: &str) -> Option<&'a str> {
    v.get(k)?.as_str().filter(|s| !s.is_empty())
}

fn u(v: &Value, k: &str) -> Option<u64> {
    let x = v.get(k)?;
    x.as_u64().or_else(|| x.as_f64().filter(|f| *f > 0.0).map(|f| f as u64))
}

fn f(v: &Value, k: &str) -> Option<f64> {
    v.get(k)?.as_f64()
}

/// "avc1.640028" → "h264", "vp09.00.40.08" → "vp9", "mp4a.40.2" → "aac".
fn codec_name(raw: &str) -> Option<String> {
    let c = raw.to_ascii_lowercase();
    let name = if c == "none" {
        return None;
    } else if c.starts_with("avc") || c.starts_with("h264") {
        "h264"
    } else if c.starts_with("vp09") || c.starts_with("vp9") {
        "vp9"
    } else if c.starts_with("vp8") {
        "vp8"
    } else if c.starts_with("av01") || c.starts_with("av1") {
        "av1"
    } else if c.starts_with("hev") || c.starts_with("hvc") || c.starts_with("h265") {
        "h265"
    } else if c.starts_with("mp4a") || c.starts_with("aac") {
        "aac"
    } else if c.starts_with("opus") {
        "opus"
    } else if c.starts_with("vorbis") {
        "vorbis"
    } else if c.starts_with("flac") {
        "flac"
    } else {
        return Some(c);
    };
    Some(name.to_string())
}

/// How the codec reads in the picker.
pub fn codec_label(codec: &str) -> String {
    match codec {
        "h264" => "H.264".into(),
        "h265" => "H.265".into(),
        "vp9" => "VP9".into(),
        "vp8" => "VP8".into(),
        "av1" => "AV1".into(),
        "aac" => "AAC".into(),
        "opus" => "Opus".into(),
        "vorbis" => "Vorbis".into(),
        other => other.to_uppercase(),
    }
}

/// Turns `yt-dlp -J <video>` output into the same shape every source has.
pub fn media(json: &str) -> Result<MediaInfo, MagpieError> {
    let v: Value = serde_json::from_str(json).map_err(|e| MagpieError::Parse {
        msg: format!("yt-dlp output was not JSON: {e}"),
    })?;
    if s(&v, "_type") == Some("playlist") {
        return Err(MagpieError::Parse { msg: "that link is a playlist, not a video".into() });
    }
    let id = s(&v, "id").unwrap_or_default().to_string();
    match s(&v, "live_status") {
        Some("is_live") => {
            return Err(MagpieError::Parse {
                msg: "This is live right now — it can be saved once the stream ends.".into(),
            })
        }
        Some("is_upcoming") => {
            return Err(MagpieError::Parse { msg: "This premiere has not started yet.".into() })
        }
        _ => {}
    }

    let mut info = MediaInfo {
        source: SOURCE.into(),
        media_id: id.clone(),
        title: s(&v, "title").map(str::to_string).unwrap_or_else(|| format!("youtube-{id}")),
        duration_secs: u(&v, "duration").unwrap_or(0),
        thumbnail: (!id.is_empty())
            .then(|| format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg"))
            .or_else(|| s(&v, "thumbnail").map(str::to_string)),
        video: Vec::new(),
        audio: Vec::new(),
        muxed: false,
        uploader: s(&v, "channel").or_else(|| s(&v, "uploader")).map(str::to_string),
        subtitles: subtitles(&v),
        playlist_url: None,
        chapters: chapters(&v),
        upload_date: s(&v, "upload_date")
            .filter(|d| d.len() == 8 && d.bytes().all(|b| b.is_ascii_digit()))
            .map(|d| format!("{}-{}-{}", &d[..4], &d[4..6], &d[6..])),
    };

    let empty = Vec::new();
    let formats = v.get("formats").and_then(Value::as_array).unwrap_or(&empty);
    let mut seen: Vec<String> = Vec::new();
    for fmt in formats {
        let Some(r) = rendition(fmt) else { continue };
        // yt-dlp asks several player clients and can list an itag twice.
        if seen.contains(&r.id) {
            continue;
        }
        seen.push(r.id.clone());
        if r.kind == "audio" {
            info.audio.push(r);
        } else {
            info.video.push(r);
        }
    }

    if info.video.is_empty() && info.audio.is_empty() {
        return Err(MagpieError::NoMedia);
    }
    info.estimate_sizes();
    Ok(info)
}

fn rendition(fmt: &Value) -> Option<Rendition> {
    // Plain HTTPS only. HLS and DASH-segment formats are live streams and
    // storyboards here; the downloader fetches one file per stream.
    if !matches!(s(fmt, "protocol"), Some("https" | "http")) {
        return None;
    }
    if fmt.get("has_drm").and_then(Value::as_bool) == Some(true) {
        return None;
    }
    let url = s(fmt, "url")?;
    let id = s(fmt, "format_id")?.to_string();
    // Dynamic-range-compressed duplicates of the normal audio: quieter, not better.
    if id.contains("-drc") {
        return None;
    }
    let note = s(fmt, "format_note").unwrap_or_default();
    let vcodec = s(fmt, "vcodec").and_then(codec_name);
    let acodec = s(fmt, "acodec").and_then(codec_name);
    let kind = match (&vcodec, &acodec) {
        (Some(_), Some(_)) => "muxed",
        (Some(_), None) => "video",
        (None, Some(_)) => "audio",
        (None, None) => return None,
    };
    let ext = s(fmt, "ext").unwrap_or("mp4");
    let bitrate = f(fmt, "tbr").map(|k| (k * 1000.0) as u64).unwrap_or(0);
    let (approx_bytes, exact_size) = match u(fmt, "filesize") {
        Some(n) => (Some(n), true),
        None => (u(fmt, "filesize_approx"), false),
    };
    let fps = u(fmt, "fps").map(|n| n as u32).filter(|n| *n > 0);
    let hdr = s(fmt, "dynamic_range").is_some_and(|d| d != "SDR");
    let height = u(fmt, "height").map(|n| n as u32);

    let label = if kind == "audio" {
        let kbps = f(fmt, "abr").or_else(|| f(fmt, "tbr")).unwrap_or(0.0).round() as u64;
        let c = acodec.as_deref().map(codec_label).unwrap_or_default();
        if kbps > 0 { format!("{c} {kbps} kbps") } else { c }
    } else {
        let mut l = match height {
            Some(h) => format!("{h}p"),
            None => note.to_string(),
        };
        if let Some(fr) = fps.filter(|n| *n > 30) {
            l.push_str(&fr.to_string());
        }
        if hdr {
            l.push_str(" HDR");
        }
        l
    };

    let language = s(fmt, "language").map(str::to_string);
    // yt-dlp puts the original track at preference 10 and dubs below it.
    let original = fmt.get("language_preference").and_then(Value::as_i64) == Some(10)
        || note.contains("original");
    // The note reads "English (US) original (default), medium": the name is
    // the part before the comma, without the flags `original` already carries.
    let track_name = (kind != "video" && language.is_some())
        .then(|| {
            note.split(',').next().unwrap_or("")
                .replace(" (default)", "")
                .replace(" original", "")
                .trim()
                .to_string()
        })
        .filter(|n| !n.is_empty() && !n.ends_with('p'));

    Some(Rendition {
        id,
        label,
        kind: kind.into(),
        width: u(fmt, "width").map(|n| n as u32),
        height,
        bitrate,
        approx_bytes,
        exact_size,
        mime: Some(
            match (kind, ext) {
                ("audio", "webm") => "audio/webm",
                ("audio", _) => "audio/mp4",
                (_, "webm") => "video/webm",
                _ => "video/mp4",
            }
            .into(),
        ),
        codec: if kind == "audio" { acodec } else { vcodec },
        url: url.to_string(),
        fps,
        hdr,
        language,
        track_name,
        original,
        user_agent: fmt
            .get("http_headers")
            .and_then(|h| s(h, "User-Agent"))
            .map(str::to_string),
    })
}

fn chapters(v: &Value) -> Vec<Chapter> {
    let Some(list) = v.get("chapters").and_then(Value::as_array) else { return Vec::new() };
    list.iter()
        .filter_map(|c| {
            let start = f(c, "start_time")?;
            Some(Chapter {
                start_ms: (start.max(0.0) * 1000.0) as u64,
                title: s(c, "title").unwrap_or("").trim().to_string(),
            })
        })
        .collect()
}

fn subtitles(v: &Value) -> Vec<SubTrack> {
    let mut out = Vec::new();
    for (key, auto) in [("subtitles", false), ("automatic_captions", true)] {
        let Some(map) = v.get(key).and_then(Value::as_object) else { continue };
        for (lang, list) in map {
            // "live_chat" is a replay of the chat, not captions.
            if lang == "live_chat" {
                continue;
            }
            // Speech recognition is offered machine-translated into every
            // language; only the one actually spoken ("-orig") is worth
            // listing. Without that marker a long list is all translations.
            if auto {
                let marked = map.keys().any(|k| k.ends_with("-orig"));
                if (marked && !lang.ends_with("-orig")) || (!marked && map.len() > 3) {
                    continue;
                }
            }
            let Some(list) = list.as_array() else { continue };
            // json3 keeps speech-recognition lines whole; vtt repeats each
            // line as the next one rolls in.
            let pick = ["json3", "vtt"].iter().find_map(|want| {
                list.iter().find(|t| s(t, "ext") == Some(want))
            });
            let Some(t) = pick else { continue };
            let Some(url) = s(t, "url") else { continue };
            let lang_code = lang.trim_end_matches("-orig").to_string();
            let name = s(t, "name").unwrap_or(&lang_code).trim_end_matches(" (Original)").to_string();
            out.push(SubTrack {
                lang: lang_code,
                name,
                auto,
                url: url.to_string(),
                ext: s(t, "ext").unwrap_or("vtt").to_string(),
            });
        }
    }
    // Written captions are already first; within each kind, by name.
    out.sort_by(|a, b| a.auto.cmp(&b.auto).then_with(|| a.name.cmp(&b.name)));
    out
}

/// Turns `yt-dlp -J --flat-playlist <list>` output into a selectable list.
pub fn playlist(json: &str) -> Result<YtPlaylist, MagpieError> {
    let v: Value = serde_json::from_str(json).map_err(|e| MagpieError::Parse {
        msg: format!("yt-dlp output was not JSON: {e}"),
    })?;
    if s(&v, "_type") != Some("playlist") {
        return Err(MagpieError::Parse { msg: "that link is a single video, not a list".into() });
    }
    let empty = Vec::new();
    let raw = v.get("entries").and_then(Value::as_array).unwrap_or(&empty);
    let mut entries = Vec::new();
    for (i, e) in raw.iter().enumerate() {
        let Some(id) = s(e, "id").filter(|id| VIDEO_ID.is_match(id)) else { continue };
        let title = s(e, "title").unwrap_or("Untitled").to_string();
        // Private and deleted entries keep their slot, titled in brackets.
        let gone = title.starts_with('[') && title.ends_with(']');
        let locked = matches!(
            s(e, "availability"),
            Some("private" | "premium_only" | "subscriber_only" | "needs_auth")
        );
        let live = matches!(s(e, "live_status"), Some("is_live" | "is_upcoming"));
        entries.push(YtEntry {
            id: id.to_string(),
            url: format!("https://www.youtube.com/watch?v={id}"),
            title,
            duration_secs: u(e, "duration").unwrap_or(0),
            thumbnail: format!("https://i.ytimg.com/vi/{id}/mqdefault.jpg"),
            index: u(e, "playlist_index").map(|n| n as u32).unwrap_or(i as u32 + 1),
            available: !gone && !locked && !live,
            channel: s(e, "channel").or_else(|| s(e, "uploader")).map(str::to_string),
            views: u(e, "view_count"),
        });
    }
    if entries.is_empty() {
        return Err(MagpieError::NoMedia);
    }
    Ok(YtPlaylist {
        id: s(&v, "id").unwrap_or_default().to_string(),
        title: s(&v, "title").unwrap_or("Playlist").to_string(),
        uploader: s(&v, "channel").or_else(|| s(&v, "uploader")).map(str::to_string),
        entries,
    })
}

// ---- choosing ------------------------------------------------------------

/// Lower is preferred.
fn codec_rank(codec: Option<&str>, prefs: &YtPrefs) -> u8 {
    let compat = prefs.codec != "small";
    match (codec.unwrap_or(""), compat) {
        ("h264", true) | ("av1", false) => 0,
        ("vp9", _) => 1,
        ("av1", true) | ("h264", false) => 2,
        _ => 3,
    }
}

/// The video a preset lands on, plus the audio to go with it.
pub fn pick(info: &MediaInfo, prefs: &YtPrefs) -> Option<YtPick> {
    if prefs.quality == "audio" || info.video.is_empty() {
        return Some(YtPick { video: None, audio: Some(audio_for(info, None, prefs)?) });
    }
    let video = if prefs.quality == "smallest" {
        // The list is already ascending by size.
        info.video.first()?.clone()
    } else {
        let cap: u32 = prefs.quality.parse().unwrap_or(0);
        let h = |r: &Rendition| r.height.unwrap_or(0);
        let under: Vec<&Rendition> =
            info.video.iter().filter(|r| cap == 0 || h(r) <= cap).collect();
        // Nothing that small: take the lowest height there is rather than
        // nothing at all.
        let pool = if under.is_empty() {
            let low = info.video.iter().map(h).min()?;
            info.video.iter().filter(|r| h(r) == low).collect()
        } else {
            under
        };
        // Tallest, then smoothest, then the preferred codec; SDR over HDR,
        // which looks washed out on the screens most of these end up on.
        (*pool.iter().max_by(|a, b| {
            h(a).cmp(&h(b))
                .then(a.fps.unwrap_or(0).cmp(&b.fps.unwrap_or(0)))
                .then(codec_rank(b.codec.as_deref(), prefs).cmp(&codec_rank(a.codec.as_deref(), prefs)))
                .then(b.hdr.cmp(&a.hdr))
                .then(a.bitrate.cmp(&b.bitrate))
        })?)
        .clone()
    };
    let audio = if video.kind == "muxed" { None } else { audio_for(info, Some(&video), prefs) };
    Some(YtPick { video: Some(video), audio })
}

/// The audio track to merge with `video`, or to save alone when it is None.
///
/// Language first — the one asked for, else the original rather than a dub —
/// then a codec that suits the video: AAC beside H.264, Opus beside VP9 and
/// AV1. Highest bitrate within that, except on the "smallest" preset.
pub fn audio_for(info: &MediaInfo, video: Option<&Rendition>, prefs: &YtPrefs) -> Option<Rendition> {
    if info.audio.is_empty() {
        return None;
    }
    let lang_ok = |r: &Rendition, want: &str| {
        r.language.as_deref().is_some_and(|l| {
            let l = l.to_ascii_lowercase();
            let w = want.to_ascii_lowercase();
            l == w || l.starts_with(&format!("{w}-"))
        })
    };
    let mut pool: Vec<&Rendition> = match prefs.audio_lang.as_deref().filter(|l| !l.is_empty()) {
        Some(want) if info.audio.iter().any(|r| lang_ok(r, want)) => {
            info.audio.iter().filter(|r| lang_ok(r, want)).collect()
        }
        _ if info.audio.iter().any(|r| r.original) => {
            info.audio.iter().filter(|r| r.original).collect()
        }
        _ => info.audio.iter().collect(),
    };
    let want_aac = match video.and_then(|v| v.codec.as_deref()) {
        Some("h264") => true,
        Some(_) => false,
        None => prefs.codec != "small",
    };
    let preferred: Vec<&Rendition> = pool
        .iter()
        .copied()
        .filter(|r| (r.codec.as_deref() == Some("aac")) == want_aac)
        .collect();
    if !preferred.is_empty() {
        pool = preferred;
    }
    let chosen = if prefs.quality == "smallest" {
        pool.into_iter().min_by_key(|r| r.bitrate)
    } else {
        pool.into_iter().max_by_key(|r| r.bitrate)
    };
    chosen.cloned()
}

// ---- failures ------------------------------------------------------------

/// Sorts yt-dlp's stderr into something the UI can act on.
pub fn failure(stderr: &str) -> YtFailure {
    let line = stderr
        .lines()
        .rev()
        .find(|l| l.starts_with("ERROR:"))
        .or_else(|| stderr.lines().rev().find(|l| !l.trim().is_empty()))
        .unwrap_or("yt-dlp failed")
        .trim();
    // "ERROR: [youtube] abc123: Video unavailable" → "Video unavailable"
    let mut msg = line.trim_start_matches("ERROR:").trim();
    if msg.starts_with('[') {
        if let Some(i) = msg.find("]") {
            msg = msg[i + 1..].trim();
            if let Some((head, tail)) = msg.split_once(": ") {
                if !head.contains(' ') {
                    msg = tail.trim();
                }
            }
        }
    }
    // The advice about --cookies is aimed at the command line, not a phone.
    let msg = msg.split(". Use --cookies").next().unwrap_or(msg);
    let msg = msg.split(" Use --cookies").next().unwrap_or(msg).trim().to_string();
    let low = stderr.to_lowercase();
    let has = |needles: &[&str]| needles.iter().any(|n| low.contains(n));

    let kind = if has(&["not a bot", "sign in to confirm", "age-restricted", "inappropriate for some users",
        "members-only", "join this channel", "private video", "login required", "this video is private"])
    {
        "signin"
    } else if has(&["is live", "live event will begin", "premieres in", "this live event"]) {
        "live"
    } else if has(&["video unavailable", "has been removed", "not available in your country",
        "account associated with this video has been terminated", "does not exist"])
    {
        "unavailable"
    } else if has(&["nsig", "signature", "unable to extract", "no video formats found",
        "requested format is not available", "please report this issue", "jsc", "challenge"])
    {
        "outdated"
    } else if has(&["http error 429", "unable to download", "timed out", "network is unreachable",
        "temporary failure in name resolution", "connection reset", "getaddrinfo", "urlopen error"])
    {
        "network"
    } else {
        "other"
    };
    YtFailure { kind: kind.into(), message: msg }
}

// ---- captions ------------------------------------------------------------

/// Captions in YouTube's json3 or WebVTT, as SubRip.
pub fn captions_to_srt(body: &str, ext: &str) -> Option<String> {
    let cues = match ext {
        "json3" => json3_cues(body)?,
        _ => vtt_cues(body),
    };
    if cues.is_empty() {
        return None;
    }
    let mut out = String::new();
    for (n, (start, end, text)) in cues.iter().enumerate() {
        out.push_str(&format!("{}\n{} --> {}\n{}\n\n", n + 1, srt_time(*start), srt_time(*end), text));
    }
    Some(out)
}

fn srt_time(ms: u64) -> String {
    format!(
        "{:02}:{:02}:{:02},{:03}",
        ms / 3_600_000,
        (ms / 60_000) % 60,
        (ms / 1000) % 60,
        ms % 1000
    )
}

fn json3_cues(body: &str) -> Option<Vec<(u64, u64, String)>> {
    let v: Value = serde_json::from_str(body).ok()?;
    let mut cues: Vec<(u64, u64, String)> = Vec::new();
    for e in v.get("events")?.as_array()? {
        // Speech recognition sends "append" events that only carry the
        // newline joining two rolling lines.
        if e.get("aAppend").is_some() {
            continue;
        }
        let Some(segs) = e.get("segs").and_then(Value::as_array) else { continue };
        let text: String = segs.iter().filter_map(|s| s.get("utf8")?.as_str()).collect();
        let text = text.trim().to_string();
        if text.is_empty() {
            continue;
        }
        let start = u(e, "tStartMs").unwrap_or(0);
        let end = start + u(e, "dDurationMs").unwrap_or(2000);
        cues.push((start, end, text));
    }
    clip_overlaps(&mut cues);
    Some(cues)
}

fn vtt_cues(body: &str) -> Vec<(u64, u64, String)> {
    static TAG: Lazy<Regex> = Lazy::new(|| Regex::new(r"<[^>]*>").unwrap());
    let mut cues: Vec<(u64, u64, String)> = Vec::new();
    let mut lines = body.lines().peekable();
    while let Some(line) = lines.next() {
        let Some((a, b)) = line.split_once("-->") else { continue };
        let (Some(start), Some(end)) = (vtt_time(a), vtt_time(b.split_whitespace().next().unwrap_or(""))) else {
            continue;
        };
        let mut text = Vec::new();
        while let Some(l) = lines.peek() {
            if l.trim().is_empty() {
                break;
            }
            let clean = TAG.replace_all(l, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
            let clean = clean.trim().to_string();
            if !clean.is_empty() {
                text.push(clean);
            }
            lines.next();
        }
        // Rolling captions repeat the previous line on top; keep what is new.
        if let Some((_, _, prev)) = cues.last() {
            let prev_last = prev.lines().last().unwrap_or("").to_string();
            if text.first() == Some(&prev_last) {
                text.remove(0);
            }
        }
        if text.is_empty() || end <= start + 20 {
            continue;
        }
        cues.push((start, end, text.join("\n")));
    }
    clip_overlaps(&mut cues);
    cues
}

fn vtt_time(t: &str) -> Option<u64> {
    let t = t.trim();
    let (hms, ms) = t.split_once('.')?;
    let parts: Vec<u64> = hms.split(':').map(|p| p.parse().ok()).collect::<Option<_>>()?;
    let (h, m, s) = match parts.as_slice() {
        [h, m, s] => (*h, *m, *s),
        [m, s] => (0, *m, *s),
        _ => return None,
    };
    Some(((h * 60 + m) * 60 + s) * 1000 + ms.get(..3)?.parse::<u64>().ok()?)
}

/// Speech-recognition cues overlap so two lines show at once; a player
/// stacks them unreadably. Each ends where the next begins.
fn clip_overlaps(cues: &mut [(u64, u64, String)]) {
    for i in 1..cues.len() {
        let next_start = cues[i].0;
        let prev = &mut cues[i - 1];
        if prev.1 > next_start && next_start > prev.0 {
            prev.1 = next_start;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn prefs(q: &str, c: &str) -> YtPrefs {
        YtPrefs { quality: q.into(), codec: c.into(), audio_lang: None }
    }

    const SAMPLE: &str = r#"{
      "_type": "video", "id": "abcdefghijk", "title": "A lecture", "duration": 600,
      "channel": "Some Channel", "live_status": "not_live", "upload_date": "20240903",
      "chapters": [{"start_time": 0, "title": "Intro", "end_time": 26}, {"start_time": 26.5, "title": " What is it? ", "end_time": 600}],
      "subtitles": {"en": [{"ext": "json3", "url": "https://x/en.json3", "name": "English"},
                           {"ext": "vtt", "url": "https://x/en.vtt", "name": "English"}]},
      "automatic_captions": {
        "bn-orig": [{"ext": "json3", "url": "https://x/bn.json3", "name": "Bangla (Original)"}],
        "bn": [{"ext": "json3", "url": "https://x/bn2.json3", "name": "Bangla"}],
        "fr": [{"ext": "json3", "url": "https://x/fr.json3", "name": "French"}]
      },
      "formats": [
        {"format_id": "sb0", "protocol": "mhtml", "url": "https://sb", "vcodec": "none", "acodec": "none", "ext": "mhtml"},
        {"format_id": "233", "protocol": "m3u8_native", "url": "https://hls", "vcodec": "none", "acodec": "mp4a.40.5"},
        {"format_id": "139", "protocol": "https", "url": "https://a/139", "vcodec": "none", "acodec": "mp4a.40.5", "abr": 48.8, "tbr": 48.8, "filesize": 3600000, "ext": "m4a", "language": "en", "language_preference": 10, "format_note": "English original, low"},
        {"format_id": "140", "protocol": "https", "url": "https://a/140", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129.5, "tbr": 129.5, "filesize": 9700000, "ext": "m4a", "language": "en", "language_preference": 10, "format_note": "English (US) original (default), medium"},
        {"format_id": "140-drc", "protocol": "https", "url": "https://a/140d", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129.5, "filesize": 9700000, "ext": "m4a"},
        {"format_id": "251", "protocol": "https", "url": "https://a/251", "vcodec": "none", "acodec": "opus", "abr": 135.0, "tbr": 135.0, "filesize": 9100000, "ext": "webm", "language": "en", "language_preference": 10, "format_note": "English original, medium"},
        {"format_id": "251-1", "protocol": "https", "url": "https://a/251-1", "vcodec": "none", "acodec": "opus", "abr": 130.0, "tbr": 130.0, "filesize": 9000000, "ext": "webm", "language": "es", "language_preference": -1, "format_note": "Spanish, medium"},
        {"format_id": "18", "protocol": "https", "url": "https://v/18", "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "height": 360, "width": 640, "fps": 30, "tbr": 500.0, "filesize_approx": 37000000, "ext": "mp4"},
        {"format_id": "134", "protocol": "https", "url": "https://v/134", "vcodec": "avc1.4d401e", "acodec": "none", "height": 360, "width": 640, "fps": 30, "tbr": 300.0, "filesize": 22000000, "ext": "mp4"},
        {"format_id": "136", "protocol": "https", "url": "https://v/136", "vcodec": "avc1.4d401f", "acodec": "none", "height": 720, "width": 1280, "fps": 30, "tbr": 1100.0, "filesize": 80000000, "ext": "mp4", "http_headers": {"User-Agent": "UA/1"}},
        {"format_id": "247", "protocol": "https", "url": "https://v/247", "vcodec": "vp09.00.31.08", "acodec": "none", "height": 720, "width": 1280, "fps": 30, "tbr": 800.0, "filesize": 60000000, "ext": "webm"},
        {"format_id": "398", "protocol": "https", "url": "https://v/398", "vcodec": "av01.0.05M.08", "acodec": "none", "height": 720, "width": 1280, "fps": 30, "tbr": 600.0, "filesize": 45000000, "ext": "mp4"},
        {"format_id": "299", "protocol": "https", "url": "https://v/299", "vcodec": "avc1.64002a", "acodec": "none", "height": 1080, "width": 1920, "fps": 60, "tbr": 4000.0, "ext": "mp4"},
        {"format_id": "337", "protocol": "https", "url": "https://v/337", "vcodec": "vp09.02.51.10", "acodec": "none", "height": 2160, "width": 3840, "fps": 60, "dynamic_range": "HDR10", "tbr": 20000.0, "filesize": 1500000000, "ext": "webm"},
        {"format_id": "136", "protocol": "https", "url": "https://v/136-dup", "vcodec": "avc1.4d401f", "acodec": "none", "height": 720, "fps": 30, "ext": "mp4"}
      ]
    }"#;

    #[test]
    fn reads_formats_under_house_rules() {
        let info = media(SAMPLE).unwrap();
        assert_eq!(info.source, "youtube");
        assert_eq!(info.uploader.as_deref(), Some("Some Channel"));
        assert_eq!(info.upload_date.as_deref(), Some("2024-09-03"));
        assert_eq!(
            info.chapters,
            [
                Chapter { start_ms: 0, title: "Intro".into() },
                Chapter { start_ms: 26_500, title: "What is it?".into() },
            ]
        );
        let ids: Vec<&str> = info.video.iter().map(|r| r.id.as_str()).collect();
        // Smallest first, the duplicate itag and the HLS/storyboard rows gone.
        assert_eq!(ids, ["134", "18", "398", "247", "136", "299", "337"]);
        // 299 had no size: estimated from tbr x duration.
        let r299 = info.video.iter().find(|r| r.id == "299").unwrap();
        assert_eq!(r299.approx_bytes, Some(4_000_000 / 8 * 600));
        assert!(!r299.exact_size);
        assert_eq!(r299.label, "1080p60");
        assert_eq!(info.video.last().unwrap().label, "2160p60 HDR");
        assert_eq!(info.video[1].kind, "muxed");
        let aud: Vec<&str> = info.audio.iter().map(|r| r.id.as_str()).collect();
        assert_eq!(aud, ["139", "251-1", "251", "140"]);
        let es = info.audio.iter().find(|r| r.id == "251-1").unwrap();
        assert_eq!(es.track_name.as_deref(), Some("Spanish"));
        let en = info.audio.iter().find(|r| r.id == "140").unwrap();
        assert_eq!(en.track_name.as_deref(), Some("English (US)"));
        assert!(en.original);
        assert!(!es.original);
        assert_eq!(info.video.iter().find(|r| r.id == "136").unwrap().user_agent.as_deref(), Some("UA/1"));
    }

    #[test]
    fn subtitles_keep_only_the_spoken_auto_track() {
        let info = media(SAMPLE).unwrap();
        let subs: Vec<(String, bool)> = info.subtitles.iter().map(|s| (s.lang.clone(), s.auto)).collect();
        assert_eq!(subs, [("en".to_string(), false), ("bn".to_string(), true)]);
        assert_eq!(info.subtitles[0].ext, "json3");
        assert_eq!(info.subtitles[1].name, "Bangla");
    }

    #[test]
    fn presets_pick_the_right_pair() {
        let info = media(SAMPLE).unwrap();
        let p = pick(&info, &prefs("720", "compat")).unwrap();
        assert_eq!(p.video.as_ref().unwrap().id, "136");
        assert_eq!(p.audio.as_ref().unwrap().id, "140");

        let p = pick(&info, &prefs("720", "small")).unwrap();
        assert_eq!(p.video.as_ref().unwrap().id, "398");
        assert_eq!(p.audio.as_ref().unwrap().id, "251");

        // Best prefers SDR 1080p60 only when capped; uncapped takes 4K.
        assert_eq!(pick(&info, &prefs("1080", "compat")).unwrap().video.unwrap().id, "299");
        assert_eq!(pick(&info, &prefs("best", "compat")).unwrap().video.unwrap().id, "337");

        // Below the lowest height on offer: the lowest there is.
        assert_eq!(pick(&info, &prefs("144", "compat")).unwrap().video.unwrap().height, Some(360));

        let p = pick(&info, &prefs("smallest", "compat")).unwrap();
        assert_eq!(p.video.as_ref().unwrap().id, "134");
        assert_eq!(p.audio.as_ref().unwrap().id, "139");

        let p = pick(&info, &prefs("audio", "compat")).unwrap();
        assert!(p.video.is_none());
        assert_eq!(p.audio.unwrap().id, "140");

        let mut es = prefs("720", "small");
        es.audio_lang = Some("es".into());
        assert_eq!(pick(&info, &es).unwrap().audio.unwrap().id, "251-1");
    }

    #[test]
    fn links() {
        let w = |id: &str| Some(format!("https://www.youtube.com/watch?v={id}"));
        assert_eq!(link("https://youtu.be/dQw4w9WgXcQ?si=abc").unwrap().video_url, w("dQw4w9WgXcQ"));
        assert_eq!(link("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10").unwrap().video_url, w("dQw4w9WgXcQ"));
        assert_eq!(link("https://www.youtube.com/shorts/dQw4w9WgXcQ").unwrap().video_url, w("dQw4w9WgXcQ"));
        assert_eq!(link("https://music.youtube.com/watch?v=dQw4w9WgXcQ").unwrap().video_url, w("dQw4w9WgXcQ"));
        let both = link("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx1").unwrap();
        assert_eq!(both.video_url, w("dQw4w9WgXcQ"));
        assert_eq!(both.playlist_url.as_deref(), Some("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx1"));
        let pl = link("https://youtube.com/playlist?list=PLx1&si=z").unwrap();
        assert_eq!(pl.video_url, None);
        assert_eq!(pl.playlist_url.as_deref(), Some("https://www.youtube.com/playlist?list=PLx1"));
        assert_eq!(
            link("https://www.youtube.com/@someone").unwrap().playlist_url.as_deref(),
            Some("https://www.youtube.com/@someone/videos")
        );
        assert_eq!(
            link("https://www.youtube.com/channel/UCabc/shorts").unwrap().playlist_url.as_deref(),
            Some("https://www.youtube.com/channel/UCabc/shorts")
        );
        assert!(link("https://www.youtube.com/feed/history").is_none());
        assert!(link("https://notyoutube.com/watch?v=dQw4w9WgXcQ").is_none());
        assert!(matches("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"));
    }

    #[test]
    fn flat_playlist() {
        let json = r#"{"_type": "playlist", "id": "PLx", "title": "Course", "channel": "Prof",
          "entries": [
            {"_type": "url", "id": "aaaaaaaaaaa", "title": "One", "duration": 61.0, "channel": "Prof", "view_count": 1200},
            {"_type": "url", "id": "bbbbbbbbbbb", "title": "[Private video]", "duration": null},
            {"_type": "url", "id": "ccccccccccc", "title": "Three", "availability": "subscriber_only"},
            {"_type": "url", "id": "ddddddddddd", "title": "Four", "duration": 10}
          ]}"#;
        let p = playlist(json).unwrap();
        assert_eq!(p.title, "Course");
        assert_eq!(p.entries.len(), 4);
        assert_eq!(p.entries[0].duration_secs, 61);
        assert_eq!(p.entries.iter().map(|e| e.available).collect::<Vec<_>>(), [true, false, false, true]);
        assert_eq!(p.entries[3].index, 4);
        assert_eq!(p.entries[0].channel.as_deref(), Some("Prof"));
        assert_eq!(p.entries[0].views, Some(1200));
        assert_eq!(p.entries[1].views, None);
    }

    #[test]
    fn failures_are_sorted() {
        let f = failure("WARNING: x\nERROR: [youtube] jNQXAC9IVRw: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.");
        assert_eq!(f.kind, "signin");
        assert_eq!(f.message, "Sign in to confirm you’re not a bot");
        assert_eq!(failure("ERROR: [youtube] abc: Video unavailable").kind, "unavailable");
        assert_eq!(failure("ERROR: [youtube] abc: Video unavailable").message, "Video unavailable");
        assert_eq!(failure("ERROR: [youtube] abc: No video formats found!; please report this issue").kind, "outdated");
        assert_eq!(failure("ERROR: Unable to download API page: HTTP Error 429").kind, "network");
    }

    #[test]
    fn json3_to_srt() {
        let body = r#"{"events": [
          {"tStartMs": 0, "dDurationMs": 3000, "segs": [{"utf8": "hello"}, {"utf8": " world"}]},
          {"tStartMs": 2000, "dDurationMs": 10, "aAppend": 1, "segs": [{"utf8": "\n"}]},
          {"tStartMs": 2000, "dDurationMs": 4000, "segs": [{"utf8": "second line"}]},
          {"tStartMs": 7000, "dDurationMs": 1000}
        ]}"#;
        let srt = captions_to_srt(body, "json3").unwrap();
        assert_eq!(
            srt,
            "1\n00:00:00,000 --> 00:00:02,000\nhello world\n\n2\n00:00:02,000 --> 00:00:06,000\nsecond line\n\n"
        );
    }

    #[test]
    fn vtt_to_srt() {
        let body = "WEBVTT\n\n00:00:01.000 --> 00:00:03.500 align:start\n<c>Hello</c> &amp; welcome\n\n01:02:03.004 --> 01:02:04.000\nBye\n";
        let srt = captions_to_srt(body, "vtt").unwrap();
        assert_eq!(
            srt,
            "1\n00:00:01,000 --> 00:00:03,500\nHello & welcome\n\n2\n01:02:03,004 --> 01:02:04,000\nBye\n\n"
        );
    }
}
