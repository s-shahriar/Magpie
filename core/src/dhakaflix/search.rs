//! Searching a category with h5ai's search API.
//!
//! One POST per scope searches every subfolder below it on the server, so a
//! whole category costs a few requests rather than a crawl. Listing folders
//! one by one is kept only as the fallback for a server with search off.

use super::catalog::{self, Category, Kind, Scope};
use super::listing::{self, build_url, canonical_href, decode, encode_segment};
use super::{DfError, DfItem, DfSearch};
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Letters and digits. Shorter queries match tens of thousands of entries.
pub const MIN_QUERY_CHARS: usize = 3;
/// A year folder is small enough for titles like "Up".
pub const MIN_QUERY_CHARS_WITH_YEAR: usize = 2;
/// The slowest healthy server takes about 3s for a whole category.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
/// A server that just failed (down for maintenance, say) is skipped this long
/// rather than making every search wait out its timeout again.
const DEAD_SERVER_TTL: Duration = Duration::from_secs(120);
pub const MAX_RESULTS: usize = 200;
/// The listing fallback reads whole folders, which can be large.
const LISTING_TIMEOUT: Duration = Duration::from_secs(30);

static DEAD: Lazy<Mutex<HashMap<String, Instant>>> = Lazy::new(Default::default);

/// One raw h5ai search hit.
#[derive(Debug, Clone)]
pub struct Hit {
    /// Encoded path, as h5ai returns it. Folders end in `/`.
    pub href: String,
    pub time_ms: Option<i64>,
    pub size: Option<u64>,
}

enum ScopeError {
    /// The server answered, but not as an h5ai search endpoint.
    Unsupported,
    Failed(DfError),
}

/// Turn a query into an h5ai search pattern (a regex on the server).
///
/// Only letters and digits survive, and one optional separator is allowed
/// between any two characters, so "spiderman" matches "Spider-Man", "xmen"
/// matches "X-Men" and "oceans eleven" matches "Ocean's Eleven". `None` when
/// the query is too short to be worth sending.
pub fn pattern(query: &str, has_year: bool) -> Option<String> {
    let lower = query.to_lowercase();
    let words: Vec<&str> = lower.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).collect();
    let chars: usize = words.iter().map(|w| w.chars().count()).sum();
    let min = if has_year { MIN_QUERY_CHARS_WITH_YEAR } else { MIN_QUERY_CHARS };
    if chars < min {
        return None;
    }
    Some(
        words
            .iter()
            .map(|w| w.chars().map(String::from).collect::<Vec<_>>().join("[^a-z0-9]?"))
            .collect::<Vec<_>>()
            .join("[^a-z0-9]*"),
    )
}

pub fn search(category: &Category, query: &str, year: Option<&str>) -> Result<DfSearch, DfError> {
    let year = year.map(str::trim).unwrap_or("");
    let well_formed = year.len() == 4 && year.chars().all(|c| c.is_ascii_digit());
    if !year.is_empty() && !well_formed {
        return Err(DfError::Input { msg: "Year must be 4 digits, e.g. 2024".into() });
    }
    let Some(pattern) = pattern(query, !year.is_empty()) else {
        let mut msg = format!("Type at least {MIN_QUERY_CHARS} letters or digits");
        if category.supports_year() {
            msg.push_str(", or add a year for shorter titles");
        }
        return Err(DfError::Input { msg });
    };

    let outcome = search_scopes(category, &pattern, query, year, true)?;

    // A year narrows the search, but must never hide a title that is filed
    // under the wrong year: if the year folders have nothing, look everywhere.
    let narrowed = !year.is_empty()
        && category.scopes.iter().any(|s| s.year.is_some_and(|y| y.folder(year).is_some()));
    if narrowed && outcome.items.is_empty() && !outcome.used_fallback {
        return search_scopes(category, &pattern, query, year, false);
    }
    Ok(outcome)
}

