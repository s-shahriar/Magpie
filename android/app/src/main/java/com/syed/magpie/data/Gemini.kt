package com.syed.magpie.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one call the Subtitles module makes: a batch of numbered lines out,
 * the same lines back with Bengali hints added.
 *
 * Plain HttpURLConnection and org.json, like every other client in the app,
 * and no Android types so the request and reply parsing can be tested on
 * the desktop against a real key.
 *
 * Errors are typed by what the engine should do about them: wait, stop for
 * the day, try again, or give up.
 */
object Gemini {

    enum class Model(val id: String, val label: String, val detail: String) {
        FLASH_LITE(
            "gemini-3.5-flash-lite",
            "Gemini 3.5 Flash-Lite",
            "Recommended · fast, and the free tier allows the most requests a day",
        ),
        FLASH(
            "gemini-3.8-flash",
            "Gemini 3.8 Flash",
            "Best quality · the free tier allows only a handful of requests a day",
        );

        /** "Flash-Lite", "Flash": enough to tell two rows of the same film apart. */
        val short: String get() = label.removePrefix("Gemini ").substringAfter(' ')

        companion object {
            fun of(name: String?): Model = entries.firstOrNull { it.name == name } ?: FLASH_LITE
        }
    }

    /** HTTP 429. [daily] means the day's allowance is gone, not just this minute's. */
    class RateLimited(message: String, val retryAfterMs: Long, val daily: Boolean) : IOException(message)

    /** HTTP 5xx, or Google's "high demand" reply: transient, worth a retry. */
    class Overloaded(message: String) : IOException(message)

    /** Any other 4xx: a bad key, a model this key cannot use. Retrying will not help. */
    class Rejected(message: String) : IOException(message)

    /** A 200 whose body could not be read as the numbered array asked for. */
    class BadReply(message: String) : IOException(message)

