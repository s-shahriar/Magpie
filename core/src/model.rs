//! Types crossing the FFI boundary into Kotlin.

/// One downloadable stream. Facebook and Drive both fan out into several of
/// these; the UI shows them as a quality list with a size estimate.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Rendition {
    /// Stable id for this rendition within a probe (what the UI passes back).
    pub id: String,
    /// Human label: "720p", "Original", "audio".
    pub label: String,
    /// "video", "audio", or "muxed" (already has both tracks).
    pub kind: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
    /// Bits per second, 0 when the source does not say.
    pub bitrate: u64,
    /// Byte size: exact when the server reported it, otherwise estimated from
    /// bitrate x duration. `exact_size` says which.
    pub approx_bytes: Option<u64>,
    pub exact_size: bool,
    pub mime: Option<String>,
    pub codec: Option<String>,
    pub url: String,
}

/// Everything a probe learned about one piece of media.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MediaInfo {
    /// "facebook" | "gdrive"
    pub source: String,
    pub media_id: String,
    pub title: String,
    pub duration_secs: u64,
    pub thumbnail: Option<String>,
    /// Video-bearing renditions, ascending by size.
    pub video: Vec<Rendition>,
    /// Audio-only renditions, ascending by size. Empty when video is muxed.
    pub audio: Vec<Rendition>,
    /// True when a chosen `video` already contains audio and needs no muxing.
    pub muxed: bool,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MagpieError {
    #[error("network: {msg}")]
    Network { msg: String },
    #[error("this link is not supported yet: {url}")]
    Unsupported { url: String },
    #[error("no media found on that page — check you are signed in and have access")]
    NoMedia,
    #[error("sign-in required for {service}")]
    AuthRequired { service: String },
    #[error("{msg}")]
    Parse { msg: String },
}

impl MediaInfo {
    /// Fill in byte estimates from bitrate once duration is known.
    pub(crate) fn estimate_sizes(&mut self) {
        let d = self.duration_secs;
        for r in self.video.iter_mut().chain(self.audio.iter_mut()) {
            if r.approx_bytes.is_none() && r.bitrate > 0 && d > 0 {
                r.approx_bytes = Some(r.bitrate / 8 * d);
            }
        }
        // Ascending by size, with unknown sizes last so the picker's first row
        // is always the smallest known option.
        let key = |r: &Rendition| r.approx_bytes.unwrap_or(u64::MAX);
        self.video.sort_by_key(key);
        self.audio.sort_by_key(key);
    }
}