fn search_scopes(
    category: &Category,
    pattern: &str,
    query: &str,
    year: &str,
    use_year_folders: bool,
) -> Result<DfSearch, DfError> {
    let results: Vec<Result<Vec<DfItem>, ScopeError>> = std::thread::scope(|s| {
        let handles: Vec<_> = category
            .scopes
            .iter()
            .map(|scope| {
                s.spawn(move || {
                    let folder = if use_year_folders && !year.is_empty() {
                        scope.year.and_then(|y| y.folder(year))
                    } else {
                        None
                    };
                    let path = match folder {
                        Some(f) => format!("{}/{f}/", scope.path.trim_end_matches('/')),
                        None => scope.path.to_string(),
                    };
                    search_scope(scope.server, &path, pattern).map(|hits| collapse(&hits, scope, category))
                })
            })
            .collect();
        handles
            .into_iter()
            .map(|h| h.join().unwrap_or_else(|_| Err(ScopeError::Failed(DfError::Network {
                msg: "search crashed".into(),
                endpoint: String::new(),
            }))))
            .collect()
    });

    let mut items = Vec::new();
    let mut failed_sources = Vec::new();
    let mut errors = Vec::new();
    let mut unsupported = 0;
    for (result, scope) in results.into_iter().zip(category.scopes) {
        match result {
            Ok(found) => items.extend(found),
            Err(e) => {
                failed_sources.push(source_name(category, scope));
                match e {
                    ScopeError::Unsupported => unsupported += 1,
                    ScopeError::Failed(err) => errors.push(err),
                }
            }
        }
    }

    // Partial results beat no results; only a total loss is an error.
    if items.is_empty() && failed_sources.len() == category.scopes.len() {
        if errors.is_empty() && unsupported > 0 {
            let (items, failed_sources) = legacy_search(category, query, year)?;
            let ranked = rank(items, query, year);
            return Ok(DfSearch {
                truncated: ranked.len() > MAX_RESULTS,
                items: ranked.into_iter().take(MAX_RESULTS).collect(),
                failed_sources,
                used_fallback: true,
            });
        }
        return Err(errors.into_iter().next().expect("a failure"));
    }

    let ranked = rank(items, query, year);
    Ok(DfSearch {
        truncated: ranked.len() > MAX_RESULTS,
        items: ranked.into_iter().take(MAX_RESULTS).collect(),
        failed_sources,
        used_fallback: false,
    })
}

/// "172.16.50.9 (Anime & Cartoon)" — or just the host when searching
/// everything, where the category name would say nothing.
fn source_name(category: &Category, scope: &Scope) -> String {
    let host = scope.server.trim_start_matches("http://").trim_start_matches("https://");
    if category.kind == Kind::All {
        return host.to_string();
    }
    match scope.label {
        Some(label) => format!("{host} ({} {label})", category.name),
        None => format!("{host} ({})", category.name),
    }
}

