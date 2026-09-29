package com.syed.magpie.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTest {

    @Test
    fun `prompt carries every cue with its number and asks for the same back`() {
        val p = Gemini.prompt(listOf(Cue(1049, "That we could get trapped so deep"), Cue(1050, "that when we wound up")))
        assertTrue(p.contains("\"n\":1049"))
        assertTrue(p.contains("\"n\":1050"))
        assertTrue(p.contains("Return EXACTLY 2 items"))
        assertTrue(p.contains("NEVER merge two numbered items"))
        assertTrue(p.contains("SKIP common everyday words"))
    }

    @Test
    fun `reply parses through fences and keeps only the numbers sent`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"```json\n[{\"n\":5,\"t\":\"Hi (হাই)\"},{\"n\":6,\"t\":\"Bye\"},{\"n\":99,\"t\":\"noise\"},{\"n\":5,\"t\":\"dup\"}]\n```"}]}}],
            "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20}}"""
        val r = Gemini.parseReply(body, setOf(5, 6, 7), 10)
        assertEquals(mapOf(5 to "Hi (হাই)", 6 to "Bye"), r.lines)
        assertEquals(100, r.promptTokens)
        assertEquals(20, r.responseTokens)
    }

    @Test(expected = Gemini.BadReply::class)
    fun `an empty candidate is a bad reply`() {
        Gemini.parseReply("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""", setOf(1), 0)
    }

    @Test
    fun `a per-minute 429 waits for what google says`() {
        val body = """{"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"12.5s"}]}}"""
        val e = Gemini.classify(429, body) as Gemini.RateLimited
        assertFalse(e.daily)
        assertEquals(12_500L, e.retryAfterMs)
    }

    @Test
    fun `a daily 429 is a pause, by quota id or by wording`() {
        val byId = """{"error":{"code":429,"message":"quota exceeded","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
        assertTrue((Gemini.classify(429, byId) as Gemini.RateLimited).daily)
        val byWords = """{"error":{"code":429,"message":"You have exceeded your daily quota. Please try again tomorrow."}}"""
        assertTrue((Gemini.classify(429, byWords) as Gemini.RateLimited).daily)
    }

    @Test
    fun `high demand and 5xx are transient, a bad key is not`() {
        assertTrue(Gemini.classify(503, """{"error":{"code":503,"message":"This model is currently experiencing high demand."}}""") is Gemini.Overloaded)
        assertTrue(Gemini.classify(500, "") is Gemini.Overloaded)
        val bad = Gemini.classify(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}""")
        assertTrue(bad is Gemini.Rejected)
        assertEquals("Google rejected the API key", bad.message)
    }
}
