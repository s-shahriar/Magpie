package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Names as the DhakaFlix servers actually have them. */
class EpisodesTest {

    private fun e(name: String) = Episodes.Entry(name, "http://x/$name", null)

    private val bb = (1..7).map { e("Breaking Bad S01E%02d  1080p BluRay x265 HEVC ESub [Dual Audio][Hindi 2.0+English 5.1] -MsMod.mkv".format(it)) }

    @Test fun numbers() {
        assertEquals(Episodes.Number(1, 5), Episodes.number(bb[4].name))
        assertEquals(Episodes.Number(2, 10), Episodes.number("Show 2x10 720p.mkv"))
        assertEquals(Episodes.Number(null, 12), Episodes.number("Anime Episode 12 [1080p].mkv"))
        assertNull(Episodes.number("Hopeu.2026.1080p.WEBRip.x264.AAC5.1.mkv"))
        assertNull(Episodes.number("Dune Part Two (2024) 1080p.mkv"))
    }

    @Test fun nextInFolder() {
        val later = Episodes.after(bb, Episodes.number(bb[2].name)!!, "")
        assertEquals(bb[3].name, later.first().name)
        assertEquals(4, later.size)
    }

    @Test fun lastInFolder() {
        assertEquals(0, Episodes.after(bb, Episodes.number(bb[6].name)!!, "").size)
    }

    @Test fun prefersSameRelease() {
        val mixed = listOf(
            e("Show S01E01 720p x264.mkv"), e("Show S01E01 1080p x265.mkv"),
            e("Show S01E02 720p x264.mkv"), e("Show S01E02 1080p x265.mkv"),
        )
        val tpl = "Show # 1080p x265.mkv"
        assertEquals("Show S01E02 1080p x265.mkv", Episodes.after(mixed, Episodes.Number(1, 1), tpl).single().name)
    }

    @Test fun firstOfNextSeason() {
        val s2 = listOf(e("Show S02E02.mkv"), e("Show S02E01.mkv"))
        assertEquals("Show S02E01.mkv", Episodes.after(s2, Episodes.Number(2, 0), "").first().name)
    }

    @Test fun seasons() {
        assertEquals(2, Episodes.seasonOf("Season 2"))
        assertEquals(3, Episodes.seasonOf("Season 3 [Hindi+English]"))
        assertEquals(1, Episodes.seasonOf("S01"))
        assertEquals(
            Episodes.seasonTemplate("Season 1 [Hindi+English]"),
            Episodes.seasonTemplate("Season 2 [Hindi+English]"),
        )
        assert(Episodes.seasonTemplate("Season 2 [Spanish+English]") != Episodes.seasonTemplate("Season 1 [Hindi+English]"))
    }
}
