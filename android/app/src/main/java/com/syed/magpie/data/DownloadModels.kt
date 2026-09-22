package com.syed.magpie.data

import org.json.JSONArray
import org.json.JSONObject

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, MERGING, SAVING, COMPLETED, FAILED;

    val active: Boolean get() = this == DOWNLOADING || this == MERGING || this == SAVING
    val resumable: Boolean get() = this == PAUSED || this == FAILED
    val stoppable: Boolean get() = this == DOWNLOADING || this == QUEUED
}

/**
 * One download, as persisted.
 *
 * Everything needed to resume lives here rather than in memory, so a job
 * survives the process being killed: the stream URLs, how far it got, and
 * where it is going. Facebook and Drive URLs are time-limited, so a job that
 * fails with an expired URL is re-probed from [sourceUrl] rather than retried
 * blindly.
 */
data class DownloadJob(
    val id: String,
    val sourceUrl: String,
    val source: String,
    val title: String,
    val quality: String,
    val renditionId: String,
    val videoUrl: String,
    val audioUrl: String?,
    val fileName: String,
    val totalBytes: Long?,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val stage: String = "Queued",
    val error: String? = null,
    val outputUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float
        get() {
            if (status == DownloadStatus.COMPLETED) return 1f
            val t = totalBytes ?: return 0f
            if (t <= 0) return 0f
            return (downloadedBytes.toFloat() / t).coerceIn(0f, 1f)
        }

    val etaSeconds: Long?
        get() {
            val t = totalBytes ?: return null
            if (bytesPerSecond <= 0 || status != DownloadStatus.DOWNLOADING) return null
            return ((t - downloadedBytes) / bytesPerSecond).coerceAtLeast(0)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sourceUrl", sourceUrl)
        put("source", source)
        put("title", title)
        put("quality", quality)
        put("renditionId", renditionId)
        put("videoUrl", videoUrl)
        put("audioUrl", audioUrl ?: JSONObject.NULL)
        put("fileName", fileName)
        put("totalBytes", totalBytes ?: JSONObject.NULL)
        // A job interrupted mid-flight comes back as paused, never as running:
        // nothing is actually downloading when the process starts again.
        put("status", if (status.active) DownloadStatus.PAUSED.name else status.name)
        put("downloadedBytes", downloadedBytes)
        put("stage", stage)
        put("error", error ?: JSONObject.NULL)
        put("outputUri", outputUri ?: JSONObject.NULL)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DownloadJob(
            id = o.getString("id"),
            sourceUrl = o.optString("sourceUrl"),
            source = o.optString("source"),
            title = o.optString("title"),
            quality = o.optString("quality"),
            renditionId = o.optString("renditionId"),
            videoUrl = o.optString("videoUrl"),
            audioUrl = o.optStringOrNull("audioUrl"),
            fileName = o.optString("fileName"),
            totalBytes = if (o.isNull("totalBytes")) null else o.optLong("totalBytes"),
            status = runCatching { DownloadStatus.valueOf(o.optString("status")) }
                .getOrDefault(DownloadStatus.PAUSED),
            downloadedBytes = o.optLong("downloadedBytes"),
            stage = o.optString("stage", "Paused"),
            error = o.optStringOrNull("error"),
            outputUri = o.optStringOrNull("outputUri"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        fun listToJson(jobs: List<DownloadJob>): String =
            JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<DownloadJob> = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
