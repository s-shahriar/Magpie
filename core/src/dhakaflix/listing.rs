//! Reading h5ai folder listings, and the URL spelling the servers expect.

use super::{DfError, DfItem};
use once_cell::sync::Lazy;
use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use regex::Regex;
use std::time::Duration;

pub const VIDEO_EXTENSIONS: &[&str] = &["mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v"];
pub const SUBTITLE_EXTENSIONS: &[&str] = &["srt"];

/// What `encodeURIComponent` leaves alone, plus `(` and `)` — which it also
/// leaves alone, and which the servers expect unescaped in year folders.
const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'_')
    .remove(b'.')
    .remove(b'!')
    .remove(b'~')
    .remove(b'*')
    .remove(b'\'')
    .remove(b'(')
    .remove(b')');

pub fn encode_segment(segment: &str) -> String {
    utf8_percent_encode(segment, SEGMENT).to_string()
}

pub fn decode(text: &str) -> String {
    percent_decode_str(text)
        .decode_utf8()
        .map(|s| s.into_owned())
        .unwrap_or_else(|_| text.to_string())
}

/// `server` + each segment of `base` and `extra`, encoded, with a trailing
/// slash: `("http://h", "/A B", ["(2025)"])` → `http://h/A%20B/(2025)/`.
pub fn build_url(server: &str, base: &str, extra: &[&str]) -> String {
    let parts: Vec<String> = base
        .split('/')
        .filter(|p| !p.is_empty())
        .chain(extra.iter().copied())
        .map(encode_segment)
        .collect();
    format!("{server}/{}/", parts.join("/"))
}

/// The same path always spelled the same way, whatever h5ai sent.
pub fn canonical_href(href: &str) -> String {
    let folder = href.ends_with('/');
    let parts: Vec<String> = href
        .split('/')
        .filter(|p| !p.is_empty())
        .map(|p| encode_segment(&decode(p)))
        .collect();
    format!("/{}{}", parts.join("/"), if folder { "/" } else { "" })
}

fn extension(name: &str) -> Option<String> {
    let dot = name.rfind('.')?;
    (dot > 0).then(|| name[dot + 1..].to_ascii_lowercase())
}

pub fn is_video(name: &str) -> bool {
    extension(name).is_some_and(|e| VIDEO_EXTENSIONS.contains(&e.as_str()))
}

pub fn is_media(name: &str) -> bool {
    extension(name)
        .is_some_and(|e| VIDEO_EXTENSIONS.contains(&e.as_str()) || SUBTITLE_EXTENSIONS.contains(&e.as_str()))
}

fn is_image(name: &str) -> bool {
    extension(name).is_some_and(|e| matches!(e.as_str(), "jpg" | "jpeg" | "png" | "webp"))
}

/// Folders, videos and `.srt`; posters, `.nfo` and the rest are noise.
pub fn media_only(items: Vec<DfItem>) -> Vec<DfItem> {
    items.into_iter().filter(|i| i.is_folder || is_media(&i.name)).collect()
}

/// The poster in a title folder: DhakaFlix names it `a_AL_.jpg` (sometimes
/// beside an `a_VL_.jpg`), older uploads use any image.
pub fn pick_poster(items: &[DfItem]) -> Option<String> {
    let images: Vec<&DfItem> = items.iter().filter(|i| !i.is_folder && is_image(&i.name)).collect();
    images
        .iter()
        .find(|i| i.name.to_ascii_lowercase().starts_with("a_al_"))
        .or(images.first())
        .map(|i| i.url.clone())
}

