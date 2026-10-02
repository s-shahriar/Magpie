# DhakaFlix module — migrating FTPDownloader into Magpie

Source app: `~/Projects/Self/FTPDownloader` (React Native/Expo, v1.9.2,
"DFlix Downloader"). It searches and browses the DhakaFlix h5ai media servers
on the `172.16.50.x` LAN and downloads films, series and subtitles.

Target: a fifth Magpie module, **DhakaFlix**, with its own library tab. It
should behave the same as the old app, and use Magpie's queue, notifications,
storage and settings wherever Magpie already does the job.

---

## 1. Feature inventory (what the old app does)

### 1.1 Servers and categories

There are 12 categories, and the default is **All Categories**. Each one maps
to one or more h5ai *search scopes*: a server, a path, an optional year format
and a badge label.

| Category | Type | Scopes |
|---|---|---|
| All Categories | all | `/DHAKA-FLIX-{7,14,12,9}/` (server 8 is games/software, so it is left out). The badge is the first subfolder. |
| English Movies | merged | 7 `/DHAKA-FLIX-7/English Movies` (paren, "720p") + 14 `/DHAKA-FLIX-14/English Movies (1080p)` (paren_1080p, "1080p") |
| Hindi Movies | with_year | 14 `/DHAKA-FLIX-14/Hindi Movies` (paren) |
| South Indian Movies | merged | 14 `…/SOUTH INDIAN MOVIES/South Movies` (bare, "Original") + `…/Hindi Dubbed` (paren, "Hindi Dubbed") |
| Animation Movies | merged | 14 `Animation Movies` (paren, "720p") + `Animation Movies (1080p)` (none, "1080p") |
| TV & Web Series | tv_series | 12 `/DHAKA-FLIX-12/TV-WEB-Series` |
| Korean TV & Web Series | korean_tv | 14 `/DHAKA-FLIX-14/KOREAN TV & WEB Series` |
| Korean / Japanese / Chinese Movies | flat | 7 `/DHAKA-FLIX-7/Foreign Language Movies/<Lang> Language` |
| Anime & Cartoon | anime_series | 9 `/DHAKA-FLIX-9/Anime & Cartoon TV Series` |
| Foreign Language Movies | foreign | 7 `…/Foreign Language Movies`, without the Chinese/Japanese/Korean folders. The badge is the language subfolder. |

- Year folder formats: `paren` → `(2025)`; `paren_1080p` → `(2025) 1080p`;
  `bare` → `2025`; `none` means the source has no year folders.
- Each category has its own colour, icon and hint line under the picker. For
  example, "Searches 720p + 1080p together · year optional" is built from the
  source labels.
- Only `with_year`, `merged` and `all` offer the year field.

### 1.2 Search (h5ai search API)

- **Request:** each scope gets one `POST` to `http://<server>/<first segment>/`
  with the body `{action:"get", search:{href, pattern, ignorecase:true}}`.
  The scopes run in parallel. The timeout is 10s.
- **Pattern:** the query is lowercased and split on anything that is not a
  letter or digit. Inside a word, each letter may be followed by one optional
  separator (`[^a-z0-9]?`), and words are joined with `[^a-z0-9]*`. So
  "spiderman" matches "Spider-Man", "xmen" matches "X-Men", and "oceans
  eleven" matches "Ocean's Eleven".
- **Minimum length:** 3 letters or digits, or 2 when a year is given. A year
  must be exactly 4 digits. Breaking either rule shows an inline "Refine
  search" message, not an error.
- **Year:** first only the year folders are searched. If that finds nothing,
  the whole scope is searched.
- **Collapsing hits:** a hit is replaced by its topmost matched ancestor
  folder, so "breaking bad" returns the show folder rather than 60 episode
  files. A loose file whose folders don't match is kept as a file row. Hrefs
  are made canonical (decode each segment, then re-encode it, keeping `(` and
  `)`) before comparing. Excluded subfolders are dropped. Non-media files are
  dropped.
- **Ranking:** points are added up, then ties are broken by name and then by
  badge.
  - year in the name: +8
  - exact title (ignoring a leading "The"): +4
  - name starts with the query: +2
  - folder: +1
- **Cap:** 200 results. When more were found, a "Showing top 200 — refine your
  search" line appears.
- **Partial failure:** a scope that fails becomes a toast, "Partial results:
  couldn't reach <host (Category label)>". The whole search fails only if
  every scope fails.
