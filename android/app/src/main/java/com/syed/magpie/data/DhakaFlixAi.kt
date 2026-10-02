package com.syed.magpie.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Which film did I mean?" — the title typed into DhakaFlix, looked up by
 * Gemini with Google Search grounding, and mapped onto the category it would
 * be filed under. Picking a match fills in the category and the year; the
 * search itself is still the user's to start.
 *
 * Uses the same key as the Subtitles module.
 */
object DhakaFlixAi {

    data class Match(
        val title: String,
        val year: String?,
        val industry: String,
        val isSeries: Boolean,
        val language: String,
        /** The DhakaFlix category this would be under, null if none fits. */
        val categoryId: String?,
    )

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    /**
     * Not the Subtitles list: what matters here is Google Search grounding,
     * and the free tier gives the 3.x models no grounding quota at all — a
     * grounded request is refused with a 429 while the same key annotates
     * subtitles happily. Checked 2026-10-02 against a free key.
     */
    enum class Model(val id: String, val label: String) {
        FLASH_35("gemini-3.5-flash", "3.5 Flash"),
        FLASH_25("gemini-2.5-flash", "2.5 Flash · live search"),
    }

    fun identify(apiKey: String, model: Model, query: String): List<Match> {
        if (apiKey.isBlank()) {
            throw IOException("No Gemini API key yet. Add yours in Settings → Gemini.")
        }
        return try {
            parse(ask(apiKey, model, query, grounded = true))
        } catch (e: Gemini.RateLimited) {
            // Grounding refused, not the model: ask again from what the model
            // already knows. Very new releases may be missing, but a title the
            // user half-remembers is still found.
            parse(ask(apiKey, model, query, grounded = false))
        }
    }

    private fun ask(apiKey: String, model: Model, query: String, grounded: Boolean): String {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(query))))),
            )
            .put("generationConfig", JSONObject().put("temperature", 0.1))
        if (grounded) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val conn = URL("$ENDPOINT/${model.id}:generateContent").openConnection() as HttpURLConnection
        val text = try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val reply = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Gemini.classify(code, reply)
            reply
        } finally {
            conn.disconnect()
        }
        return text
    }

    internal fun parse(reply: String): List<Match> {
        val parts = JSONObject(reply).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        // Grounded replies can come in several parts; the array is in one of them.
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (text.isBlank()) throw IOException("Empty response from Gemini")
        val json = Regex("""\[[\s\S]*]""").find(text)?.value ?: throw IOException("Gemini did not return a list")
        val arr = runCatching { JSONArray(json) }.getOrElse { throw IOException("Could not read Gemini's reply") }
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("title").isNotBlank() && it.optString("industry").isNotBlank() && it.optString("type").isNotBlank() }
            .map {
                val industry = it.optString("industry")
                val series = it.optString("type") == "tv_series"
                Match(
                    title = it.optString("title"),
                    // A series comes back as "2019-2020"; the year field wants one year.
                    year = it.opt("year")?.takeUnless { y -> y == JSONObject.NULL }?.toString()
                        ?.let { y -> Regex("\\d{4}").find(y)?.value },
                    industry = industry,
                    isSeries = series,
                    language = it.optString("language"),
                    categoryId = categoryFor(industry, series),
                )
            }
            // Newest first; an unknown year (an ongoing series) goes last.
            .sortedWith(compareByDescending<Match> { it.year?.toIntOrNull() ?: Int.MIN_VALUE })
    }

    fun categoryFor(industry: String, series: Boolean): String? = if (series) {
        when (industry) {
            "Korean" -> "korean_tv_series"
            "Anime" -> "anime_cartoon"
            else -> "tv_web_series"
        }
    } else {
        when (industry) {
            "Hollywood" -> "english_movies"
            "Bollywood" -> "hindi_movies"
            "South Indian" -> "south_indian_movies"
            "Korean" -> "korean_movies"
            "Japanese" -> "japanese_movies"
            "Chinese" -> "chinese_movies"
            "Anime" -> "anime_cartoon"
            "Animation" -> "animation_movies"
            "Other" -> "foreign_movies"
            else -> null
        }
    }

    fun emojiFor(industry: String) = when (industry) {
        "Hollywood" -> "🎬"
        "Bollywood" -> "🎭"
        "South Indian" -> "🌴"
        "Korean" -> "🇰🇷"
        "Japanese" -> "🇯🇵"
        "Chinese" -> "🇨🇳"
        "Anime" -> "✨"
        "Animation" -> "🎨"
        else -> "🌍"
    }

    private fun prompt(query: String) = """
        Search query: "$query"

        1. Extract the film/series title — strip any language or industry context words (e.g. "south indian", "hindi", "telugu", "korean", "dubbed").
        2. Use Google Search to find ALL matching movies/series, including recent releases. If an industry hint was present, list those results first.
        3. Return a JSON array only. Each item:
        { "title": string, "year": string|null, "industry": "Hollywood"|"Bollywood"|"South Indian"|"Korean"|"Japanese"|"Chinese"|"Anime"|"Animation"|"Other", "type": "movie"|"tv_series", "language": string }

        South Indian = Tamil/Telugu/Malayalam/Kannada. Bollywood = Hindi Indian films. Anime = Japanese animation.
        No markdown. No explanation. JSON array only.
    """.trimIndent()
}
