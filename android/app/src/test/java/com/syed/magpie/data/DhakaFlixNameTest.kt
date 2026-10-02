package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DhakaFlixNameTest {

    @Test
    fun `a release folder splits into title, year and tags`() {
        val t = DhakaFlix.describe("Frankenstein (2025) 720p NF [Dual Audio]")
        assertEquals("Frankenstein", t.title)
        assertEquals("2025", t.year)
        assertEquals(listOf("720p", "NF", "Dual Audio"), t.tags)
    }

    @Test
    fun `a series keeps its year range`() {
        val t = DhakaFlix.describe("Crash Landing on You (TV Series 2019–2020) 1080p NF")
        assertEquals("Crash Landing on You", t.title)
        assertEquals("2019–2020", t.year)
        assertEquals(listOf("1080p", "NF"), t.tags)
    }

    @Test
    fun `names without a year stay whole`() {
        val t = DhakaFlix.describe("Season 1")
        assertEquals("Season 1", t.title)
        assertNull(t.year)
        assertNull(DhakaFlix.describe("Movie (Extended Cut) 720p").year)
    }
}
