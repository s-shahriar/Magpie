package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSubsTest {

    // ---- payload decoders ---------------------------------------------

    @Test
    fun `mkv ass payload keeps its commas and unfolds its breaks`() {
        assertEquals(
            "Well, sir,\nworld.",
            VideoSubs.assText("0,0,Default,,0,0,0,,Well, sir,\\Nworld."),
        )
        // A payload without the nine fields is text as it stands.
        assertEquals("Just text", VideoSubs.assText("Just text"))
        assertEquals("a b", VideoSubs.assText("0,0,Default,,0,0,0,,a\\hb"))
    }

    @Test
    fun `tx3g sample with a style list keeps only the text`() {
        val styled = byteArrayOf(0, 12) + ByteArray(12) + "Hello".toByteArray()
        assertEquals("Hello", VideoSubs.tx3gText(styled))
        assertEquals("", VideoSubs.tx3gText(byteArrayOf(0, 0)))
    }

    @Test
    fun `tx3g text in utf16 is decoded through its bom`() {
        val bytes = byteArrayOf(0, 0) + "Hello".toByteArray(Charsets.UTF_16)
        assertEquals("Hello", VideoSubs.tx3gText(bytes))
    }

    @Test
    fun `plain payload drops any srt framing a mux left behind`() {
        assertEquals("Hi there", VideoSubs.plainText("1\n00:00:01,000 --> 00:00:02,000\nHi there"))
        assertEquals("Two\nlines", VideoSubs.plainText("Two\nlines"))
    }

    @Test
    fun `ttml keeps the words and loses the tags`() {
        assertEquals("Hello world", VideoSubs.ttmlText("<p>Hello <i>world</i></p>"))
    }

    @Test
    fun `srt timestamps come out as hours minutes seconds millis`() {
        assertEquals("00:00:00,000", VideoSubs.srtTime(0))
        assertEquals("01:01:02,500", VideoSubs.srtTime(((1 * 3600 + 60 + 2) * 1000 + 500) * 1000L))
    }

    // ---- what counts as a film ----------------------------------------

    @Test
    fun `video extensions are recognised, subtitle ones are not`() {
        assertTrue(VideoSubs.isVideoName("Inception.2010.mkv"))
        assertTrue(VideoSubs.isVideoName("FILM.MP4"))
        assertTrue(VideoSubs.isVideoName("capture.webm"))
        assertFalse(VideoSubs.isVideoName("Inception.srt"))
        assertFalse(VideoSubs.isVideoName("Inception.ass"))
    }
}