fn search_scope(server: &str, path: &str, pattern: &str) -> Result<Vec<Hit>, ScopeError> {
    if let Some(failed) = DEAD.lock().unwrap().get(server) {
        if failed.elapsed() < DEAD_SERVER_TTL {
            return Err(ScopeError::Failed(DfError::Network {
                msg: format!("Network request failed (skipping {server}, it failed moments ago)"),
                endpoint: server.into(),
            }));
        }
    }

    let segments: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    let Some(first) = segments.first() else {
        return Err(ScopeError::Unsupported);
    };
    let endpoint = format!("{server}/{}/", encode_segment(first));
    let href = format!(
        "/{}/",
        segments.iter().map(|s| encode_segment(s)).collect::<Vec<_>>().join("/")
    );
    let body = serde_json::json!({
        "action": "get",
        "search": { "href": href, "pattern": pattern, "ignorecase": true },
    });

    let resp = crate::http::client()
        .post(&endpoint)
        .header("content-type", "application/json")
        .body(body.to_string())
        .timeout(REQUEST_TIMEOUT)
        .send();
    let resp = match resp {
        Ok(r) => {
            DEAD.lock().unwrap().remove(server);
            r
        }
        Err(e) => {
            DEAD.lock().unwrap().insert(server.to_string(), Instant::now());
            return Err(ScopeError::Failed(DfError::from_send(e, &endpoint, REQUEST_TIMEOUT)));
        }
    };

    let status = resp.status().as_u16();
    // Not an h5ai API endpoint: the fallback can still list folders.
    if (403..=405).contains(&status) {
        return Err(ScopeError::Unsupported);
    }
    if !resp.status().is_success() {
        return Err(ScopeError::Failed(DfError::Http { status, endpoint }));
    }
    let text = resp.text().map_err(|e| ScopeError::Failed(DfError::from_send(e, &endpoint, REQUEST_TIMEOUT)))?;
    let json: serde_json::Value = serde_json::from_str(&text).map_err(|_| ScopeError::Unsupported)?;
    let Some(hits) = json.get("search").and_then(|s| s.as_array()) else {
        return Err(ScopeError::Unsupported);
    };
    Ok(hits
        .iter()
        .filter_map(|h| {
            Some(Hit {
                href: h.get("href")?.as_str()?.to_string(),
                time_ms: h.get("time").and_then(|t| t.as_f64()).map(|t| t as i64),
                size: h.get("size").and_then(|s| s.as_f64()).map(|s| s as u64),
            })
        })
        .collect())
}

/// Reduce raw hits to one result per title.
///
/// h5ai matches files as well as folders, so "breaking bad" returns the show
/// folder and every episode in it. Each hit is replaced by its topmost
/// ancestor folder that matched too; a file whose folders did not match is
/// kept as a file of its own.
pub fn collapse(hits: &[Hit], scope: &Scope, category: &Category) -> Vec<DfItem> {
    let scope_depth = scope.path.split('/').filter(|p| !p.is_empty()).count();
    let exclude: HashSet<String> = category.exclude.iter().map(|e| e.to_lowercase()).collect();

    let mut order: Vec<String> = Vec::new();
    let mut by_href: HashMap<String, &Hit> = HashMap::new();
    for hit in hits {
        let key = canonical_href(&hit.href);
        if by_href.insert(key.clone(), hit).is_none() {
            order.push(key);
        }
    }

    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for href in &order {
        let parts: Vec<&str> = href.split('/').filter(|p| !p.is_empty()).collect();
        let is_folder = href.ends_with('/');

        let first_below = parts.get(scope_depth).map(|p| decode(p)).unwrap_or_default();
        if exclude.contains(&first_below.to_lowercase()) {
            continue;
        }

        let max_depth = if is_folder { parts.len() } else { parts.len().saturating_sub(1) };
        let chosen = (scope_depth + 1..=max_depth)
            .map(|d| format!("/{}/", parts[..d].join("/")))
            .find(|ancestor| by_href.contains_key(ancestor));
        let key = chosen.unwrap_or_else(|| href.clone());
        if !seen.insert(key.clone()) {
            continue;
        }

        let hit = by_href[&key];
        let folder = key.ends_with('/');
        let name = decode(key.split('/').rfind(|p| !p.is_empty()).unwrap_or(""));
        if !folder && !listing::is_media(&name) {
            continue;
        }
        let label = scope.label.map(str::to_string).or_else(|| {
            (scope.label_from_subfolder && !first_below.is_empty()).then(|| first_below.clone())
        });
        out.push(DfItem {
            name,
            url: format!("{}{key}", scope.server),
            is_folder: folder,
            label,
            size_bytes: if folder { None } else { hit.size },
            modified_ms: hit.time_ms.filter(|t| *t > 0),
        });
    }
    out
}