- **Dead servers:** a server that fails is skipped for 120s.
- **Fallback:** if every scope answers 403, 404 or 405, or sends something
  that is not JSON or has no `search` array, the app lists folders instead.
  It filters the folder names by substring. Year-folder categories need a
  year for this, and All Categories is refused with a message.
  - The listing fallback uses alpha groups for TV
    (`TV Series ★ 0 — 9`, `♥ A — L`, `♦ M — R`, `♦ S — Z`).
  - It uses alpha groups for Anime too (`0-9`, `A-F`, `G-M`, `N-S`, `T-Z`).
  - Foreign lists each language folder.
- **Cancelling:** a new search cancels the one in flight, and the cancelled
  one is silent.
- **No results:** "No results for "<q>" in <year>".
- **Rows:** a result row has a poster (folders only, and only the first 40
  rows), the name, a badge, the size (files) and an Added/Modified date.

### 1.3 Posters

- The poster is the folder's `a_AL_*` image, or else the first image in the
  folder listing.
- The lookup is the folder's HTML listing, which is a few KB.
- Posters are cached per folder URL, fetched at most 4 at a time, with an 8s
  timeout. A failure is not cached, so it is tried again later.

### 1.4 Folder browser

- **Fetching:** the app fetches the h5ai HTML listing (30s timeout) and
  parses `<a href>` links. It skips `../`, `/_h5ai*` and absolute
  `http(s)://` links, and resolves relative hrefs against the folder URL.
- **Filter:** folders, video files (`.mkv .mp4 .avi .mov .wmv .flv .webm
  .m4v`) and `.srt` are kept. Images, `.nfo` and everything else are hidden.
  Rows stay in the server's order.
- **Header:** folder name, "N folders · M files", and a breadcrumb card with
  the poster, the category and the decoded path.
- **States:** loading; empty ("No media found"); error (the classified error
  dialog).
- **Navigation:** tapping a folder goes deeper, and Back goes up a level.
- **File actions:** download, and copy link.
- **Search shortcuts:** tapping a loose file in search results opens its
  parent folder. If a search re-run from history finds exactly one folder,
  the app opens that folder directly.

### 1.5 Search history

- Up to 10 entries of `{query, category, timestamp}`, newest first.
- A repeat search with the same query (case-insensitive) in the same category
  replaces the old entry.
- Only searches that found something are saved.
- The history sheet shows a category colour bar, a badge, a relative time
  ("Just now / Nm ago / Nh ago / Yesterday / Nd ago / Mon D") and the query.
- Tapping an entry restores its category and query, clears the year and runs
  the search. There is a clear-all with a confirmation.

### 1.6 AI search (Gemini, grounded)

- **Opening:** a sparkle button next to the query, available only when the
  query is not empty. The sheet searches as soon as it opens.
- **Models:** Flash 2.5 (default) and Flash Lite 3.1. Changing the chip does
  not re-run the search; the user presses retry or refresh.
- **Prompt:** remove industry and language words from the title, use the
  `google_search` tool, include 2024–2026 releases, put results matching an
  industry hint first, and return a JSON array of
  `{title, year|null, industry, type, language}`. Temperature is 0.1.
- **Parsing:** take the first `[...]` in the reply, drop items without a
  title, industry or type, and sort by year descending with no year last.
- **Industry and type → category:**

  | Type | Industry | Category |
  |---|---|---|
  | tv | Korean | Korean TV |
  | tv | Anime | Anime & Cartoon |
  | tv | anything else | TV & Web Series |
  | movie | Hollywood | English |
  | movie | Bollywood | Hindi |
  | movie | South Indian | South Indian |
  | movie | Korean / Japanese / Chinese | that language's movie category |
  | movie | Anime | Anime & Cartoon |
  | movie | Animation | Animation |
  | movie | Other | Foreign |

- **Cards:** an industry emoji, year, industry and type chips, the category
  badge, and a hint ("Sets year to 2024", "Year unknown" or "No year needed").
  A match with no category is dimmed, marked "Not in library", and cannot be
  tapped.
- **Picking a match:** sets the category and the year (if the match has
  one). It keeps the user's query and does not search.
- **Errors:** missing key; a Gemini error message; an empty response; "No
  matches found". Each has a retry.

### 1.7 Downloads

- **Queue:** up to 4 at once, first in first out. Extra downloads are
  Queued. The queue survives restarts.
- **Restart:** anything that was running comes back **Paused**.
- **Resume:** always continues from the length of the partial file on disk,
  whatever stopped it: the user pausing, the network dropping, or the process
  dying.
