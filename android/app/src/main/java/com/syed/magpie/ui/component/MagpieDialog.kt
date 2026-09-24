package com.syed.magpie.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** One button in a [MagpieDialog], in the order it should be read. */
data class DialogAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The app's confirmation dialog.
 *
 * Actions are stacked full width, one per line, rather than laid along the
 * bottom: Material's row wraps as soon as the labels are longer than a word,
 * which left "Delete video" stranded on its own line above the two it was
 * meant to be weighed against. Stacked, the order on screen is the order of
 * consequence — the destructive choice first in its own tinted container,
 * then the mild one, then the way out.
 */
@Composable
fun MagpieDialog(
    title: String,
    message: String,
    primary: DialogAction,
    onDismiss: () -> Unit,
    subject: String? = null,
    secondary: DialogAction? = null,
    dismissLabel: String = "Cancel",
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 26.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                // The name goes on its own lines. A Facebook title is one long
                // unbroken token, and inside a sentence it breaks mid-word —
                // given room of its own it can end in an ellipsis instead.
                subject?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))

                Button(
                    onClick = primary.onClick,
                    shape = MaterialTheme.shapes.small,
                    colors = if (primary.destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(primary.label, style = MaterialTheme.typography.labelLarge) }

                secondary?.let {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = it.onClick,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(it.label, style = MaterialTheme.typography.labelLarge) }
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(
                        dismissLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