    data class Reply(
        /** Returned lines by cue number; only numbers that were sent are kept. */
        val lines: Map<Int, String>,
        val promptTokens: Int,
        val responseTokens: Int,
        val millis: Long,
    )

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    /** The wall-clock moment the free tier's daily counters reset: midnight Pacific. */
    fun nextDailyReset(): Long {
        val zone = ZoneId.of("America/Los_Angeles")
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * [onConnection] hands the open connection to the caller before the
     * blocking read, so a Stop can disconnect it: a read in progress does not
     * notice coroutine cancellation, and would otherwise hold Stop up for as
     * long as the model takes to answer.
     */
    fun annotate(
        apiKey: String,
        model: Model,
        cues: List<Cue>,
        onConnection: (HttpURLConnection) -> Unit = {},
    ): Reply {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(cues))))),
        )
        val started = System.currentTimeMillis()
        val conn = URL("$ENDPOINT/${model.id}:generateContent").openConnection() as HttpURLConnection
        onConnection(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            // A thinking model on a 400-line batch can take a while; the
            // engine's own timeout is the retry policy, not this socket.
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw classify(code, text)
            return parseReply(text, cues.map { it.n }.toSet(), System.currentTimeMillis() - started)
        } finally {
            conn.disconnect()
        }
    }

    // ---- the reply -----------------------------------------------------

    internal fun parseReply(body: String, wanted: Set<Int>, millis: Long): Reply {
        val root = runCatching { JSONObject(body) }.getOrElse { throw BadReply("Not JSON") }
        root.optJSONObject("error")?.let { throw classify(it.optInt("code", 500), body) }
        val usage = root.optJSONObject("usageMetadata")
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        if (text.isBlank()) {
            val why = root.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")
            throw BadReply(if (why.isNullOrBlank()) "Empty reply" else "Empty reply ($why)")
        }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) throw BadReply("No array in the reply")
        val arr = runCatching { JSONArray(text.substring(start, end + 1)) }
            .getOrElse { e ->
                // The position org.json names is what tells a truncated reply
                // from a stray character, so it goes in the message.
                val at = Regex("""at (\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                val near = at?.let { text.substring(maxOf(0, start + it - 60), minOf(text.length, start + it + 60)) }
                throw BadReply("Reply array would not parse: ${e.message}" + (near?.let { " near «${it.replace("\n", "⏎")}»" } ?: ""))
            }
        val lines = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val n = item.optInt("n", -1)
            val t = item.optString("t", "")
            // A number that was never sent, or one sent back twice, is noise.
            if (n in wanted && n !in lines) lines[n] = t
        }
        if (lines.isEmpty()) throw BadReply("Reply carried no numbered lines")
        return Reply(
            lines = lines,
            promptTokens = usage?.optInt("promptTokenCount") ?: 0,
            responseTokens = usage?.optInt("candidatesTokenCount") ?: 0,
            millis = millis,
        )
    }

    // ---- errors --------------------------------------------------------

    internal fun classify(code: Int, body: String): IOException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP $code"
        val details = error?.optJSONArray("details")
        if (code == 429) {
            var retryAfterMs = 30_000L
            var daily = false
            if (details != null) for (i in 0 until details.length()) {
                val d = details.optJSONObject(i) ?: continue
                when (d.optString("@type").substringAfterLast('.')) {
                    "RetryInfo" -> parseSeconds(d.optString("retryDelay"))?.let { retryAfterMs = (it * 1000).toLong() }
                    "QuotaFailure" -> {
                        val v = d.optJSONArray("violations")
                        if (v != null) for (j in 0 until v.length()) {
                            val id = v.optJSONObject(j)?.optString("quotaId").orEmpty()
                            if (id.contains("PerDay", ignoreCase = true)) daily = true
                        }
                    }
                }
            }
            // Google words the two the same way in the message; the quota id,
            // a "per day" phrase, or a wait past the next few minutes all mean
            // the day is spent.
            if (Regex("per day|daily", RegexOption.IGNORE_CASE).containsMatchIn(message)) daily = true
            if (retryAfterMs > 10 * 60_000L) daily = true
            return RateLimited(message, retryAfterMs, daily)
        }
        if (code >= 500 || message.contains("high demand", ignoreCase = true)) return Overloaded(message)
        return Rejected(
            when (code) {
                400 -> if (message.contains("API key", ignoreCase = true)) "Google rejected the API key" else message
                401, 403 -> "Google rejected the API key"
                404 -> "This model is not available to your key"
                else -> message
            },
        )
    }

    private fun parseSeconds(s: String): Double? =
        Regex("""([\d.]+)s?""").matchEntire(s.trim())?.groupValues?.get(1)?.toDoubleOrNull()

    // ---- the prompt ----------------------------------------------------

    /**
     * Word for word the prompt the SRT Bengali app settled on, with one
     * change: the lines carry their numbers. A bare array let the model
     * "fix" a sentence split across two cues by merging them, and the reply
     * then came back one short — every time, for that batch. Numbered items
     * make a merge show up as one missing number instead.
     */
    internal fun prompt(cues: List<Cue>): String {
        val n = cues.size
        val input = JSONArray().apply {
            cues.forEach { put(JSONObject().put("n", it.n).put("t", it.text)) }
        }.toString()
        return """You are helping a Bengali speaker learn English through movie subtitles.

TASK: Return the same $n numbered subtitle lines with Bengali translations added after uncommon English words.

════════════════════════════════════════
⚠ ABSOLUTE RULES — NEVER BREAK THESE:
════════════════════════════════════════
1. Return EXACTLY $n items in the JSON array, each as {"n": <the same number>, "t": "<the line>"}. Every n from the input appears exactly once. No more, no fewer.
2. NEVER merge two numbered items into one, even when they read as a single sentence — the split is the film's timing and must stay. NEVER split one item into two. Do NOT reorder.
3. Do NOT add or remove newline characters (\n) inside any subtitle line.
4. Do NOT change punctuation, spacing, capitalization, or any existing text.
5. Preserve ALL formatting tags exactly as they appear: <i>, </i>, <b>, </b>, {\i1}, {\i0}, etc.
6. If a line has no uncommon words → return it COMPLETELY UNCHANGED.

════════════════════════════════════════
ANNOTATION RULES:
════════════════════════════════════════
- Format: word (বাংলা অর্থ)  — placed immediately after the English word
- Only annotate B2–C1 level vocabulary: words a Bengali adult with 2–3 years of English would NOT know
- SKIP common everyday words — including but not limited to: is, was, the, have, go, come, get, make, said, good, bad, want, know, like, see, look, feel, tell, need, just, very, also, then, when, that, this, with, from, they, what, who, beautiful, truth, guest, believe, appear, serious, possible, ordinary, youth, identify, report, searching, yet, seems, kindly, reality, simple, bigger, challenging, total, correct, perfect, special, normal, allow, agree, admit, accept, avoid, begin, call, carry, catch, cause, choose, consider, continue, create, decide, depend, describe, discover, dream, enjoy, exist, expect, explain, fail, fall, follow, forget, happen, help, hope, imagine, include, involve, keep, lead, learn, leave, let, lose, love, manage, miss, move, offer, open, pay, play, prepare, prevent, produce, prove, provide, reach, realize, receive, remain, remember, remove, require, result, return, run, seem, send, set, show, sit, spend, stand, start, stay, stop, support, suppose, suggest, turn, understand, use, wait, win, wish, work, write, honestly, usually, typically, however, clearly, finally, basically, definitely, absolutely, literally, probably, technically, obviously, apparently, certainly, eventually, seriously, extremely, entirely, suddenly, properly, recently, attractive, romantic, miserable, lonely, dramatic, comfortable, uncomfortable, confusing, aggressive, mysterious, strict, decent, selfish, brave, proud, jealous, handsome, intense, weird, strange, disgusting, embarrassed, annoyed, focused, complicated, practical, impossible, incredible, depressing, violent, shocking, grateful, desperate, angry, guilty, nervous, obvious, familiar, independent, convinced, arrived, departing, ignore, pretend, recommend, complain, protect, deserve, appreciate, guarantee, mention, discuss, compare, propose, compete, remind, assume, announce, engage, burst, trick, spoil, approve, permission, decision, situation, generation, collection, responsibility, improvement, possibility, obligation, circumstance, distraction, competition, experiment, surgery, funeral, apartment, basement, closet, garage, angel, captain, celebrity, client, soul, event, gender, biology, favor, prize, secret, accent, context, trauma, cancer, vacation, paradise, nightmare, policy, bonds, toast, proposal, etc.
- ONLY annotate words like: perpetrator, clasp, nuance, flaunting, hesitation, rarity, coax, smudge, ploy, endured, obstinate, treacherous, mangled, desperation, compensation, deteriorate, surveillance, concealed, retaliate, extortion, etc.
- Use sentence context for the correct Bengali meaning — "bank" near "river" = নদীর তীর, not ব্যাংক
- Keep Bengali translation short: 1–3 Bengali words max
- Annotate each word only on its first occurrence per line
- PHRASAL VERBS (rule out, give up, hold on, look into, etc.): place the annotation AFTER the COMPLETE phrasal verb, never in the middle. Example — WRONG: "rule out (বাদ দেওয়া) it out" / CORRECT: "rule it out (বাদ দেওয়া)" — or skip the phrasal verb entirely if unsure.

EXAMPLE:
Input:  [{"n":12,"t":"She was obstinate about leaving."},{"n":13,"t":"Hello, how are you?"},{"n":14,"t":"The river bank was steep and treacherous."}]
Output: [{"n":12,"t":"She was obstinate (একগুঁয়ে) about leaving."},{"n":13,"t":"Hello, how are you?"},{"n":14,"t":"The river bank (নদীর তীর) was steep and treacherous (বিপজ্জনক)."}]

INPUT ($n numbered subtitle lines as a JSON array):
$input

Respond with ONLY the JSON array. No explanation. No markdown code fences."""
    }
}
