# Magpie

Android app for keeping subscribed course material offline — the lectures are
already paid for, streaming them twice is just a worse way to study.

Resolves a link to its actual renditions, shows what each one costs in
megabytes, and downloads the one you pick straight into `Downloads/Magpie`.

| | |
|---|---|
| **Sources** | Facebook (incl. private groups you're a member of), Google Drive (incl. view-only) |
| **Quality** | Every rendition listed with a size preview before you commit |
| **Storage** | MediaStore → `Downloads/Magpie`. No storage permission, **no root** |
| **Merging** | Platform `MediaMuxer`, stream copy. No bundled ffmpeg |
| **Updates** | In-app, from GitHub releases |
| **Device** | arm64, Android 12+ (built against SDK 36) |

## Architecture

```
┌──────────────────────────────────────┐
│  Kotlin + Jetpack Compose            │
│  UI · WebView sign-in · MediaStore   │
│  MediaMuxer · self-update            │
└───────────────┬──────────────────────┘
                │ UniFFI
┌───────────────┴──────────────────────┐
│  magpie-core  (Rust)                 │
│  site extractors · DASH parsing      │
│  rendition + size resolution         │
└───────────────┬──────────────────────┘
                │ same crate
        magpie  (desktop CLI)
```

The split is deliberate. Parsing is fiddly, changes whenever a site ships a
redesign, and is worth testing on a laptop rather than a phone — so it lives in
Rust and is shared. Everything Android is genuinely better at (scoped storage,
muxing, foreground work, WebView cookies) stays in Kotlin, because fighting
`MediaStore` from native code buys nothing.

## Why not yt-dlp

yt-dlp's Facebook extractor cannot read current group pages — `No video formats
found!` on a permalink, `Cannot parse data` on `/watch/?v=`. Magpie reads the
page itself: Facebook embeds every DASH rendition as a `"base_url"` JSON string
whose `efg` query parameter is base64 JSON carrying the authoritative
`video_id`, `vencode_tag`, `bitrate` and `duration_s`.

Two details that are easy to get wrong and cost real debugging time:

* **Unescape `base_url` as JSON**, not by replacing `\/`. Leaving `\uXXXX`
  escapes intact corrupts the base64 and silently drops ~2/3 of renditions —
  including the smallest, so "lowest quality" resolves to the largest file.
* **Pick the target by `efg` metadata**, not document order alone. A group page
  routinely carries half a dozen unrelated sidebar reels.

For Drive, `get_video_info?docid=` returns the transcoded ladder (360p/720p/1080p)
with exact sizes available via ranged `Content-Range` probes.

## Desktop CLI

```bash
cargo build --release
yt-dlp --cookies-from-browser chrome --cookies /tmp/ck.txt --simulate https://www.facebook.com/

./target/release/magpie probe "<url>" --cookies /tmp/ck.txt
./target/release/magpie get   "<url>" --cookies /tmp/ck.txt --quality lowest
```

Cookies are matched by host **and path**. Sending every `google.com` cookie
builds a ~15 KB header and Drive answers HTTP 400.

## Building the app

```bash
cd android
./gradlew :app:assembleDebug        # arm64 only
./gradlew :app:assembleRelease -PallAbis   # all three ABIs
```

The Gradle build drives Cargo: `cargoBuild` → `uniffiBindings` → `stripNativeLibs`.
Cargo's `strip` must stay off, because `uniffi-bindgen --library` reads interface
metadata from the library's symbols and emits nothing when they are stripped;
the `.so` is stripped afterwards instead (11 MB → 4.4 MB).

## Scope

Magpie downloads media the signed-in account can already watch. It does not
break DRM, defeat encryption, or grant access to anything you don't have.
