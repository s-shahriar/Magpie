package com.syed.magpie.ui.component

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One entry in a card's ⋮ menu. */
data class CardAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** The footer: the saved line when done, otherwise a bar and a status line. */
sealed interface CardStatus {
    data object Saved : CardStatus
    data class Working(val progress: Float, val line: String, val failed: Boolean = false) : CardStatus
}

/**
 * The row every module's library is built from, so they read as one app:
 * thumbnail, a two-line title, one line of facts, the one action that
 * matters now, and everything else behind ⋮.
 */
@Composable
fun LibraryCard(
    title: String,
    meta: String,
    thumb: Thumb,
    status: CardStatus,
    primary: @Composable () -> Unit,
    menu: List<CardAction>,
    /** Where this module's files land; LiveMCQ's do not go to Magpie's folder. */
    savedIn: String = "Downloads/Magpie",
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LibraryThumb(thumb)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                primary()
                CardMenu(menu)
            }
            when (status) {
                CardStatus.Saved -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Saved to $savedIn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is CardStatus.Working -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (status.failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status.line,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The card's round action button, in the style every primary action uses. */
@Composable
fun CardButton(icon: ImageVector, label: String, accent: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            label,
            tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A spinner in the primary action's place, for work that cannot be touched. */
@Composable
fun CardBusy() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun CardMenu(actions: List<CardAction>) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            actions.forEach { a ->
                val tint = if (a.destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(a.label, style = MaterialTheme.typography.bodyMedium, color = tint) },
                    leadingIcon = { Icon(a.icon, null, Modifier.size(20.dp), tint = tint) },
                    onClick = {
                        open = false
                        a.onClick()
                    },
                )
            }
        }
    }
}

/**
 * What a card shows on its left: a frame of the saved video when there is
 * one, else a still image, else the module's icon.
 */
data class Thumb(val video: Uri? = null, val image: String? = null, val icon: ImageVector)

@Composable
private fun LibraryThumb(thumb: Thumb) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, thumb.video, thumb.image) {
        value = withContext(Dispatchers.IO) {
            thumb.video?.let { uri ->
                runCatching {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, uri)
                        r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 200)
                    } finally {
                        r.release()
                    }
                }.getOrNull()?.asImageBitmap()
            } ?: thumb.image?.let { path ->
                // ImageDecoder, not BitmapFactory: it applies EXIF rotation.
                runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(File(path)),
                    ) { d, info, _ ->
                        val long = maxOf(info.size.width, info.size.height)
                        d.setTargetSampleSize((long / 160).coerceAtLeast(1))
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(thumb.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
