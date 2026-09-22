package com.syed.magpie.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.Cookies
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.ProbeState

@Composable
fun HomeScreen(
    vm: MagpieViewModel,
    onSignIn: (Cookies.Site) -> Unit,
    onCapture: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text(
            "Magpie",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(26.dp))

        OutlinedTextField(
            value = vm.link,
            onValueChange = { vm.link = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Paste a Facebook or Drive link") },
            singleLine = false,
            maxLines = 3,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.fetch() }),
            trailingIcon = {
                if (vm.link.isEmpty()) {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { vm.link = it.trim() }
                    }) { Icon(Icons.Default.ContentPaste, "Paste") }
                } else {
                    IconButton(onClick = vm::clearLink) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = vm::fetch,
            enabled = vm.link.isNotBlank() && vm.probe !is ProbeState.Working,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) {
            // The label sits at true centre and the spinner floats beside it.
            // Laying them out as a centred row pushed the text off-centre by
            // half the spinner's width the moment the button was pressed.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val working = vm.probe is ProbeState.Working
                Text(if (working) "Reading link…" else "Fetch qualities")
                if (working) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.CenterStart).size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        when (val s = vm.probe) {
            is ProbeState.NeedsLogin -> Notice(
                title = "Sign in to ${s.site.label}",
                body = "Magpie opens ${s.site.label} in its own window and keeps the session " +
                    "for later. Nothing is sent anywhere else.",
                actionLabel = "Sign in",
                onAction = { onSignIn(s.site) },
            )
            is ProbeState.NeedsCapture -> Notice(
                title = "One more tap for this one",
                body = "Facebook no longer puts the video in the page, so Magpie " +
                    "watches the player instead. Open it and press play — the " +
                    "qualities appear as it starts.",
                actionLabel = "Open video",
                onAction = onCapture,
            )
            is ProbeState.Failed -> Notice(
                title = "Could not read that link",
                body = s.message,
                actionLabel = null,
                onAction = {},
                error = true,
            )
            else -> Unit
        }

        Spacer(Modifier.height(30.dp))
        Text(
            "Works with view-only Drive files and private group posts you already have access to.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun Notice(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    error: Boolean = false,
) {
    val accent = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.medium,
        // A calm card with a coloured edge. The filled container read as a
        // slab of orange in dark mode, louder than the thing it was announcing.
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
                    Button(
                        onClick = onAction,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
