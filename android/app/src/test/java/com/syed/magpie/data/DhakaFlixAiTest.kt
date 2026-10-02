package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DhakaFlixAiTest {

    @Test
    fun `matches map to categories and sort newest first, unknown year last`() {
        val text = """```json
            [{"title":"Squid Game","year":"2021-2025","industry":"Korean","type":"tv_series","language":"Korean"},
             {"title":"Pushpa","year":"2021","industry":"South Indian","type":"movie","language":"Telugu"},
             {"title":"Pushpa 2","year":2024,"industry":"South Indian","type":"movie","language":"Telugu"},
             {"title":"","year":"2020","industry":"Hollywood","type":"movie"},
             {"title":"Odd","year":"2019","industry":"Martian","type":"movie","language":"?"}]
            ```"""
        val body = """{"candidates":[{"content":{"parts":[{"text":${org.json.JSONObject.quote(text)}}]}}]}"""
        val m = DhakaFlixAi.parse(body)
        assertEquals(listOf("Pushpa 2", "Squid Game", "Pushpa", "Odd"), m.map { it.title })
        assertEquals("2024", m[0].year)
        assertEquals("south_indian_movies", m[0].categoryId)
        assertNull(m[3].categoryId)
        assertEquals("korean_tv_series", m[1].categoryId)
        assertEquals("2021", m[1].year)
    }

    @Test
    fun `series fall back to TV and Web Series`() {
        assertEquals("tv_web_series", DhakaFlixAi.categoryFor("Hollywood", series = true))
        assertEquals("anime_cartoon", DhakaFlixAi.categoryFor("Anime", series = true))
        assertEquals("foreign_movies", DhakaFlixAi.categoryFor("Other", series = false))
    }
}
