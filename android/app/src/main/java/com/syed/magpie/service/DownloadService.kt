package com.syed.magpie.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.syed.magpie.data.DownloadEngine
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive while the app is in the background.
 *
 * Android will freeze or kill an app's work shortly after it leaves the
 * foreground, and these are multi-hundred-megabyte files over a phone
 * connection. The service does no work itself — [DownloadEngine] owns the
 * queue — it exists to hold the process up and to be the voice of the queue in
 * the notification shade: progress while something is running, and a lasting
 * row per file once it is saved or has failed.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notifier: DownloadNotifier

    /**
     * Last seen status per job, so a terminal notification is posted on the
     * transition rather than on every re-emission of the list.
     */
    private var seen: Map<String, DownloadStatus>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
        notifier = DownloadNotifier(this)
        notifier.ensureChannels()
        post(notifier.progress(emptyList()))
        scope.launch {
            DownloadEngine.jobs.collectLatest { jobs ->
                announce(jobs)
                val busy = jobs.any { it.status.active || it.status == DownloadStatus.QUEUED }
                if (busy) {
                    post(notifier.progress(jobs))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** Notification actions arrive here; the engine already guards the rest. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_JOB_ID)
        if (id != null) {
            when (intent.action) {
                ACTION_PAUSE -> DownloadEngine.pause(id)
                ACTION_CANCEL -> DownloadEngine.cancel(id)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Posts one row per job that has just finished, and takes back the row of
     * any job that has left a terminal state — retried, or cleared from the
     * Library — so the shade never contradicts the app.
     *
     * The first emission only seeds the map: jobs restored from disk at launch
     * finished in some earlier session and must not re-announce themselves.
     */
    private fun announce(jobs: List<DownloadJob>) {
        val previous = seen
        val current = jobs.associate { it.id to it.status }
        seen = current
        if (previous == null) return

        // A row removed from the Library takes its notification with it.
        (previous.keys - current.keys).forEach { notifier.clear(it) }

        jobs.forEach { job ->
            val before = previous[job.id]
            if (before == job.status) return@forEach
            when (job.status) {
                DownloadStatus.COMPLETED -> notifier.saved(job)
                DownloadStatus.FAILED -> notifier.failed(job)
                else -> if (before == DownloadStatus.COMPLETED || before == DownloadStatus.FAILED) {
                    notifier.clear(job.id)
                }
            }
        }
    }

    /** minSdk 31, so the typed form is always the right one. */
    private fun post(n: Notification) =
        startForeground(DownloadNotifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    companion object {
        const val EXTRA_JOB_ID = "jobId"
        const val ACTION_PAUSE = "com.syed.magpie.action.PAUSE"
        const val ACTION_CANCEL = "com.syed.magpie.action.CANCEL"
    }
}
