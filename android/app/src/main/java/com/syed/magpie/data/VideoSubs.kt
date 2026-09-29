package com.syed.magpie.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Subtitles already inside a video file, pulled out before the module works
 * on them. One path covers every container Android's own demuxer opens —
 * MKV, MP4 and MOV, WebM, TS — because the extractor already knows how to
 * walk each of them; only the reading of the samples is done here.
 *
 * What comes back is SRT, whatever the embedded track was: SubRip, ASS or
 * timed text. Bitmap subtitle streams (PGS, DVB, VobSub) are pictures, not
 * text, and are reported as such rather than pretended at.
 *
 * Timing lives in the container, not the payload, so each cue's end is the
 * next cue's start — the usual conversion — capped at five seconds so one
 * stray gap cannot leave a line on screen for minutes.
 */
object VideoSubs {

    /** One text track a film is carrying. */
    data class TextTrack(
        val index: Int,
        val mime: String,
        val language: String?,
        val title: String?,
        /** Set when this track came out of a Matroska file read natively. */
        val mkv: MkvSubs.Track? = null,
    ) {
        /** "English · SubRip" — what the track chooser shows. */
        val label: String
            get() = mkv?.label ?: listOfNotNull(
                language?.takeIf { it.isNotBlank() }?.uppercase(),
                when {
                    mime.contains("subrip") || mime.contains("srt") -> "SubRip"
                    mime.contains("ass") || mime.contains("ssa") -> "ASS"
                    mime.contains("vtt") -> "WebVTT"
                    mime.contains("tx3g") || mime.contains("3gpp") -> "Timed text"
                    mime.contains("ttml") -> "TTML"
                    else -> null
                },
            ).joinToString(" · ").ifEmpty { "Track ${index + 1}" }
    }

    /** No readable text track in the film. [images] when the ones it has are bitmaps. */
    class NoSubtitles(val images: Boolean) :
        Exception(
            if (images) "The subtitles in this video are images (PGS/DVB), not text"
            else "No readable subtitles in this video — it has none embedded, or only " +
                "closed captions inside the picture"
        )

    private val VIDEO_EXT = setOf(
        "mkv", "mks", "mp4", "m4v", "mov", "webm", "ts", "m2ts", "mts", "avi", "3gp", "ogm", "ogv",
    )

    /** Whether a picked file should be treated as a film to read subtitles from. */
    fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    private fun isMatroskaName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mks", "webm")

    /**
     * Lists the text tracks, reading only the container's headers. Fast.
     *
     * Matroska is read by [MkvSubs] — Android's demuxer does not hand out MKV
     * subtitle tracks, so asking it would answer "none" for a full film.
     */
    fun tracks(context: Context, uri: Uri, fileName: String? = null): List<TextTrack> {
        if (fileName != null && isMatroskaName(fileName)) {
            val head = MkvSubs.headBytes {
                context.contentResolver.openInputStream(uri) ?: error("Could not open that video")
            }
            val all = MkvSubs.readTracks(head)
            val readable = all.filterNot { it.compressed || it.bitmap }
            if (readable.isEmpty()) throw NoSubtitles(images = all.any { it.bitmap })
            // The muxer's default track leads, so the chooser opens on it.
            return readable.sortedByDescending { it.default }.map {
                TextTrack(it.number, it.mime, it.language, it.name, mkv = it)
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val out = mutableListOf<TextTrack>()
            var bitmap = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val text = mime.startsWith("text/") ||
                    listOf("subrip", "ssa", "ass", "vtt", "tx3g", "3gpp", "ttml").any { mime.contains(it) }
                when {
                    text -> out += TextTrack(
                        index = i,
                        mime = mime,
                        language = format.getString(MediaFormat.KEY_LANGUAGE),
                        title = null,
                    )
                    listOf("pgs", "dvbsub", "vobsub", "xsub").any { mime.contains(it) } -> bitmap = true
                }
            }
            if (out.isEmpty()) throw NoSubtitles(images = bitmap)
            return out
        } finally {
            extractor.release()
        }
    }

    /**
     * Reads the whole track and returns it as an SRT file's text. This walks
     * the film end to end, so it is the slow part; [onProgress] reports how
     * far through, when the container says how long it is.
     */
    fun extract(context: Context, uri: Uri, track: TextTrack, onProgress: (Float) -> Unit = {}): String {
        val cues = if (track.mkv != null) readMatroska(context, uri, track.mkv!!, onProgress)
        else readWithExtractor(context, uri, track, onProgress)
        if (cues.isEmpty()) throw NoSubtitles(images = false)
        return buildSrt(cues)
    }

