package com.syed.magpie.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.DownloadJob
import com.syed.magpie.ui.MagpieViewModel

@Composable
fun LibraryScreen(vm: MagpieViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("Library", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (vm.jobs.isEmpty()) {
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
                items(vm.jobs, key = { it.id }) { job -> JobCard(job, vm) }
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
                    Text(
                        job.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    Text(
                        "${if (job.source == "facebook") "Facebook" else "Drive"} · ${job.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    job.done -> IconButton(onClick = { job.uri?.let(vm::open) }) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
                    }
                    job.error == null -> IconButton(onClick = { vm.cancel(job) }) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                }
            }

            val p = job.progress
            if (p != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { p.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(p.stage)
                        if (p.total != null) {
                            append(" · ${formatBytes(p.bytes)} / ${formatBytes(p.total)}")
                        }
                        if (p.bytesPerSecond > 0) append(" · ${formatBytes(p.bytesPerSecond)}/s")
                        p.etaSeconds?.let { append(" · ${it / 60}m ${it % 60}s left") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            job.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (job.done) {
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
