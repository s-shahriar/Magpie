# Magpie — Project Instructions

Rust extraction core + Kotlin/Compose Android app + desktop CLI, one workspace.

```
core/     magpie-core   Rust: site extractors, no file I/O, no storage
cli/      magpie        Rust: desktop front end (replaces the old fbdl script)
android/  Magpie        Kotlin/Compose app; Gradle drives Cargo
```

## Build order (Gradle does this for you)

`cargoBuild` → `uniffiBindings` → `stripNativeLibs` → `compile*Kotlin`

Three constraints that will silently break the build if changed:

1. **Cargo's release profile must not set `strip`.** `uniffi-bindgen --library`
   reads interface metadata out of the library's symbols; with `strip = true` it
   exits 0 and writes nothing, and Kotlin then fails on `Unresolved reference
   'uniffi'`. The `.so` is stripped afterwards by `stripNativeLibs` instead.
2. **No `panic = "abort"`.** UniFFI catches panics at the FFI boundary.
3. **`jniLibs/` is gitignored.** It is a build output; `cargoBuild` refills it.

## GitHub Release Rules

The in-app updater (`android/.../data/UpdateService.kt`) reads the releases
**atom feed** and derives the APK URL from the tag. Two things must hold:

1. **The repository must stay public.** `releases.atom` is not fetchable without
   auth on a private repo, and the app sends no credentials.
2. **The asset must be named `Magpie-vX.Y.Z.apk`** — matching `ASSET_PREFIX` in
   `UpdateService`. Hyphen, `v` prefix, no underscores.

### 1. Find the last release tag
```bash
gh release list -R s-shahriar/Magpie --limit 1 --json tagName --jq '.[0].tagName'
```

### 2. Bump the version
Patch for fixes, minor for features, major for breaking changes. Ask if unclear.

Set **both** in `android/app/build.gradle.kts`:
```kotlin
versionCode = 2          // +1 every release
versionName = "0.2.0"    // must equal the tag without the leading v
```
`UpdateService` compares the feed's tag to `BuildConfig.VERSION_NAME`, so a
mismatch means the app either misses updates or re-offers one it already has.

Keep `core/Cargo.toml`'s version in step when the engine changes — it is shown
in Settings → About.

### 3. Build, rename, release
```bash
cd android && ./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../Magpie-vX.Y.Z.apk
cd .. && gh release create vX.Y.Z --repo s-shahriar/Magpie \
  --title "Magpie vX.Y.Z — <short description>" \
  --notes "<notes>" \
  "Magpie-vX.Y.Z.apk#Magpie-vX.Y.Z.apk"
```

Then confirm: open Magpie → Settings → Check for updates.

## Signing

`android/keystore.properties` + `android/magpie-release.jks` are gitignored and
exist only on this machine. **Losing them means no in-place updates ever again**
— a differently-signed APK cannot upgrade an installed one, only replace it
after an uninstall. Back both up.

## Adding a source

1. New module in `core/src/extract/`, exposing `matches(&str)` and
   `probe(&str, &str) -> Result<MediaInfo, MagpieError>`.
2. Register it in `core/src/extract/mod.rs` (`probe` and `service_for`).
3. If it needs sign-in, add a variant to `Cookies.Site` in the Android app with
   its login URL and the cookie that proves a live session.
4. Verify on the desktop first — `magpie probe <url> --cookies ck.txt` — before
   touching the app. Much faster loop than rebuilding an APK.

## DhakaFlix module

Searches and browses the DhakaFlix h5ai servers (`172.16.50.{7,9,12,14}`, LAN
only, plain HTTP). Search/listing/ranking live in `core/src/dhakaflix/`;
downloads go through the normal `DownloadEngine` with `source = "dhakaflix"`,
saved under their own name to `Downloads/Magpie/DhakaFlix`. Spec and the
feature list carried over from the old FTPDownloader app:
`specs/dhakaflix-module.md`.

- Test search from the desktop on the LAN: `magpie dhakaflix search <q> --category <id> [--year y]`.
- `res/xml/network_security_config.xml` whitelists those IPs for cleartext.
  Remove it and downloads/posters fail (the Rust core's requests are unaffected,
  which makes the breakage confusing).
- The engine runs 4 DhakaFlix jobs at once and 2 of everything else; a dropped
  LAN connection parks a job as Paused with the reason, not Failed.

## YouTube

