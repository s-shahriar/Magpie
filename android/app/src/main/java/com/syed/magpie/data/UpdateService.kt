package com.syed.magpie.data

import android.content.Context
import com.syed.magpie.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** What a release check found. */
data class UpdateInfo(
    val available: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String?,
    val assetName: String?,
    val notes: String?,
)

/** Live download state, reported at a human rate rather than per buffer. */
data class DownloadProgress(
    val fraction: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long,
) {
    /** Seconds remaining at the current rate, or null when it cannot be estimated. */
    val etaSeconds: Long?
        get() {
            val total = totalBytes ?: return null
            if (bytesPerSecond <= 0L) return null
            return ((total - bytesDownloaded) / bytesPerSecond).coerceAtLeast(0)
        }
}

/**
 * Checks GitHub releases for a newer APK and installs it.
 *
 * Uses the releases **atom feed** rather than api.github.com: the REST API allows only
 * 60 unauthenticated requests per hour per IP, which a carrier-NAT connection burns
 * through collectively. github.com itself imposes no such limit.
 */
class UpdateService(private val context: Context) {

    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        val feed = fetchFeed(RELEASES_FEED)
        val entry = feed.split("<entry>").getOrNull(1)
            ?: throw IllegalStateException("No releases published yet")

        val tag = TAG_PATTERN.find(entry)?.groupValues?.get(1)?.let(::decodeEntities)
            ?: throw IllegalStateException("Could not read the latest version")

        val latest = tag.removePrefix("v")
        val current = BuildConfig.VERSION_NAME
        val assetName = "$ASSET_PREFIX-$tag.apk"

        UpdateInfo(
            available = compareVersions(latest, current) > 0,
            latestVersion = latest,
            currentVersion = current,
            downloadUrl = "$RELEASE_DOWNLOAD_BASE/$tag/$assetName",
            assetName = assetName,
            notes = NOTES_PATTERN.find(entry)?.groupValues?.get(1)?.let(::htmlToPlainText),
        )
    }

    /**
     * Downloads the APK into cache, resuming a partial file when one exists.
     *
     * Resume matters here: on a slow link a 20 MB APK takes minutes, and without a Range
     * request every dropped connection would start again from zero.
     *
     * [onProgress] is throttled — it previously fired on every buffer, which on a 20 MB
     * file meant well over a thousand UI state writes and recompositions competing with
     * the transfer itself.
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val url = info.downloadUrl ?: throw IllegalStateException("No download URL")
        val target = File(context.cacheDir, info.assetName ?: DEFAULT_ASSET)

        val alreadyHave = if (target.exists()) target.length() else 0L
        var connection = openDownloadConnection(url, resumeFrom = alreadyHave)

        // A cached file that is *already complete* asks for "bytes=<size>-", which is
        // unsatisfiable — GitHub answers 416, verified against the live endpoint. That
        // happens after any finished download the user did not install (tapped "Later",
        // cancelled the installer, killed the app), and since nothing clears the cache it
        // repeated forever: every future update attempt died on 416 with no way out of
        // the app. Drop the file and fetch it once from the start.
        if (connection.responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
            connection.disconnect()
            target.delete()
            connection = openDownloadConnection(url, resumeFrom = 0L)
        }

        // A 206 means the server honoured our range; anything else restarts the file.
        val resuming = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        val startOffset = if (resuming) alreadyHave else 0L
        val remaining = connection.contentLengthLong.takeIf { it > 0 }
        val total = remaining?.plus(startOffset)

        val startedAt = System.nanoTime()
        var copied = startOffset
        var lastReportAt = 0L
        var lastReportedPercent = -1

        try {
            connection.inputStream.use { input ->
                BufferedOutputStream(
                    FileOutputStream(target, resuming),
                    DOWNLOAD_BUFFER,
                ).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read

                        val elapsedNanos = System.nanoTime() - startedAt
                        val percent = total
                            ?.let { ((copied * 100) / it).toInt() }
                            ?: -1

                        // Report on a whole percent, or on a timer when size is unknown.
                        val dueByPercent = percent != lastReportedPercent
                        val dueByTime = elapsedNanos - lastReportAt >= REPORT_INTERVAL_NANOS
                        if (dueByPercent || dueByTime) {
                            lastReportedPercent = percent
                            lastReportAt = elapsedNanos
                            val seconds = elapsedNanos / 1_000_000_000.0
                            val rate = if (seconds > 0) {
                                ((copied - startOffset) / seconds).toLong()
                            } else {
                                0L
                            }
                            onProgress(
                                DownloadProgress(
                                    fraction = total
                                        ?.let { (copied.toFloat() / it).coerceIn(0f, 1f) }
                                        ?: 0f,
                                    bytesDownloaded = copied,
                                    totalBytes = total,
                                    bytesPerSecond = rate,
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        // A truncated transfer must not be handed to the package installer.
        if (total != null && target.length() < total) {
            throw IllegalStateException(
                "Download incomplete: ${target.length()} of $total bytes. Try again to resume."
            )
        }
        target
    }

    /** Removes any APKs left behind by a previous update attempt. */
    fun clearStaleDownloads() {
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.endsWith(".apk") }
                ?.forEach { it.delete() }
        }
    }

    /** Semantic-ish compare; missing components count as zero. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(left.size, right.size)) { index ->
            val diff = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    private fun fetchFeed(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = FEED_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/atom+xml")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Binary fetch. The read timeout is generous because a slow link can legitimately
     * stall well past what a feed request should tolerate.
     */
    private fun openDownloadConnection(url: String, resumeFrom: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", USER_AGENT)
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            // 416 is not an error here — the caller retries it from zero.
            if (responseCode !in 200..299 &&
                responseCode != HttpURLConnection.HTTP_PARTIAL &&
                responseCode != HTTP_RANGE_NOT_SATISFIABLE
            ) {
                val code = responseCode
                disconnect()
                throw IllegalStateException("GitHub returned HTTP $code")
            }
        }

    /** Undo one round of XML escaping. `&amp;` must come last or it double-decodes. */
    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun htmlToPlainText(html: String): String = decodeEntities(html)
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
        .replace(Regex("</(p|h[1-6]|li|ul|ol|div|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    companion object {
        const val REPO = "s-shahriar/Magpie"

        /** Release assets must be named `Magpie-vX.Y.Z.apk` for this to resolve. */
        const val ASSET_PREFIX = "Magpie"

        fun releasePageUrl(): String = "https://github.com/$REPO/releases/latest"

        private const val RELEASES_FEED = "https://github.com/$REPO/releases.atom"
        private const val RELEASE_DOWNLOAD_BASE = "https://github.com/$REPO/releases/download"
        private const val DEFAULT_ASSET = "magpie-update.apk"
        private const val USER_AGENT = "Magpie"

        /** java.net.HttpURLConnection has no constant for "Range Not Satisfiable". */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val FEED_READ_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

        /** 64 KiB: fewer syscalls per megabyte than the previous 16 KiB. */
        private const val DOWNLOAD_BUFFER = 64 * 1024

        /** Floor on progress reporting when the total size is unknown. */
        private const val REPORT_INTERVAL_NANOS = 500_000_000L

        private val TAG_PATTERN = Regex("""href="[^"]*/releases/tag/([^"]+)"""")
        private val NOTES_PATTERN = Regex("""<content[^>]*>([\s\S]*?)</content>""")
    }
}
