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
        val ua = job.userAgent ?: UA
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
        // Every byte already here — a save that failed last time. Asking for
        // the range past the end earns a 416 and a fresh download; skip
        // straight to saving instead. Length alone proves nothing while a
        // `.chunks` map exists: the segmented fetch sizes the file up front.
        val chunkMap = File(videoFile.parentFile, videoFile.name + ".chunks")
        val alreadyWhole = !needsAudio && job.totalBytes != null && !chunkMap.exists() &&
            videoFile.exists() && videoFile.length() == job.totalBytes
        // Subtitles first: a few kilobytes, and their URL expires with the
        // streams', so fetching them after an hour-long video could miss.
        val subFile = subtitleFile(context, job.id)
        if (job.subtitleUrl != null && !subFile.exists()) fetchSubtitles(job, subFile, ua)

        if (!alreadyWhole) {
            fetch(job.videoUrl, videoFile, cookie, ua, "", videoShare, 0f, job, onTick)
        }
        if (needsAudio) {
            fetch(job.audioUrl!!, audioFile, cookie, ua, "", 0.1f, videoShare, job, onTick)
        }

        val uri: Uri
        // An AAC-only YouTube download goes through the writer too, alone:
        // what YouTube serves is a fragmented stream file, and a clean .m4a
        // is one every player opens and the only kind that can carry tags.
        val remuxAudio = !needsAudio && job.meta != null && job.mime == "audio/mp4"
        if (needsAudio || remuxAudio) {
            // Mux directly into the destination file. Writing the merged MP4 to
            // cache and then copying it into MediaStore meant a second full
            // pass over several hundred megabytes for no reason — on a 361 MB
            // video that copy alone was most of the wait.
            uri = createPending(job)
            try {
                context.contentResolver.openFileDescriptor(uri, "rw").use { pfd ->
                    checkNotNull(pfd) { "Could not open the output file" }
                    val began = System.nanoTime()
                    // The download's one file is the audio, when there is no video.
                    val video = videoFile.takeIf { needsAudio }
                    val audio = if (needsAudio) audioFile else videoFile
                    mux(video, audio, pfd.fileDescriptor, job.totalBytes) { done, total ->
                        onTick(Tick(DownloadStatus.MERGING, "Merging", done, total, 0))
                    }
                    android.util.Log.i(
                        "MagpieMerge",
                        "merged ${formatBytes((video?.length() ?: 0) + audio.length())} in " +
                            "${(System.nanoTime() - began) / 1_000_000} ms",
                    )
                    val cover = if (remuxAudio) fetchCover(job) else null
                    YouTube.takeIf { job.source == it.SOURCE }?.tagsFor(job, cover)
                        ?.let { tagMp4(pfd.fileDescriptor, it) }
                }
                markReady(uri)
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        } else {
            onTick(Tick(DownloadStatus.SAVING, "Saving", job.totalBytes ?: 0, job.totalBytes, 0))
            uri = publish(videoFile, job)
        }

        videoFile.delete()
        audioFile.delete()
        uri
    }

    /**
     * Captions as an .srt, named to sit beside the video so players pick
     * them up. Never fails the job: the video is what was asked for.
     */
    private fun fetchSubtitles(job: DownloadJob, target: File, ua: String) {
        runCatching {
            val conn = (URL(job.subtitleUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", ua)
            }
            val body = try {
                if (conn.responseCode !in 200..299) return
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            uniffi.magpie_core.captionsToSrt(body, job.subtitleExt ?: "vtt")?.let { target.writeText(it) }
        }
    }

    /** Publishes the .srt fetched for [job], if one was. Returns its URI. */
    fun publishSubtitles(job: DownloadJob): Uri? {
        val file = subtitleFile(context, job.id)
        if (!file.exists() || job.subtitleLang == null) return null
        return runCatching {
            publish(file, job.copy(fileName = subtitleName(job.fileName, job.subtitleLang), mime = "application/x-subrip"))
        }.getOrNull().also { file.delete() }
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
        ua: String,
        stage: String,
        weight: Float,
        offset: Float,
        job: DownloadJob,
        onTick: (Tick) -> Unit,
    ) {
        // Several connections when the server allows it: a single response
        // from Drive is throttled to about playback speed regardless of the
        // link, so one stream is the bottleneck rather than the network.
        val probe = SegmentedFetch.probe(url, cookie, ua)
        // Already all here — a merge or save that was cut off last time. A
        // finished segmented fetch drops its chunk map, so without this the
        // file looked new and every byte was fetched again. Length is only
        // proof once the map is gone: while it exists the file is
        // preallocated to full size.
        val chunkMap = File(target.parentFile, target.name + ".chunks")
        if (probe.total != null && !chunkMap.exists() && target.exists() && target.length() == probe.total) {
            return
        }
        if (SegmentedFetch.worthIt(probe)) {
            val size = probe.total!!
            val began = System.nanoTime()
            val window = RateWindow()
            val written = java.util.concurrent.atomic.AtomicLong(0)
            var lastAt = 0L
            SegmentedFetch.fetch(url, target, cookie, size, ua = ua) { delta ->
                val now = written.addAndGet(delta)
                val elapsed = System.nanoTime() - began
                if (elapsed - lastAt >= REPORT_NANOS) {
                    lastAt = elapsed
                    val rate = window.add(elapsed, now)
                    val frac = (now.toFloat() / size).coerceIn(0f, 1f)
                    val done = overallOf(job)?.let { ((offset + frac * weight) * it).toLong() } ?: now
                    onTick(Tick(DownloadStatus.DOWNLOADING, stage, done, overallOf(job) ?: size, rate))
                }
            }
            return
        }

        val have = if (target.exists()) target.length() else 0L
        var conn = open(url, cookie, ua, have)

        // A complete cached file asks for an unsatisfiable range; start over.
        if (conn.responseCode == 416) {
            conn.disconnect()
            target.delete()
            conn = open(url, cookie, ua, 0)
        }
        val resuming = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        val start = if (resuming) have else 0L
        val remaining = conn.contentLengthLong.takeIf { it > 0 }
        val total = remaining?.plus(start)
        val overall = job.totalBytes

        val began = System.nanoTime()
        val window = RateWindow()
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
                            val rate = window.add(elapsed, copied)
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

        // An IOException, like any dropped connection: the bytes so far are
        // good and the next attempt asks for the rest.
        if (total != null && target.length() < total) {
            throw java.io.IOException("Incomplete — ${target.length()} of $total bytes")
        }
        // Longer than the server said means bytes were appended that do not
        // belong — saving it would hand over a corrupt film, and its length is
        // no longer a valid resume point either, so start clean.
        if (total != null && target.length() > total) {
            target.delete()
            throw java.io.IOException("Corrupt partial file discarded — resume to restart")
        }
    }

    private fun open(url: String, cookie: String, ua: String, from: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "*/*")
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
            val code = responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL && code != 416) {
                disconnect()
                // 403 on a previously-good URL almost always means the signed
                // CDN link has expired rather than that access was lost.
                if (code == 403 || code == 410) throw LinkExpiredException()
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
        video: File?,
        audio: File,
        target: java.io.FileDescriptor,
        expected: Long?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        // Tuned for a straight copy, which made the merge several times faster:
        // - Not "streamable": that keeps the index at the front by rebuilding
        //   and rewriting it every second of media. It goes at the end;
        //   players read either.
        // - No batching and no copying: by default every sample is copied into
        //   a fresh buffer and queued, which on an hour of video is hundreds of
        //   thousands of allocations and a GC storm. Written straight from the
        //   read buffer instead, which is safe because each write completes
        //   before the next read — but see the BufferInfo note below.
        val muxer = Mp4Muxer.Builder(FileOutputStream(target))
            .setAttemptStreamableOutputEnabled(false)
            .setSampleBatchingEnabled(false)
            .setSampleCopyEnabled(false)
            .build()
        // A CDN that answered with an error page, or a transfer cut short,
        // lands here as "Failed to instantiate extractor" — true, and useless.
        // Each input with the kind of track taken from it.
        val sources = listOfNotNull(video?.let { it to "video/" }, audio to "audio/")
        val inputs = sources.map { (file, _) ->
            try {
                MediaExtractor().apply { setDataSource(file.absolutePath) }
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
                    val wanted = mime.startsWith(sources[index].second)
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
                    if (mime == MediaFormat.MIMETYPE_VIDEO_VP9) describeVp9(ex, fmt)
                    if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) describeOpus(fmt)
                    mapping += ex to muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(fmt))
                }
            }
            check(mapping.isNotEmpty()) { "Nothing to merge — no usable tracks" }

            // Direct: the extractor copies straight in, rather than through a
            // heap array on every sample.
            var buffer = ByteBuffer.allocateDirect(maxBuffer)
            var copied = 0L
            var lastReport = 0L
            var readNanos = 0L
            var writeNanos = 0L
            // Interleaved by time: always the track that is furthest behind.
            // Video-then-audio put an hour of one before any of the other,
            // which players cope with but have to seek across the file to do.
            val live = mapping.toMutableList()
            while (live.isNotEmpty()) {
                val pick = live.minBy { it.first.sampleTime }
                val (ex, track) = pick
                if (ex.sampleTime < 0) {
                    live.remove(pick)
                    continue
                }
                // A sample larger than the buffer is refused, not truncated,
                // and not every track declares its maximum input size. Grow
                // and read the same sample again.
                val t0 = System.nanoTime()
                val size = try {
                    ex.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    val next = buffer.capacity() * 2
                    check(next <= MAX_SAMPLE) {
                        "A single frame is too large to copy (over ${formatBytes(MAX_SAMPLE.toLong())})"
                    }
                    buffer = ByteBuffer.allocateDirect(next)
                    continue
                }
                if (size < 0) {
                    live.remove(pick)
                    continue
                }
                // A new BufferInfo every sample: with copying off, the muxer
                // keeps this object and builds the index from it at the end.
                // Reusing one made every entry describe the last sample, and
                // the file would not decode.
                val info = MediaCodec.BufferInfo().apply { set(0, size, ex.sampleTime, ex.sampleFlags) }
                val t1 = System.nanoTime()
                muxer.writeSampleData(track, buffer, info)
                val t2 = System.nanoTime()
                ex.advance()
                readNanos += (t1 - t0) + (System.nanoTime() - t2)
                writeNanos += t2 - t1
                copied += size
                // Without this the UI sat on a bare "Merging" for minutes
                // and looked hung.
                if (copied - lastReport > 8L * 1024 * 1024) {
                    lastReport = copied
                    onProgress(copied, expected)
                }
            }
            // close() is what writes the index — a file that is never closed
            // is a pile of samples no player will open.
            val t3 = System.nanoTime()
            muxer.close()
            android.util.Log.i(
                "MagpieMerge",
                "read ${readNanos / 1_000_000} ms, write ${writeNanos / 1_000_000} ms, " +
                    "index ${(System.nanoTime() - t3) / 1_000_000} ms",
            )
            onProgress(expected ?: copied, expected)
        } finally {
            runCatching { muxer.close() }
            inputs.forEach { runCatching { it.release() } }
        }
    }

    /**
     * Gives a VP9 track a `vpcC` it can be trusted with — see [Vp9Config].
     * One already in that form (copied out of an MP4) is left alone.
     */
    private fun describeVp9(ex: MediaExtractor, fmt: MediaFormat) {
        val csd = fmt.getByteBuffer("csd-0")
        if (csd != null && csd.remaining() >= 4 && csd.getInt(csd.position()) == 1 shl 24) return
        val first = ByteBuffer.allocate(fmt.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024))
        // Peeks: nothing advances until the copy loop runs.
        val size = runCatching { ex.readSampleData(first, 0) }.getOrDefault(-1)
        if (size <= 0) return
        val frame = ByteArray(size).also { first.get(it) }
        val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                .getOrElse { fmt.getFloat(MediaFormat.KEY_FRAME_RATE) }
        } else {
            60f // unknown: assume the higher rate, since a level too high is harmless
        }
        val box = Vp9Config.vpcC(
            frame,
            fmt.optInt(MediaFormat.KEY_WIDTH, 0),
            fmt.optInt(MediaFormat.KEY_HEIGHT, 0),
            fps,
            isoColour(fmt),
        ) ?: return
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(box))
    }

    /**
     * Writes [tags] into the finished MP4 in place. The writer puts the index
     * last, so only that box is read, extended and written back; the media in
     * front of it is never touched. Never fails the job — the file plays the
     * same untagged.
     */
    private fun tagMp4(fd: java.io.FileDescriptor, tags: uniffi.magpie_core.Mp4Tags) {
        runCatching {
            val length = android.system.Os.fstat(fd).st_size
            val head = ByteArray(16)
            var at = 0L
            while (at + 8 <= length) {
                readFully(fd, head, at)
                var size = ByteBuffer.wrap(head, 0, 4).int.toLong() and 0xffffffffL
                if (size == 1L) size = ByteBuffer.wrap(head, 8, 8).long
                if (size < 8) return
                if (String(head, 4, 4, Charsets.ISO_8859_1) == "moov") {
                    if (at + size != length) return // not last: growing it would overwrite media
                    val moov = ByteArray(size.toInt())
                    readFully(fd, moov, at)
                    val tagged = uniffi.magpie_core.mp4TagMoov(moov, tags) ?: return
                    var written = 0
                    while (written < tagged.size) {
                        written += android.system.Os.pwrite(fd, tagged, written, tagged.size - written, at + written)
                    }
                    return
                }
                at += size
            }
        }.onFailure { android.util.Log.w("MagpieMerge", "tagging skipped: ${it.message}") }
    }

    private fun readFully(fd: java.io.FileDescriptor, into: ByteArray, at: Long) {
        var read = 0
        while (read < into.size) {
            val n = android.system.Os.pread(fd, into, read, into.size - read, at + read)
            if (n <= 0) error("Short read")
            read += n
        }
    }

    /** The video's thumbnail as cover art for an audio file, if it can be had. */
    private fun fetchCover(job: DownloadJob): ByteArray? = runCatching {
        val url = job.thumbnail?.replace("/mqdefault.", "/hqdefault.") ?: return null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Opus setup rewritten into the form MP4 needs — see [OpusConfig]. */
    private fun describeOpus(fmt: MediaFormat) {
        val csd = fmt.getByteBuffer("csd-0") ?: return
        val head = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        OpusConfig.forMp4(head)?.let { fmt.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
    }

    /** MediaFormat's colour keys as ISO primaries, transfer and matrix codes. */
    private fun isoColour(fmt: MediaFormat): Triple<Int, Int, Int> {
        val standard = fmt.optInt(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        val transfer = fmt.optInt(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        val (primaries, matrix) = when (standard) {
            MediaFormat.COLOR_STANDARD_BT2020 -> 9 to 9
            MediaFormat.COLOR_STANDARD_BT601_PAL -> 5 to 5
            MediaFormat.COLOR_STANDARD_BT601_NTSC -> 6 to 6
            else -> 1 to 1
        }
        val iso = when (transfer) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> 16
            MediaFormat.COLOR_TRANSFER_HLG -> 18
            else -> 1
        }
        return Triple(primaries, iso, matrix)
    }

    private fun MediaFormat.optInt(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    private fun createPending(job: DownloadJob): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, job.fileName)
            put(MediaStore.Downloads.MIME_TYPE, job.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${job.folder}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the output file")
    }

    private fun markReady(uri: Uri) {
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }

    /** Publish into Downloads/<folder> via MediaStore — no storage permission. */
    private fun publish(file: File, job: DownloadJob): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, job.fileName)
            put(MediaStore.Downloads.MIME_TYPE, job.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${job.folder}")
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

        fun subtitleFile(context: Context, id: String): File =
            File(partsDir(context), "dl-$id-s.srt")
    }
}

/**
 * The CDN refused a URL that worked before: a signed link past its expiry,
 * or — on YouTube — one issued to a different IP. Not an IOException: that
 * would read as a dropped connection, and park a DhakaFlix job as paused.
 * YouTube jobs catch it and fetch fresh URLs; everything else fails as before.
 */
class LinkExpiredException : IllegalStateException("This link has expired — fetch the page again")

/**
 * Speed over the last few seconds rather than since the connection opened.
 * An average from the start lags for minutes after a stall or a burst, and on
 * a resumed segmented fetch it counted the chunks already on disk as speed.
 */
internal class RateWindow(private val spanNanos: Long = 3_000_000_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** [at] in nanoseconds, [bytes] cumulative. Returns bytes per second. */
    fun add(at: Long, bytes: Long): Long {
        samples.addLast(at to bytes)
        while (samples.size > 2 && at - samples.first().first > spanNanos) samples.removeFirst()
        val (t0, b0) = samples.first()
        val secs = (at - t0) / 1_000_000_000.0
        return if (secs > 0) ((bytes - b0) / secs).toLong().coerceAtLeast(0) else 0L
    }
}

/**
 * Build a display name MediaStore will accept.
 *
 * Drive titles usually already carry the container extension, so naming
 * naively produced "Class 09 ….mp4 [360p].mp4". The existing extension is
 * dropped before the quality tag is appended.
 */
fun safeFileName(title: String, quality: String, ext: String = "mp4"): String {
    val cleaned = title.replace(Regex("""[/\\:*?"<>|]"""), "-").trim()
    val base = cleaned
        .replace(Regex("""\.(mp4|mkv|mov|m4v|webm|avi|m4a)$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .take(90)
        .ifEmpty { "magpie" }
    return "$base [$quality].$ext"
}

/** "Talk [720p].mp4" + "en" → "Talk [720p].en.srt", which players load with it. */
fun subtitleName(videoName: String, lang: String): String =
    videoName.substringBeforeLast('.') + ".$lang.srt"
