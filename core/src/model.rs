//! Types crossing the FFI boundary into Kotlin.

/// One downloadable stream. Facebook and Drive both fan out into several of
/// these; the UI shows them as a quality list with a size estimate.
///
/// The fields after `url` are YouTube's: its ladder repeats a height across
/// codecs, frame rates and dubbed audio tracks, which the other sources never
/// do. They default so the Kotlin side can keep building renditions without them.
#[derive(Debug, Clone, Default, uniffi::Record)]
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
    #[uniffi(default = None)]
    pub fps: Option<u32>,
    #[uniffi(default = false)]
    pub hdr: bool,
    /// Audio track language, e.g. "en-US". Only set on multi-language videos.
    #[uniffi(default = None)]
    pub language: Option<String>,
    /// The source's own name for the track: "English (US) original".
    #[uniffi(default = None)]
    pub track_name: Option<String>,
    /// The track the video was recorded in, as opposed to a dub.
    #[uniffi(default = false)]
    pub original: bool,
    /// The User-Agent the URL was issued to. Sent back when fetching it.
    #[uniffi(default = None)]
    pub user_agent: Option<String>,
}

/// One subtitle language a video offers.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SubTrack {
    pub lang: String,
    pub name: String,
    /// Speech recognition rather than captions someone wrote.
    pub auto: bool,
    pub url: String,
    /// The format at `url`: "json3" or "vtt". `captions_to_srt` reads both.
    pub ext: String,
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
    #[uniffi(default = None)]
    pub uploader: Option<String>,
    /// Written captions first, then auto-generated ones; each by name.
    #[uniffi(default = [])]
    pub subtitles: Vec<SubTrack>,
    /// Set when the link was a video inside a playlist, so the UI can offer
    /// the whole list as well.
    #[uniffi(default = None)]
    pub playlist_url: Option<String>,
    /// The video's own chapter marks, in order.
    #[uniffi(default = [])]
    pub chapters: Vec<Chapter>,
    /// "2024-09-03".
    #[uniffi(default = None)]
    pub upload_date: Option<String>,
}

/// One chapter mark: where it starts, and what it is called.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Chapter {
    pub start_ms: u64,
    pub title: String,
}

/// What a captured stream URL declares about itself.
#[derive(Debug, Clone, uniffi::Record)]
pub struct StreamFacts {
    pub video_id: String,
    pub tag: String,
    pub label: String,
    pub is_audio: bool,
    pub bitrate: u64,
    pub duration_secs: u64,
    /// "h264", "vp9", "av1", "aac"… read out of the tag, `None` when the tag
    /// does not name one. The caller needs it to know whether the platform can
    /// merge this stream with its counterpart.
    pub codec: Option<String>,
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
