package com.syed.magpie.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * An app package as the stores hand it out: a lone `.apk`, or a split bundle
 * — bundletool/SAI `.apks`, APKPure `.xapk`, APKMirror `.apkm`, or a plain
 * `.zip` of splits. All of the bundles are ZIPs of APKs and differ only in
 * the metadata file beside them, so one reader covers the lot.
 *
 * The archive is read in place through its file descriptor, never copied:
 * an Instagram `.apkm` is 230 MB.
 */
class ApkBundle private constructor(
    private val pfd: ParcelFileDescriptor,
    private val zip: ZipFile?,
    val format: Format,
    val fileName: String,
    val sizeBytes: Long,
    val label: String?,
    val packageName: String?,
    val versionName: String?,
    val icon: Bitmap?,
    val splits: List<Split>,
    /** APKPure game data, to land in `Android/obb/<package>/`. */
    val obbs: List<Obb>,
) : Closeable {

    enum class Format(val label: String) {
        APK("APK"), APKS("APKS"), XAPK("XAPK"), APKM("APKM"), ZIP("ZIP of APKs"),
    }

    enum class Kind { BASE, FEATURE, ABI, DENSITY, LANGUAGE, OTHER }

    data class Split(
        /** Entry path inside the archive; empty for a lone `.apk`. */
        val entry: String,
        val name: String,
        val kind: Kind,
        /** The config it serves — `arm64_v8a`, `xxhdpi`, `bn` — or null. */
        val config: String?,
        val sizeBytes: Long,
        /** What the device would ask for; the user can still change it. */
        val suggested: Boolean,
    ) {
        /** Base and feature modules are the app itself, never optional. */
        val required: Boolean get() = kind == Kind.BASE || kind == Kind.FEATURE
    }

    data class Obb(val entry: String, val name: String, val sizeBytes: Long)

    /** Streams one split's bytes; the caller closes it. */
    fun open(split: Split): InputStream =
        // A fresh open of the descriptor's path, so a second attempt starts
        // from byte 0 rather than wherever the first one left the offset.
        if (zip == null) File("/proc/self/fd/${pfd.fd}").inputStream()
        else zip.getInputStream(zip.getEntry(split.entry))

    fun open(obb: Obb): InputStream = zip!!.getInputStream(zip.getEntry(obb.entry))

    override fun close() {
        runCatching { zip?.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /** What the picker and the share sheet should accept. */
        val EXTENSIONS = setOf("apk", "apks", "xapk", "apkm", "zip")

        fun isPackageName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        /**
         * Reads the archive's table of contents and metadata. Nothing is
         * extracted: the splits are streamed from the archive at install time.
         */
        fun open(context: Context, uri: Uri, fileName: String): ApkBundle {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Could not open $fileName")
            try {
                return read(context, pfd, fileName)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        private fun read(context: Context, pfd: ParcelFileDescriptor, fileName: String): ApkBundle {
            // ZipFile wants a path; /proc/self/fd is one for any seekable
            // descriptor, which is what a file on the phone's storage gives.
            val zip = try {
                ZipFile(File("/proc/self/fd/${pfd.fd}"))
            } catch (e: ZipException) {
                throw IllegalStateException(
                    if (e.message?.contains("encrypt", ignoreCase = true) == true) {
                        "This is an encrypted APKMirror bundle, from before they dropped " +
                            "the encryption. Download it again from APKMirror for a readable one."
                    } else "Not an app package: ${e.message ?: "unreadable ZIP"}",
                )
            } catch (e: java.io.IOException) {
                throw IllegalStateException(
                    "This file can't be read in place (it may still be in the cloud). " +
                        "Save it to the phone first.",
                )
            }

            val names = zip.entries().asSequence().map { it.name }.toList()

            // An APK is itself a ZIP; its manifest at the root is the tell.
            if ("AndroidManifest.xml" in names) {
                zip.close()
                val split = Split("", fileName, Kind.BASE, null, pfd.statSize, suggested = true)
                val info = archiveInfo(context, pfd)
                return ApkBundle(
                    pfd, null, Format.APK, fileName, pfd.statSize,
                    label = null, packageName = info?.packageName, versionName = info?.versionName,
                    icon = null, splits = listOf(split), obbs = emptyList(),
                )
            }

            fun text(name: String): String? = zip.getEntry(name)?.let { e ->
                zip.getInputStream(e).use { it.readBytes().decodeToString() }
            }
            fun json(name: String): JSONObject? = text(name)?.let { runCatching { JSONObject(it) }.getOrNull() }

            val xapk = json("manifest.json")?.takeIf { it.has("package_name") }
            val apkm = json("info.json")?.takeIf { it.has("pname") }
            val sai = json("meta.sai_v2.json") ?: json("meta.sai_v1.json")
            val format = when {
                xapk != null || fileName.endsWith(".xapk", true) -> Format.XAPK
                apkm != null || fileName.endsWith(".apkm", true) -> Format.APKM
                fileName.endsWith(".zip", true) -> Format.ZIP
                else -> Format.APKS
            }

            var apks = names.filter { it.endsWith(".apk", true) && !it.endsWith("/") }
            // bundletool ships standalone APKs for old Androids beside the
            // splits; with splits present they are the wrong thing to install.
            if (apks.any { it.startsWith("splits/") }) apks = apks.filter { it.startsWith("splits/") }
            if (apks.isEmpty()) {
                zip.close()
                throw IllegalStateException("There are no APKs inside $fileName.")
            }

            val pkg = xapk?.optString("package_name") ?: apkm?.optString("pname") ?: sai?.optString("package")
            val raw = apks.map { entry ->
                val (kind, config) = classify(entry, pkg)
                Triple(entry, kind, config)
            }.let { list ->
                // A bundle with no split marked base: the one that isn't a
                // config split is it (APKPure names it after the package).
                if (list.none { it.second == Kind.BASE }) {
                    val nonConfig = list.filter { it.second == Kind.FEATURE }
                    if (nonConfig.size == 1) list.map { if (it === nonConfig[0]) it.copy(second = Kind.BASE) else it }
                    else list
                } else list
            }
            val suggested = suggest(context, raw.map { it.second to it.third })
            val splits = raw.mapIndexed { i, (entry, kind, config) ->
                Split(
                    entry = entry,
                    name = entry.substringAfterLast('/'),
                    kind = kind,
                    config = config,
                    sizeBytes = zip.getEntry(entry).size.coerceAtLeast(0),
                    suggested = suggested[i],
                )
            }.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))

            val obbs = names
                .filter { it.endsWith(".obb", true) }
                .map { Obb(it, it.substringAfterLast('/'), zip.getEntry(it).size.coerceAtLeast(0)) }

            val icon = (names.firstOrNull { it.equals("icon.png", true) })
                ?.let { zip.getEntry(it) }
                ?.let { e -> runCatching { zip.getInputStream(e).use(BitmapFactory::decodeStream) }.getOrNull() }

            return ApkBundle(
                pfd, zip, format, fileName, pfd.statSize,
                label = xapk?.optString("name") ?: apkm?.optString("app_name") ?: sai?.optString("label"),
                packageName = pkg?.takeIf { it.isNotBlank() },
                versionName = (
                    xapk?.optString("version_name") ?: apkm?.optString("release_version")
                        ?: sai?.optString("version_name")
                    )?.takeIf { it.isNotBlank() },
                icon = icon,
                splits = splits,
                obbs = obbs,
            )
        }

        /** Package and version of a lone APK, which the system can read. */
        private fun archiveInfo(context: Context, pfd: ParcelFileDescriptor) = runCatching {
            context.packageManager.getPackageArchiveInfo("/proc/self/fd/${pfd.fd}", 0)
        }.getOrNull()

        private val ABIS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64")
        private val DENSITIES = mapOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
            "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
        )
        private val CONFIG = Regex("(?:^|_)config\\.(.+)$")
        private val LANGUAGE = Regex("^[a-z]{2,3}([_-][A-Za-z0-9]+)?$")

        /**
         * What a split is, from its file name — the one thing every format
         * agrees on:
         *
         *  - APKMirror / SAI: `base.apk`, `split_config.arm64_v8a.apk`
         *  - APKPure: `com.example.apk`, `config.xxhdpi.apk`
         *  - bundletool: `splits/base-master.apk`, `splits/base-en.apk`
         */
        private fun classify(entry: String, pkg: String?): Pair<Kind, String?> {
            val name = entry.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")
            val config: String? = when {
                CONFIG.containsMatchIn(name) -> CONFIG.find(name)!!.groupValues[1]
                entry.startsWith("splits/") && name.contains('-') -> name.substringAfterLast('-')
                    .takeUnless { it == "master" }
                else -> null
            }
            if (config == null) {
                val base = name == "base" || name == "base-master" || name == pkg ||
                    name.startsWith("standalone")
                return (if (base) Kind.BASE else Kind.FEATURE) to null
            }
            val c = config.lowercase()
            return when {
                c in ABIS -> Kind.ABI
                c in DENSITIES || c == "nodpi" -> Kind.DENSITY
                LANGUAGE.matches(config) -> Kind.LANGUAGE
                else -> Kind.OTHER
            } to c
        }

        /**
         * The config splits this phone would get from the Play Store: its best
         * ABI, the density bucket nearest its screen, and its languages plus
         * English. A category with nothing matching keeps everything, so a
         * bundle never ends up without, say, any native libraries.
         */
        private fun suggest(context: Context, splits: List<Pair<Kind, String?>>): List<Boolean> {
            val present = { kind: Kind -> splits.filter { it.first == kind }.mapNotNull { it.second }.toSet() }

            val abis = present(Kind.ABI)
            val abi = Build.SUPPORTED_ABIS.map { it.replace('-', '_') }.firstOrNull { it in abis }

            val dpi = context.resources.displayMetrics.densityDpi
            val densities = present(Kind.DENSITY).filter { it in DENSITIES }
            val density = densities.filter { DENSITIES.getValue(it) >= dpi }.minByOrNull { DENSITIES.getValue(it) }
                ?: densities.maxByOrNull { DENSITIES.getValue(it) }

            val locales = LocaleList.getDefault()
            val wanted = (0 until locales.size()).map { locales[it].language }.toSet() + "en"
            val languages = present(Kind.LANGUAGE).filter { it.substringBefore('_').substringBefore('-') in wanted }

            return splits.map { (kind, config) ->
                when (kind) {
                    Kind.ABI -> abi == null || config == abi
                    Kind.DENSITY -> density == null || config == density || config == "nodpi"
                    Kind.LANGUAGE -> languages.isEmpty() || config in languages
                    else -> true
                }
            }
        }

        /** The installed version of [pkg], or null when it isn't installed. */
        @Suppress("DEPRECATION")
        fun installedVersion(context: Context, pkg: String): String? = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull()
    }
}
