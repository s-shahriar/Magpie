pub mod facebook;
pub mod gdrive;

use crate::model::{MagpieError, MediaInfo};

/// Route a link to whichever extractor claims it.
pub fn probe(url: &str, cookie: &str) -> Result<MediaInfo, MagpieError> {
    if facebook::matches(url) {
        facebook::probe(url, cookie)
    } else if gdrive::matches(url) {
        gdrive::probe(url, cookie)
    } else if crate::youtube::matches(url) {
        // Read by yt-dlp, which the front end runs; see `crate::youtube`.
        Err(MagpieError::Parse {
            msg: "YouTube links are read through yt-dlp, not probed directly".into(),
        })
    } else {
        Err(MagpieError::Unsupported { url: url.into() })
    }
}

/// Which service a link belongs to, for the UI to show the right sign-in.
pub fn service_for(url: &str) -> Option<&'static str> {
    if facebook::matches(url) {
        Some("facebook")
    } else if gdrive::matches(url) {
        Some("gdrive")
    } else if crate::youtube::matches(url) {
        Some(crate::youtube::SOURCE)
    } else {
        None
    }
}
