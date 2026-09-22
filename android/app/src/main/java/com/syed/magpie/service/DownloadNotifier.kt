package com.syed.magpie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.syed.magpie.MainActivity
import com.syed.magpie.R
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadStatus
import com.syed.magpie.data.formatBytes

/**
 * Every notification Magpie posts, in one place and wearing the app's palette.
 *
 * Coral carries anything healthy — the same accent the progress bar and the
 * nav pill use — and the Library card's danger red carries a failure, so the
 * shade reads the same way the app does. It is worn lightly: the tint sits on
 * the small icon and the app-name label, over the shade's own background,
 * rather than flooding the row. A download that runs for an hour should not
 * shout for the whole hour.
 *
 * Two channels, because they are two different interruptions: progress is
 * silent and unswipeable, a finished or failed file is a one-off that should
 * make a sound and then be dismissable.
 */
class DownloadNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val accent get() = ContextCompat.getColor(context, R.color.notification_accent)
    private val danger get() = ContextCompat.getColor(context, R.color.danger)

    fun ensureChannels() {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "Downloads in progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for files Magpie is fetching"
            setShowBadge(false)
        }
        val finished = NotificationChannel(
            CHANNEL_FINISHED,
            "Finished downloads",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "When a file is saved, or a download could not finish"
            setShowBadge(true)
            enableLights(true)
            lightColor = accent
        }
        manager.createNotificationChannels(listOf(progress, finished))
    }

    // ---- in flight -----------------------------------------------------

    /**
     * The ongoing row. Shows the job actually moving; everything else waiting
     * is folded into a "+N queued" sub-line rather than a notification each,
     * which would bury the shade on a playlist-sized batch.
     */
    fun progress(jobs: List<DownloadJob>): Notification {
        val running = jobs.firstOrNull { it.status.active }
            ?: jobs.firstOrNull { it.status == DownloadStatus.QUEUED }
        val waiting = jobs.count { it.status == DownloadStatus.QUEUED } -
            if (running?.status == DownloadStatus.QUEUED) 1 else 0

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(running?.title ?: "Preparing downloads")
            .setContentText(progressLine(running))
            .setColor(accent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openLibrary())

        if (waiting > 0) builder.setSubText("+$waiting queued")

        val percent = running?.takeIf { it.totalBytes != null }?.let { (it.fraction * 100).toInt() }
        if (percent != null) {
            builder.setProgress(100, percent, running.status != DownloadStatus.DOWNLOADING)
        } else {
            builder.setProgress(0, 0, true)
        }

        // MERGING and SAVING cannot be interrupted safely — the Library card
        // hides its buttons there too, so the shade must not offer them either.
        if (running != null && running.status.stoppable) {
            builder.addAction(
                R.drawable.ic_notif_pause,
                "Pause",
                service(DownloadService.ACTION_PAUSE, running.id),
            )
            builder.addAction(
                R.drawable.ic_notif_close,
                "Cancel",
                service(DownloadService.ACTION_CANCEL, running.id),
            )
        }

        return builder.build()
    }

    private fun progressLine(job: DownloadJob?): String {
        if (job == null) return "Starting"
        return buildString {
            append(job.stage)
            val total = job.totalBytes
            if (total != null) {
                append(" · ${formatBytes(job.downloadedBytes)} / ${formatBytes(total)}")
            } else if (job.downloadedBytes > 0) {
                append(" · ${formatBytes(job.downloadedBytes)}")
            }
            if (job.bytesPerSecond > 0) append(" · ${formatBytes(job.bytesPerSecond)}/s")
            job.etaSeconds?.let { append(" · ${eta(it)} left") }
        }
    }

    private fun eta(secs: Long): String =
        if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"

    // ---- done ----------------------------------------------------------

    /** Saved. Tapping plays the file; the shade is the fastest route to it. */
    fun saved(job: DownloadJob) {
        val play = job.outputUri?.let { viewVideo(it, job) }
        val size = job.totalBytes ?: job.downloadedBytes

        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(job.title)
            .setContentText(
                buildString {
                    append("Saved · ${job.quality}")
                    if (size > 0) append(" · ${formatBytes(size)}")
                },
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append("Saved to Downloads/Magpie")
                        append("\n${job.quality}")
                        if (size > 0) append(" · ${formatBytes(size)}")
                    },
                ),
            )
            .setColor(accent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_FINISHED)
            .setAutoCancel(true)
            .setContentIntent(play ?: openLibrary())
            .apply {
                if (play != null) addAction(R.drawable.ic_notif_play, "Play", play)
            }
            .build()

        manager.notify(idFor(job), n)
    }

    /**
     * Failed. Retry goes through the activity rather than straight to the
     * engine: restarting the foreground service from a background broadcast is
     * blocked on Android 12+, so the tap opens Magpie and the queue picks up
     * from there — with the partial file still on disk, so nothing is refetched.
     */
    fun failed(job: DownloadJob) {
        val retry = activity(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
                .putExtra(MainActivity.EXTRA_RETRY_JOB, job.id),
            requestCode = idFor(job) + 1,
        )

        val reason = job.error ?: "Download failed"
        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_failed)
            .setContentTitle(job.title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSubText("Download failed")
            .setColor(danger)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_FINISHED)
            .setAutoCancel(true)
            .setContentIntent(retry)
            .addAction(R.drawable.ic_notif_retry, "Retry", retry)
            .build()

        manager.notify(idFor(job), n)
    }

    /** A row that is no longer finished — resumed, retried or removed. */
    fun clear(jobId: String) = manager.cancel(idFor(jobId))

    // ---- intents -------------------------------------------------------

    private fun openLibrary() = activity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true),
        requestCode = 0,
    )

    private fun viewVideo(uri: String, job: DownloadJob) = activity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        requestCode = idFor(job),
    )

    private fun activity(intent: Intent, requestCode: Int) = PendingIntent.getActivity(
        context,
        requestCode,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun service(action: String, jobId: String) = PendingIntent.getService(
        context,
        (action + jobId).hashCode(),
        Intent(context, DownloadService::class.java)
            .setAction(action)
            .putExtra(DownloadService.EXTRA_JOB_ID, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Stable per job, and never the foreground id. */
    private fun idFor(job: DownloadJob): Int = idFor(job.id)

    private fun idFor(jobId: String): Int {
        val h = jobId.hashCode() and 0x00FFFFFF
        return if (h == ID_PROGRESS) h + 2 else h
    }

    companion object {
        const val CHANNEL_PROGRESS = "magpie.downloads"
        const val CHANNEL_FINISHED = "magpie.downloads.finished"
        const val GROUP_FINISHED = "magpie.finished"
        const val ID_PROGRESS = 4201
    }
}
