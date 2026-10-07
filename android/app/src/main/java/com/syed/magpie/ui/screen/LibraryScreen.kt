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
import com.syed.magpie.data.DhakaFlix
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadStatus
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.Module
import com.syed.magpie.ui.StillVideoViewModel
import com.syed.magpie.ui.SubtitleViewModel
import com.syed.magpie.data.SubtitleStatus
import com.syed.magpie.data.StillJob
import com.syed.magpie.data.StillStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles

/**
 * Each module keeps its own library; the switcher under the title picks
 * which one is showing, and remembers it in the ViewModel across tabs.
 */
@Composable
fun LibraryScreen(
    vm: MagpieViewModel,
    still: StillVideoViewModel,
    subtitles: SubtitleViewModel,
    onEditStill: (StillJob) -> Unit,
    onHints: (DownloadJob) -> Unit,
    modifier: Modifier = Modifier,
) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val stills by still.jobs.collectAsStateWithLifecycle()
    val subs by subtitles.jobs.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    val module = vm.libraryModule
    val finished = when (module) {
        Module.Downloader -> jobs.count { it.status == DownloadStatus.COMPLETED && !it.isDhakaFlix }
        Module.DhakaFlix -> jobs.count {
            it.isDhakaFlix && (it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED)
        }
        Module.StillVideo -> stills.count { it.status == StillStatus.COMPLETED }
        Module.Subtitles -> subs.count { it.status == SubtitleStatus.COMPLETED }
    }

    if (clearing) {
        val noun = when (module) {
            Module.Subtitles -> "subtitle file"
            Module.DhakaFlix -> "file"
            else -> "video"
        }
        MagpieDialog(
            title = when (module) {
                Module.Downloader, Module.DhakaFlix -> "Clear finished downloads?"
                Module.Subtitles -> "Clear finished subtitles?"
                else -> "Clear finished videos?"
            },
            message = if (module == Module.DhakaFlix) {
                "${if (finished == 1) "One finished or failed download" else "$finished finished or failed downloads"} " +
                    "will leave this list. Saved files stay in Downloads/${DhakaFlix.FOLDER}; " +
                    "a failed one's partial bytes are deleted."
            } else "${if (finished == 1) "One saved $noun" else "$finished saved ${noun}s"} " +
                "will leave this list. The files stay in " +
                (if (module == Module.DhakaFlix) "Downloads/${DhakaFlix.FOLDER}." else "Downloads/Magpie.") +
                if (module == Module.StillVideo) " They can no longer be edited." else "",
            primary = DialogAction("Clear list") {
                when (module) {
                    Module.Downloader -> vm.clearFinished(dhakaflix = false)
                    Module.DhakaFlix -> vm.clearFinished(dhakaflix = true)
                    Module.Subtitles -> subtitles.clearFinished()
                    else -> still.clearFinished()
                }
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
            onSelect = { vm.libraryModule = it },
        )
        Spacer(Modifier.height(18.dp))

        when (module) {
            Module.Downloader -> DownloadList(jobs.filterNot { it.isDhakaFlix }, vm)
            Module.DhakaFlix -> DhakaFlixLibrary(jobs.filter { it.isDhakaFlix }, vm, onHints)
            Module.StillVideo -> StillLibrary(stills, still, onOpen = vm::open, onEdit = onEditStill)
            Module.Subtitles -> SubtitleLibrary(subs, subtitles)
        }
    }
}

/**
 * The module picker, built the way the floating nav bar is: only the active
 * module spells out its name, in a coral pill; the rest are icons carrying a
 * count badge. Labels are what stop a row of tabs from scaling, so dropping
 * all but one keeps a single calm line for six or seven modules. Past that
 * the row scrolls rather than squeezing, and keeps the active one in view.
 */
@Composable
private fun ModuleSwitcher(current: Module, onSelect: (Module) -> Unit) {
    val scroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Module.entries.forEach { m ->
                ModuleTab(m, active = m == current) { onSelect(m) }
            }
        }
    }
}

