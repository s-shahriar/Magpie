package com.syed.magpie.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.syed.magpie.data.Hints
import com.syed.magpie.data.Subtitles

/**
 * A job's running log, the way the SRT Bengali app showed one: every batch,
 * what came back, what was kept, every wait, and a few hinted lines so the
 * quality can be judged by eye while the film goes through. Follows the
 * newest line unless the reader scrolls up.
 */
@Composable
fun SubtitleLogPanel(
    lines: List<String>,
    hintColor: Subtitles.HintColor,
    modifier: Modifier = Modifier,
    height: Int = 280,
) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.animateScrollToItem(lines.lastIndex)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                "LOG",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 6.dp),
            )
            if (lines.isEmpty()) {
                Text(
                    "Nothing yet — the first batch is on its way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, bottom = 12.dp),
                )
                return@Column
            }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(max = height.dp).padding(horizontal = 14.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(lines) { _, line -> LogLine(line, hintColor) }
            }
        }
    }
}

@Composable
private fun LogLine(line: String, hintColor: Subtitles.HintColor) {
    val failed = line.contains("FAILED") || line.contains("Rejected")
    val waiting = line.contains("Rate limited") || line.contains("busy") ||
        line.contains("Connection problem") || line.contains("Unreadable") || line.contains("skipped") ||
        line.contains("Paused")
    val sample = line.contains("   #")
    val base = when {
        failed -> MaterialTheme.colorScheme.error
        waiting -> MaterialTheme.colorScheme.primary
        sample -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A hinted sample shows its Bengali in the colour the file will carry.
    val accent = if (hintColor.on) Color(hintColor.argb) else MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        if (!sample) {
            append(line)
            return@buildAnnotatedString
        }
        var at = 0
        Hints.hintRegex.findAll(line).forEach { m ->
            append(line.substring(at, m.range.first))
            withStyle(SpanStyle(color = accent)) { append(m.value) }
            at = m.range.last + 1
        }
        append(line.substring(at))
    }
    Text(
        text,
        color = base,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

/** The same log, full screen height, for a job opened from the library. */
@Composable
fun SubtitleLogDialog(
    title: String,
    lines: List<String>,
    hintColor: Subtitles.HintColor,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                SubtitleLogPanel(lines, hintColor, height = 460)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
