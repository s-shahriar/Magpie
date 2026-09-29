package com.syed.magpie.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Subtitle tracks out of a Matroska file, read straight from the container.
 *
 * Android's own demuxer does not hand out MKV subtitle tracks — a film can
 * carry a perfectly good S_TEXT/UTF8 track and still answer "none" — so this
 * walks the EBML itself: the track list from the head of the file, then one
 * pass over the clusters, keeping the subtitle blocks and stepping over the
 * video and audio interleaved around them. Nothing is buffered but one cue
 * at a time, so it runs at the speed of the storage.
 *
 * Covered: SRT, ASS/SSA and WebVTT text tracks; PGS, DVB and VobSub reported
 * as images rather than read; the muxer's default flag, so a many-tracked
 * film opens on the track meant to be watched; block durations as cue ends;
 * unknown-size segments and clusters, which streamed recordings use; and
 * timestamp scales other than the usual millisecond. Tracks that arrive
 * compressed (header compression) are refused rather than read wrongly.
 *
 * One thing no container walk can reach: closed captions burned into the
 * video picture itself (CEA-608/708). Those are not a subtitle track, and
 * the caller's "nothing found" message says so.
 */
object MkvSubs {

    /** A subtitle track found in the head of the file. */
    data class Track(
        val number: Int,
        val codec: String,
        val language: String?,
        val name: String?,
        /** Picture streams (PGS, DVB, VobSub) are reported, never read. */
        val bitmap: Boolean,
        /** Compressed with Matroska header compression; cannot be read here. */
        val compressed: Boolean,
        /** The muxer's default track, when it flagged one. */
        val default: Boolean,
    ) {
        val mime: String
            get() = when {
                codec.contains("UTF8") || codec.contains("SRT") -> "application/x-subrip"
                codec.contains("SSA") || codec.contains("ASS") -> "text/x-ssa"
                codec.contains("VTT") -> "text/vtt"
                else -> codec
            }

        /** The same "English · SubRip" label the demuxer path shows. */
        val label: String
            get() = listOfNotNull(
                language?.takeIf { it.isNotBlank() }?.uppercase(),
                when {
                    bitmap -> "Images"
                    mime.contains("subrip") -> "SubRip"
                    mime.contains("ssa") -> "ASS"
                    mime.contains("vtt") -> "WebVTT"
                    else -> codec
                },
                if (default) "Default" else null,
            ).joinToString(" · ").ifEmpty { "Track $number" }
    }

    /** One cue with its times settled, in microseconds. */
    data class Cue(val start: Long, val end: Long, val text: String)

    // Matroska element ids this walk needs.
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_VOID = 0xECL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_TAGS = 0x1254C367L
    private const val ID_ATTACH = 0x1941A469L
    private const val ID_CHAPTERS = 0x1043A770L

    private const val ID_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_TRACK_NAME = 0x536EL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_CODEC = 0x86L
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_FLAG_FORCED = 0x55EEL
    private const val ID_CONTENT_ENCODINGS = 0x6D80L

    private const val TYPE_SUBTITLE = 17L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L

    /** Reads the track list from the head of the file. */
    fun readTracks(window: ByteArray): List<Track> {
        val r = ByteReader(window)
        val tracks = mutableListOf<Track>()

        if (r.readId() != ID_EBML) throw IllegalArgumentException("Not a Matroska file")
        r.skipBytes(r.readSize().let { if (it < 0) 0 else it })
        if (r.readId() != ID_SEGMENT) throw IllegalArgumentException("Not a Matroska file")
        val segmentSize = r.readSize() // may be unknown; children follow either way
        val end = if (segmentSize > 0) r.position + segmentSize else Long.MAX_VALUE

        while (r.position < end) {
            val id = try { r.readId() } catch (e: EOF) { break }
            val size = try { r.readSize() } catch (e: EOF) { break }
            when (id) {
                ID_TRACKS -> {
                    if (size > 0) readTrackEntries(r, size, tracks)
                    return tracks
                }
                // Tracks always precede the data; nothing more to learn here.
                ID_CLUSTER -> return tracks
                else -> r.skipBytes(if (size < 0) window.size.toLong() else size)
            }
        }
        return tracks
    }

