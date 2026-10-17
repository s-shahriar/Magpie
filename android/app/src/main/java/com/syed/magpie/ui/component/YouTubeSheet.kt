package com.syed.magpie.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.WebImages
import com.syed.magpie.data.YouTube
import com.syed.magpie.data.YouTubePrefs
import com.syed.magpie.data.formatBytes
import com.syed.magpie.data.formatDuration
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Rendition
import uniffi.magpie_core.SubTrack
import uniffi.magpie_core.codecLabel

/**
 * YouTube's quality picker: the same contract as [QualitySheet] — every row
 * shows what it costs before anything downloads, smallest first — with what
 * YouTube adds on top. Its ladder repeats each height in up to three codecs,
 * so rows are one per height and the codec is a filter; the audio is chosen
 * rather than assumed, because a video can carry dubbed tracks; and captions
 * can come along as an .srt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSheet(
    info: MediaInfo,
    prefs: YouTubePrefs,
    onPick: (video: Rendition?, audio: Rendition?, name: String, subtitle: SubTrack?) -> Unit,
    onPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(info.mediaId) { mutableStateOf(info.title) }
    var renaming by remember(info.mediaId) { mutableStateOf(false) }
    var audioOnly by remember(info.mediaId) { mutableStateOf(info.video.isEmpty()) }
    var codec by remember(info.mediaId) { mutableStateOf<String?>(null) }

    // Audio tracks by language. One entry when the video has no dubs, and
    // then the picker for it stays out of the way.
    val tracks = remember(info.mediaId) { audioTracks(info) }
    var track by remember(info.mediaId) {
        mutableStateOf(
            tracks.firstOrNull { t -> prefs.audioLang.isNotBlank() && t.lang?.startsWith(prefs.audioLang) == true }
                ?: tracks.firstOrNull { it.original } ?: tracks.firstOrNull(),
        )
    }
    var subtitle by remember(info.mediaId) {
        mutableStateOf<SubTrack?>(
            info.subtitles.firstOrNull { !it.auto && it.lang == prefs.subtitleLang.ifBlank { null } },
        )
    }

    val codecs = remember(info.mediaId) {
        info.video.filter { it.kind == "video" }.mapNotNull { it.codec }.distinct()
            .sortedBy { listOf("h264", "vp9", "av1").indexOf(it).let { i -> if (i < 0) 9 else i } }
    }
    val rows = remember(info.mediaId, codec, track) {
        videoRows(info, codec, prefs.codec).map { v ->
            val audio = if (v.kind == "muxed") null else YouTube.audioFor(info, v, track?.lang)
            v to audio
        }
    }
    val audioRows = remember(info.mediaId, track) {
        info.audio.filter { track == null || it.language == track?.lang }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        LazyColumn(
            Modifier.padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Header(info, name, renaming, onName = { name = it }, onRenaming = { renaming = it })
            }
            if (info.playlistUrl != null) {
                item {
                    Surface(
                        onClick = onPlaylist,
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Part of a playlist", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("Show all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (info.video.isNotEmpty()) {
                        ModeTab("Video", selected = !audioOnly, Modifier.weight(1f)) { audioOnly = false }
                    }
                    ModeTab("Audio only", selected = audioOnly, Modifier.weight(1f)) { audioOnly = true }
                }
            }
            item {
                Options(
                    tracks = tracks,
                    track = track,
                    onTrack = { track = it },
                    subtitles = info.subtitles,
                    subtitle = subtitle,
                    onSubtitle = { subtitle = it },
                    showSubtitles = !audioOnly,
                )
            }
            if (!audioOnly && codecs.size > 1) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = codec == null,
                            onClick = { codec = null },
                            label = { Text(if (prefs.codec == YouTubePrefs.Codec.COMPAT) "Most compatible" else "Smallest") },
                        )
                        codecs.forEach { c ->
                            FilterChip(selected = codec == c, onClick = { codec = c }, label = { Text(codecLabel(c)) })
                        }
                    }
                }
            }

            if (audioOnly) {
                val first = audioRows.firstOrNull()
                items(audioRows, key = { it.id }) { a ->
                    ChoiceRow(
                        title = a.label,
                        detail = listOfNotNull(
                            if (a.mime == "audio/webm") ".webm" else ".m4a",
                            a.trackName?.takeIf { tracks.size > 1 },
                        ).joinToString(" · "),
                        bytes = a.approxBytes?.toLong(),
                        exact = a.exactSize,
                        smallest = a == first,
                        onClick = { onPick(null, a, name.trim().ifBlank { info.title }, null) },
                    )
                }
            } else {
                val first = rows.firstOrNull()?.first
                items(rows, key = { it.first.id }) { (v, a) ->
                    val total = v.approxBytes?.toLong()?.let { it + (a?.approxBytes?.toLong() ?: 0) }
                    ChoiceRow(
                        title = v.label,
                        detail = videoDetail(v, a),
                        bytes = total,
                        exact = v.exactSize && (a == null || a.exactSize),
                        smallest = v == first,
                        onClick = { onPick(v, a, name.trim().ifBlank { info.title }, subtitle) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    info: MediaInfo,
    name: String,
    renaming: Boolean,
    onName: (String) -> Unit,
    onRenaming: (Boolean) -> Unit,
) {
    Column {
        info.thumbnail?.let { url ->
            WebThumb(
                url,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium),
                badge = formatClock(info.durationSecs.toLong()),
            )
            Spacer(Modifier.height(14.dp))
        }
        if (renaming) {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text("File name") },
                supportingText = { Text("The quality and extension are added for you") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRenaming(false) }),
                trailingIcon = {
                    IconButton(onClick = { onRenaming(false) }) {
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
                IconButton(onClick = { onRenaming(true) }) {
                    Icon(Icons.Default.Edit, "Rename before downloading", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(
            listOfNotNull("YouTube", info.uploader, formatDuration(info.durationSecs.toLong()).ifEmpty { null })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The audio track and subtitle pickers, each only when there is a choice. */