@Composable
private fun ModuleTab(module: Module, active: Boolean, onClick: () -> Unit) {
    val pad by animateDpAsState(if (active) 16.dp else 12.dp, label = "tab")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .animateContentSize()
            .padding(horizontal = pad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Icon(module.icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                module.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            // An idle tab is just its icon, so the row stays as narrow as the
            // icons themselves.
            Icon(
                module.icon,
                module.label,
                Modifier.padding(4.dp).size(21.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadList(jobs: List<DownloadJob>, vm: MagpieViewModel) {
    var confirming by remember { mutableStateOf<DownloadJob?>(null) }
    var renaming by remember { mutableStateOf<DownloadJob?>(null) }

    renaming?.let { job ->
        RenameDialog(
            title = "Rename video",
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
                JobCard(job, vm, onConfirmDelete = { confirming = job }, onRename = { renaming = job })
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

internal val DownloadJob.isDhakaFlix get() = source == DhakaFlix.SOURCE

@Composable
internal fun JobCard(
    job: DownloadJob,
    vm: MagpieViewModel,
    onConfirmDelete: () -> Unit,
    onRename: () -> Unit,
    onHints: ((DownloadJob) -> Unit)? = null,
) {
    val done = job.status == DownloadStatus.COMPLETED
    // MERGING / SAVING cannot be interrupted safely.
    val locked = job.status == DownloadStatus.MERGING || job.status == DownloadStatus.SAVING
    LibraryCard(
        title = job.title,
        meta = listOfNotNull(
            when (job.source) {
                "facebook" -> "Facebook"
                DhakaFlix.SOURCE -> "DhakaFlix"
                else -> "Drive"
            },
            job.quality,
            job.totalBytes?.takeIf { done }?.let(::formatBytes),
        ).joinToString(" · "),
        thumb = Thumb(
            video = job.outputUri?.takeIf { done }?.toUri(),
            icon = Icons.Default.Download,
        ),
        savedIn = if (job.isDhakaFlix) "Downloads/${DhakaFlix.FOLDER}" else "Downloads/Magpie",
        status = if (done) CardStatus.Saved
        else CardStatus.Working(job.fraction, statusLine(job), failed = job.status == DownloadStatus.FAILED),
        primary = {
            when {
                done -> CardButton(Icons.Default.PlayArrow, "Play") {
                    job.outputUri?.let { vm.open(it.toUri(), job.mime) }
                }
                job.status == DownloadStatus.DOWNLOADING ->
                    CardButton(Icons.Default.Pause, "Pause", accent = false) { vm.pause(job.id) }
                job.status.resumable -> {
                    val failed = job.status == DownloadStatus.FAILED
                    CardButton(
                        if (failed) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        if (failed) "Retry" else "Resume",
                    ) { vm.resume(job.id) }
                }
                else -> CardBusy()
            }
        },
        menu = buildList {
            if (done) {
                add(CardAction("Share", Icons.Default.Share) { job.outputUri?.let { vm.share(it.toUri(), job.mime) } })
                // A film or an .srt off DhakaFlix goes straight to Subtitles.
                if (onHints != null) {
                    add(CardAction("Add Bengali hints", Icons.Default.Subtitles) { onHints(job) })
                }
                add(CardAction("Rename", Icons.Default.DriveFileRenameOutline, onClick = onRename))
            }
            if (!locked) {
                add(
                    CardAction(
                        when {
                            done -> "Delete"
                            job.status.resumable -> "Remove"
                            else -> "Cancel download"
                        },
                        if (done) Icons.Default.Delete else Icons.Default.Close,
                        destructive = true,
                        // A finished job owns a real file, so that one asks first.
                        onClick = { if (done) onConfirmDelete() else vm.cancel(job.id) },
                    ),
                )
            }
        },
    )
}

internal fun statusLine(job: DownloadJob): String {
    // Stopped by the network rather than by hand: say where, and why.
    if (job.status == DownloadStatus.PAUSED && job.error != null) {
        return "Stopped at ${(job.fraction * 100).toInt()}% — ${job.error}"
    }
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
