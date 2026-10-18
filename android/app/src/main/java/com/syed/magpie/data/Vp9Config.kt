package com.syed.magpie.data

/**
 * A VP9 `vpcC` box body for streams that arrive without one.
 *
 * MP4 needs `vpcC` to say what a VP9 stream is. A stream copied out of MP4
 * (Facebook's) brings its own and Media3 passes it through, but WebM rarely
 * carries one — YouTube's doesn't — and Android's WebM reader hands Media3 a
 * stand-in it misreads as level 1.0, 4:4:4. A player believes the box over
 * the stream, sets up a decoder for something tiny and differently sampled,
 * and the merged file will not play.
 *
 * So the box is built here from what the stream says about itself: profile,
 * bit depth, subsampling and range from the first keyframe's header, and the
 * level from the picture size and frame rate.
 */
object Vp9Config {

    /**
     * @param keyframe the first sample of the track.
     * @param colour ISO primaries, transfer and matrix codes (1, 1, 1 is BT.709).
     * @return `vpcC` version 1 with its version/flags word first — the form
     *   Media3 copies as is — or null when [keyframe] is not a VP9 keyframe.
     */
    fun vpcC(keyframe: ByteArray, width: Int, height: Int, fps: Float, colour: Triple<Int, Int, Int>): ByteArray? {
        val h = header(keyframe) ?: return null
        val level = level(width.toLong() * height, fps)
        val packed = (h.bitDepth shl 4) or (h.subsampling shl 1) or (if (h.fullRange) 1 else 0)
        return byteArrayOf(
            1, 0, 0, 0, // version 1, flags 0
            h.profile.toByte(),
            level.toByte(),
            packed.toByte(),
            colour.first.toByte(),
            colour.second.toByte(),
            colour.third.toByte(),
            0, 0, // codecInitializationDataSize: always 0 for VP9
        )
    }

    data class Header(val profile: Int, val bitDepth: Int, val subsampling: Int, val fullRange: Boolean)

    /** Reads the uncompressed header of a keyframe (VP9 spec 6.2). */
    fun header(frame: ByteArray): Header? {
        val r = Bits(frame)
        if (r.read(2) != 2) return null // frame_marker
        val low = r.read(1)
        val high = r.read(1)
        val profile = (high shl 1) or low
        if (profile == 3) r.read(1) // reserved_zero
        if (r.read(1) == 1) return null // show_existing_frame
        if (r.read(1) != 0) return null // frame_type: only a keyframe has colour config
        r.read(1) // show_frame
        r.read(1) // error_resilient_mode
        if (r.read(8) != 0x49 || r.read(8) != 0x83 || r.read(8) != 0x42) return null
        val bitDepth = if (profile >= 2) (if (r.read(1) == 1) 12 else 10) else 8
        val colorSpace = r.read(3)
        var fullRange = true
        // 0: 4:2:0 vertical, 2: 4:2:2, 3: 4:4:4.
        var subsampling = 3
        if (colorSpace != CS_RGB) {
            fullRange = r.read(1) == 1
            if (profile == 1 || profile == 3) {
                val x = r.read(1)
                val y = r.read(1)
                subsampling = when {
                    x == 1 && y == 1 -> 0
                    x == 1 -> 2
                    else -> 3
                }
            } else {
                subsampling = 0
            }
        }
        if (r.overrun) return null
        return Header(profile, bitDepth, subsampling, fullRange)
    }

    /**
     * The lowest VP9 level whose picture size and luma sample rate cover the
     * stream. Erring high is harmless; erring low is what broke playback.
     */
    fun level(pictureSize: Long, fps: Float): Int {
        val rate = (pictureSize * fps.coerceAtLeast(1f)).toLong()
        return LEVELS.firstOrNull { (_, maxRate, maxSize) -> rate <= maxRate && pictureSize <= maxSize }
            ?.first ?: 62
    }

    /** Level, max luma sample rate, max luma picture size (VP9 Annex A). */
    private val LEVELS = listOf(
        Triple(10, 829_440L, 36_864L),
        Triple(11, 2_764_800L, 73_728L),
        Triple(20, 4_608_000L, 122_880L),
        Triple(21, 9_216_000L, 245_760L),
        Triple(30, 20_736_000L, 552_960L),
        Triple(31, 36_864_000L, 983_040L),
        Triple(40, 83_558_400L, 2_228_224L),
        Triple(41, 160_432_128L, 2_228_224L),
        Triple(50, 311_951_360L, 8_912_896L),
        Triple(51, 588_251_136L, 8_912_896L),
        Triple(52, 1_176_502_272L, 8_912_896L),
        Triple(60, 1_176_502_272L, 35_651_584L),
        Triple(61, 2_353_004_544L, 35_651_584L),
        Triple(62, 4_706_009_088L, 35_651_584L),
    )

    private const val CS_RGB = 7

    private class Bits(private val data: ByteArray) {
        private var pos = 0
        var overrun = false
            private set

        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = pos / 8
                if (byte >= data.size) {
                    overrun = true
                    return 0
                }
                v = (v shl 1) or ((data[byte].toInt() shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }
    }
}
