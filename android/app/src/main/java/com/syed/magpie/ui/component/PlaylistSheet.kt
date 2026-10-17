package com.syed.magpie.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.YouTube
import com.syed.magpie.data.YouTubePrefs
import com.syed.magpie.data.formatDuration
import uniffi.magpie_core.YtPlaylist

/**
 * A playlist, mix or channel: tick what to take, pick one quality for all of
 * it, and queue. Nothing is sized here — that would mean reading every
 * video's formats up front, a minute or more for a long course — so each
 * entry finds its quality when its turn in the queue comes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(
    list: YtPlaylist,
    prefs: YouTubePrefs,
    onQueue: (ids: Set<String>, quality: YouTubePrefs.Quality, numbered: Boolean, subtitleLang: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = remember(list.id) { list.entries.filter { it.available }.map { it.id }.toSet() }
    var chosen by remember(list.id) { mutableStateOf(available) }
    var quality by remember(list.id) {
        mutableStateOf(prefs.sharedQuality.takeIf { it != YouTubePrefs.Quality.ASK } ?: YouTubePrefs.Quality.P720)
    }
    var numbered by remember(list.id) { mutableStateOf(true) }
    var subtitles by remember(list.id) { mutableStateOf<String?>(null) }
    val totalSecs = remember(list.id, chosen) {
        list.entries.filter { it.id in chosen }.sumOf { it.durationSecs.toLong() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(list.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(list.uploader, "${list.entries.size} videos").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Saves to Downloads/${YouTube.folderFor(list.title)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                // Opens scrolled to the preset in use; 720p sits off-screen
                // otherwise, and the row looks like nothing is chosen.
                LazyRow(
                    state = rememberLazyListState(
                        (YouTubePrefs.Quality.presets.indexOf(quality) - 1).coerceAtLeast(0),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(YouTubePrefs.Quality.presets) { q ->
                        FilterChip(
                            selected = quality == q,
                            onClick = { quality = q },
                            label = { Text(q.label.removePrefix("Up to ")) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = numbered,
                        onClick = { numbered = !numbered },
                        label = { Text("Number files") },
                        leadingIcon = { Icon(Icons.Default.FormatListNumbered, null, Modifier.size(18.dp)) },
                    )
                    if (quality != YouTubePrefs.Quality.AUDIO) {
                        SubtitleChoice(prefs, subtitles) { subtitles = it }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${chosen.size} of ${available.size}" +
                            formatDuration(totalSecs).let { if (it.isEmpty()) "" else " · $it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { chosen = if (chosen.size == available.size) emptySet() else available }) {
                        Text(if (chosen.size == available.size) "Select none" else "Select all")
                    }
                }
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list.entries, key = { "${it.index}-${it.id}" }) { e ->
                    val on = e.id in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = e.available) {
                                chosen = if (on) chosen - e.id else chosen + e.id
                            }
                            .alpha(if (e.available) 1f else 0.45f)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { chosen = if (on) chosen - e.id else chosen + e.id },
                            enabled = e.available,
                        )
                        WebThumb(
                            e.thumbnail,
                            Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                            badge = formatClock(e.durationSecs.toLong()),
                            maxWidth = 200,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (e.available) "#${e.index}" else "#${e.index} · unavailable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Button(
                    onClick = { onQueue(chosen, quality, numbered, subtitles) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                        .navigationBarsPadding()
                        .height(54.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    val noun = if (quality == YouTubePrefs.Quality.AUDIO) "track" else "video"
                    Text(
                        when (chosen.size) {
                            0 -> "Choose something to download"
                            1 -> "Download 1 $noun"
                            else -> "Download ${chosen.size} ${noun}s"
                        },
                    )
                }
            }
        }
    }
}

/**
 * Which captions to fetch beside each video. A playlist's languages are not
 * known until each video is read, so this offers the usual few; a video
 * without the language simply comes without an .srt.
 */
@Composable
private fun SubtitleChoice(prefs: YouTubePrefs, current: String?, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = buildList {
        add(null to "No subtitles")
        prefs.subtitleLang.takeIf { it.isNotBlank() }?.let { add(it to it.uppercase()) }
        listOf("en" to "English", "bn" to "Bangla", "hi" to "Hindi", "ar" to "Arabic")
            .filter { (code, _) -> code != prefs.subtitleLang }
            .forEach { add(it) }
    }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(options.firstOrNull { it.first == current }?.second?.let { if (current == null) it else "Subtitles: $it" } ?: "Subtitles") },
            leadingIcon = { Icon(Icons.Default.ClosedCaption, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChoose(code) })
            }
        }
    }
}
