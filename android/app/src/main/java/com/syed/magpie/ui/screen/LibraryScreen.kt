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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun LibraryScreen(vm: MagpieViewModel, modifier: Modifier = Modifier) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (jobs.any { it.status == DownloadStatus.COMPLETED }) {
                TextButton(onClick = vm::clearFinished) { Text("Clear done") }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet.\nPaste a link on the Fetch tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 110.dp),
            ) {
                items(jobs, key = { it.id }) { job -> JobCard(job, vm) }
            }
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob, vm: MagpieViewModel) {
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
                Controls(job, vm)
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
private fun Controls(job: DownloadJob, vm: MagpieViewModel) {
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
            IconButton(onClick = { vm.cancel(job.id) }) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}

private fun statusLine(job: DownloadJob): String {
    job.error?.let { return it }
    return buildString {
        append(job.stage)
        val total = job.totalBytes
        if (total != null && job.downloadedBytes > 0) {
            append(" · ${formatBytes(job.downloadedBytes)} / ${formatBytes(total)}")
        }
        if (job.bytesPerSecond > 0) append(" · ${formatBytes(job.bytesPerSecond)}/s")
        job.etaSeconds?.let { append(" · ${it / 60}m ${it % 60}s left") }
    }
}
