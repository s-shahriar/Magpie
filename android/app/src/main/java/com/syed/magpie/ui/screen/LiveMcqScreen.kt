package com.syed.magpie.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.Cookies
import com.syed.magpie.ui.LiveMcqViewModel
import com.syed.magpie.ui.Module

/**
 * Favourites out of LiveMCQ and into a file.
 *
 * Deliberately one screen and one button: the old way was a rooted Termux
 * shell, and anything more than "how many, then go" would not be an
 * improvement on it.
 */
@Composable
fun LiveMcqScreen(
    vm: LiveMcqViewModel,
    onBack: () -> Unit,
    onSignIn: (Cookies.Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        ModuleHeader(Module.LiveMcq, onBack)
        Spacer(Modifier.height(14.dp))

        if (!vm.signedIn || vm.signedOut) {
            Notice(
                title = "Sign in to LiveMCQ",
                body = "Use the phone number and OTP form — the Google and Facebook " +
                    "buttons need a popup this WebView cannot open. The session " +
                    "stays on this phone and is all Magpie needs; no root, no Termux.",
                actionLabel = "Sign in",
                icon = Icons.Default.Login,
                onAction = { onSignIn(Cookies.Site.LIVEMCQ) },
            )
            Spacer(Modifier.height(100.dp))
            return@Column
        }

        Text(
            "Your favourites, saved as JSON in Downloads/live_fav — the same file " +
                "the quiz admin panel expects.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Choice(
            label = "New since last export",
            detail = vm.baseline?.let { "Everything above favourite $it" }
                ?: "Nothing to count from yet — take a batch by count first",
            selected = vm.newOnly,
            enabled = vm.baseline != null && !vm.running,
            onClick = { vm.newOnly = true },
        )
        Spacer(Modifier.height(10.dp))
        Choice(
            label = "Newest by count",
            detail = "Takes them 20 at a time, newest first",
            selected = !vm.newOnly,
            enabled = !vm.running,
            onClick = { vm.newOnly = false },
        )

        if (!vm.newOnly) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = vm.count,
                onValueChange = { new -> vm.count = new.filter { it.isDigit() }.take(4) },
                label = { Text("How many") },
                singleLine = true,
                enabled = !vm.running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = vm::export,
            enabled = !vm.running,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (vm.running) "Fetching…" else "Export favourites")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = vm::check, enabled = !vm.running) { Text("Check account") }
            if (vm.baseline != null) {
                TextButton(onClick = vm::forget, enabled = !vm.running) {
                    Text("Forget last export")
                }
            }
        }

        vm.progress?.let { p ->
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (p.pages > 0) p.page.toFloat() / p.pages else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Page ${p.page} of ${p.pages} · ${p.questions} question" +
                    if (p.questions == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.peek?.let { p ->
            Spacer(Modifier.height(14.dp))
            Notice(
                title = "${p.total} favourites on the account",
                body = "${p.pages} pages" +
                    (p.newest?.let { " · newest is $it" } ?: "") +
                    (vm.baseline?.let { "\nLast exported up to $it" } ?: ""),
                actionLabel = null,
                onAction = {},
            )
        }

        vm.result?.let { done ->
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${done.count} saved",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Download/live_fav/${done.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    done.newest?.let {
                        Text(
                            "Newest favourite $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = vm::upload,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(Icons.Default.Upload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Upload to admin")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Opens the admin panel in your browser — pick this file " +
                            "under \"Choose livefav JSON\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        vm.error?.let {
            Spacer(Modifier.height(14.dp))
            Notice(
                title = "Nothing was saved",
                body = it,
                actionLabel = "Dismiss",
                onAction = vm::dismissError,
                error = true,
            )
        }

        Spacer(Modifier.height(100.dp))
    }
}

/** A radio row that reads as a card, matching the module hub's surfaces. */
@Composable
private fun Choice(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val edge = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
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

/** Same calm card the downloader uses for its prompts. */
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
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
