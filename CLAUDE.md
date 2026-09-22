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

## House rules

- Renditions are always sorted smallest-first, unknown sizes last; the UI marks
  the first row "smallest".
- Never offer a rendition that cannot be sized *and* is known to be blocked —
  Drive's `source` download 403s on view-only files, so it is only listed when a
  size probe succeeds.
- All file I/O stays in Kotlin. The Rust core must not touch the filesystem.
