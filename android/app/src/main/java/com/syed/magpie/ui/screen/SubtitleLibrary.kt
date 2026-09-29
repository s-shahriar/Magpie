package com.syed.magpie.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.Gemini
import com.syed.magpie.data.SubtitleJob
import com.syed.magpie.data.SubtitleStatus
import com.syed.magpie.ui.SubtitleViewModel
import com.syed.magpie.ui.component.CardAction
import com.syed.magpie.ui.component.CardBusy
import com.syed.magpie.ui.component.CardButton
import com.syed.magpie.ui.component.CardStatus
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.LibraryCard
import com.syed.magpie.ui.component.MagpieDialog
import com.syed.magpie.ui.component.RenameDialog
import com.syed.magpie.ui.component.Thumb

/** The Subtitles module's library: its own rows, never mixed with videos. */
@Composable
fun SubtitleLibrary(jobs: List<SubtitleJob>, vm: SubtitleViewModel) {
    var deleting by remember { mutableStateOf<SubtitleJob?>(null) }
    var renaming by remember { mutableStateOf<SubtitleJob?>(null) }
    var viewing by remember { mutableStateOf<SubtitleJob?>(null) }
    val logs by vm.logs.collectAsStateWithLifecycle()

    viewing?.let { job ->
        SubtitleLogDialog(
            title = job.title,
            lines = logs[job.id].orEmpty(),
            hintColor = vm.hintColor,
            onDismiss = { viewing = null },
        )
    }

    deleting?.let { job ->
        MagpieDialog(
            title = "Delete this subtitle file?",
            subject = job.outputName,
            message = "It will be removed from Downloads/Magpie. This cannot be undone.",
            primary = DialogAction("Delete file", destructive = true) {
                vm.deleteWithFile(job.id)
                deleting = null
            },
            secondary = DialogAction("Remove from list only") {
                vm.remove(job.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    renaming?.let { job ->
        RenameDialog(
            title = "Rename subtitles",
            // The film's name, not the file's: `_translated` is kept.
            current = job.title,
            onRename = {
                vm.rename(job.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    val rows = remember(jobs) { jobs.sortedByDescending { it.createdAt } }
    if (rows.isEmpty()) {
        EmptyLibrary("Nothing here yet.\nOpen Subtitles from Modules and pick a file.")
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 110.dp),
    ) {
        items(rows, key = { it.id }) { job ->
            SubtitleCard(
                job = job,
                onOpen = { vm.open(job) },
                onShare = { vm.share(job) },
                onRename = { renaming = job },
                onLog = { viewing = job },
                onStop = { vm.stop(job.id) },
                onResume = { vm.resume(job.id) },
                onResumeOn = { vm.resumeOn(job.id, it) },
                // A finished job owns a real file, so that one asks first.
                onRemove = {
                    if (job.status == SubtitleStatus.COMPLETED) deleting = job else vm.remove(job.id)
                },
            )
        }
    }
}

@Composable
private fun SubtitleCard(
    job: SubtitleJob,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onLog: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onResumeOn: (Gemini.Model) -> Unit,
    onRemove: () -> Unit,
) {
    val done = job.status == SubtitleStatus.COMPLETED
    val busy = job.status.active || job.status == SubtitleStatus.QUEUED
    LibraryCard(
        title = job.title,
        // Two facts, so the line never truncates: which model, and what it
        // gave. The line counts and batch tallies live in the log.
        meta = listOf(
            job.model.short,
            if (done) "${job.hinted} hints" else "${job.cues} lines",
        ).joinToString(" · "),
        thumb = Thumb(icon = Icons.Default.Subtitles),
        status = when (job.status) {
            SubtitleStatus.COMPLETED -> CardStatus.Saved
            SubtitleStatus.QUEUED -> CardStatus.Working(job.progress, "Queued")
            SubtitleStatus.RUNNING, SubtitleStatus.WAITING -> CardStatus.Working(job.progress, job.line ?: "Working")
            SubtitleStatus.PAUSED -> CardStatus.Working(job.progress, job.line ?: "Paused until the quota resets")
            SubtitleStatus.STOPPED -> CardStatus.Working(job.progress, "Stopped · ${job.doneBatches} of ${job.batches} done")
            // The first sentence only; the full reason is in the log.
            SubtitleStatus.FAILED -> CardStatus.Working(
                job.progress,
                (job.error ?: "Failed").substringBefore(". ").removeSuffix("."),
                failed = true,
            )
        },
        primary = {
            when (job.status) {
                SubtitleStatus.COMPLETED -> CardButton(Icons.Default.OpenInNew, "Open", onClick = onOpen)
                SubtitleStatus.RUNNING, SubtitleStatus.WAITING ->
                    CardButton(Icons.Default.Stop, "Stop", accent = false, onClick = onStop)
                SubtitleStatus.QUEUED -> CardBusy()
                SubtitleStatus.PAUSED, SubtitleStatus.STOPPED ->
                    CardButton(Icons.Default.PlayArrow, "Resume", onClick = onResume)
                SubtitleStatus.FAILED -> CardButton(Icons.Default.Refresh, "Retry", onClick = onResume)
            }
        },
        menu = buildList {
            add(CardAction("Log", Icons.Default.ReceiptLong, onClick = onLog))
            // Carry on with the other model, keeping every batch already done:
            // the way out when Flash is overloaded or its day is spent.
            if (job.status.resumable) {
                Gemini.Model.entries.filter { it != job.model }.forEach { m ->
                    add(CardAction("Resume on ${m.short}", Icons.Default.PlayArrow) { onResumeOn(m) })
                }
            }
            if (done) {
                add(CardAction("Share", Icons.Default.Share, onClick = onShare))
                add(CardAction("Rename", Icons.Default.DriveFileRenameOutline, onClick = onRename))
            }
            add(
                CardAction(
                    if (done) "Delete" else if (busy) "Cancel" else "Remove",
                    if (done) Icons.Default.Delete else Icons.Default.Close,
                    destructive = true,
                    onClick = onRemove,
                ),
            )
        },
    )
}
