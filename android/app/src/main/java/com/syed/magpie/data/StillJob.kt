package com.syed.magpie.data

import org.json.JSONArray
import org.json.JSONObject

enum class StillStatus {
    QUEUED, ENCODING, COMPLETED, FAILED, STOPPED;

    /** Can be sent round again with the settings it already has. */
    val retryable: Boolean get() = this == FAILED || this == STOPPED
}

/**
 * One Still → Video job, as persisted.
 *
 * [photo] is Magpie's own copy of the picked image, not the picker's URI:
 * photo-picker grants lapse with the process, and a job must stay retryable
 * and editable after a restart.
 */
data class StillJob(
    val id: String,
    val title: String,
    val photo: String,
    val seconds: Int,
    val frame: StillFrame,
    val texts: List<TextLayer> = emptyList(),
    val status: StillStatus = StillStatus.QUEUED,
    val progress: Float = 0f,
    val error: String? = null,
    val outputUri: String? = null,
    val sizeBytes: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fileName: String get() = safeFileName(title, "${seconds}s")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("photo", photo)
        put("seconds", seconds)
        put("frame", frame.name)
        put("texts", TextLayer.listToJson(texts))
        // Nothing is encoding when the process starts again; an interrupted
        // job comes back stopped, with a retry button beside it.
        put("status", (if (status == StillStatus.ENCODING) StillStatus.STOPPED else status).name)
        put("error", error ?: JSONObject.NULL)
        put("outputUri", outputUri ?: JSONObject.NULL)
        put("sizeBytes", sizeBytes ?: JSONObject.NULL)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = StillJob(
            id = o.getString("id"),
            title = o.optString("title"),
            photo = o.optString("photo"),
            seconds = o.optInt("seconds", 15),
            frame = runCatching { StillFrame.valueOf(o.optString("frame")) }
                .getOrDefault(StillFrame.Story),
            texts = TextLayer.listFromJson(o.optJSONArray("texts")),
            status = runCatching { StillStatus.valueOf(o.optString("status")) }
                .getOrDefault(StillStatus.STOPPED),
            error = if (o.isNull("error")) null else o.optString("error"),
            outputUri = if (o.isNull("outputUri")) null else o.optString("outputUri"),
            sizeBytes = if (o.isNull("sizeBytes")) null else o.optLong("sizeBytes"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        fun listToJson(jobs: List<StillJob>): String =
            JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<StillJob> = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