@Composable
private fun Options(
    tracks: List<Track>,
    track: Track?,
    onTrack: (Track) -> Unit,
    subtitles: List<SubTrack>,
    subtitle: SubTrack?,
    onSubtitle: (SubTrack?) -> Unit,
    showSubtitles: Boolean,
) {
    val anyTracks = tracks.size > 1
    val anySubs = showSubtitles && subtitles.isNotEmpty()
    if (!anyTracks && !anySubs) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (anyTracks) {
            Picker(
                icon = Icons.Default.GraphicEq,
                label = track?.name ?: "Audio",
                options = tracks.map { it.name to it },
                onChoose = onTrack,
                modifier = Modifier.weight(1f),
            )
        }
        if (anySubs) {
            Picker(
                icon = Icons.Default.ClosedCaption,
                label = subtitle?.let { it.name + if (it.auto) " (auto)" else "" } ?: "No subtitles",
                options = listOf<Pair<String, SubTrack?>>("No subtitles" to null) +
                    subtitles.map { (it.name + if (it.auto) " (auto)" else "") to it },
                onChoose = onSubtitle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun <T> Picker(
    icon: ImageVector,
    label: String,
    options: List<Pair<String, T>>,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onChoose(value) })
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    detail: String,
    bytes: Long?,
    exact: Boolean,
    smallest: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = if (smallest) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (smallest) {
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
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                bytes?.let { (if (exact) "" else "~") + formatBytes(it) } ?: "size unknown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A picture from the web, faded in over a quiet placeholder. */
@Composable
fun WebThumb(url: String, modifier: Modifier, badge: String? = null, maxWidth: Int = 480) {
    val image by produceState<ImageBitmap?>(WebImages.cached(url), url) {
        if (value == null) value = WebImages.load(url, maxWidth)
    }
    Box(modifier.background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
        AnimatedVisibility(image != null, enter = fadeIn(tween(250)), modifier = Modifier.matchParentSize()) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        if (!badge.isNullOrEmpty()) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** "1:02:03" / "4:05", the way a video player labels a length. */
fun formatClock(secs: Long): String = when {
    secs <= 0 -> ""
    secs >= 3600 -> "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
    else -> "%d:%02d".format(secs / 60, secs % 60)
}

// ---- shaping the ladder ----------------------------------------------------

/** One audio language on offer. */
private data class Track(val lang: String?, val name: String, val original: Boolean)

private fun audioTracks(info: MediaInfo): List<Track> =
    info.audio.groupBy { it.language }.map { (lang, list) ->
        val original = list.any { it.original }
        val name = list.firstNotNullOfOrNull { it.trackName } ?: lang ?: "Audio"
        Track(lang, name.replace(" original", "") + if (original && info.audio.map { it.language }.distinct().size > 1) " (original)" else "", original)
    }.sortedWith(compareByDescending<Track> { it.original }.thenBy { it.name })

/**
 * One row per height, frame rate and dynamic range, in the codec asked for —
 * or, with no codec chosen, the one the codec setting prefers. A file with
 * its own audio keeps a row of its own. Smallest first, as everywhere.
 */
private fun videoRows(info: MediaInfo, codec: String?, pref: YouTubePrefs.Codec): List<Rendition> {
    val order = if (pref == YouTubePrefs.Codec.COMPAT) listOf("h264", "vp9", "av1") else listOf("av1", "vp9", "h264")
    val rank = { r: Rendition -> order.indexOf(r.codec).let { if (it < 0) 9 else it } }
    return info.video
        .filter { codec == null || it.codec == codec || it.kind == "muxed" && codec == "h264" }
        .groupBy { listOf(it.height, it.fps, it.hdr, it.kind == "muxed") }
        .values
        .map { group -> group.minWith(compareBy<Rendition>(rank).thenByDescending { it.bitrate }) }
        .sortedBy { it.approxBytes?.toLong() ?: Long.MAX_VALUE }
}

private fun videoDetail(v: Rendition, a: Rendition?): String = buildList {
    if (v.kind == "muxed") {
        add("audio included")
    } else {
        v.codec?.let { add(codecLabel(it)) }
        a?.let { add("${codecLabel(it.codec ?: "")} audio") }
    }
    if (v.width != null && v.height != null) add("${v.width}×${v.height}")
}.joinToString(" · ")
