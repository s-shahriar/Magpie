package com.syed.magpie.ui.screen

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.syed.magpie.data.StillJob
import com.syed.magpie.data.StillStatus
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.StillVideoViewModel
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.MagpieDialog
import com.syed.magpie.ui.component.RenameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(job.photo)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            "%d:%02d".format(job.seconds / 60, job.seconds % 60),
                            job.frame.label,
                            job.sizeBytes?.takeIf { job.status == StillStatus.COMPLETED }?.let(::formatBytes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (job.status) {
                    StillStatus.COMPLETED -> IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
                    }
                    StillStatus.ENCODING -> IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                    StillStatus.QUEUED -> {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    StillStatus.FAILED, StillStatus.STOPPED -> IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, "Retry", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                More(job, onShare, onRename, onEdit)
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove") }
            }

            if (job.status != StillStatus.COMPLETED) {
                val failed = job.status == StillStatus.FAILED
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (job.status) {
                        StillStatus.QUEUED -> "Queued"
                        StillStatus.ENCODING -> "Encoding · ${(job.progress * 100).toInt()}%"
                        StillStatus.STOPPED -> "Stopped"
                        else -> job.error ?: "Failed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved to Downloads/Magpie",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Share, rename and edit — the actions a row has room for only in a menu. */
@Composable
private fun More(job: StillJob, onShare: () -> Unit, onRename: () -> Unit, onEdit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val busy = job.status == StillStatus.ENCODING || job.status == StillStatus.QUEUED
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            if (job.status == StillStatus.COMPLETED) {
                MenuRow("Share", Icons.Default.Share) { open = false; onShare() }
            }
            MenuRow("Rename", Icons.Default.DriveFileRenameOutline) { open = false; onRename() }
            // Editing a job mid-encode would race the encoder for its file.
            if (!busy) MenuRow("Edit", Icons.Default.Edit) { open = false; onEdit() }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** The job's own copy of its photo, decoded small. */
@Composable
private fun Thumb(path: String) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            // ImageDecoder, not BitmapFactory: it applies EXIF rotation, so a
            // camera photo does not show up sideways.
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, info, _ ->
                    val long = maxOf(info.size.width, info.size.height)
                    d.setTargetSampleSize((long / 160).coerceAtLeast(1))
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
