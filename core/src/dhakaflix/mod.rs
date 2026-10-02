//! DhakaFlix: the h5ai media servers on the `172.16.50.x` LAN.
//!
//! Not an extractor — there is no page to resolve, just servers to search and
//! folders to walk. Files are plain HTTP downloads, which the app fetches
//! itself like any other job. Nothing here touches the filesystem.

pub mod catalog;
pub mod listing;
pub mod search;

use std::time::Duration;

/// A category as the picker shows it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DfCategory {
    pub id: String,
    pub name: String,
    /// "all", "movie_merged", "tv_series"…
    pub kind: String,
    /// A Material icon name: "movie", "tv", "apps"…
    pub icon: String,
    /// "#RRGGBB"
    pub color: String,
    pub supports_year: bool,
    /// What a search here covers, shown under the picker.
    pub hint: String,
}

/// A folder or file on a server.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DfItem {
    pub name: String,
    /// Absolute, already encoded.
    pub url: String,
    pub is_folder: bool,
    /// Which source a search result came from: "1080p", "Hindi Dubbed",
    /// "Korean Language"…
    pub label: Option<String>,
    /// Known for search hits only; listings do not carry sizes.
    pub size_bytes: Option<u64>,
    pub modified_ms: Option<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DfSearch {
    /// Ranked, at most [`search::MAX_RESULTS`].
    pub items: Vec<DfItem>,
    /// Sources that could not be reached, e.g. "172.16.50.9 (Anime & Cartoon)".
    /// The search still succeeds when some answered.
    pub failed_sources: Vec<String>,
    /// More matched than fit.
    pub truncated: bool,
    /// The servers had search off, so folders were listed instead.
    pub used_fallback: bool,
}

/// A folder's contents.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DfFolder {
    /// Folders, videos and subtitles, in the server's order.
    pub items: Vec<DfItem>,
    pub poster: Option<String>,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum DfError {
    /// The query cannot be searched as typed. Shown as a hint, not an error.
    #[error("{msg}")]
    Input { msg: String },
    #[error("{msg}")]
    Network { msg: String, endpoint: String },
    #[error("Request timeout after {secs}s")]
    Timeout { secs: u64, endpoint: String },
    #[error("HTTP {status}")]
    Http { status: u16, endpoint: String },
}

impl DfError {
    pub(crate) fn from_send(e: reqwest::Error, endpoint: &str, timeout: Duration) -> Self {
        if e.is_timeout() {
            DfError::Timeout { secs: timeout.as_secs(), endpoint: endpoint.into() }
        } else {
            DfError::Network { msg: root_cause(&e), endpoint: endpoint.into() }
        }
    }
}

/// reqwest's own message is "error sending request for url (…)"; the reason
/// worth showing ("Connection refused") is at the bottom of the chain.
fn root_cause(e: &dyn std::error::Error) -> String {
    let mut cur = e;
    while let Some(next) = cur.source() {
        cur = next;
    }
    cur.to_string()
}

pub fn categories() -> Vec<DfCategory> {
    catalog::CATEGORIES
        .iter()
        .map(|c| DfCategory {
            id: c.id.into(),
            name: c.name.into(),
            kind: c.kind.name().into(),
            icon: c.icon.into(),
            color: c.color.into(),
            supports_year: c.supports_year(),
            hint: c.hint(),
        })
        .collect()
}

pub fn search(category_id: &str, query: &str, year: Option<&str>) -> Result<DfSearch, DfError> {
    let category = catalog::find(category_id)
        .ok_or_else(|| DfError::Input { msg: format!("Unknown category {category_id}") })?;
    let year = if category.supports_year() { year } else { None };
    search::search(category, query, year)
}

pub fn folder(url: &str) -> Result<DfFolder, DfError> {
    let timeout = Duration::from_secs(30);
    // The API gives sizes and dates; the page is the fallback for a server
    // that does not answer it.
    let all = match listing::fetch_api(url, timeout)? {
        Some(items) if !items.is_empty() => items,
        _ => listing::fetch(url, timeout)?,
    };
    let poster = listing::pick_poster(&all);
    Ok(DfFolder { items: listing::media_only(all), poster })
}

/// The poster for a title folder, or `None`. Reads the folder's listing — a
/// few KB, the cheapest way to find it — and gives up quickly.
pub fn poster(folder_url: &str) -> Option<String> {
    let items = listing::fetch(folder_url, Duration::from_secs(8)).ok()?;
    listing::pick_poster(&items)
}
