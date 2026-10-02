//! The DhakaFlix categories and the server folders each one searches.
//!
//! Folder names are the servers' own and are matched byte for byte, odd
//! spacing and all ("TV Series ♥  A  —  L"), so they are not tidied here.

/// How a category is laid out on the server. Search treats most of them the
/// same; the difference matters for the folder-listing fallback and for
/// whether a year is offered.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Every server at once.
    All,
    /// One source split into year folders.
    WithYear,
    /// Several sources searched together (720p + 1080p, original + dubbed).
    Merged,
    /// One folder of titles, no years.
    Flat,
    /// Split into alphabetical group folders.
    Tv,
    KoreanTv,
    Anime,
    /// One folder per language.
    Foreign,
}

impl Kind {
    pub fn name(self) -> &'static str {
        match self {
            Kind::All => "all",
            Kind::WithYear => "movie_with_year",
            Kind::Merged => "movie_merged",
            Kind::Flat => "movie_flat",
            Kind::Tv => "tv_series",
            Kind::KoreanTv => "korean_tv_series",
            Kind::Anime => "anime_series",
            Kind::Foreign => "movie_foreign",
        }
    }
}

/// How a source names its year folders.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum YearFormat {
    /// `(2025)`
    Paren,
    /// `(2025) 1080p`
    Paren1080p,
    /// `2025`
    Bare,
    /// No year folders at all.
    None,
}

impl YearFormat {
    /// The year folder's name, or `None` when this source has none.
    pub fn folder(self, year: &str) -> Option<String> {
        match self {
            YearFormat::Paren => Some(format!("({year})")),
            YearFormat::Paren1080p => Some(format!("({year}) 1080p")),
            YearFormat::Bare => Some(year.to_string()),
            YearFormat::None => None,
        }
    }
}

/// One folder a category searches. h5ai's search walks every subfolder below
/// `path`, so one scope covers a whole category in one request.
#[derive(Debug)]
pub struct Scope {
    pub server: &'static str,
    pub path: &'static str,
    /// Set when `path` holds year folders, which a year can narrow to.
    pub year: Option<YearFormat>,
    /// A fixed badge for every result, e.g. "1080p".
    pub label: Option<&'static str>,
    /// Badge each result with the first folder below `path` instead, e.g.
    /// "Hindi Movies" or "Korean Language".
    pub label_from_subfolder: bool,
}

#[derive(Debug)]
pub struct Category {
    pub id: &'static str,
    pub name: &'static str,
    pub kind: Kind,
    /// A Material icon name; the app maps it to its own vector.
    pub icon: &'static str,
    pub color: &'static str,
    pub scopes: &'static [Scope],
    /// Subfolders left out because a category of their own covers them.
    pub exclude: &'static [&'static str],
}

impl Category {
    /// Year-folder categories, and All, take an optional year.
    pub fn supports_year(&self) -> bool {
        matches!(self.kind, Kind::WithYear | Kind::Merged | Kind::All)
    }

    /// One line under the picker saying what a search here will cover.
    pub fn hint(&self) -> String {
        match self.kind {
            Kind::All => "Searches every DhakaFlix server · year optional".into(),
            Kind::Tv | Kind::Anime => "Searches every letter group — no year needed".into(),
            Kind::KoreanTv | Kind::Flat => "Searches all titles — no year needed".into(),
            Kind::WithYear => "Searches all years · year optional, narrows the search".into(),
            Kind::Merged => {
                let labels: Vec<&str> = self.scopes.iter().filter_map(|s| s.label).collect();
                format!("Searches {} together · year optional", labels.join(" + "))
            }
            Kind::Foreign => "Searches all language folders — no year needed".into(),
        }
    }
}

const S7: &str = "http://172.16.50.7";
const S9: &str = "http://172.16.50.9";
const S12: &str = "http://172.16.50.12";
const S14: &str = "http://172.16.50.14";

const fn plain(server: &'static str, path: &'static str) -> Scope {
    Scope { server, path, year: None, label: None, label_from_subfolder: false }
}

const fn sourced(
    server: &'static str,
    path: &'static str,
    year: YearFormat,
    label: &'static str,
) -> Scope {
    Scope { server, path, year: Some(year), label: Some(label), label_from_subfolder: false }
}

const fn by_subfolder(server: &'static str, path: &'static str) -> Scope {
    Scope { server, path, year: None, label: None, label_from_subfolder: true }
}