/// Year matches first, then exact titles, then titles starting with the
/// query (a leading "The" ignored), then folders before loose files, then by
/// name and badge.
pub fn rank(items: Vec<DfItem>, query: &str, year: &str) -> Vec<DfItem> {
    let compact = |s: &str| s.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect::<String>();
    let q = compact(query);
    let the_q = format!("the{q}");
    let score = |item: &DfItem| {
        let name = compact(&item.name);
        // Folder names look like "Title (2014) 720p [Dual Audio]".
        let title = compact(item.name.split(" (").next().unwrap_or(""));
        let mut v = 0;
        if !year.is_empty() && item.name.contains(year) {
            v += 8;
        }
        if title == q || title == the_q {
            v += 4;
        }
        if name.starts_with(&q) || name.starts_with(&the_q) {
            v += 2;
        }
        if item.is_folder {
            v += 1;
        }
        v
    };
    let mut scored: Vec<(i32, DfItem)> = items.into_iter().map(|i| (score(&i), i)).collect();
    scored.sort_by(|(sa, a), (sb, b)| {
        sb.cmp(sa)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.label.cmp(&b.label))
    });
    scored.into_iter().map(|(_, i)| i).collect()
}

/// Search by listing folders and matching their names. Only for servers that
/// have h5ai search turned off; year-folder categories need a year for it.
fn legacy_search(category: &Category, query: &str, year: &str) -> Result<(Vec<DfItem>, Vec<String>), DfError> {
    let needle = query.trim().to_lowercase();
    let matching = |items: Vec<DfItem>, label: Option<String>| -> Vec<DfItem> {
        items
            .into_iter()
            .filter(|i| i.is_folder && i.name.to_lowercase().contains(&needle))
            .map(|mut i| {
                i.label = label.clone();
                i
            })
            .collect()
    };
    let list = |url: &str| listing::fetch(url, LISTING_TIMEOUT);

    match category.kind {
        Kind::All => Err(DfError::Input {
            msg: "Search across all categories is not available on this server. Pick a category.".into(),
        }),
        Kind::WithYear | Kind::Merged if year.is_empty() => Err(DfError::Input {
            msg: "Search is not available on this server right now. Add a year to browse that year folder.".into(),
        }),
        Kind::WithYear | Kind::Merged => {
            let mut items = Vec::new();
            let mut failed = Vec::new();
            for scope in category.scopes {
                let folder = scope.year.and_then(|y| y.folder(year));
                let extra: Vec<&str> = folder.as_deref().into_iter().collect();
                match list(&build_url(scope.server, scope.path, &extra)) {
                    Ok(found) => items.extend(matching(found, scope.label.map(str::to_string))),
                    Err(_) if category.scopes.len() > 1 => failed.push(source_name(category, scope)),
                    Err(e) => return Err(e),
                }
            }
            Ok((items, failed))
        }
        Kind::Foreign => {
            let scope = &category.scopes[0];
            let exclude: HashSet<String> = category.exclude.iter().map(|e| e.to_lowercase()).collect();
            let languages: Vec<DfItem> = list(&build_url(scope.server, scope.path, &[]))?
                .into_iter()
                .filter(|i| i.is_folder && !exclude.contains(&i.name.to_lowercase()))
                .collect();
            let items = std::thread::scope(|s| {
                let handles: Vec<_> = languages
                    .iter()
                    .map(|lang| s.spawn(|| list(&lang.url).map(|f| matching(f, Some(lang.name.clone())))))
                    .collect();
                handles
                    .into_iter()
                    .flat_map(|h| h.join().ok().and_then(Result::ok).unwrap_or_default())
                    .collect()
            });
            Ok((items, Vec::new()))
        }
        Kind::Tv | Kind::Anime | Kind::Flat | Kind::KoreanTv => {
            let scope = &category.scopes[0];
            let group = match category.kind {
                Kind::Tv => Some(catalog::tv_group(query)),
                Kind::Anime => Some(catalog::anime_group(query)),
                _ => None,
            };
            let extra: Vec<&str> = group.into_iter().collect();
            Ok((matching(list(&build_url(scope.server, scope.path, &extra))?, None), Vec::new()))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hit(href: &str, size: Option<u64>) -> Hit {
        Hit { href: href.into(), time_ms: Some(1_700_000_000_000), size }
    }

    #[test]
    fn pattern_tolerates_punctuation() {
        let p = pattern("Spiderman", false).unwrap();
        let re = regex::Regex::new(&format!("(?i){p}")).unwrap();
        assert!(re.is_match("Spider-Man (2002)"));
        let p = pattern("oceans eleven", false).unwrap();
        let re = regex::Regex::new(&format!("(?i){p}")).unwrap();
        assert!(re.is_match("Ocean's Eleven (2001)"));
        assert!(pattern("up", false).is_none());
        assert!(pattern("up", true).is_some());
        assert!(pattern("!!", true).is_none());
    }

    #[test]
    fn bad_year_and_short_query_are_input_errors() {
        let all = catalog::find("all").unwrap();
        assert!(matches!(search(all, "batman", Some("20")), Err(DfError::Input { .. })));
        match search(catalog::find("tv_web_series").unwrap(), "ab", None) {
            Err(DfError::Input { msg }) => assert_eq!(msg, "Type at least 3 letters or digits"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn hits_collapse_to_the_title_folder() {
        let cat = catalog::find("tv_web_series").unwrap();
        let scope = &cat.scopes[0];
        let g = "/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A5%20A%20%E2%80%94%20L";
        let hits = vec![
            hit(&format!("{g}/Breaking%20Bad%20(TV%20Series%202008%E2%80%932013)/"), None),
            hit(&format!("{g}/Breaking%20Bad%20(TV%20Series%202008%E2%80%932013)/Season%201/"), None),
            hit(&format!("{g}/Breaking%20Bad%20(TV%20Series%202008%E2%80%932013)/Season%201/Breaking.Bad.S01E01.mkv"), Some(500)),
            hit(&format!("{g}/Other%20Show/Breaking.Bad.Parody.mkv"), Some(42)),
            hit(&format!("{g}/Other%20Show/breaking-bad.nfo"), Some(1)),
        ];
        let items = collapse(&hits, scope, cat);
        assert_eq!(items.len(), 2);
        assert!(items[0].is_folder);
        assert_eq!(items[0].name, "Breaking Bad (TV Series 2008–2013)");
        assert!(items[0].url.starts_with("http://172.16.50.12/DHAKA-FLIX-12/"));
        assert!(!items[1].is_folder);
        assert_eq!(items[1].size_bytes, Some(42));
    }

    #[test]
    fn subfolder_badges_and_exclusions() {
        let cat = catalog::find("foreign_movies").unwrap();
        let base = "/DHAKA-FLIX-7/Foreign%20Language%20Movies";
        let hits = vec![
            hit(&format!("{base}/Korean%20Language/Parasite%20(2019)/"), None),
            hit(&format!("{base}/Spanish%20Language/Parasite%20Hunter%20(2020)/"), None),
        ];
        let items = collapse(&hits, &cat.scopes[0], cat);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].label.as_deref(), Some("Spanish Language"));
    }

    #[test]
    fn ranking_prefers_year_then_exact_title() {
        let item = |name: &str, folder: bool| DfItem {
            name: name.into(),
            url: String::new(),
            is_folder: folder,
            label: None,
            size_bytes: None,
            modified_ms: None,
        };
        let ranked = rank(
            vec![
                item("Batman Begins (2005) 720p", true),
                item("The Batman (2022) 1080p", true),
                item("Batman (1989) 720p", true),
                item("batman.mkv", false),
            ],
            "batman",
            "2022",
        );
        let names: Vec<&str> = ranked.iter().map(|i| i.name.as_str()).collect();
        assert_eq!(
            names,
            ["The Batman (2022) 1080p", "Batman (1989) 720p", "Batman Begins (2005) 720p", "batman.mkv"]
        );
    }
}
