package com.syed.magpie.data

import android.content.Context
import java.io.File

/**
 * Persists the queue as JSON in the app's own storage.
 *
 * Writes go through a temp file and an atomic rename: the list is saved on
 * every progress tick, and a process death mid-write would otherwise leave
 * unparseable JSON and lose the whole queue.
 */
class DownloadStore(context: Context) {

    private val file = File(context.filesDir, "downloads.json")
    private val tmp = File(context.filesDir, "downloads.json.tmp")

    @Volatile
    private var lastWrite = 0L

    fun load(): List<DownloadJob> = runCatching {
        if (!file.exists()) emptyList() else DownloadJob.listFromJson(file.readText())
    }.getOrDefault(emptyList())

    /**
     * [force] bypasses the write throttle, for status changes that must not be
     * lost. Progress ticks are throttled — they arrive several times a second
     * and the queue is not worth that much disk churn.
     */
    @Synchronized
    fun save(jobs: List<DownloadJob>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWrite < 1000) return
        lastWrite = now
        runCatching {
            tmp.writeText(DownloadJob.listToJson(jobs))
            tmp.renameTo(file)
        }
    }
}