/// In picker order. All comes first and is the default: searching across
/// everything is the common case.
pub static CATEGORIES: &[Category] = &[
    Category {
        id: "all",
        name: "All Categories",
        kind: Kind::All,
        icon: "apps",
        color: "#357F6D",
        // Server 8 hosts only games and software, so it is left out.
        scopes: &[
            by_subfolder(S7, "/DHAKA-FLIX-7/"),
            by_subfolder(S14, "/DHAKA-FLIX-14/"),
            by_subfolder(S12, "/DHAKA-FLIX-12/"),
            by_subfolder(S9, "/DHAKA-FLIX-9/"),
        ],
        exclude: &[],
    },
    Category {
        id: "english_movies",
        name: "English Movies",
        kind: Kind::Merged,
        icon: "movie",
        color: "#B4543C",
        scopes: &[
            sourced(S7, "/DHAKA-FLIX-7/English Movies", YearFormat::Paren, "720p"),
            sourced(S14, "/DHAKA-FLIX-14/English Movies (1080p)", YearFormat::Paren1080p, "1080p"),
        ],
        exclude: &[],
    },
    Category {
        id: "hindi_movies",
        name: "Hindi Movies",
        kind: Kind::WithYear,
        icon: "movie_creation",
        color: "#2F8078",
        scopes: &[Scope {
            server: S14,
            path: "/DHAKA-FLIX-14/Hindi Movies",
            year: Some(YearFormat::Paren),
            label: None,
            label_from_subfolder: false,
        }],
        exclude: &[],
    },
    Category {
        id: "south_indian_movies",
        name: "South Indian Movies",
        kind: Kind::Merged,
        icon: "movie",
        color: "#A9702F",
        scopes: &[
            sourced(S14, "/DHAKA-FLIX-14/SOUTH INDIAN MOVIES/South Movies", YearFormat::Bare, "Original"),
            sourced(S14, "/DHAKA-FLIX-14/SOUTH INDIAN MOVIES/Hindi Dubbed", YearFormat::Paren, "Hindi Dubbed"),
        ],
        exclude: &[],
    },
    Category {
        id: "animation_movies",
        name: "Animation Movies",
        kind: Kind::Merged,
        icon: "animation",
        color: "#7B6098",
        scopes: &[
            sourced(S14, "/DHAKA-FLIX-14/Animation Movies", YearFormat::Paren, "720p"),
            sourced(S14, "/DHAKA-FLIX-14/Animation Movies (1080p)", YearFormat::None, "1080p"),
        ],
        exclude: &[],
    },
    Category {
        id: "tv_web_series",
        name: "TV & Web Series",
        kind: Kind::Tv,
        icon: "tv",
        color: "#A8546B",
        scopes: &[plain(S12, "/DHAKA-FLIX-12/TV-WEB-Series")],
        exclude: &[],
    },
    Category {
        id: "korean_tv_series",
        name: "Korean TV & Web Series",
        kind: Kind::KoreanTv,
        icon: "tv",
        color: "#3A7B92",
        scopes: &[plain(S14, "/DHAKA-FLIX-14/KOREAN TV & WEB Series")],
        exclude: &[],
    },
    Category {
        id: "korean_movies",
        name: "Korean Movies",
        kind: Kind::Flat,
        icon: "movie",
        color: "#AB4F52",
        scopes: &[plain(S7, "/DHAKA-FLIX-7/Foreign Language Movies/Korean Language")],
        exclude: &[],
    },
    Category {
        id: "japanese_movies",
        name: "Japanese Movies",
        kind: Kind::Flat,
        icon: "movie",
        color: "#B07430",
        scopes: &[plain(S7, "/DHAKA-FLIX-7/Foreign Language Movies/Japanese Language")],
        exclude: &[],
    },
    Category {
        id: "chinese_movies",
        name: "Chinese Movies",
        kind: Kind::Flat,
        icon: "movie",
        color: "#45836F",
        scopes: &[plain(S7, "/DHAKA-FLIX-7/Foreign Language Movies/Chinese Language")],
        exclude: &[],
    },
    Category {
        id: "anime_cartoon",
        name: "Anime & Cartoon",
        kind: Kind::Anime,
        icon: "tv",
        color: "#96566E",
        scopes: &[plain(S9, "/DHAKA-FLIX-9/Anime & Cartoon TV Series")],
        exclude: &[],
    },
    Category {
        id: "foreign_movies",
        name: "Foreign Language Movies",
        kind: Kind::Foreign,
        icon: "public",
        color: "#4A7F86",
        scopes: &[by_subfolder(S7, "/DHAKA-FLIX-7/Foreign Language Movies")],
        exclude: &["Chinese Language", "Japanese Language", "Korean Language"],
    },
];

pub fn find(id: &str) -> Option<&'static Category> {
    CATEGORIES.iter().find(|c| c.id == id)
}

/// The TV group folder a title lives under, by its first character.
pub fn tv_group(query: &str) -> &'static str {
    match first_upper(query) {
        '0'..='9' => "TV Series \u{2605}  0  \u{2014}  9",
        'A'..='L' => "TV Series \u{2665}  A  \u{2014}  L",
        'M'..='R' => "TV Series \u{2666}  M  \u{2014}  R",
        _ => "TV Series \u{2666}  S  \u{2014}  Z",
    }
}

/// The Anime & Cartoon group folder a title lives under.
pub fn anime_group(query: &str) -> &'static str {
    match first_upper(query) {
        '0'..='9' => "Anime-TV Series \u{2605}  0  \u{2014}  9",
        'A'..='F' => "Anime-TV Series \u{2665}  A  \u{2014}  F",
        'G'..='M' => "Anime-TV Series \u{2665}  G  \u{2014}  M",
        'N'..='S' => "Anime-TV Series \u{2666}  N  \u{2014}  S",
        _ => "Anime-TV Series \u{2666}  T  \u{2014}  Z",
    }
}

fn first_upper(query: &str) -> char {
    query
        .trim()
        .chars()
        .next()
        .map(|c| c.to_ascii_uppercase())
        .unwrap_or('A')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn groups_follow_the_first_letter() {
        assert_eq!(tv_group("breaking bad"), "TV Series \u{2665}  A  \u{2014}  L");
        assert_eq!(tv_group("1899"), "TV Series \u{2605}  0  \u{2014}  9");
        assert_eq!(tv_group("Squid Game"), "TV Series \u{2666}  S  \u{2014}  Z");
        assert_eq!(anime_group("naruto"), "Anime-TV Series \u{2666}  N  \u{2014}  S");
    }

    #[test]
    fn merged_hint_names_its_sources() {
        assert_eq!(
            find("english_movies").unwrap().hint(),
            "Searches 720p + 1080p together · year optional"
        );
    }

    #[test]
    fn year_offered_only_where_folders_have_years() {
        assert!(find("all").unwrap().supports_year());
        assert!(find("hindi_movies").unwrap().supports_year());
        assert!(!find("tv_web_series").unwrap().supports_year());
    }
}
