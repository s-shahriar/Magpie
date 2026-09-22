package com.syed.magpie.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.syed.magpie.service.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * The download queue.
 *
 * A process-wide singleton rather than something owned by a ViewModel: a
 * two-hour lecture must keep going while the user navigates away, and the
 * foreground service needs the same instance the UI is looking at.
 *
 * Pausing cancels the coroutine but leaves the partial file in place; every
 * fetch issues a `Range` request, so resuming continues rather than restarts.
 */
object DownloadEngine {

    private const val MAX_CONCURRENT = 2

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, Job>()
    private val lock = Mutex()

    private lateinit var appContext: Context
    private lateinit var store: DownloadStore
    private lateinit var downloader: Downloader
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        store = DownloadStore(appContext)
        downloader = Downloader(appContext)
        _jobs.value = store.load()
        sweepOrphans()
    }

    // ---- queue control -------------------------------------------------

    fun enqueue(
        sourceUrl: String,
        source: String,
        title: String,
        quality: String,
        renditionId: String,
        videoUrl: String,
        audioUrl: String?,
        fileName: String,
        totalBytes: Long?,
    ) {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            sourceUrl = sourceUrl,
            source = source,
            title = title,
            quality = quality,
            renditionId = renditionId,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            fileName = fileName,
            totalBytes = totalBytes,
        )
        update { it + job }
        pump()
    }

    fun pause(id: String) = scope.launch {
        lock.withLock { running.remove(id) }?.cancelAndJoin()
        patch(id) { it.copy(status = DownloadStatus.PAUSED, stage = "Paused", bytesPerSecond = 0) }
        pump()
    }

    fun resume(id: String) {
        patch(id) { it.copy(status = DownloadStatus.QUEUED, stage = "Queued", error = null) }
        pump()
    }

    /**
     * Removes a job and every byte it was holding.
     *
     * A finished job's saved file in Downloads is deliberately left alone —
     * that is the thing the user asked for. Anything still in flight or paused
     * is scratch, and goes.
     */
    fun cancel(id: String) = scope.launch {
        lock.withLock { running.remove(id) }?.cancelAndJoin()
        partials(id).forEach { it.delete() }
        update { list -> list.filterNot { it.id == id } }
        sweepOrphans()
        pump()
    }

    /** Clears finished rows without touching the saved files. */
    fun clearFinished() {
        update { list -> list.filterNot { it.status == DownloadStatus.COMPLETED } }
    }

    fun pauseAll() {
        _jobs.value.filter { it.status.stoppable }.forEach { pause(it.id) }
    }

    // ---- the pump ------------------------------------------------------

    private fun pump() {
        scope.launch {
            lock.withLock {
                val activeIds = running.keys.toSet()
                if (activeIds.size >= MAX_CONCURRENT) return@withLock
                val next = _jobs.value
                    .filter { it.status == DownloadStatus.QUEUED && it.id !in activeIds }
                    .sortedBy { it.createdAt }
                    .take(MAX_CONCURRENT - activeIds.size)
                next.forEach { job -> running[job.id] = launchJob(job) }
            }
            syncService()
        }
    }

    private fun launchJob(job: DownloadJob) = scope.launch {
        patch(job.id) { it.copy(status = DownloadStatus.DOWNLOADING, stage = "Starting", error = null) }
        try {
            val uri = downloader.run(current(job.id) ?: job) { p ->
                patch(job.id) {
                    it.copy(
                        status = p.status,
                        stage = p.stage,
                        downloadedBytes = p.bytes,
                        totalBytes = p.total ?: it.totalBytes,
                        bytesPerSecond = p.bytesPerSecond,
                    )
                }
            }
            patch(job.id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    stage = "Saved",
                    outputUri = uri.toString(),
                    bytesPerSecond = 0,
                    downloadedBytes = it.totalBytes ?: it.downloadedBytes,
                )
            }
            partials(job.id).forEach { it.delete() }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Throwable) {
            patch(job.id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    stage = "Failed",
                    bytesPerSecond = 0,
                    error = e.message ?: "Download failed",
                )
            }
        } finally {
            lock.withLock { running.remove(job.id) }
            pump()
        }
    }

    // ---- state ---------------------------------------------------------

    private fun current(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun patch(id: String, f: (DownloadJob) -> DownloadJob) {
        update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun update(f: (List<DownloadJob>) -> List<DownloadJob>) {
        val previous = _jobs.value.associate { it.id to it.status }
        val next = f(_jobs.value)
        _jobs.value = next

        // Progress ticks are throttled, but a status change must reach disk
        // immediately. Without this the write that marked a job COMPLETED
        // could be swallowed by the throttle, and the job came back after a
        // restart as PAUSED at 99% — with its file already saved.
        val statusChanged = next.size != previous.size ||
            next.any { previous[it.id] != it.status }
        store.save(next, force = statusChanged)
        syncService()
    }

    /** Foreground service runs exactly while something is in flight. */
    private fun syncService() {
        if (!started) return
        val busy = _jobs.value.any { it.status.active || it.status == DownloadStatus.QUEUED }
        val intent = Intent(appContext, DownloadService::class.java)
        runCatching {
            if (busy) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }

    /**
     * Every scratch file a job can own.
     *
     * The `.chunks` bitmaps belong here too: the segmented fetch preallocates
     * its `.part` to the full size, so a cancelled 1080p job that left these
     * behind would strand a couple of hundred megabytes in app storage.
     */
    internal fun partials(id: String): List<File> = listOf(
        "dl-$id-v.part",
        "dl-$id-v.part.chunks",
        "dl-$id-a.part",
        "dl-$id-a.part.chunks",
        "dl-$id-out.mp4",
    ).map { File(appContext.cacheDir, it) }

    /**
     * Deletes scratch files with no job behind them.
     *
     * Covers anything a crash, a force-stop mid-write, or an older build left
     * behind — none of which route through [cancel].
     */
    private fun sweepOrphans() {
        val live = _jobs.value.map { it.id }.toSet()
        runCatching {
            appContext.cacheDir.listFiles()
                ?.filter { it.name.startsWith("dl-") }
                ?.filterNot { f -> live.any { f.name.startsWith("dl-$it-") } }
                ?.forEach { it.delete() }
        }
    }
}
