package com.syed.magpie.data

/**
 * Opus setup data in the form MP4's `dOps` box needs.
 *
 * Android hands over Opus setup as Ogg's `OpusHead`: version 1,
 * little-endian. MP4's `dOps` carries the same fields but as version 0,
 * big-endian. Media3's writer only strips the "OpusHead" magic and copies
 * the rest, so the merged file got an `OpusHead` body where `dOps` belongs —
 * which players refuse outright, video and all. It happened on every VP9 or
 * AV1 YouTube merge, since those pair with Opus audio.
 *
 * So the conversion happens before Media3 sees it, keeping the magic it
 * expects to skip.
 */
object OpusConfig {

    private val MAGIC = "OpusHead".toByteArray()

    /** `OpusHead` (as Android gives it) → "OpusHead" + a `dOps` body. Null if it is not one. */
    fun forMp4(opusHead: ByteArray): ByteArray? {
        if (opusHead.size < 19 || !opusHead.copyOfRange(0, 8).contentEquals(MAGIC)) return null
        val channels = opusHead[9].toInt() and 0xff
        val preSkip = le16(opusHead, 10)
        val rate = le32(opusHead, 12)
        val gain = le16(opusHead, 16)
        val family = opusHead[18].toInt() and 0xff
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(0) // dOps version
        out.write(channels)
        out.write(preSkip shr 8); out.write(preSkip)
        out.write((rate shr 24).toInt()); out.write((rate shr 16).toInt())
        out.write((rate shr 8).toInt()); out.write(rate.toInt())
        out.write(gain shr 8); out.write(gain)
        out.write(family)
        // Stream count, coupled count and the channel map read the same in
        // both forms; only a non-zero family has them.
        if (family != 0) {
            if (opusHead.size < 21 + channels) return null
            out.write(opusHead, 19, 2 + channels)
        }
        return out.toByteArray()
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, at: Int): Long =
        (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)
}
