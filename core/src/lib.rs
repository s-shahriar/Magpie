//! Magpie extraction core.
//!
//! Deliberately does no file I/O and owns no storage: Android's scoped storage
//! makes writing files from native code a fight, so the Kotlin layer keeps all
//! of that. This crate resolves a link to a list of downloadable renditions and
//! stops there. The same crate backs the desktop CLI.

pub mod dhakaflix;
pub mod extract;
pub mod http;
pub mod model;
pub mod mp4tags;
pub mod youtube;

pub use dhakaflix::{DfCategory, DfError, DfFolder, DfItem, DfSearch};
pub use model::{Chapter, MagpieError, MediaInfo, Rendition, StreamFacts, SubTrack};
pub use mp4tags::Mp4Tags;
pub use youtube::{YtEntry, YtFailure, YtLink, YtPick, YtPlaylist, YtPrefs};

uniffi::setup_scaffolding!();

/// Resolve a link to its renditions.
///
/// `cookie` is a raw `Cookie:` header value for the site, which the Android app
/// reads out of its WebView after sign-in. Pass an empty string for public media.
#[uniffi::export]
pub fn probe(url: String, cookie: String) -> Result<MediaInfo, MagpieError> {
    extract::probe(url.trim(), &cookie)
}

/// "facebook" | "gdrive" | "youtube" | null — lets the UI offer the right
/// login before probing.
#[uniffi::export]
pub fn service_for(url: String) -> Option<String> {
    extract::service_for(url.trim()).map(|s| s.to_string())
}

/// Identify a stream URL captured from the player's network traffic.
#[uniffi::export]
pub fn describe_stream(url: String) -> Option<StreamFacts> {
    extract::facebook::describe(&url)
}

/// The DhakaFlix categories, in picker order.
#[uniffi::export]
pub fn dhakaflix_categories() -> Vec<DfCategory> {
    dhakaflix::categories()
}

/// Search a DhakaFlix category. Blocking: a few seconds on a healthy LAN,
/// up to the 10s timeout when a server is down.
#[uniffi::export]
pub fn dhakaflix_search(
    category_id: String,
    query: String,
    year: Option<String>,
) -> Result<DfSearch, DfError> {
    dhakaflix::search(&category_id, &query, year.as_deref())
}

/// List a DhakaFlix folder: its folders, videos and subtitles, and its poster.
#[uniffi::export]
pub fn dhakaflix_folder(url: String) -> Result<DfFolder, DfError> {
    dhakaflix::folder(&url)
}

/// The poster image URL for a title folder, if it has one.
#[uniffi::export]
pub fn dhakaflix_poster(folder_url: String) -> Option<String> {
    dhakaflix::poster(&folder_url)
}

/// What a YouTube link names: a video, a list, or a video inside a list.
#[uniffi::export]
pub fn youtube_link(url: String) -> Option<YtLink> {
    youtube::link(url.trim())
}

/// Arguments for a yt-dlp read: the fast first try, or the fallback.
#[uniffi::export]
pub fn youtube_ytdlp_args(fallback: bool) -> Vec<String> {
    if fallback { youtube::ytdlp_fallback_args() } else { youtube::ytdlp_args() }
}

/// True when a first read came back without separate video streams.
#[uniffi::export]
pub fn youtube_is_thin(info: MediaInfo) -> bool {
    youtube::is_thin(&info)
}

/// Renditions from `yt-dlp -J <video>` output.
#[uniffi::export]
pub fn youtube_media(json: String) -> Result<MediaInfo, MagpieError> {
    youtube::media(&json)
}

/// Entries from `yt-dlp -J --flat-playlist <list>` output.
#[uniffi::export]
pub fn youtube_playlist(json: String) -> Result<YtPlaylist, MagpieError> {
    youtube::playlist(&json)
}

/// The video and audio a quality preset lands on.
#[uniffi::export]
pub fn youtube_pick(info: MediaInfo, prefs: YtPrefs) -> Option<YtPick> {
    youtube::pick(&info, &prefs)
}

/// The audio track to go with `video`, or to save alone when it is null.
#[uniffi::export]
pub fn youtube_audio_for(info: MediaInfo, video: Option<Rendition>, prefs: YtPrefs) -> Option<Rendition> {
    youtube::audio_for(&info, video.as_ref(), &prefs)
}

/// What went wrong, from yt-dlp's stderr.
#[uniffi::export]
pub fn youtube_failure(stderr: String) -> YtFailure {
    youtube::failure(&stderr)
}

/// "H.264", "VP9", "Opus"… for a rendition's `codec`.
#[uniffi::export]
pub fn codec_label(codec: String) -> String {
    youtube::codec_label(&codec)
}

/// YouTube captions (json3 or WebVTT) as SubRip, or null when there are none.
#[uniffi::export]
pub fn captions_to_srt(body: String, ext: String) -> Option<String> {
    youtube::captions_to_srt(&body, &ext)
}

/// `moov` with title, channel, date, chapters and cover added, or null when
/// it cannot be tagged. The caller writes it back where it came from.
#[uniffi::export]
pub fn mp4_tag_moov(moov: Vec<u8>, tags: Mp4Tags) -> Option<Vec<u8>> {
    mp4tags::tag_moov(&moov, &tags)
}

/// Version string, shown in the app's About row.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
