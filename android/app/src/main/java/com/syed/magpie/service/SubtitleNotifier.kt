package com.syed.magpie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.syed.magpie.MainActivity
import com.syed.magpie.R
import com.syed.magpie.data.SubtitleJob
import com.syed.magpie.data.SubtitleStatus
import com.syed.magpie.ui.Module

/**
 * The Subtitles module's notifications, in the same voice as the downloader's:
 * one quiet ongoing row while a file is being worked through, one dismissable
 * row when it is saved, paused for the day, or could not be done.
 *
 * A film takes a few minutes and may sit through a rate-limit countdown, and
 * the phone is usually in a pocket by then. The row is what says it is still
 * going.
 */
class SubtitleNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val accent get() = ContextCompat.getColor(context, R.color.notification_accent)
    private val danger get() = ContextCompat.getColor(context, R.color.danger)

    fun ensureChannels() {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "Subtitles in progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for subtitle files Magpie is adding hints to"
            setShowBadge(false)
        }
        val finished = NotificationChannel(
            CHANNEL_FINISHED,
            "Finished subtitles",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "When a subtitle file is saved, or has to wait for tomorrow"
            setShowBadge(true)
            enableLights(true)
            lightColor = accent
        }
        manager.createNotificationChannels(listOf(progress, finished))
    }

    // ---- in flight -----------------------------------------------------

    fun progress(job: SubtitleJob) {
        val n: Notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(job.title)
            .setContentText(job.line ?: "Starting")
            .setColor(accent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openLibrary())
            .setProgress(100, (job.progress * 100).toInt(), job.status == SubtitleStatus.WAITING)
            .build()
        manager.notify(ID_PROGRESS, n)
    }

    fun clearProgress() = manager.cancel(ID_PROGRESS)

    // ---- done ----------------------------------------------------------

    fun saved(job: SubtitleJob) {
        val open = job.outputUri?.let { uri ->
            activity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri.toUri(), "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                requestCode = idFor(job),
            )
        }
        val summary = buildString {
            append("${job.hinted} hints in ${job.cues} lines")
            if (job.skipped > 0) append(" · ${job.skipped} left as they were")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(job.outputName)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Saved to Downloads/Magpie\n$summary"))
            .setColor(accent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(open ?: openLibrary())
            .build()
        manager.notify(idFor(job), n)
    }

    /** Paused for the day, or failed: either way the row leads back to the library. */
    fun stopped(job: SubtitleJob) {
        val paused = job.status == SubtitleStatus.PAUSED
        val reason = job.error ?: job.line ?: if (paused) "Waiting for tomorrow's quota" else "Could not finish"
        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(if (paused) R.drawable.ic_stat_download else R.drawable.ic_stat_failed)
            .setContentTitle(job.title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSubText(if (paused) "Paused until the quota resets" else "Subtitles failed")
            .setColor(if (paused) accent else danger)
            .setCategory(if (paused) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(openLibrary())
            .build()
        manager.notify(idFor(job), n)
    }

    fun clear(job: SubtitleJob) = manager.cancel(idFor(job))

    // ---- plumbing ------------------------------------------------------

    private fun openLibrary() = activity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
            .putExtra(MainActivity.EXTRA_LIBRARY_MODULE, Module.Subtitles.name),
        requestCode = 0,
    )

    private fun activity(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun idFor(job: SubtitleJob) = ID_BASE + (job.id.hashCode() and 0xFFFF)

    companion object {
        const val CHANNEL_PROGRESS = "magpie.subtitles"
        const val CHANNEL_FINISHED = "magpie.subtitles.finished"
        const val GROUP = "magpie.subtitles.finished"
        const val ID_PROGRESS = 4301
        private const val ID_BASE = 43_000
    }
}
