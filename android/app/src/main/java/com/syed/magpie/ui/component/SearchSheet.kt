package com.syed.magpie.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.magpie_core.YtEntry
import uniffi.magpie_core.YtPlaylist

/**
 * YouTube search results. A tap reads that video exactly as a pasted link
 * would, so the quality picker that follows is the usual one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    query: String,
    results: YtPlaylist,
    loadingMore: Boolean,
    onOpen: (YtEntry) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Text(
                "“$query”",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results.entries, key = { it.id }) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = e.available) { onOpen(e) }
                            .alpha(if (e.available) 1f else 0.45f)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WebThumb(
                            e.thumbnail,
                            Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                            badge = formatClock(e.durationSecs.toLong()).ifEmpty { if (e.available) null else "live" },
                            maxWidth = 320,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                listOfNotNull(e.channel, e.views?.let { views(it.toLong()) }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = onMore) { Text("More results") }
                        }
                    }
                }
            }
        }
    }
}

/** "1.2M views", the way YouTube rounds them. */
private fun views(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB views".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM views".format(n / 1e6)
    n >= 1_000 -> "%.0fK views".format(n / 1e3)
    else -> "$n views"
}.replace(".0", "")
