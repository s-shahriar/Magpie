package com.syed.magpie.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syed.magpie.data.Gemini
import com.syed.magpie.data.SubtitleJob
import com.syed.magpie.data.SubtitleStatus
import com.syed.magpie.data.Subtitles
import com.syed.magpie.data.VideoSubs
import com.syed.magpie.ui.Module
import com.syed.magpie.ui.SubtitleViewModel

/**
 * Bengali hints into a subtitle file.
 *
 * Replaces the SRT Bengali app: pick the file, pick how the model is asked,
 * go. Only the dialogue leaves the phone; the numbering and the timing are
 * never sent and never change.
 */
@Composable
fun SubtitleScreen(
    vm: SubtitleViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitles.srt"
        vm.pick(uri, name)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        ModuleHeader(Module.Subtitles, onBack)
        Spacer(Modifier.height(14.dp))

        if (!vm.hasKey) {
            Notice(
                title = "Add your Gemini API key",
                body = "Google gives one away at aistudio.google.com. It stays on this " +
                    "phone and is sent to Google alone.",
                actionLabel = "Open Settings",
                icon = Icons.Default.Key,
                onAction = onSettings,
            )
            Spacer(Modifier.height(16.dp))
        }

        // ---- the file ------------------------------------------------
        Label("Subtitle file")
        val picked = vm.picked
        val extracting = vm.extracting
        Surface(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !vm.reading,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(
                if (picked != null) 2.dp else 1.dp,
                if (picked != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        picked?.fromVideo != null -> Icons.Default.Movie
                        picked != null -> Icons.Default.Subtitles
                        else -> Icons.Default.UploadFile
                    },
                    null,
                    Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        vm.reading -> Text("Reading…", style = MaterialTheme.typography.titleSmall)
                        picked != null -> {
                            Text(picked.preview.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            picked.fromVideo?.let {
                                Text(
                                    "Subtitles read out of $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${picked.preview.cues} lines · ${picked.preview.format.name} · " +
                                    "${picked.preview.requests(vm.batchSize)} requests",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "→ ${Subtitles.outputName(picked.preview.fileName)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        else -> {
                            Text("Choose a file", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Subtitle files, or a film with subtitles inside",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // The film's own subtitles, being read out of it. This walks the whole
        // file, so it is the one step with an unknown wait attached.
        if (extracting >= 0f) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { extracting },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Reading the subtitles out of the film · ${(extracting * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // A film carrying several tracks asks which one to read.
        vm.trackChoices?.let { tracks ->
            Spacer(Modifier.height(14.dp))
            Label("Subtitle tracks in this film")
            tracks.forEach { t ->
                Spacer(Modifier.height(8.dp))
                Choice(
                    label = t.label,
                    detail = t.title ?: t.mime,
                    selected = false,
                    enabled = true,
                    onClick = { vm.chooseTrack(t) },
                )
            }
        }

        vm.pickError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // ---- the model -----------------------------------------------
        Spacer(Modifier.height(22.dp))
        Label("Model")
        Gemini.Model.entries.forEachIndexed { i, m ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Choice(
                label = m.label,
                detail = m.detail,
                selected = vm.model == m,
                enabled = true,
                onClick = { vm.chooseModel(m) },
            )
        }
        if (vm.model == Gemini.Model.FLASH && picked != null && picked.preview.requests(vm.batchSize) > 10) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This file needs more requests than the free tier usually allows " +
                    "Flash in a day. It will pause and finish tomorrow if it runs out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---- the batch size ------------------------------------------
        Spacer(Modifier.height(22.dp))
        Label("Lines per request")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Subtitles.BATCH_SIZES.forEach { size ->
                Chip("$size", selected = vm.batchSize == size, modifier = Modifier.weight(1f)) { vm.chooseBatch(size) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Fewer lines per request means more requests from the day's allowance; " +
                "more lines means a longer wait for each reply. A line the model " +
                "loses costs only that line, never the whole request.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- the hint colour ------------------------------------------
        Spacer(Modifier.height(22.dp))
        Label("Hint colour")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Subtitles.HintColor.entries.forEach { c ->
                Chip(c.label, selected = vm.hintColor == c, modifier = Modifier.weight(1f)) { vm.chooseColor(c) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // How a line will read while the film plays: dialogue in the player's
        // white, the Bengali in the chosen colour.
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        val accent = if (vm.hintColor.on) Color(vm.hintColor.argb) else dim
        Text(
            buildAnnotatedString {
                append("He was delirious ")
                withStyle(SpanStyle(color = accent)) { append("(প্রলাপ বকা)") }
                append(" about leaving.")
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (vm.hintColor.on)
                "The brackets carry the colour; the dialogue stays the player's white. " +
                    "Added on this phone when the file is saved — the model never sees it."
            else
                "Plain file, no colour tags. For players that would print the tags " +
                    "as text instead of colouring them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- go ------------------------------------------------------
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = vm::start,
            enabled = picked != null && vm.hasKey && !vm.reading,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Add Bengali hints")
        }

        // ---- the job just sent ---------------------------------------
        val job = vm.lastJob?.let { id -> jobs.firstOrNull { it.id == id } }
        if (job != null) {
            Spacer(Modifier.height(16.dp))
            JobPanel(
                job,
                onStop = { vm.stop(job.id) },
                onResume = { vm.resume(job.id) },
                onOpen = { vm.open(job) },
                onLibrary = onLibrary,
            )
            Spacer(Modifier.height(12.dp))
            SubtitleLogPanel(logs[job.id].orEmpty(), vm.hintColor)
        } else if (jobs.any { it.status.active || it.status == SubtitleStatus.QUEUED }) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onLibrary) { Text("A file is already being worked on · open Library") }
        }

        Spacer(Modifier.height(100.dp))
    }
}

/** The card under the button: the film going through, or the file it became. */
@Composable
private fun JobPanel(
    job: SubtitleJob,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onLibrary: () -> Unit,
) {
    val done = job.status == SubtitleStatus.COMPLETED
    val failed = job.status == SubtitleStatus.FAILED
    val edge = when {
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, edge.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        done -> "${job.hinted} hints in ${job.cues} lines"
                        failed -> "Nothing was saved"
                        else -> job.title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when {
                    done -> TextButton(onClick = onOpen) { Text("Open") }
                    job.status.active || job.status == SubtitleStatus.QUEUED ->
                        IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
                    job.status.resumable ->
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.Refresh, "Resume", tint = MaterialTheme.colorScheme.primary)
                        }
                }
            }
            if (!done) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(6.dp))
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Text(
                when {
                    done -> "Downloads/Magpie/${job.outputName}" +
                        if (job.skipped > 0) "\n${job.skipped} lines left as they were" else ""
                    else -> job.error ?: job.line ?: "Queued"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (done) FontFamily.Monospace else null,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job.requests > 0 && !done) {
                Text(
                    "${job.requests} request${if (job.requests == 1) "" else "s"} so far",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onLibrary, contentPadding = PaddingValues(0.dp)) { Text("Open Library") }
        }
    }
}

// ---- the same private helpers the other module screens carry ------------

@Composable
private fun Choice(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val edge = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp, edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Notice(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    error: Boolean = false,
) {
    val accent = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actionLabel != null) {
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onAction, shape = MaterialTheme.shapes.small) {
                        if (icon != null) {
                            Icon(icon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