Read by **yt-dlp running on the phone** (youtubedl-android: Python + yt-dlp +
QuickJS, no ffmpeg), not by a Rust extractor — YouTube's JS challenges, PO
tokens and SABR change monthly and yt-dlp keeps up. yt-dlp only *reads*
(`-J`); its JSON goes to `core/src/youtube.rs` (renditions, preset picking,
captions → SRT, error classification), and bytes go through the normal
`DownloadEngine` + Media3 merge with `source = "youtube"`.

- Test on the desktop first: `magpie probe <yt-url>` / `magpie get <yt-url> --quality 720`
  (needs `yt-dlp` on PATH). This machine's IP gets "confirm you're not a bot"
  for videos; playlists and channels still list.
- yt-dlp updates itself: synchronously on first use (the APK's copy is old),
  then daily in the background, and on an "outdated"-looking failure.
  Settings → YouTube → Update forces it.
- Stream URLs expire (`expire=`) and are IP-bound, so `YouTube.prepare` re-reads
  the link before a job starts, and once more on a 403 (`LinkExpiredException`).
  Format ids live in `renditionId` ("137+140") so partial bytes stay valid.
- Playlist entries are queued unresolved (`preset` set, URLs empty) and read
  when their turn comes. YouTube has its own lane of 2 in the engine.
- `jniLibs { useLegacyPackaging = true }` is required: Python is unpacked from
  `libpython.zip.so` in the installed native-lib dir. Adds ~17 MB to the APK.
- Clients: `youtube::ytdlp_args()` asks `web_embedded` only, skipping the watch
  page; a failed or "thin" read (no separate video streams) retries with
  `ytdlp_fallback_args()` (`mweb,default`). yt-dlp's defaults get "confirm
  you're not a bot" on a flagged home IP; `web_embedded` still gets the ladder.
- **Read speed** comes from `assets/ytdlp/magpie_cache.py`, a yt-dlp plugin
  (`--plugin-dirs`, one level above `yt_dlp_plugins/`) that keeps the player JS
  and enables EJS's preprocessed-player cache. Without it every read fetched
  and preprocessed ~3 MB of JS: ~12 s → ~4 s per read.
- **Pass `--cache-dir`.** youtubedl-android adds `--no-cache-dir` when a request
  has none, which silently disables every yt-dlp cache.
- The library's updater reads GitHub with no timeout; `YouTube.update` waits
  30 s on first run, 90 s otherwise, and lets a slow update finish in the background.
- Debug builds log yt-dlp's `-v` output and timing under the `MagpieYtdlp` tag.
- Waydroid (`waydroid session start`, `adb connect <ip>:5555`) is x86_64 with
  4 KB pages — a good test device. Build with an init script adding the
  `x86_64` abiFilter, and `cargo ndk -t x86_64` first: Gradle only rebuilds arm64.
- Search is `ytsearchN:<query>` with `--flat-playlist`; it parses as a playlist.

## Merging (Media3 `Mp4Muxer`) — what bit us

- **Opus:** Android hands over `OpusHead` (v1, little-endian); Media3 1.5.1
  copies it into `dOps`, which must be v0, big-endian. Players then refuse the
  whole file. `OpusConfig` converts it first.
- **VP9 from WebM:** no usable `vpcC`; Media3 wrote level 1.0, 4:4:4 and the
  file would not play. `Vp9Config` builds one from the first keyframe.
- **Speed:** streamable output (index rewritten every second of media), sample
  batching and sample copying are all off. Samples are fed interleaved by time.
  ~20–45 MB/s on Waydroid, from 3.7 MB/s.
- **With copying off, Media3 keeps the `BufferInfo` you pass** and builds the
  index from it at the end. Reusing one object made every index entry describe
  the last sample — a file that would not decode. New `BufferInfo` per sample.
- **A track ends only when a read returns nothing — never on a negative
  time.** AAC with an edit list (Facebook's audio) starts at about −20 ms;
  treating that as the end dropped the whole audio track (0.10.0–0.10.3). A
  track that receives no samples now fails the merge instead of saving silently.
- Tags and chapters (`mp4tags.rs`) are added by rewriting the trailing `moov`
  in place, so the merged index must stay the last box.
- `magpie probe` + ffprobe on a pulled file is the fastest way to check a box:
  `ffprobe -show_chapters -show_entries format_tags`.

## House rules

- Renditions are always sorted smallest-first, unknown sizes last; the UI marks
  the first row "smallest".
- Never offer a rendition that cannot be sized *and* is known to be blocked —
  Drive's `source` download 403s on view-only files, so it is only listed when a
  size probe succeeds.
- All file I/O stays in Kotlin. The Rust core must not touch the filesystem.
