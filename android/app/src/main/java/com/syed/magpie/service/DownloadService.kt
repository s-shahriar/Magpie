package com.syed.magpie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.syed.magpie.MainActivity
import com.syed.magpie.R
import com.syed.magpie.data.DownloadEngine
import com.syed.magpie.data.DownloadStatus
import com.syed.magpie.data.formatBytes
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
 * queue — it exists to hold the process up and show progress.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadEngine.init(this)
        createChannel()
        startForegroundCompat(build(null))
        scope.launch {
            DownloadEngine.jobs.collectLatest { jobs ->
                val active = jobs.filter { it.status.active || it.status == DownloadStatus.QUEUED }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifier().notify(ID, build(jobs))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(jobs: List<com.syed.magpie.data.DownloadJob>?): Notification {
        val running = jobs?.firstOrNull { it.status.active }
        val queued = jobs?.count { it.status == DownloadStatus.QUEUED } ?: 0

        val title = running?.title ?: "Preparing downloads"
        val text = when {
            running == null -> "Starting"
            running.totalBytes != null -> buildString {
                append(running.stage)
                append(" · ${formatBytes(running.downloadedBytes)} / ${formatBytes(running.totalBytes!!)}")
                if (queued > 0) append(" · $queued queued")
            }
            else -> running.stage
        }

        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .apply {
                val pct = running?.let { (it.fraction * 100).toInt() }
                if (pct != null && running.totalBytes != null) {
                    setProgress(100, pct, running.status != DownloadStatus.DOWNLOADING)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
    }

    private fun notifier() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress for files Magpie is fetching"
            setShowBadge(false)
        }
        notifier().createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL = "magpie.downloads"
        private const val ID = 4201
    }
}