    /** The native Matroska path: one stream over the file, cue by cue. */
    private fun readMatroska(
        context: Context,
        uri: Uri,
        track: MkvSubs.Track,
        onProgress: (Float) -> Unit,
    ): List<MkvSubs.Cue> {
        val size = runCatching {
            context.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        }.getOrDefault(-1L)
        context.contentResolver.openInputStream(uri)?.use { s ->
            return MkvSubs.scan(s, track, size, onProgress)
        }
        error("Could not open that video")
    }

    /** The demuxer path, for the MP4 family. */
    private fun readWithExtractor(
        context: Context,
        uri: Uri,
        track: TextTrack,
        onProgress: (Float) -> Unit,
    ): List<MkvSubs.Cue> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track.index)
            // The container's length lives in the track's own format.
            val trackFormat = extractor.getTrackFormat(track.index)
            val durationUs =
                if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) trackFormat.getLong(MediaFormat.KEY_DURATION)
                else -1L
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            val rows = mutableListOf<Pair<Long, String>>()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val bytes = ByteArray(size)
                buffer.get(0, bytes)
                val text = decode(bytes, track.mime)
                if (text.isNotBlank()) rows += extractor.sampleTime to text
                if (durationUs > 0) {
                    onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                extractor.advance()
            }
            rows.sortBy { it.first }
            // Each cue ends where the next begins, capped at five seconds so
            // one stray gap cannot leave a line on screen for minutes.
            return rows.mapIndexed { i, (start, text) ->
                val next = rows.getOrNull(i + 1)?.first
                MkvSubs.Cue(start, minOf(next ?: (start + 3_000_000), start + 5_000_000), text)
            }
        } finally {
            extractor.release()
        }
    }

    internal fun buildSrt(cues: List<MkvSubs.Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(srtTime(c.start)).append(" --> ").append(srtTime(c.end)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    /**
     * Clears extraction files an earlier build left in the cache. Nothing is
     * written there any more: a film is streamed, never copied, and what is
     * read out of it stays in memory.
     */
    fun sweepCache(context: Context) {
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("extracted_") }
                ?.forEach { it.delete() }
        }
    }

    // ---- payload decoders, pure so they run under JUnit -----------------

    internal fun decode(bytes: ByteArray, mime: String): String = when {
        mime.contains("ass") || mime.contains("ssa") -> assText(String(bytes, Charsets.UTF_8))
        mime.contains("tx3g") || mime.contains("3gpp") -> tx3gText(bytes)
        mime.contains("ttml") -> ttmlText(String(bytes, Charsets.UTF_8))
        // SubRip and WebVTT payloads are plain text; some muxes carry whole
        // blocks, so timing and index lines are dropped defensively.
        else -> plainText(String(bytes, Charsets.UTF_8))
    }

    /** MKV stores an ASS line as nine fields of which the text is the last. */
    internal fun assText(raw: String): String {
        val text = if (raw.count { it == ',' } >= 8) raw.split(",", limit = 9).last() else raw
        return text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
    }

    /** tx3g: a 16-bit style list, then the text to the end of the sample. */
    internal fun tx3gText(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val styleLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val from = 2 + styleLen
        val text = if (from in 0..bytes.size) bytes.copyOfRange(from, bytes.size) else bytes
        return plainText(decodeBommed(text))
    }

    private fun decodeBommed(b: ByteArray): String = when {
        b.size >= 2 && (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ||
            b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) -> String(b, Charsets.UTF_16)
        else -> String(b, Charsets.UTF_8)
    }

    /** Drops SRT/VTT framing lines, in case a payload carries whole blocks. */
    internal fun plainText(raw: String): String =
        raw.lines().filterNot { it.contains("-->") || Regex("^\\s*\\d+\\s*$").matches(it) }
            .joinToString("\n").trim()

    /** TTML samples are XML fragments; the tags go, the words stay. */
    internal fun ttmlText(raw: String): String = raw.replace(Regex("<[^>]*>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    internal fun srtTime(us: Long): String {
        val ms = us / 1000
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms % 3_600_000 / 60_000, ms % 60_000 / 1000, ms % 1000)
    }
}
