package com.syed.magpie.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer.TrackToken
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Codecs the muxer will not write into MP4.
 *
 * Short list, because merging no longer goes through the platform's
 * [android.media.MediaMuxer] — which rejects VP9 although the MP4 container
 * has held it for years, and Facebook now publishes whole videos in a VP9-only
 * ladder. Media3's writer takes AV1, H.263, H.264, H.265, MPEG-4 and VP9 with
 * AAC, AMR, Opus or Vorbis; what is left over is listed here so a stream it
 * cannot take fails by name rather than blindly.
 */
private val UNMUXABLE = mapOf(
    "video/x-vnd.on2.vp8" to "VP8",
    "audio/flac" to "FLAC audio",
)

/** A progress tick from a running job. */
data class Tick(
    val status: DownloadStatus,
    val stage: String,
    val bytes: Long,
    val total: Long?,
    val bytesPerSecond: Long,
)

/**
 * Fetches a job's streams and lands a finished file in shared storage.
 *
 * File work stays on the Kotlin side: shared storage is reached through
 * MediaStore, and merging uses Media3's MP4 writer, so there is no bundled
 * ffmpeg and no root. Streams are copied, never re-encoded.
 */
class Downloader(private val context: Context) {

    suspend fun run(job: DownloadJob, onTick: (Tick) -> Unit): Uri = withContext(Dispatchers.IO) {
        val cookie = Cookies.Site.of(job.source)?.let { Cookies.headerFor(it) }.orEmpty()
        val needsAudio = job.audioUrl != null

        val videoFile = partFile(context, job.id, "v")
        val audioFile = partFile(context, job.id, "a")
        ensureSpace(job, videoFile, audioFile)

        // Total is split between the two streams so the bar reflects real work.
        val videoShare = if (needsAudio) 0.88f else 1f

        // No stage label while bytes are moving: a running bar beside a size
        // and a speed already says "downloading", and which of the two streams
        // is on the wire is an implementation detail. The label comes back for
        // the stages that are not self-evident — merging, saving, paused.
        fetch(job.videoUrl, videoFile, cookie, "", videoShare, 0f, job, onTick)
        if (needsAudio) {
            fetch(job.audioUrl!!, audioFile, cookie, "", 0.1f, videoShare, job, onTick)
        }

        val uri: Uri
        if (needsAudio) {
            // Mux directly into the destination file. Writing the merged MP4 to
            // cache and then copying it into MediaStore meant a second full
            // pass over several hundred megabytes for no reason — on a 361 MB
            // video that copy alone was most of the wait.
            uri = createPending(job.fileName)
            try {
                context.contentResolver.openFileDescriptor(uri, "rw").use { pfd ->
                    checkNotNull(pfd) { "Could not open the output file" }
                    mux(videoFile, audioFile, pfd.fileDescriptor, job.totalBytes) { done, total ->
                        onTick(Tick(DownloadStatus.MERGING, "Merging", done, total, 0))
                    }
                }
                markReady(uri)
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        } else {
            onTick(Tick(DownloadStatus.SAVING, "Saving", job.totalBytes ?: 0, job.totalBytes, 0))
            uri = publish(videoFile, job.fileName)
        }

        videoFile.delete()
        audioFile.delete()
        uri
    }

    /**
     * Ranged, resumable GET. A dropped connection — or a pause — on a 300 MB
     * lecture must not mean starting over, so the partial file is kept and the
     * next attempt asks for the remainder.
     */
    private suspend fun fetch(
        url: String,
        target: File,
        cookie: String,
        stage: String,
        weight: Float,
        offset: Float,
        job: DownloadJob,
        onTick: (Tick) -> Unit,
    ) {
        // Several connections when the server allows it: a single response
        // from Drive is throttled to about playback speed regardless of the
        // link, so one stream is the bottleneck rather than the network.
        val probe = SegmentedFetch.probe(url, cookie)
        if (SegmentedFetch.worthIt(probe)) {
            val size = probe.total!!
            val began = System.nanoTime()
            val written = java.util.concurrent.atomic.AtomicLong(0)
            var lastAt = 0L
            SegmentedFetch.fetch(url, target, cookie, size) { delta ->
                val now = written.addAndGet(delta)
                val elapsed = System.nanoTime() - began
                if (elapsed - lastAt >= REPORT_NANOS) {
                    lastAt = elapsed
                    val secs = elapsed / 1_000_000_000.0
                    val rate = if (secs > 0) (now / secs).toLong() else 0L
                    val frac = (now.toFloat() / size).coerceIn(0f, 1f)
                    val done = overallOf(job)?.let { ((offset + frac * weight) * it).toLong() } ?: now
                    onTick(Tick(DownloadStatus.DOWNLOADING, stage, done, overallOf(job) ?: size, rate))
                }
            }
            return
        }

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
        val overall = job.totalBytes

        val began = System.nanoTime()
        var copied = start
        var lastPct = -1
        var lastAt = 0L

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
                        if (pct != lastPct || elapsed - lastAt >= REPORT_NANOS) {
                            lastPct = pct
                            lastAt = elapsed
                            val secs = elapsed / 1_000_000_000.0
                            val rate = if (secs > 0) ((copied - start) / secs).toLong() else 0L
                            val frac = total?.let { (copied.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                            val done = overall?.let { ((offset + frac * weight) * it).toLong() } ?: copied
                            onTick(Tick(DownloadStatus.DOWNLOADING, stage, done, overall ?: total, rate))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (total != null && target.length() < total) {
            error("Transfer incomplete: ${target.length()} of $total bytes")
        }
    }

    private fun open(url: String, cookie: String, from: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
            val code = responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL && code != 416) {
                disconnect()
                // 403 on a previously-good URL almost always means the signed
                // CDN link has expired rather than that access was lost.
                if (code == 403 || code == 410) {
                    error("This link has expired — fetch the page again")
                }
                error("Server returned HTTP $code")
            }
        }

    /**
     * Stream-copy both inputs into one MP4. No transcode, so the cost is
     * almost entirely moving bytes — which is why it is worth not moving them
     * twice.
     *
     * The writer is Media3's, not the platform's. `MediaMuxer` refuses any
     * video it was not taught about — VP9 included, though MP4 has carried VP9
     * for years and this phone plays it happily — and a VP9-only ladder is
     * exactly what Facebook now serves for some videos. Media3's `Mp4Muxer` is
     * the same stream copy with a wider list, so the merge stops being the
     * thing that decides which videos can be saved.
     */
    @OptIn(UnstableApi::class)
    private fun mux(
        video: File,
        audio: File,
        target: java.io.FileDescriptor,
        expected: Long?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val muxer = Mp4Muxer.Builder(FileOutputStream(target)).build()
        // A CDN that answered with an error page, or a transfer cut short,
        // lands here as "Failed to instantiate extractor" — true, and useless.
        val inputs = listOf(video, audio).map {
            try {
                MediaExtractor().apply { setDataSource(it.absolutePath) }
            } catch (e: Exception) {
                error("The downloaded stream could not be read — remove this row and fetch the link again")
            }
        }
        try {
            val mapping = mutableListOf<Pair<MediaExtractor, TrackToken>>()
            var maxBuffer = 512 * 1024
            inputs.forEachIndexed { index, ex ->
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    val wanted = (index == 0 && mime.startsWith("video/")) ||
                        (index == 1 && mime.startsWith("audio/"))
                    if (!wanted) continue
                    // A muxer's own complaint is "Failed to add the track",
                    // which says nothing about the cause. The picker already
                    // hides these, so reaching this line means a codec the
                    // core could not name from its tag.
                    UNMUXABLE[mime]?.let {
                        error("Android cannot put $it in an MP4 beside an audio track")
                    }
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxBuffer = maxOf(maxBuffer, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    ex.selectTrack(i)
                    mapping += ex to muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(fmt))
                }
            }
            check(mapping.isNotEmpty()) { "Nothing to merge — no usable tracks" }

            var buffer = ByteBuffer.allocate(maxBuffer)
            val info = MediaCodec.BufferInfo()
            var copied = 0L
            var lastReport = 0L
            mapping.forEach { (ex, track) ->
                while (true) {
                    // A sample larger than the buffer is refused, not
                    // truncated, and not every track declares its maximum
                    // input size. Grow and read the same sample again.
                    val size = try {
                        ex.readSampleData(buffer, 0)
                    } catch (e: IllegalArgumentException) {
                        val next = buffer.capacity() * 2
                        check(next <= MAX_SAMPLE) {
                            "A single frame is too large to copy (over ${formatBytes(MAX_SAMPLE.toLong())})"
                        }
                        buffer = ByteBuffer.allocate(next)
                        continue
                    }
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(track, buffer, info)
                    ex.advance()
                    copied += size
                    // Without this the UI sat on a bare "Merging" for minutes
                    // and looked hung.
                    if (copied - lastReport > 8L * 1024 * 1024) {
                        lastReport = copied
                        onProgress(copied, expected)
                    }
                }
            }
            // close() is what writes the index — a file that is never closed
            // is a pile of samples no player will open.
            muxer.close()
            onProgress(expected ?: copied, expected)
        } finally {
            runCatching { muxer.close() }
            inputs.forEach { runCatching { it.release() } }
        }
    }

    private fun createPending(fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Magpie")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the output file")
    }

    private fun markReady(uri: Uri) {
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }

    /** Publish into Downloads/Magpie via MediaStore — no storage permission. */
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

    /**
     * Refuses a job that cannot fit before spending an hour finding out.
     *
     * Twice the finished size, because the partials and the output are alive
     * at the same time: the merge writes the MP4 while both `.part` files are
     * still on disk, and the single-stream path copies rather than moves.
     * Whatever is already downloaded counts towards it, so a resumed job is
     * not asked for space it has already spent.
     */
    private fun ensureSpace(job: DownloadJob, vararg partials: File) {
        val total = job.totalBytes ?: return
        val have = partials.sumOf { if (it.exists()) it.length() else 0L }
        val need = (total * 2) - have + HEADROOM
        val free = StatFs(context.filesDir.path).availableBytes
        if (free < need) {
            error(
                "Not enough space — this needs about ${formatBytes(need)} " +
                    "and ${formatBytes(free)} is free",
            )
        }
    }

    private fun overallOf(job: DownloadJob): Long? = job.totalBytes

    companion object {
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val BUFFER = 64 * 1024
        private const val REPORT_NANOS = 400_000_000L
        private const val MAX_SAMPLE = 64 * 1024 * 1024
        private const val HEADROOM = 128L * 1024 * 1024

        /**
         * Where a half-finished download waits.
         *
         * `filesDir`, not `cacheDir`: Android empties the cache when storage
         * runs low, and a two-hour lecture sitting at 80% is exactly the fat
         * file it would pick. Losing it mid-flight meant starting over.
         */
        fun partsDir(context: Context): File =
            File(context.filesDir, "parts").apply { mkdirs() }

        fun partFile(context: Context, id: String, kind: String): File =
            File(partsDir(context), "dl-$id-$kind.part")
    }
}

/**
 * Build a display name MediaStore will accept.
 *
 * Drive titles usually already carry the container extension, so naming
 * naively produced "Class 09 ….mp4 [360p].mp4". The existing extension is
 * dropped before the quality tag is appended.
 */
fun safeFileName(title: String, quality: String): String {
    val cleaned = title.replace(Regex("""[/\\:*?"<>|]"""), "-").trim()
    val base = cleaned
        .replace(Regex("""\.(mp4|mkv|mov|m4v|webm|avi)$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .take(90)
        .ifEmpty { "magpie" }
    return "$base [$quality].mp4"
}
