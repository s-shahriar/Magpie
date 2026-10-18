package com.syed.magpie.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpusConfigTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun stereoOpusHeadBecomesBigEndianVersion0() {
        // YouTube's itag 251 as Android reports it: version 1, 2 channels,
        // pre-skip 312, 48000 Hz, gain 0, family 0 — all little-endian.
        val head = "OpusHead".toByteArray() + hex("0102380180bb0000000000")
        assertArrayEquals(
            "OpusHead".toByteArray() + hex("0002013800" + "00bb80" + "000000"),
            OpusConfig.forMp4(head),
        )
    }

    @Test
    fun surroundKeepsItsChannelMap() {
        // 6 channels, family 1: four streams, two coupled, then the map.
        val head = "OpusHead".toByteArray() + hex("0106380180bb0000" + "0000" + "01" + "0402" + "000401020305")
        assertArrayEquals(
            "OpusHead".toByteArray() + hex("0006013800" + "00bb80" + "0000" + "01" + "0402" + "000401020305"),
            OpusConfig.forMp4(head),
        )
    }

    @Test
    fun refusesWhatIsNotOpusHead() {
        assertNull(OpusConfig.forMp4(hex("0102380180bb0000000000")))
        assertNull(OpusConfig.forMp4("OpusHead".toByteArray() + hex("01")))
    }
}
