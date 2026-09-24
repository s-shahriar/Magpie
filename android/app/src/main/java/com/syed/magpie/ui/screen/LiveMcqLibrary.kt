package com.syed.magpie.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.LiveMcq
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.LiveMcqViewModel
import com.syed.magpie.ui.component.CardAction
import com.syed.magpie.ui.component.CardButton
import com.syed.magpie.ui.component.CardStatus
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.LibraryCard
import com.syed.magpie.ui.component.MagpieDialog
import com.syed.magpie.ui.component.RenameDialog
import com.syed.magpie.ui.component.Thumb

/**
 * LiveMCQ's library: the exported files themselves, never mixed with the
 * other modules' rows.
 *
 * There is no queue behind this — the list is a read of Downloads/live_fav,
 * so a file deleted from Files disappears here too. Only Magpie's own exports
 * are visible; scoped storage hides anything another app wrote there, which
 * includes whatever the old Termux script left in the same folder.
 */
@Composable
fun LiveMcqLibrary(vm: LiveMcqViewModel) {
    var renaming by remember { mutableStateOf<LiveMcq.Saved?>(null) }
    var deleting by remember { mutableStateOf<LiveMcq.Saved?>(null) }

    // Changing a file the app does not own needs the system to ask first.
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { vm.consentAnswered() }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(vm.consent) {
        vm.consent?.let { ask.launch(IntentSenderRequest.Builder(it).build()) }
    }

    renaming?.let { file ->
        RenameDialog(
            title = "Rename export",
            current = file.fileName.removeSuffix(".json"),
            onRename = {
                vm.rename(file, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { file ->
        MagpieDialog(
            title = "Delete this export?",
            subject = file.fileName,
            message = "It will be removed from Downloads/live_fav. Your favourites " +
                "on LiveMCQ are untouched — you can always export them again.",
            primary = DialogAction("Delete file", destructive = true) {
                vm.delete(file)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        when {
            vm.loading && vm.saved.isEmpty() ->
                LinearProgressIndicator(Modifier.fillMaxWidth())

            vm.saved.isEmpty() -> Text(
                "No exports yet. The LiveMCQ module writes them here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.saved, key = { it.uri.toString() }) { file ->
                    LibraryCard(
                        title = file.fileName,
                        meta = describe(file),
                        thumb = Thumb(icon = Icons.Default.Star),
                        status = CardStatus.Saved,
                        savedIn = "Downloads/live_fav",
                        primary = {
                            CardButton(Icons.Default.Upload, "Upload") { vm.upload() }
                        },
                        menu = listOf(
                            CardAction("Open", Icons.Default.OpenInNew) { vm.view(file) },
                            CardAction("Send a copy", Icons.Default.Share) { vm.send(file) },
                            CardAction("Rename", Icons.Default.DriveFileRenameOutline) {
                                renaming = file
                            },
                            CardAction("Delete", Icons.Default.Delete, destructive = true) {
                                deleting = file
                            },
                        ),
                    )
                }
                item { Spacer(Modifier.height(110.dp)) }
            }
        }
    }
}

/**
 * "30 questions · 64.8 KB" — and honest when it cannot tell.
 *
 * No date: the card gives this one line and ellipsised it away, and the
 * default file name already carries the minute the export was taken.
 */
private fun describe(file: LiveMcq.Saved): String = listOf(
    when (file.count) {
        null -> "Not a favourites file"
        1 -> "1 question"
        else -> "${file.count} questions"
    },
    formatBytes(file.bytes),
).joinToString(" · ")