- **Network errors:** a dropped connection leaves the item Paused with the
  reason ("Stopped at 43% — Connection lost"), not Failed.
- **Integrity checks:**
  - A file shorter than its expected size is parked as "Incomplete — a of b
    bytes".
  - A file longer than expected (the server ignored Range, or a 416 body was
    appended) is deleted, and the item is parked with "Corrupt partial file
    discarded".
  - If every byte is already on disk, the app does not request the range;
    it goes straight to saving.
- **Saving:** after 100%, a separate status ("Saving to storage…") covers
  the copy into public storage.
  - If saving fails, the item is Failed but the bytes are kept, so Retry
    saves again without downloading again.
- **Cancel:** deletes the partial file, so a retry starts from zero.
- **Retry:** keeps progress.
- **Delete:** removes the entry. A checkbox, "Also delete file from device",
  is on by default.
- **Clear completed:** clears completed and cancelled items. It has the same
  checkbox.
- **Speed:** measured over a 3s rolling window. Progress is saved to disk and
  notifications are updated about every 2s.
- **Screen:**
  - Filter tabs: All / Active / Done.
  - A strip reading "N active · M queued".
  - An empty state with a "Browse media" button.
- **Card for each status:** an icon and colour, the text, a progress bar
  (teal while running, amber while paused), "x / y", speed and ETA. The
  actions depend on the status:
  - downloading: Pause, Cancel
  - queued: Cancel
  - paused: Resume, Cancel
  - completed: Open, Delete
  - failed or cancelled: Retry, Delete
- **Open:** sends a VIEW intent; "No app can open this" if nothing handles it.
- **Notifications:**
  - Each download has its own notification with Pause / Resume / Cancel /
    Retry / Clear.
  - Completion has an Open action; failure has Retry and Dismiss.
  - Tapping a notification opens Downloads.
  - The foreground service runs while anything is in flight.
- **Floating bar:** shown on every screen except Downloads. It reads
  "Downloading N files · P% · speed", the fill is the average progress, and
  tapping it opens Downloads.

### 1.8 Storage location

- A one-time SAF folder pick ("like 1DM") with a persisted tree permission.
  Files are written into that folder.
- If no folder is picked, files go to the MediaStore album
  Pictures/FTPDownloader.
- Settings has a verify button for the folder ("Access lost" if it is no
  longer granted) and a change button (which releases the old grant first).

### 1.9 Error classification

| Type | Matched on | Message |
|---|---|---|
| network | Failed to fetch / Network request failed / ECONNREFUSED / ENOTFOUND | Unable to connect, Connection refused, or Server not found |
| timeout | timeout / ETIMEDOUT | Request timed out |
| server | 404 / 403 / ≥500 / other HTTP status; SSL | Resource not found, Access forbidden, Internal server error, or Server returned N |
| unknown | anything else | the error's own message |

The dialog shows the message, the endpoint, the status code, details, debug
information and three troubleshooting tips for the type. It has a single
"Got it" button.

### 1.10 Also in the old app — already covered by Magpie

- **SRT Converter** (Gemini Bengali hints in subtitles). Magpie's
  **Subtitles** module replaced it (fd43102) and does more: MKV/MP4
  extraction, quota handling, a log and a library.
- **In-app updater** (releases.atom). Magpie has its own UpdateService.
- **Gemini API key setting.** Magpie keeps it in `SubtitlePrefs.apiKey`
  (Settings).
- **Web build / CORS proxy, splash, the fake "Connected" pill, and the
  storage-permission gate.** These were RN/Expo workarounds and are not
  needed.

---

## 2. Principles to keep

1. **One search request per scope, never crawling folders.** The h5ai search
   API covers a whole category in about 3s. Listing folders is only the
   fallback.
2. **Partial results beat failure.** Report the servers that could not be
   reached; don't throw away the ones that answered.
3. **Remember dead servers for 120s.** One server down for maintenance must
   not slow every search.
4. **One result per title.** Collapse hits to the topmost matched folder.
5. **Fuzzy, punctuation-blind matching**, and the minimum query length,
   because short queries return tens of thousands of hits.
6. **A year narrows the search but never hides a title.** If the year folders
   find nothing, search the whole category.
7. **Posters are cheap and lazy.** Fetch them a few at a time, only for the
   first 40 rows, cache them, and retry ones that failed.
