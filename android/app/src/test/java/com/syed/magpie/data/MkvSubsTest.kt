package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * A tiny Matroska file, built byte by byte: enough of a shell to prove the
 * walk — headers, tracks, interleaved clusters, block groups — without a
 * real film in the way.
 */
class MkvSubsTest {

    // ---- building the bytes ---------------------------------------------

    private fun idBytes(id: Long): ByteArray {
        var len = 1
        var s = id
        while (s > 0xFF) { s = s shr 8; len++ }
        return ByteArray(len) { i -> (id shr (8 * (len - 1 - i))).toByte() }
    }

    private fun sizeBytes(n: Int): ByteArray {
        var len = 1
        while (n >= 1 shl (7 * len)) len++
        val out = ByteArray(len)
        var v = n
        for (i in len - 1 downTo 0) { out[i] = (v and 0xFF).toByte(); v = v shr 8 }
        // The marker sits above the value: 1-byte sizes carry 0x80, two-byte 0x40…
        out[0] = (out[0].toInt() or (1 shl (8 - len))).toByte()
        return out
    }

    private fun el(id: Long, payload: ByteArray): ByteArray = idBytes(id) + sizeBytes(payload.size) + payload

    /** A uint element at its narrowest width; cluster timestamps need two. */
    private fun uintEl(id: Long, v: Long): ByteArray {
        var len = 1
        var x = v
        while (x > 0xFF) { x = x shr 8; len++ }
        val payload = ByteArray(len) { i -> (v shr (8 * (len - 1 - i))).toByte() }
        return el(id, payload)
    }

    private fun textEl(id: Long, s: String): ByteArray = el(id, s.toByteArray())
    private fun unknownSizeEl(id: Long, payload: ByteArray): ByteArray =
        idBytes(id) + byteArrayOf(0xFF.toByte()) + payload

    /** A block's payload: data-vint track number, int16 timecode, flags, data. */
    private fun block(track: Int, timecode: Int, data: ByteArray, flags: Int = 0): ByteArray {
        val head = byteArrayOf(
            (0x80 or track).toByte(),
            ((timecode shr 8) and 0xFF).toByte(),
            (timecode and 0xFF).toByte(),
            flags.toByte(),
        )
        return head + data
    }

    private fun film(segment: (ByteArray) -> ByteArray): ByteArray {
        val ebml = el(0x1A45DFA3L, uintEl(0x4286L, 1)) // EBML, DocTypeVersion 1
        val body = segment(ByteArray(0))
        return ebml + body
    }

    private fun tracks(subCodec: String = "S_TEXT/UTF8", extra: ByteArray = ByteArray(0)): ByteArray =
        el(0x1654AE6BL,
            el(0xAEL, uintEl(0xD7L, 1) + uintEl(0x83L, 1) + textEl(0x86L, "V_MPEG4/ISO/AVC")) +
                el(0xAEL, uintEl(0xD7L, 3) + uintEl(0x83L, 17) + textEl(0x86L, subCodec) +
                    textEl(0x22B59CL, "eng") + uintEl(0x88L, 1) + extra)
        )

    private fun info(scaleNs: Long = 1_000_000): ByteArray =
        el(0x1549A966L, el(0x2AD7B1L, byteArrayOf(
            (scaleNs shr 16).toByte(), (scaleNs shr 8).toByte(), scaleNs.toByte())))

    private fun cluster(timecode: Int, vararg children: ByteArray): ByteArray =
        el(0x1F43B675L, uintEl(0xE7L, timecode.toLong()) + (children.reduceOrNull { a, b -> a + b } ?: ByteArray(0)))

    // ---- the track list -------------------------------------------------

    @Test
    fun `subtitle track is found with codec language and default flag`() {
        val bytes = film { seg -> el(0x18538067L, info() + tracks() + cluster(0)) }
        val found = MkvSubs.readTracks(bytes)
        assertEquals(1, found.size)
        val t = found.first()
        assertEquals(3, t.number)
        assertEquals("S_TEXT/UTF8", t.codec)
        assertEquals("eng", t.language)
        assertTrue(t.default)
        assertTrue(!t.bitmap && !t.compressed)
        assertEquals("ENG · SubRip · Default", t.label)
    }

