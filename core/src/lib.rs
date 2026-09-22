//! Magpie extraction core.
//!
//! Deliberately does no file I/O and owns no storage: Android's scoped storage
//! makes writing files from native code a fight, so the Kotlin layer keeps all
//! of that. This crate resolves a link to a list of downloadable renditions and
//! stops there. The same crate backs the desktop CLI.

pub mod extract;
pub mod http;
pub mod model;

pub use model::{MagpieError, MediaInfo, Rendition};

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

/// Version string, shown in the app's About row.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