    private fun readTrackEntries(r: ByteReader, size: Long, out: MutableList<Track>) {
        val end = r.position + size
        while (r.position < end) {
            val id = try { r.readId() } catch (e: EOF) { return }
            val entrySize = try { r.readSize() } catch (e: EOF) { return }
            if (id != ID_TRACK_ENTRY || entrySize < 0) {
                r.skipBytes(if (entrySize < 0) end - r.position else entrySize)
                continue
            }
            val entryEnd = r.position + entrySize
            var number = -1
            var type = -1L
            var codec = ""
            var language: String? = null
            var name: String? = null
            var compressed = false
            var default = false
            while (r.position < entryEnd) {
                val field = try { r.readId() } catch (e: EOF) { break }
                val fieldSize = try { r.readSize() } catch (e: EOF) { break }
                if (fieldSize < 0) break
                when (field) {
                    ID_TRACK_NUMBER -> number = r.readUint(fieldSize.toInt()).toInt()
                    ID_TRACK_TYPE -> type = r.readUint(fieldSize.toInt())
                    ID_CODEC -> codec = r.readText(fieldSize)
                    ID_LANGUAGE -> language = r.readText(fieldSize)
                    ID_TRACK_NAME -> name = r.readText(fieldSize)
                    ID_FLAG_DEFAULT -> default = r.readUint(fieldSize.toInt()) != 0L
                    ID_FLAG_FORCED -> if (r.readUint(fieldSize.toInt()) != 0L) default = true
                    ID_CONTENT_ENCODINGS -> compressed = true.also { r.skipBytes(fieldSize) }
                    else -> r.skipBytes(fieldSize)
                }
            }
            if (type == TYPE_SUBTITLE && number > 0) {
                out += Track(
                    number = number,
                    codec = codec,
                    language = language,
                    name = name,
                    bitmap = codec.contains("PGS") || codec.contains("DVB") || codec.contains("VOBSUB"),
                    compressed = compressed,
                    default = default,
                )
            }
            r.position = entryEnd
        }
    }

    /**
     * One pass over the file, collecting the chosen track's cues with their
     * ends settled. [onProgress] receives bytes-read fractions when the
     * caller knows the file's length.
     */
    fun scan(
        stream: InputStream,
        track: Track,
        fileSize: Long,
        onProgress: (Float) -> Unit = {},
    ): List<Cue> {
        val r = StreamReader(stream)
        val found = mutableListOf<Pair<Long, ByteArray>>() // start µs, payload
        val ownEnds = HashMap<Long, Long>() // start µs → duration µs
        var usPerUnit = 1000L // µs per timestamp unit at the usual scale

        if (r.readId() != ID_EBML) throw IllegalArgumentException("Not a Matroska file")
        r.skipBytes(r.readSize().let { if (it < 0) 0 else it })
        if (r.readId() != ID_SEGMENT) throw IllegalArgumentException("Not a Matroska file")
        val segmentSize = r.readSize()
        val end = if (segmentSize > 0) r.position + segmentSize else Long.MAX_VALUE

        while (r.position < end) {
            val id = try { r.readId() } catch (e: EOF) { break }
            val size = try { r.readSize() } catch (e: EOF) { break }
            when (id) {
                ID_INFO -> {
                    val infoEnd = if (size > 0) r.position + size else continue
                    while (r.position < infoEnd) {
                        val field = try { r.readId() } catch (e: EOF) { break }
                        val fieldSize = try { r.readSize() } catch (e: EOF) { break }
                        if (field == ID_TIMESTAMP_SCALE && fieldSize in 1..8) {
                            // ns per unit → µs per unit; the default is 1 ms.
                            usPerUnit = (r.readUint(fieldSize.toInt()) / 1_000).coerceAtLeast(1L)
                        } else if (fieldSize < 0) break else r.skipBytes(fieldSize)
                    }
                }
                ID_CLUSTER -> readCluster(r, size, track, usPerUnit, fileSize, found, ownEnds, onProgress)
                else -> if (size < 0) break else r.skipBytes(size)
            }
        }

        found.sortBy { it.first }
        return found.mapIndexed { i, (start, payload) ->
            val text = decodePayload(payload, track.codec)
            val next = found.getOrNull(i + 1)?.first
            val endAt = when {
                ownEnds[start] != null -> start + ownEnds.getValue(start) // the muxer said so
                next != null -> minOf(next, start + 5_000_000)
                else -> start + 3_000_000
            }
            Cue(start, endAt, text)
        }.filter { it.text.isNotBlank() }
    }

