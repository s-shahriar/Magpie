package com.syed.magpie.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Vp9ConfigTest {

    /** The first 12 bytes of a real YouTube 1080p VP9 keyframe (itag 248). */
    private val youtube1080 = byteArrayOf(
        0x82.toByte(), 0x49, 0x83.toByte(), 0x42, 0x40, 0x77, 0xf0.toByte(), 0x43, 0x74, 0x10, 0x26, 0x10,
    )

    @Test
    fun readsAProfile0Keyframe() {
        assertEquals(Vp9Config.Header(profile = 0, bitDepth = 8, subsampling = 0, fullRange = false), Vp9Config.header(youtube1080))
    }

    @Test
    fun readsAProfile2HdrKeyframe() {
        // frame_marker 10, profile 2 (low 0, high 1), key, shown; sync; ten bit; BT.2020; studio.
        val frame = byteArrayOf(0x92.toByte(), 0x49, 0x83.toByte(), 0x42, 0x50, 0, 0, 0)
        assertEquals(Vp9Config.Header(profile = 2, bitDepth = 10, subsampling = 0, fullRange = false), Vp9Config.header(frame))
    }

    @Test
    fun refusesWhatIsNotAKeyframe() {
        assertNull(Vp9Config.header(byteArrayOf(0x86.toByte(), 0, 0, 0))) // inter frame
        assertNull(Vp9Config.header(byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x41, 0x40))) // bad sync
        assertNull(Vp9Config.header(byteArrayOf(0x82.toByte())))
    }

    @Test
    fun levelsCoverThePicture() {
        assertEquals(40, Vp9Config.level(1920L * 1080, 30f))
        assertEquals(41, Vp9Config.level(1920L * 1080, 60f))
        assertEquals(50, Vp9Config.level(2560L * 1440, 30f))
        assertEquals(51, Vp9Config.level(3840L * 2160, 60f))
        assertEquals(10, Vp9Config.level(256L * 144, 15f))
    }

    @Test
    fun buildsTheBoxMedia3CopiesAsIs() {
        val box = Vp9Config.vpcC(youtube1080, 1920, 1080, 30f, Triple(1, 1, 1))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 40, 0x80.toByte(), 1, 1, 1, 0, 0), box)
    }
}