    @Test
    fun `pgs and compressed tracks are marked, not read`() {
        val pgs = film { seg -> el(0x18538067L, tracks("S_HDMV/PGS")) }
        assertEquals(true, MkvSubs.readTracks(pgs).first().bitmap)
        val zipped = film { seg ->
            el(0x18538067L, tracks(extra = el(0x6D80L, uintEl(0x1034L, 3).drop(1).toByteArray())))
        }
        assertEquals(true, MkvSubs.readTracks(zipped).first().compressed)
    }

    @Test
    fun `a file that is not matroska is refused`() {
        val threw = try { MkvSubs.readTracks("not a matroska file at all".toByteArray()); false }
        catch (e: IllegalArgumentException) { true }
        assertTrue(threw)
    }

    // ---- the scan -------------------------------------------------------

    @Test
    fun `cues come out with cluster and block times, skipping other tracks`() {
        val junk = ByteArray(500) { 0x55 }
        val bytes = film { seg ->
            el(0x18538067L,
                info() + tracks() +
                    cluster(0,
                        el(0xA3L, block(1, 0, junk)),                       // video, stepped over
                        el(0xA3L, block(3, 1000, "Hello\nworld".toByteArray())),
                    ) +
                    cluster(5000,
                        el(0xA3L, block(1, 2000, junk)),
                        el(0xA3L, block(3, 0, "Bye".toByteArray())),
                        el(0xA0L,                                     // block group with a duration
                            el(0xA1L, block(3, 2500, "Late".toByteArray())) +
                                uintEl(0x9BL, 1200)
                        ),
                    ),
            )
        }
        val cues = MkvSubs.scan(ByteArrayInputStream(bytes), MkvSubs.readTracks(bytes).first(), bytes.size.toLong())
        assertEquals(3, cues.size)
        assertEquals(1_000_000L, cues[0].start)                 // (0 + 1000) ms
        assertEquals("Hello\nworld", cues[0].text)
        assertEquals(5_000_000L, cues[0].end)                   // next cue's start
        assertEquals(5_000_000L, cues[1].start)
        assertEquals("Bye", cues[1].text)
        assertEquals(7_500_000L, cues[2].start)                 // (5000 + 2500) ms
        assertEquals(8_700_000L, cues[2].end)                   // own duration 1200 ms
    }

    @Test
    fun `an unknown-size segment walks to the end of the stream`() {
        val junk = ByteArray(200) { 0x33 }
        val bytes = film { seg ->
            unknownSizeEl(0x18538067L,
                info() + tracks() +
                    cluster(0, el(0xA3L, block(3, 500, "Streaming".toByteArray()))) +
                    cluster(9000, el(0xA3L, block(1, 0, junk)), el(0xA3L, block(3, 0, "Out".toByteArray()))),
            )
        }
        val cues = MkvSubs.scan(ByteArrayInputStream(bytes), MkvSubs.readTracks(bytes).first(), bytes.size.toLong())
        assertEquals(listOf("Streaming", "Out"), cues.map { it.text })
        assertEquals(500_000L, cues[0].start)
    }

    @Test
    fun `ass payload keeps only the dialogue text`() {
        val bytes = film { seg ->
            el(0x18538067L, info() + tracks("S_TEXT/ASS") +
                cluster(0, el(0xA3L, block(3, 0, "5,0,Default,,0,0,0,,Well,\\Nworld.".toByteArray()))))
        }
        val cues = MkvSubs.scan(ByteArrayInputStream(bytes), MkvSubs.readTracks(bytes).first(), bytes.size.toLong())
        assertEquals("Well,\nworld.", cues.first().text)
    }

    @Test
    fun `a different timestamp scale is honoured`() {
        val bytes = film { seg ->
            el(0x18538067L, info(scaleNs = 100_000) + tracks() +   // 100 µs units
                cluster(10_000, el(0xA3L, block(3, 0, "Ticks".toByteArray()))))
        }
        val cues = MkvSubs.scan(ByteArrayInputStream(bytes), MkvSubs.readTracks(bytes).first(), bytes.size.toLong())
        assertEquals(1_000_000L, cues.first().start)               // 10 000 × 100 µs
    }
}
