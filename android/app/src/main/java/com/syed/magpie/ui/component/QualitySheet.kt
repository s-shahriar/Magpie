package com.syed.magpie.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.Catalog
import com.syed.magpie.data.formatBytes
import com.syed.magpie.data.formatDuration
import com.syed.magpie.data.sizeLabel
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Rendition

/**
 * Quality picker. Every row shows the size it will cost before anything is
 * downloaded — the whole point of probing first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    info: MediaInfo,
    onPick: (Rendition) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Text(
                info.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(if (info.source == "facebook") "Facebook" else "Google Drive")
                    val d = formatDuration(info.durationSecs.toLong())
                    if (d.isNotEmpty()) append(" · $d")
                    if (!info.muxed && info.audio.isNotEmpty()) append(" · audio merged in")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(info.video) { r ->
                    QualityRow(
                        rendition = r,
                        totalBytes = Catalog.totalBytes(info, r),
                        recommended = r == info.video.firstOrNull(),
                        onClick = { onPick(r) },
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
    val w = width
    val h = height
    return when {
        w != null && h != null -> "${w}x${h}"
        bitrate > 0uL -> "${bitrate.toLong() / 1000} kbps"
        else -> null
    }
}
