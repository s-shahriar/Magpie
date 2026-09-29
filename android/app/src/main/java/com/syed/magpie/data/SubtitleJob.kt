package com.syed.magpie.data

import org.json.JSONArray
import org.json.JSONObject

enum class SubtitleStatus {
    QUEUED, RUNNING,
    /** Google asked for a short wait; the countdown is in the status line. */
    WAITING,
    /** The day's free requests are spent; picks up again after the reset. */
    PAUSED,
    COMPLETED, FAILED, STOPPED;

    val active: Boolean get() = this == RUNNING || this == WAITING

    /** Can be sent round again, from the batch it was on. */
    val resumable: Boolean get() = this == PAUSED || this == STOPPED || this == FAILED
}

/**
 * One subtitle file going through the model, as persisted.
 *
 * [source] is Magpie's own copy of the picked file, not the picker's URI, so
 * a job survives a restart and a paused one can continue tomorrow. What each
 * finished batch produced lives beside it, so nothing is asked for twice.
 */
data class SubtitleJob(
    val id: String,
    /** The picked file's name; the saved file is this marked `_translated`. */
    val fileName: String,
    val source: String,
    val model: Gemini.Model,
    val batchSize: Int,
    val cues: Int,
    val batches: Int,
    val doneBatches: Int = 0,
    /** Cues that came back with a hint added. */
    val hinted: Int = 0,
    /** Cues the model lost or altered, left as they were. */
    val skipped: Int = 0,
    val requests: Int = 0,
    val status: SubtitleStatus = SubtitleStatus.QUEUED,
    /** What the row says under its bar: the batch, a countdown, a reason. */
    val line: String? = null,
    val error: String? = null,
    val outputUri: String? = null,
    /** For [SubtitleStatus.PAUSED]: when the engine will try again on its own. */
    val resumeAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val title: String get() = fileName.substringBeforeLast('.')
    val format: SubFormat get() = SubFormat.of(fileName)
    val outputName: String get() = Subtitles.outputName(fileName)
    val progress: Float get() = if (batches == 0) 0f else doneBatches.toFloat() / batches

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("source", source)
        put("model", model.name)
        put("batchSize", batchSize)
        put("cues", cues)
        put("batches", batches)
        put("doneBatches", doneBatches)
        put("hinted", hinted)
        put("skipped", skipped)
        put("requests", requests)
        // Nothing is running when the process starts again; an interrupted
        // job comes back stopped, its finished batches kept, with a resume
        // button beside it.
        put("status", (if (status.active || status == SubtitleStatus.QUEUED) SubtitleStatus.STOPPED else status).name)
        put("line", line ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("outputUri", outputUri ?: JSONObject.NULL)
        put("resumeAt", resumeAt ?: JSONObject.NULL)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = SubtitleJob(
            id = o.getString("id"),
            fileName = o.optString("fileName"),
            source = o.optString("source"),
            model = Gemini.Model.of(o.optString("model")),
            batchSize = o.optInt("batchSize", Subtitles.DEFAULT_BATCH),
            cues = o.optInt("cues"),
            batches = o.optInt("batches"),
            doneBatches = o.optInt("doneBatches"),
            hinted = o.optInt("hinted"),
            skipped = o.optInt("skipped"),
            requests = o.optInt("requests"),
            status = runCatching { SubtitleStatus.valueOf(o.optString("status")) }
                .getOrDefault(SubtitleStatus.STOPPED),
            line = if (o.isNull("line")) null else o.optString("line"),
            error = if (o.isNull("error")) null else o.optString("error"),
            outputUri = if (o.isNull("outputUri")) null else o.optString("outputUri"),
            resumeAt = if (o.isNull("resumeAt")) null else o.optLong("resumeAt"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        fun listToJson(jobs: List<SubtitleJob>): String =
            JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<SubtitleJob> = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/**
 * What a job has got back so far, kept in its own file next to the source
 * copy: which batches are done, the accepted lines by cue number, and the
 * cues still owed a second try.
 */
data class SubtitleProgress(
    val done: Set<Int> = emptySet(),
    val hints: Map<Int, String> = emptyMap(),
    val leftover: Set<Int> = emptySet(),
    /** The final pass over [leftover] has run; what is still there stays skipped. */
    val swept: Boolean = false,
    /** The part of [leftover] owed by a whole skipped batch, not by a refused line. */
    val fromSkips: Set<Int> = emptySet(),
) {
    fun toJson(): String = JSONObject().apply {
        put("done", JSONArray(done.sorted()))
        put("hints", JSONObject().also { o -> hints.forEach { (n, t) -> o.put(n.toString(), t) } })
        put("leftover", JSONArray(leftover.sorted()))
        put("swept", swept)
        put("fromSkips", JSONArray(fromSkips.sorted()))
    }.toString()

    companion object {
        fun fromJson(text: String): SubtitleProgress = runCatching {
            val o = JSONObject(text)
            val done = o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            val left = o.optJSONArray("leftover")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            val h = o.optJSONObject("hints")
            val hints = LinkedHashMap<Int, String>()
            h?.keys()?.forEach { k -> k.toIntOrNull()?.let { hints[it] = h.getString(k) } }
            val skips = o.optJSONArray("fromSkips")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            SubtitleProgress(done.toSet(), hints, left.toSet(), o.optBoolean("swept"), skips.toSet())
        }.getOrDefault(SubtitleProgress())
    }
}
