package com.syed.magpie.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syed.magpie.BuildConfig
import com.syed.magpie.data.Cookies
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.MagpieViewModel
import com.syed.magpie.ui.UpdateState
import uniffi.magpie_core.coreVersion

@Composable
fun SettingsScreen(
    vm: MagpieViewModel,
    onSignIn: (Cookies.Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refresh by remember { mutableIntStateOf(0) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(22.dp))
        SectionTitle("Accounts")
        Cookies.Site.entries.forEach { site ->
            key(site, refresh) {
                val signedIn = Cookies.isSignedIn(site)
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(site.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (signedIn) "Signed in" else "Not signed in",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (signedIn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            if (signedIn) Cookies.signOut(site) { refresh++ } else onSignIn(site)
                        }) { Text(if (signedIn) "Sign out" else "Sign in") }
                    }
                }
            }
        }

        ImportCookiesRow { refresh++ }

        Spacer(Modifier.height(22.dp))
        SectionTitle("Updates")
        UpdateCard(vm)

        Spacer(Modifier.height(22.dp))
        SectionTitle("About")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("App version", BuildConfig.VERSION_NAME)
                InfoRow("Engine", "magpie-core ${coreVersion()}")
                InfoRow("Saves to", "Downloads/Magpie")
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

/**
 * Seeds the cookie jar from a desktop export.
 *
 * Useful when a site's sign-in will not work in a WebView — Facebook's passkey
 * flow, for one — or simply to avoid typing a password on a phone. Export with
 * `yt-dlp --cookies-from-browser chrome --cookies cookies.txt`.
 */
@Composable
private fun ImportCookiesRow(onImported: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        result = runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read that file")
            val counts = Cookies.importNetscape(text)
            if (counts.isEmpty()) {
                "No Facebook or Drive cookies in that file"
            } else {
                counts.entries.joinToString(", ") { "${it.key.label}: ${it.value}" }
            }
        }.getOrElse { it.message ?: "Import failed" }
        onImported()
    }

    Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Import cookies", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Sign in on a computer and bring the session over, instead of " +
                    "typing a password here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Action("Choose cookies.txt") {
                picker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            }
            result?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun UpdateCard(vm: MagpieViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (val s = vm.update) {
                is UpdateState.Idle -> Action("Check for updates") { vm.checkForUpdate() }
                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.UpToDate -> {
                    Text("You’re on the latest version (${s.version}).",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Action("Check again") { vm.checkForUpdate() }
                }
                is UpdateState.Available -> {
                    Text("Version ${s.info.latestVersion} is available",
                        style = MaterialTheme.typography.titleMedium)
                    s.info.notes?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it.take(400), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Action("Download") { vm.downloadUpdate(s.info) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = vm::dismissUpdate) { Text("Later") }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading update…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { s.progress.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatBytes(s.progress.bytesDownloaded)}" +
                            (s.progress.totalBytes?.let { " / ${formatBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Text("Ready to install ${s.info.latestVersion}",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Action("Install") { vm.install(s.file) }
                }
                is UpdateState.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(10.dp))
                    Action("Try again") { vm.checkForUpdate() }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ),
    ) { Text(label) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
