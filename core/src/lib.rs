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

pub use dhakaflix::{DfCategory, DfError, DfFolder, DfItem, DfSearch};
pub use model::{MagpieError, MediaInfo, Rendition, StreamFacts};

uniffi::setup_scaffolding!();

/// Resolve a link to its renditions.
///
/// `cookie` is a raw `Cookie:` header value for the site, which the Android app
/// reads out of its WebView after sign-in. Pass an empty string for public media.
#[uniffi::export]
pub fn probe(url: String, cookie: String) -> Result<MediaInfo, MagpieError> {
    extract::probe(url.trim(), &cookie)
}

/// "facebook" | "gdrive" | null — lets the UI offer the right login before probing.
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

/// Version string, shown in the app's About row.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
