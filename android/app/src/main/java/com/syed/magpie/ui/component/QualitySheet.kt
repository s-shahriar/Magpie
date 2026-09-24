package com.syed.magpie.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.Catalog
import com.syed.magpie.data.formatBytes
import com.syed.magpie.data.formatDuration
import com.syed.magpie.data.selfContained
import com.syed.magpie.data.sizeLabel
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Rendition

/**
 * Quality picker. Every row shows the size it will cost before anything is
 * downloaded — the whole point of probing first.
 *
 * The name is editable here because this is the only moment it can be: once a
 * job is queued the file name is what MediaStore was told to create, and
 * Facebook's own titles are often just `facebook-1081708091120089`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    info: MediaInfo,
    onPick: (Rendition, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the title: a second link probed without leaving the screen
    // starts from its own name rather than the previous one.
    var name by remember(info.title) { mutableStateOf(info.title) }
    var renaming by remember(info.title) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        // Not info.video: a rendition this device cannot merge with its audio
        // track is never offered. See Catalog.usableVideo.
        val choices = Catalog.usableVideo(info)
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            if (renaming) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name") },
                    supportingText = { Text("The quality and .mp4 are added for you") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { renaming = false }),
                    trailingIcon = {
                        IconButton(onClick = { renaming = false }) {
                            Icon(Icons.Default.Check, "Done", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name.ifBlank { info.title },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { renaming = true }) {
                        Icon(
                            Icons.Default.Edit,
                            "Rename before downloading",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(if (info.source == "facebook") "Facebook" else "Google Drive")
                    val d = formatDuration(info.durationSecs.toLong())
                    if (d.isNotEmpty()) append(" · $d")
                    // Only when every row on offer actually needs the merge;
                    // a progressive MP4 says so on its own row instead.
                    if (!info.muxed && info.audio.isNotEmpty() &&
                        choices.none { it.selfContained }
                    ) {
                        append(" · audio merged in")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(choices) { r ->
                    QualityRow(
                        rendition = r,
                        totalBytes = Catalog.totalBytes(info, r),
                        recommended = r == choices.firstOrNull(),
                        onClick = { onPick(r, name.trim().ifBlank { info.title }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityRow(
    rendition: Rendition,
    totalBytes: Long?,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = if (recommended) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rendition.label, style = MaterialTheme.typography.titleMedium)
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "smallest",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                rendition.dimensionLabel()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                totalBytes?.let { (if (rendition.exactSize) "" else "~") + formatBytes(it) }
                    ?: rendition.sizeLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun Rendition.dimensionLabel(): String? {
    if (selfContained) return "audio included"
    val w = width
    val h = height
    return when {
        w != null && h != null -> "${w}x${h}"
        bitrate > 0uL -> "${bitrate.toLong() / 1000} kbps"
        else -> null
    }
}