static LINK: Lazy<Regex> =
    Lazy::new(|| Regex::new(r#"(?i)<a\s+href="([^"]+)"[^>]*>([^<]*)</a>"#).unwrap());

fn unescape_html(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
}

/// Every entry in an h5ai listing page, with absolute URLs.
///
/// h5ai's links are relative and already encoded, so they are resolved
/// against the folder URL rather than re-encoded. Its own chrome (`/_h5ai`),
/// the parent link and external links are skipped.
pub fn parse_listing(html: &str, folder_url: &str) -> Vec<DfItem> {
    let parent = if folder_url.ends_with('/') {
        folder_url.to_string()
    } else {
        format!("{folder_url}/")
    };
    let origin = origin_of(&parent);

    LINK.captures_iter(html)
        .filter_map(|c| {
            let href = unescape_html(&c[1]);
            let name = unescape_html(c[2].trim());
            if name.is_empty()
                || href == "../"
                || href == ".."
                || href.starts_with("/_h5ai")
                || href.starts_with("http://")
                || href.starts_with("https://")
            {
                return None;
            }
            let url = if href.starts_with('/') {
                format!("{origin}{href}")
            } else {
                format!("{parent}{href}")
            };
            Some(DfItem {
                is_folder: href.ends_with('/'),
                name,
                url,
                label: None,
                size_bytes: None,
                modified_ms: None,
            })
        })
        .collect()
}

pub fn origin_of(url: &str) -> &str {
    let after = url.find("://").map(|i| i + 3).unwrap_or(0);
    match url[after..].find('/') {
        Some(i) => &url[..after + i],
        None => url,
    }
}

/// GET a folder's listing page.
pub fn fetch(url: &str, timeout: Duration) -> Result<Vec<DfItem>, DfError> {
    let resp = crate::http::client()
        .get(url)
        .header("accept", "text/html")
        .timeout(timeout)
        .send()
        .map_err(|e| DfError::from_send(e, url, timeout))?;
    let status = resp.status();
    if !status.is_success() {
        return Err(DfError::Http { status: status.as_u16(), endpoint: url.into() });
    }
    let html = resp.text().map_err(|e| DfError::from_send(e, url, timeout))?;
    Ok(parse_listing(&html, url))
}

#[cfg(test)]
mod tests {
    use super::*;

    const PAGE: &str = r#"
        <a href="/_h5ai/public/images/ui/sort.svg">x</a>
        <a href="../">Parent Directory</a>
        <a href="Frankenstein%20(2025)%20720p%20NF%20%5BDual%20Audio%5D/">Frankenstein (2025) 720p NF [Dual Audio]</a>
        <a href="a_AL_.jpg">a_AL_.jpg</a>
        <a href="Movie.mkv">Movie.mkv</a>
        <a href="Movie.nfo">Movie.nfo</a>
        <a href="/DHAKA-FLIX-14/Korean%20TV%20%26%20WEB/">KOREAN TV &amp; WEB</a>
        <a href="https://larsjung.de/h5ai/">h5ai</a>
    "#;

    #[test]
    fn listing_resolves_links_and_skips_chrome() {
        let items = parse_listing(PAGE, "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/(2025)");
        let names: Vec<&str> = items.iter().map(|i| i.name.as_str()).collect();
        assert_eq!(
            names,
            [
                "Frankenstein (2025) 720p NF [Dual Audio]",
                "a_AL_.jpg",
                "Movie.mkv",
                "Movie.nfo",
                "KOREAN TV & WEB",
            ]
        );
        assert!(items[0].is_folder);
        assert_eq!(
            items[2].url,
            "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/(2025)/Movie.mkv"
        );
        assert_eq!(items[4].url, "http://172.16.50.7/DHAKA-FLIX-14/Korean%20TV%20%26%20WEB/");
    }

    #[test]
    fn media_filter_and_poster() {
        let items = parse_listing(PAGE, "http://h/x/");
        assert!(pick_poster(&items).unwrap().ends_with("/a_AL_.jpg"));
        let media = media_only(items);
        assert!(media.iter().all(|i| i.is_folder || i.name.ends_with(".mkv")));
    }

    #[test]
    fn urls_keep_parentheses() {
        assert_eq!(
            build_url("http://172.16.50.7", "/DHAKA-FLIX-7/English Movies", &["(2025)"]),
            "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/(2025)/"
        );
        assert_eq!(canonical_href("/A%20B/%28x%29/"), "/A%20B/(x)/");
        assert_eq!(canonical_href("/A B/f.mkv"), "/A%20B/f.mkv");
    }
}