    private fun readCluster(
        r: StreamReader,
        size: Long,
        track: Track,
        usPerUnit: Long,
        fileSize: Long,
        found: MutableList<Pair<Long, ByteArray>>,
        ownEnds: HashMap<Long, Long>,
        onProgress: (Float) -> Unit,
    ) {
        val clusterEnd = if (size > 0) r.position + size else Long.MAX_VALUE
        var clusterTime = 0L
        while (true) {
            // An unknown-size cluster ends where one of its siblings begins.
            val peeked = r.peekId() ?: return
            if (size < 0 && peeked in LEVEL_ONE) return
            val id = try { r.readId() } catch (e: EOF) { return }
            val childSize = try { r.readSize() } catch (e: EOF) { return }
            if (childSize < 0) return
            when (id) {
                ID_TIMECODE -> clusterTime = r.readUint(childSize.toInt())
                ID_SIMPLE_BLOCK -> readBlock(r, childSize, clusterTime, track, usPerUnit, found, ownEnds)
                ID_BLOCK_GROUP -> {
                    val groupEnd = r.position + childSize
                    var blockTime = -1L
                    while (r.position < groupEnd) {
                        val gid = try { r.readId() } catch (e: EOF) { break }
                        val gsize = try { r.readSize() } catch (e: EOF) { break }
                        if (gsize < 0) break
                        when (gid) {
                            ID_BLOCK -> blockTime = readBlock(r, gsize, clusterTime, track, usPerUnit, found, ownEnds)
                            ID_BLOCK_DURATION -> {
                                // Durations are in the same units as times.
                                val ms = r.readUint(gsize.toInt())
                                if (blockTime >= 0) ownEnds[blockTime] = ms * usPerUnit
                            }
                            else -> r.skipBytes(gsize)
                        }
                    }
                }
                else -> r.skipBytes(childSize)
            }
            if (size > 0 && r.position >= clusterEnd) return
            if (fileSize > 0) onProgress((r.position.toFloat() / fileSize).coerceIn(0f, 1f))
        }
    }

    /** Reads one block, keeping its payload when it is the wanted track. */
    private fun readBlock(
        r: StreamReader,
        size: Long,
        clusterTime: Long,
        track: Track,
        usPerUnit: Long,
        found: MutableList<Pair<Long, ByteArray>>,
        ownEnds: HashMap<Long, Long>,
    ): Long {
        val start = r.position
        val number = r.readDataVint().toInt()
        val timecode = r.readInt16()
        val flags = r.readByte()
        val timeUs = (clusterTime + timecode) * usPerUnit
        if (number == track.number && size > 0) {
            val left = (start + size - r.position).toInt()
            // Lacing never applies to subtitles; a laced "subtitle" block is
            // a sign the track is not what its header claimed.
            if (left > 0 && flags and 0x06 == 0) {
                found += timeUs to r.read(left)
                r.skipBytes(maxOf(0L, start + size - r.position))
                return timeUs
            }
        }
        r.skipBytes(maxOf(0L, start + size - r.position))
        return if (number == track.number) timeUs else -1
    }

    private fun decodePayload(payload: ByteArray, codec: String): String = when {
        codec.contains("SSA") || codec.contains("ASS") ->
            VideoSubs.assText(String(payload, Charsets.UTF_8))
        else -> VideoSubs.plainText(String(payload, Charsets.UTF_8))
    }

