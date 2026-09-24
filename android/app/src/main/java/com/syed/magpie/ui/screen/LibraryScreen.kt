package com.syed.magpie.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadStatus
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.Module
import com.syed.magpie.ui.StillVideoViewModel
import com.syed.magpie.data.StillJob
import com.syed.magpie.data.StillStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.MagpieDialog

/**
 * Each module keeps its own library; the switcher under the title picks
 * which one is showing, and remembers it in the ViewModel across tabs.
 */
@Composable
fun LibraryScreen(
    vm: MagpieViewModel,
    still: StillVideoViewModel,
    onEditStill: (StillJob) -> Unit,
    modifier: Modifier = Modifier,
) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val stills by still.jobs.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    val module = vm.libraryModule
    val finished = when (module) {
        Module.Downloader -> jobs.count { it.status == DownloadStatus.COMPLETED }
        Module.StillVideo -> stills.count { it.status == StillStatus.COMPLETED }
    }

    if (clearing) {
        MagpieDialog(
            title = if (module == Module.Downloader) "Clear finished downloads?" else "Clear finished videos?",
            message = "${if (finished == 1) "One saved video" else "$finished saved videos"} " +
                "will leave this list. The files stay in Downloads/Magpie." +
                if (module == Module.StillVideo) " They can no longer be edited." else "",
            primary = DialogAction("Clear list") {
                if (module == Module.Downloader) vm.clearFinished() else still.clearFinished()
                clearing = false
            },
            onDismiss = { clearing = false },
        )
    }

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(30.dp))
        // The title keeps the centre; the sweep sits in the corner where a
        // screen action belongs, instead of hanging under the heading and
        // pushing the whole list down whenever something finishes.
        Box(Modifier.fillMaxWidth()) {
            Text(
                "Library",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            )
            if (finished > 0) {
                IconButton(
                    onClick = { clearing = true },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        Icons.Default.PlaylistRemove,
                        "Clear finished",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        ModuleSwitcher(
            current = module,
            counts = mapOf(Module.Downloader to jobs.size, Module.StillVideo to stills.size),
            onSelect = { vm.libraryModule = it },
        )
        Spacer(Modifier.height(18.dp))

        when (module) {
            Module.Downloader -> DownloadList(jobs, vm)
            Module.StillVideo -> StillLibrary(stills, still, onOpen = vm::open, onEdit = onEditStill)
        }
    }
}

/** A pill of module tabs, drawn like the floating nav bar. */
@Composable
private fun ModuleSwitcher(current: Module, counts: Map<Module, Int>, onSelect: (Module) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(5.dp)) {
            Module.entries.forEach { m ->
                val active = m == current
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(m) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(m.icon, null, Modifier.size(18.dp), tint = tint)
                    Spacer(Modifier.width(6.dp))
                    Text(m.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                    val n = counts[m] ?: 0
                    if (n > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("$n", style = MaterialTheme.typography.labelMedium, color = tint.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadList(jobs: List<DownloadJob>, vm: MagpieViewModel) {
    var confirming by remember { mutableStateOf<DownloadJob?>(null) }

    confirming?.let { job ->
        MagpieDialog(
            title = "Delete this video?",
            subject = job.title,
            message = "It will be removed from Downloads/Magpie. This cannot be undone.",
            primary = DialogAction("Delete video", destructive = true) {
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
        EmptyLibrary("Nothing here yet.\nOpen Downloader from Modules and paste a link.")
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 110.dp),
        ) {
            items(jobs, key = { it.id }) { job ->
                JobCard(job, vm) { confirming = job }
            }
        }
    }
}

@Composable
internal fun EmptyLibrary(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun JobCard(job: DownloadJob, vm: MagpieViewModel, onConfirmDelete: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        "${if (job.source == "facebook") "Facebook" else "Drive"} · ${job.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Controls(job, vm, onConfirmDelete)
            }

            if (job.status != DownloadStatus.COMPLETED) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { job.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (job.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    statusLine(job),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (job.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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

@Composable
private fun Controls(job: DownloadJob, vm: MagpieViewModel, onConfirmDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            job.status == DownloadStatus.COMPLETED -> {
                IconButton(onClick = { job.outputUri?.let { vm.open(it.toUri()) } }) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
                }
            }
            job.status == DownloadStatus.DOWNLOADING -> {
                IconButton(onClick = { vm.pause(job.id) }) { Icon(Icons.Default.Pause, "Pause") }
            }
            job.status == DownloadStatus.QUEUED -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
            }
            job.status.resumable -> {
                IconButton(onClick = { vm.resume(job.id) }) {
                    Icon(
                        if (job.status == DownloadStatus.FAILED) Icons.Default.Refresh
                        else Icons.Default.PlayArrow,
                        if (job.status == DownloadStatus.FAILED) "Retry" else "Resume",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // MERGING / SAVING cannot be interrupted safely.
            else -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
            }
        }
        if (job.status != DownloadStatus.MERGING && job.status != DownloadStatus.SAVING) {
            IconButton(
                onClick = {
                    // A finished job owns a real file, so that one asks first.
                    if (job.status == DownloadStatus.COMPLETED) onConfirmDelete()
                    else vm.cancel(job.id)
                },
            ) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}

private fun statusLine(job: DownloadJob): String {
    job.error?.let { return it }
    // The stage is blank while bytes are moving — the bar says that much on
    // its own — so the parts are joined rather than concatenated, or the line
    // would open on a stray separator.
    val parts = buildList {
        add(job.stage)
        val total = job.totalBytes
        if (total != null && job.downloadedBytes > 0) {
            add("${formatBytes(job.downloadedBytes)} / ${formatBytes(total)}")
        }
        if (job.bytesPerSecond > 0) add("${formatBytes(job.bytesPerSecond)}/s")
        job.etaSeconds?.let { add("${it / 60}m ${it % 60}s left") }
    }.filter { it.isNotBlank() }
    return parts.joinToString(" · ").ifEmpty { "Starting" }
}
