package com.syed.magpie.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Rendition
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/** Progress for one job, reported at a human rate rather than per buffer. */
data class Progress(
    val stage: String,
    val fraction: Float,
    val bytes: Long,
    val total: Long?,
    val bytesPerSecond: Long,
) {
    val etaSeconds: Long?
        get() {
            val t = total ?: return null
            if (bytesPerSecond <= 0) return null
            return ((t - bytes) / bytesPerSecond).coerceAtLeast(0)
        }
}

/**
 * Fetches the chosen renditions and lands a finished file in shared storage.
 *
 * All file work is deliberately on this side of the FFI: Android's scoped
 * storage is reached through MediaStore, and fighting that from native code
 * buys nothing. Merging uses the platform's own [MediaMuxer], so there is no
 * bundled ffmpeg and no root requirement — the streams are copied, never
 * re-encoded.
 */
class Downloader(private val context: Context) {

    suspend fun download(
        info: MediaInfo,
        video: Rendition,
        fileName: String,
        onProgress: (Progress) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val cookie = Cookies.Site.of(info.source)?.let { Cookies.headerFor(it) }.orEmpty()
        val needsAudio = !info.muxed && info.audio.isNotEmpty()

        val videoFile = File(context.cacheDir, "dl-${info.mediaId}-v.mp4")
        fetch(video.url, videoFile, cookie, "Downloading video", if (needsAudio) 0.75f else 1f, 0f, onProgress)

        val finished: File
        if (needsAudio) {
            val audioFile = File(context.cacheDir, "dl-${info.mediaId}-a.mp4")
            fetch(info.audio.first().url, audioFile, cookie, "Downloading audio", 0.2f, 0.75f, onProgress)

            onProgress(Progress("Merging", 0.96f, 0, null, 0))
            finished = File(context.cacheDir, "dl-${info.mediaId}-out.mp4")
            mux(videoFile, audioFile, finished)
            videoFile.delete()
            audioFile.delete()
        } else {
            finished = videoFile
        }

        onProgress(Progress("Saving", 0.98f, 0, null, 0))
        val uri = publish(finished, fileName)
        finished.delete()
        onProgress(Progress("Done", 1f, 0, null, 0))
        uri
    }

    /**
     * Ranged, resumable GET. A dropped connection on a 300 MB lecture must not
     * mean starting over.
     */
    private suspend fun fetch(
        url: String,
        target: File,
        cookie: String,
        stage: String,
        weight: Float,
        offset: Float,
        onProgress: (Progress) -> Unit,
    ) {
        val have = if (target.exists()) target.length() else 0L
        var conn = open(url, cookie, have)

        // A complete cached file asks for an unsatisfiable range; start over.
        if (conn.responseCode == 416) {
            conn.disconnect()
            target.delete()
            conn = open(url, cookie, 0)
        }
        val resuming = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        val start = if (resuming) have else 0L
        val remaining = conn.contentLengthLong.takeIf { it > 0 }
        val total = remaining?.plus(start)

        val began = System.nanoTime()
        var copied = start
        var lastPercent = -1
        var lastReport = 0L

        try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, resuming).buffered(BUFFER).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n

                        val elapsed = System.nanoTime() - began
                        val pct = total?.let { ((copied * 100) / it).toInt() } ?: -1
                        if (pct != lastPercent || elapsed - lastReport >= REPORT_NANOS) {
                            lastPercent = pct
                            lastReport = elapsed
                            val secs = elapsed / 1_000_000_000.0
                            val rate = if (secs > 0) ((copied - start) / secs).toLong() else 0L
                            val frac = total?.let { (copied.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                            onProgress(Progress(stage, offset + frac * weight, copied, total, rate))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (total != null && target.length() < total) {
            error("Download incomplete: ${target.length()} of $total bytes. Retry to resume.")
        }
    }

    private fun open(url: String, cookie: String, from: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", "https://www.facebook.com/")
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
            if (responseCode !in 200..299 &&
                responseCode != HttpURLConnection.HTTP_PARTIAL &&
                responseCode != 416
            ) {
                val code = responseCode
                disconnect()
                error("Server returned HTTP $code")
            }
        }

    /** Stream-copy both inputs into one MP4. No transcode, so it is fast. */
    private fun mux(video: File, audio: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val inputs = listOf(video, audio).map { file ->
            MediaExtractor().apply { setDataSource(file.absolutePath) }
        }
        try {
            // Map each source track onto a muxer track before writing anything.
            val mapping = mutableListOf<Triple<MediaExtractor, Int, Int>>()
            var maxBuffer = 256 * 1024
            inputs.forEach { ex ->
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    val wanted = (ex === inputs[0] && mime.startsWith("video/")) ||
                        (ex === inputs[1] && mime.startsWith("audio/"))
                    if (!wanted) continue
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxBuffer = maxOf(maxBuffer, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    ex.selectTrack(i)
                    mapping += Triple(ex, i, muxer.addTrack(fmt))
                }
            }
            check(mapping.isNotEmpty()) { "Nothing to merge — no usable tracks" }

            muxer.start()
            val buffer = ByteBuffer.allocate(maxBuffer)
            val bufferInfo = MediaCodec.BufferInfo()
            mapping.forEach { (ex, _, outTrack) ->
                while (true) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = ex.sampleTime
                    bufferInfo.flags = ex.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, bufferInfo)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            inputs.forEach { runCatching { it.release() } }
        }
    }

    /** Publish into Downloads/Magpie via MediaStore — no storage permission needed. */
    private fun publish(file: File, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Magpie")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the output file")
        resolver.openOutputStream(uri).use { out ->
            checkNotNull(out) { "Could not open the output file" }
            file.inputStream().use { it.copyTo(out, BUFFER) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    companion object {
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val BUFFER = 64 * 1024
        private const val REPORT_NANOS = 400_000_000L
    }
}

/** Strip characters MediaStore will not accept in a display name. */
fun safeFileName(title: String, quality: String): String {
    val base = title.replace(Regex("""[/\\:*?"<>|]"""), "-").trim().take(90).ifEmpty { "magpie" }
    return "$base [$quality].mp4"
}