    /** The first bytes of the file, wide enough to hold the Tracks element. */
    fun headBytes(want: Int = 8 shl 20, open: () -> InputStream): ByteArray {
        open().use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < want) {
                val r = s.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                total += r
            }
            return out.toByteArray()
        }
    }

    private val LEVEL_ONE = setOf(
        ID_SEEKHEAD, ID_INFO, ID_TRACKS, ID_CLUSTER, ID_CUES, ID_TAGS, ID_ATTACH, ID_CHAPTERS,
    )

    // ---- the byte-level walk -------------------------------------------

    private class EOF : Exception()

    private interface Source {
        val position: Long
        fun readByte(): Int
        fun read(n: Int): ByteArray
        fun skipBytes(n: Long)
        fun readId(): Long
        fun readSize(): Long
        fun readDataVint(): Long
        fun readUint(size: Int): Long
        fun readInt16(): Int
    }

    /** Over a byte window, as the head of the file is parsed. */
    private class ByteReader(private val data: ByteArray) : Source {
        override var position = 0L

        override fun readByte(): Int =
            if (position < data.size) data[position++ .toInt()].toInt() and 0xFF else throw EOF()

        override fun read(n: Int): ByteArray {
            if (position + n > data.size) throw EOF()
            val out = data.copyOfRange(position.toInt(), (position + n).toInt())
            position += n
            return out
        }

        override fun skipBytes(n: Long) { position += n }

        override fun readId(): Long {
            val first = readByte()
            var v = first.toLong()
            repeat(vintLength(first) - 1) { v = (v shl 8) or readByte().toLong() }
            return v
        }

        override fun readSize(): Long {
            val first = readByte()
            val len = vintLength(first)
            var v = (first and ((1 shl (8 - len)) - 1)).toLong()
            var allOnes = v == (1L shl (8 - len)) - 1
            repeat(len - 1) {
                val b = readByte().toLong()
                v = (v shl 8) or b
                if (b != 0xFFL) allOnes = false
            }
            return if (allOnes) -1 else v
        }

        override fun readDataVint(): Long {
            val first = readByte()
            val len = vintLength(first)
            var v = (first and ((1 shl (8 - len)) - 1)).toLong()
            repeat(len - 1) { v = (v shl 8) or readByte().toLong() }
            return v
        }

        override fun readUint(size: Int): Long {
            var v = 0L
            repeat(size) { v = (v shl 8) or readByte().toLong() }
            return v
        }

        override fun readInt16(): Int {
            val hi = readByte(); val lo = readByte()
            return ((hi shl 8) or lo).toShort().toInt()
        }

        fun readText(size: Long): String = String(read(size.toInt()), Charsets.UTF_8)
    }

    /**
     * The same walk over a live stream, skipping without buffering, with a
     * few bytes of pushback so ids can be looked ahead without consuming.
     */
    private class StreamReader(private val stream: InputStream) : Source {
        override var position = 0L
        private val scratch = ByteArray(64 * 1024)
        private val pushback = ArrayDeque<Int>()

        override fun readByte(): Int {
            // Bytes handed back by peekId count as consumed again, so the
            // position stays the truth the skip arithmetic relies on.
            if (pushback.isNotEmpty()) { position++; return pushback.removeFirst() }
            val b = stream.read()
            if (b < 0) throw EOF()
            position++
            return b
        }

        override fun read(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                if (pushback.isNotEmpty()) { out[got++] = pushback.removeFirst().toByte(); continue }
                val r = stream.read(out, got, n - got)
                if (r < 0) throw EOF()
                got += r
            }
            position += n
            return out
        }

        override fun skipBytes(n: Long) {
            var left = n
            while (left > 0) {
                if (pushback.isNotEmpty()) { pushback.removeFirst(); left--; position++; continue }
                val r = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (r < 0) throw EOF()
                left -= r
                position += r
            }
        }

        /** Reads an id without consuming it; null at the end of the stream. */
        fun peekId(): Long? {
            val saved = position
            return try {
                readId()
            } catch (e: EOF) {
                null
            }.also { id ->
                if (id != null) pushBackId(id)
                position = saved
            }
        }

        /** Puts an id back so the next read takes it again. */
        fun pushBackId(id: Long) {
            idBytes(id).forEach { b -> pushback.add(b.toInt() and 0xFF) }
        }

        override fun readId(): Long {
            val first = readByte()
            var v = first.toLong()
            repeat(vintLength(first) - 1) { v = (v shl 8) or readByte().toLong() }
            return v
        }

        override fun readSize(): Long {
            val first = readByte()
            val len = vintLength(first)
            var v = (first and ((1 shl (8 - len)) - 1)).toLong()
            var allOnes = v == (1L shl (8 - len)) - 1
            repeat(len - 1) {
                val b = readByte().toLong()
                v = (v shl 8) or b
                if (b != 0xFFL) allOnes = false
            }
            return if (allOnes) -1 else v
        }

        override fun readDataVint(): Long {
            val first = readByte()
            val len = vintLength(first)
            var v = (first and ((1 shl (8 - len)) - 1)).toLong()
            repeat(len - 1) { v = (v shl 8) or readByte().toLong() }
            return v
        }

        override fun readUint(size: Int): Long {
            var v = 0L
            repeat(size) { v = (v shl 8) or readByte().toLong() }
            return v
        }

        override fun readInt16(): Int {
            val hi = readByte(); val lo = readByte()
            return ((hi shl 8) or lo).toShort().toInt()
        }
    }

    /** Length in bytes of a vint, from its first byte's marker bit. */
    private fun vintLength(first: Int): Int = when {
        first and 0x80 != 0 -> 1
        first and 0x40 != 0 -> 2
        first and 0x20 != 0 -> 3
        first and 0x10 != 0 -> 4
        first and 0x08 != 0 -> 5
        first and 0x04 != 0 -> 6
        first and 0x02 != 0 -> 7
        first and 0x01 != 0 -> 8
        else -> throw EOF()
    }

    /** An element id back as its bytes. */
    private fun idBytes(id: Long): ByteArray {
        var len = 1
        var shifted = id
        while (shifted > 0xFF) { shifted = shifted shr 8; len++ }
        return ByteArray(len) { i -> (id shr (8 * (len - 1 - i))).toByte() }
    }
}
