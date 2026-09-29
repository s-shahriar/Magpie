package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {

    private val srtCrlf = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHe was delirious. But asked for you\r\nby name.\r\n\r\n" +
        "2\r\n00:00:03,000 --> 00:00:04,000\r\n[CHILDREN LAUGHING]\r\n\r\n" +
        "3\r\n00:00:05,000 --> 00:00:06,000\r\n<i>That we could get trapped so deep</i>\r\n"

    @Test
    fun `srt with windows endings parses without stray carriage returns`() {
        val f = SubtitleFile.parse(srtCrlf, SubFormat.SRT)
        assertEquals(3, f.count)
        assertEquals("He was delirious. But asked for you\nby name.", f.cues[0].text)
        assertTrue(f.cues.none { it.text.contains('\r') })
    }

    @Test
    fun `render with nothing replaced gives the file back byte for byte`() {
        val f = SubtitleFile.parse(srtCrlf, SubFormat.SRT)
        assertEquals(srtCrlf, f.render(emptyMap()))
    }

    @Test
    fun `render swaps only the replaced cue and keeps the endings`() {
        val f = SubtitleFile.parse(srtCrlf, SubFormat.SRT)
        val out = f.render(mapOf(1 to "He was delirious (প্রলাপ বকা). But asked for you\nby name."))
        assertTrue(out.contains("delirious (প্রলাপ বকা). But asked for you\r\nby name.\r\n"))
        assertTrue(out.contains("[CHILDREN LAUGHING]\r\n"))
        assertEquals(srtCrlf.count { it == '\n' }, out.count { it == '\n' })
    }

    @Test
    fun `vtt keeps its header and note blocks`() {
        val vtt = "WEBVTT\nKind: captions\n\nNOTE made by hand\n\n00:01.000 --> 00:02.000\nHello there\n\nid7\n00:03.000 --> 00:04.000\nSecond <b>line</b>\n"
        val f = SubtitleFile.parse(vtt, SubFormat.VTT)
        assertEquals(listOf("Hello there", "Second <b>line</b>"), f.cues.map { it.text })
        assertEquals(vtt, f.render(emptyMap()))
        assertTrue(f.render(mapOf(2 to "Second <b>line (লাইন)</b>")).contains("id7\n00:03.000 --> 00:04.000\nSecond <b>line (লাইন)</b>\n"))
    }

    @Test
    fun `ass replaces only the dialogue text, commas and all`() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Well, well, well.\n" +
            "Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,ignored\n" +
            "Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\\i1}Obstinate{\\i0}, are we?\n"
        val f = SubtitleFile.parse(ass, SubFormat.ASS)
        assertEquals(listOf("Well, well, well.", "{\\i1}Obstinate{\\i0}, are we?"), f.cues.map { it.text })
        assertEquals(ass, f.render(emptyMap()))
        val out = f.render(mapOf(2 to "{\\i1}Obstinate (একগুঁয়ে){\\i0}, are we?"))
        assertTrue(out.contains("Default,,0,0,0,,{\\i1}Obstinate (একগুঁয়ে){\\i0}, are we?\n"))
        assertTrue(out.contains("Comment: 0,0:00:01.00"))
    }

    @Test
    fun `batches close on the line cap and on the character cap`() {
        val short = List(10) { Cue(it + 1, "abc") }
        assertEquals(listOf(0..3, 4..7, 8..9), Subtitles.cuts(short.map { it.text.length }, maxLines = 4, maxChars = 1000))
        val long = listOf(Cue(1, "x".repeat(60)), Cue(2, "x".repeat(60)), Cue(3, "x".repeat(60)))
        assertEquals(listOf(0..0, 1..1, 2..2), Subtitles.cuts(long.map { it.text.length }, maxLines = 10, maxChars = 100))
        assertEquals(3, Subtitles.batches(long, 10, 100).size)
        assertEquals(1, Subtitles.batches(long, 10, 1000).size)
    }

    @Test
    fun `a tail of a few lines folds into the batch before it`() {
        // 251 lines at 250 a request: one request, not 250 + 1.
        assertEquals(listOf(0..250), Subtitles.cuts(List(251) { 10 }, maxLines = 250))
        // A real batch's worth stays its own.
        assertEquals(listOf(0..249, 250..336), Subtitles.cuts(List(337) { 10 }, maxLines = 250))
        // Not when the character cap would be broken by folding.
        assertEquals(2, Subtitles.cuts(List(251) { 40 }, maxLines = 250, maxChars = 10_000).size)
    }

    @Test
    fun `progress remembers which owed lines came from a skipped batch`() {
        val p = SubtitleProgress(done = setOf(0), leftover = setOf(1, 2, 495), fromSkips = setOf(1, 2))
        val back = SubtitleProgress.fromJson(p.toJson())
        assertEquals(setOf(1, 2), back.fromSkips)
        assertEquals(setOf(1, 2, 495), back.leftover)
    }

    @Test
    fun `output name goes before the extension`() {
        assertEquals("Film.2010-en_translated.srt", Subtitles.outputName("Film.2010-en.srt"))
        assertEquals("noext_translated", Subtitles.outputName("noext"))
    }

    // ---- the acceptance check ----------------------------------------

    @Test
    fun `a line with hints added is accepted`() {
        assertTrue(Hints.accepts("The river bank was steep and treacherous.", "The river bank (নদীর তীর) was steep and treacherous (বিপজ্জনক)."))
        assertTrue(Hints.hasHint("resilient (সহনশীল) parasite"))
    }

    // ---- colour -------------------------------------------------------

    @Test
    fun `srt and vtt hints get the font tag, ass gets its own override`() {
        val line = "He was obstinate (একগুঁয়ে) about leaving."
        assertEquals(
            "He was obstinate <font color=\"#FFFF00\">(একগুঁয়ে)</font> about leaving.",
            Subtitles.colorize(line, Subtitles.HintColor.YELLOW, SubFormat.SRT),
        )
        assertEquals(
            "He was obstinate <font color=\"#FFC400\">(একগুঁয়ে)</font> about leaving.",
            Subtitles.colorize(line, Subtitles.HintColor.GOLD, SubFormat.VTT),
        )
        // ASS wants BGR: FFFF00 becomes 00FFFF, and white is restored after.
        assertEquals(
            "He was obstinate {\\c&H00FFFF&}(একগুঁয়ে){\\c&HFFFFFF&} about leaving.",
            Subtitles.colorize(line, Subtitles.HintColor.YELLOW, SubFormat.ASS),
        )
    }

    @Test
    fun `off leaves the line untouched and two hints are both wrapped`() {
        assertEquals("a (b) c", Subtitles.colorize("a (b) c", Subtitles.HintColor.OFF, SubFormat.SRT))
        val two = "bank (নদীর তীর) and steep (খাড়া)"
        val coloured = Subtitles.colorize(two, Subtitles.HintColor.YELLOW, SubFormat.SRT)
        assertEquals(2, coloured.count { it == '#' })
    }

    @Test
    fun `a coloured line reads back as its plain self`() {
        val plain = "He was obstinate (একগুঁয়ে) about leaving."
        val original = "He was obstinate about leaving."
        val srt = Subtitles.colorize(plain, Subtitles.HintColor.YELLOW, SubFormat.SRT)
        // Tags and hint both go: what is left is the line the film had.
        assertEquals(original, Hints.stripped(srt))
        // The acceptance check holds on a coloured round trip, so a file
        // Magpie coloured can be sent round again without losing lines.
        assertTrue(Hints.accepts(original, srt))
        val ass = Subtitles.colorize(plain, Subtitles.HintColor.CYAN, SubFormat.ASS)
        assertTrue(Hints.accepts(original, ass))
        // Italic overrides survive the stripping; colour ones do not.
        assertEquals("{\\i1}Stay{\\i0}", Hints.stripped("{\\i1}Stay{\\i0} <font color=\"#FFFF00\">(থাকো)</font>"))
    }

    @Test
    fun `an unchanged line is accepted and carries no hint`() {
        assertTrue(Hints.accepts("Hello, how are you?", "Hello, how are you?"))
        assertFalse(Hints.hasHint("Hello (hi), how are you?"))
    }

    @Test
    fun `a merged pair is refused`() {
        val original = "That we could get trapped so deep"
        val merged = "That we could get trapped so deep\nthat when we wound up on the shore\nof our own subconscious (অবচেতন)..."
        assertFalse(Hints.accepts(original, merged))
    }

    @Test
    fun `a dropped tag or changed punctuation is refused`() {
        assertFalse(Hints.accepts("<i>Stay.</i>", "Stay (থাকো)."))
        assertFalse(Hints.accepts("Don't go...", "Don't go…"))
    }

    @Test
    fun `stray carriage returns and doubled spaces are forgiven`() {
        assertTrue(Hints.accepts("He was delirious. But asked for you\r\nby name.", "He was delirious (প্রলাপ). But asked for you\nby  name."))
    }
}
