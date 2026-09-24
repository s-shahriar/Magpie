package com.syed.magpie.ui.screen

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.syed.magpie.data.StillJob
import com.syed.magpie.data.StillStatus
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.StillVideoViewModel
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.MagpieDialog
import com.syed.magpie.ui.component.RenameDialog
import com.syed.magpie.ui.component.CardAction
import com.syed.magpie.ui.component.CardBusy
import com.syed.magpie.ui.component.CardButton
import com.syed.magpie.ui.component.CardStatus
import com.syed.magpie.ui.component.LibraryCard
import com.syed.magpie.ui.component.Thumb
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie

/** Still → Video's library: its own rows, never mixed with downloads. */
@Composable
fun StillLibrary(
    jobs: List<StillJob>,
    vm: StillVideoViewModel,
    onOpen: (Uri) -> Unit,
    onEdit: (StillJob) -> Unit,
) {
    var deleting by remember { mutableStateOf<StillJob?>(null) }
    var renaming by remember { mutableStateOf<StillJob?>(null) }

    deleting?.let { job ->
        MagpieDialog(
            title = "Delete this video?",
            subject = job.title,
            message = "It will be removed from Downloads/Magpie. This cannot be undone.",
            primary = DialogAction("Delete video", destructive = true) {
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
            title = "Rename video",
            current = job.title,
            onRename = {
                vm.rename(job.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    // Newest first: this list is a gallery of things made, not a queue.
    val rows = remember(jobs) { jobs.sortedByDescending { it.createdAt } }
    if (rows.isEmpty()) {
        EmptyLibrary("Nothing made yet.\nOpen Still → Video from Modules and pick a photo.")
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 110.dp),
    ) {
        items(rows, key = { it.id }) { job ->
            StillCard(
                job = job,
                onPlay = { job.outputUri?.let { onOpen(it.toUri()) } },
                onShare = { vm.share(job) },
                onRename = { renaming = job },
                onEdit = { onEdit(job) },
                onStop = { vm.stop(job.id) },
                onRetry = { vm.retry(job.id) },
                // A finished job owns a real file, so that one asks first.
                onRemove = {
                    if (job.status == StillStatus.COMPLETED) deleting = job else vm.remove(job.id)
                },
            )
        }
    }
}

@Composable
private fun StillCard(
    job: StillJob,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val done = job.status == StillStatus.COMPLETED
    val busy = job.status == StillStatus.ENCODING || job.status == StillStatus.QUEUED
    LibraryCard(
        title = job.title,
        meta = listOfNotNull(
            "%d:%02d".format(job.seconds / 60, job.seconds % 60),
            job.frame.label,
            job.sizeBytes?.takeIf { done }?.let(::formatBytes),
        ).joinToString(" · "),
        // The finished video, so the thumbnail carries its text as well.
        thumb = Thumb(
            video = job.outputUri?.takeIf { done }?.toUri(),
            image = job.photo,
            icon = Icons.Default.Movie,
        ),
        status = when (job.status) {
            StillStatus.COMPLETED -> CardStatus.Saved
            StillStatus.QUEUED -> CardStatus.Working(0f, "Queued")
            StillStatus.ENCODING -> CardStatus.Working(job.progress, "Encoding · ${(job.progress * 100).toInt()}%")
            StillStatus.STOPPED -> CardStatus.Working(0f, "Stopped")
            StillStatus.FAILED -> CardStatus.Working(0f, job.error ?: "Failed", failed = true)
        },
        primary = {
            when (job.status) {
                StillStatus.COMPLETED -> CardButton(Icons.Default.PlayArrow, "Play", onClick = onPlay)
                StillStatus.ENCODING -> CardButton(Icons.Default.Stop, "Stop", accent = false, onClick = onStop)
                StillStatus.QUEUED -> CardBusy()
                StillStatus.FAILED, StillStatus.STOPPED ->
                    CardButton(Icons.Default.Refresh, "Retry", onClick = onRetry)
            }
        },
        menu = buildList {
            if (done) add(CardAction("Share", Icons.Default.Share, onClick = onShare))
            add(CardAction("Rename", Icons.Default.DriveFileRenameOutline, onClick = onRename))
            // Editing a job mid-encode would race the encoder for its file.
            if (!busy) add(CardAction("Edit", Icons.Default.Edit, onClick = onEdit))
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
