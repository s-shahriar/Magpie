package com.syed.magpie.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.DhakaFlix
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadStatus
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.MagpieDialog
import com.syed.magpie.ui.component.RenameDialog

private enum class Filter(val label: String) { All("All"), Active("Active"), Done("Done") }

private val DownloadJob.isActive
    get() = status != DownloadStatus.COMPLETED && status != DownloadStatus.FAILED

/**
 * DhakaFlix downloads are films of several gigabytes, often a whole season
 * queued at once, so this library can be narrowed to what is still moving
 * or what is finished.
 */
@Composable
internal fun DhakaFlixLibrary(
    jobs: List<DownloadJob>,
    vm: MagpieViewModel,
    onHints: (DownloadJob) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var confirming by remember { mutableStateOf<DownloadJob?>(null) }
    var renaming by remember { mutableStateOf<DownloadJob?>(null) }

    renaming?.let { job ->
        RenameDialog(
            title = "Rename file",
            current = job.title,
            onRename = {
                vm.rename(job, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    confirming?.let { job ->
        MagpieDialog(
            title = "Delete this file?",
            subject = job.fileName,
            message = "It will be removed from Downloads/${DhakaFlix.FOLDER}. This cannot be undone.",
            primary = DialogAction("Delete file", destructive = true) {
                vm.deleteWithFile(job)
                confirming = null
            },
            secondary = DialogAction("Remove from list only") {
                vm.cancel(job.id)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }

    if (jobs.isEmpty()) {
        EmptyLibrary("Nothing here yet.\nOpen DhakaFlix from Modules and find a film.")
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Filter.entries.forEach { f ->
            FilterChip(
                selected = filter == f,
                onClick = { filter = f },
                label = { Text(if (f == Filter.All) "${f.label} · ${jobs.size}" else f.label) },
            )
        }
    }

    val downloading = jobs.count { it.status == DownloadStatus.DOWNLOADING }
    val queued = jobs.count { it.status == DownloadStatus.QUEUED }
    if (downloading + queued > 0) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sync, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                "$downloading active" + if (queued > 0) " · $queued queued" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    val shown = when (filter) {
        Filter.All -> jobs
        Filter.Active -> jobs.filter { it.isActive }
        Filter.Done -> jobs.filterNot { it.isActive }
    }
    if (shown.isEmpty()) {
        EmptyLibrary(if (filter == Filter.Active) "Nothing downloading." else "Nothing finished yet.")
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 110.dp),
    ) {
        items(shown, key = { it.id }) { job ->
            JobCard(
                job,
                vm,
                onConfirmDelete = { confirming = job },
                onRename = { renaming = job },
                onHints = onHints,
            )
        }
    }
}