8. **Resume offset = the bytes on disk.** That is the only source of truth.
   One code path covers pause, a network drop and process death.
9. **Never save a file of the wrong length.** A short file is parked; a file
   that is too long is discarded.
10. **A network error is not a failure.** Keep the bytes, show Paused with
    the reason, and let the user resume.
11. **Saving is its own visible stage**, so a multi-GB copy doesn't look hung
    at 100%.
12. **Cancel means forget**: delete the partial. Delete asks before removing
    the file on disk.
13. **Claim the concurrency slot before the first suspension**, or the pump
    starts the whole queue at once. This was a real bug in the old app.
14. **Wrong input gets an inline message, not an error dialog.** Real network
    errors get the classified dialog.

---

## 3. How it fits Magpie

| Old app | In Magpie |
|---|---|
| Home / SearchResults / Downloads screens | The `Module.DhakaFlix` screen (search and browse) and a DhakaFlix tab in Library |
| DownloadManager (expo resumable) | **`DownloadEngine`**, which already has ranged resume from disk, 206/200/416 handling, a filesDir partial, a foreground service and a notifier with Pause/Cancel. It needs a *plain-file* path (see below). |
| SAF folder / MediaLibrary album | **MediaStore `Downloads/Magpie/DhakaFlix`**. This needs no permission and no onboarding, which is Magpie's rule. |
| SRT Converter | A "Add Bengali hints" action on a downloaded `.srt` or video, handing the file to the Subtitles module |
| Gemini key + AI search | Reuse the key in `SubtitlePrefs`. AI search gets its own small grounded call, next to `Gemini.kt`. |
| Search history, AsyncStorage | SharedPreferences (JSON), the same as the other modules |
| Toast / alert / error modal | `MagpieDialog` and the snackbar. The error classification is ported as a function. |
| Floating "Downloading N files" bar | Magpie's existing in-app queue indicator and notification. A module-level bar is optional. |

### Changes needed in shared code

- **Cleartext HTTP.** Magpie has no `networkSecurityConfig`, so plain
  `http://172.16.50.x` is blocked on Android 9+. Add a config that allows
  cleartext for those hosts.
- **A plain-file download path in `Downloader`.**
  - Today it forces a `.mp4` name, a `[quality]` tag and the `video/mp4`
    MIME type, and it lands files in `Downloads/Magpie`.
  - DhakaFlix files must keep their original name and extension
    (`.mkv`/`.srt`), get a MIME type from the extension, and go to the
    `DhakaFlix` subfolder.
  - Add to `DownloadJob`: `mime`, a subfolder, and whether to keep the name.
- **Integrity check.** Add a "length ≠ expected" check before publishing,
  if `Downloader` doesn't already do it.
- **Concurrency.** `MAX_CONCURRENT` is 2 for the whole engine; the old app
  ran 4 on the LAN. Either have a cap per source (LAN 4, internet 2) or keep
  one global cap.
- **Duplicates.** Neither app guards against downloading the same URL twice.
  Add the guard.
- **Delete with file.** Library rows need the "Also delete file" option.
  Today Magpie's Cancel keeps the saved file.

### Where the search code lives

The project rule ("Adding a source") puts network extractors in the Rust
`core/`, verified first with the CLI. The h5ai client fits that:
`core/src/dhakaflix/` would hold the categories, the pattern builder, the
POST search, collapsing, ranking, listing parsing and poster picking. It
would be exposed through UniFFI and get a CLI command,
`magpie dhakaflix search "spiderman" --category english_movies --year 2019`. Kotlin
would keep the downloads (all file I/O), the UI and the prefs.

---

## Decisions (2026-10-02)

- Storage: MediaStore `Downloads/Magpie/DhakaFlix`, no SAF picker.
- Search code: Rust core (`core/src/dhakaflix/`) + `magpie dhakaflix` CLI; Kotlin keeps UI and downloads.
- Concurrency: per-source caps — DhakaFlix 4, others 2, one queue.

---

## 4. Old quirks — deliberately not ported

- The "Connected" pill: it was hardcoded and never checked anything.
- The "Recent Searches" title appearing twice.
- The list order flipping after bulk actions.
- The misleading "Auto-resume" tip.
- Copy-link giving no feedback. Magpie will show a snackbar.
- The download icon on a search-result file navigating to its folder.
  Downloading from search results will be offered directly instead.
- Clear Completed skipping Failed items.
- The unused `SearchBar`, `formatEndpoint` and `@user_preferences`.
