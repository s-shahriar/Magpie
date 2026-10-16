package com.syed.magpie.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.syed.magpie.data.ApkBundle
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.InstallerViewModel
import com.syed.magpie.ui.InstallerViewModel.State
import com.syed.magpie.ui.Module

/**
 * SAI, inside Magpie: one picker for `.apk`, `.apks`, `.xapk`, `.apkm` and
 * zipped splits. Splits are pre-chosen for this phone, the way the Play Store
 * would, and can be changed before installing.
 */
@Composable
fun InstallerScreen(vm: InstallerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Coming back from the "Install unknown apps" switch.
    LifecycleResumeEffect(Unit) {
        vm.refreshAllowed()
        onPauseOrDispose { }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "package.apk"
        vm.pick(uri, name)
    }
    val pick = { picker.launch(arrayOf("*/*")) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        ModuleHeader(Module.Installer, onBack)
        Spacer(Modifier.height(14.dp))

        if (!vm.allowed) {
            Notice(
                title = "Let Magpie install apps",
                body = "Android asks once per app. Turn on \"Allow from this source\" for " +
                    "Magpie, then come back.",
                actionLabel = "Open Settings",
                icon = Icons.Default.Security,
                onAction = vm::openAllowSettings,
            )
            Spacer(Modifier.height(16.dp))
        }

        Label("Package")
        when (val s = vm.state) {
            State.Empty -> PickCard(
                title = "Choose a file",
                detail = ".apk  ·  .apks  ·  .xapk  ·  .apkm  ·  .zip",
                onClick = pick,
            )
            State.Reading -> PickCard(title = "Reading…", detail = null, onClick = {}, enabled = false)
            is State.Ready -> {
                BundleCard(s.bundle, s.installed, onClick = pick)
                Spacer(Modifier.height(22.dp))
                SplitList(s.bundle, vm.chosen, vm::toggle)
                if (s.bundle.obbs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Carries ${s.bundle.obbs.size} game data file" +
                            "${if (s.bundle.obbs.size == 1) "" else "s"} (OBB), copied to " +
                            "Android/obb after the app installs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                val size = s.bundle.splits.filter { it.entry in vm.chosen }.sumOf { it.sizeBytes }
                Button(
                    onClick = vm::install,
                    enabled = vm.allowed,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Icon(Icons.Default.InstallMobile, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        (if (s.installed != null) "Update" else "Install") + "  ·  ${formatBytes(size)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            is State.Installing -> {
                BundleCard(s.bundle, null, onClick = {}, enabled = false)
                Spacer(Modifier.height(18.dp))
                Progress(
                    title = "Copying splits…",
                    detail = "${(s.progress * 100).toInt()}%",
                    progress = s.progress,
                    onCancel = vm::cancel,
                )
            }
            is State.Confirming -> {
                BundleCard(s.bundle, null, onClick = {}, enabled = false)
                Spacer(Modifier.height(18.dp))
                Progress(
                    title = "Waiting for you to confirm",
                    detail = "Android shows its own install sheet. If it didn't open, tap Cancel and try again.",
                    progress = null,
                    onCancel = vm::cancel,
                )
            }
            is State.Done -> {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("${s.label} installed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                        s.note?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row {
                            val pkg = s.packageName
                            if (pkg != null && remember(pkg) { vm.canLaunch(pkg) }) {
                                Button(onClick = { vm.launch(pkg) }, shape = MaterialTheme.shapes.small) { Text("Open") }
                                Spacer(Modifier.width(8.dp))
                            }
                            TextButton(onClick = { vm.reset(); pick() }) { Text("Install another") }
                        }
                    }
                }
            }
            is State.Failed -> {
                if (s.bundle != null) {
                    BundleCard(s.bundle, null, onClick = pick)
                    Spacer(Modifier.height(16.dp))
                }
                Notice(
                    title = if (s.bundle != null) "Not installed" else "Can't read this file",
                    body = s.message,
                    actionLabel = if (s.bundle != null) "Back to splits" else "Choose another",
                    onAction = { if (s.bundle != null) vm.back() else pick() },
                    error = true,
                )
            }
        }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun PickCard(title: String, detail: String?, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.InstallMobile, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What was picked: its icon and name, and how it compares to what's installed. */
@Composable
private fun BundleCard(bundle: ApkBundle, installed: String?, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = bundle.icon
            if (icon != null) {
                Image(
                    remember(icon) { icon.asImageBitmap() },
                    null,
                    Modifier.size(44.dp).clip(MaterialTheme.shapes.small),
                )
            } else {
                Box(
                    Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.InstallMobile, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(bundle.label ?: bundle.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                val version = listOfNotNull(bundle.versionName, bundle.format.label, formatBytes(bundle.sizeBytes))
                Text(
                    version.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                bundle.packageName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installed != null) {
                    Text(
                        "Installed: $installed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitList(bundle: ApkBundle, chosen: Set<String>, onToggle: (ApkBundle.Split) -> Unit) {
    if (bundle.splits.size <= 1) return
    Label("Splits  ·  ${chosen.size} of ${bundle.splits.size}")
    Text(
        "Picked for this phone. Uncheck what you don't need, or add more.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            bundle.splits.forEach { split ->
                Row(
                    Modifier.fillMaxWidth().padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = split.entry in chosen,
                        onCheckedChange = { onToggle(split) },
                        enabled = !split.required,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(split.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            kindLabel(split),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        formatBytes(split.sizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun kindLabel(split: ApkBundle.Split): String = when (split.kind) {
    ApkBundle.Kind.BASE -> "The app"
    ApkBundle.Kind.FEATURE -> "Feature module"
    ApkBundle.Kind.ABI -> "Native code · ${split.config?.replace('_', '-')}"
    ApkBundle.Kind.DENSITY -> "Screen density · ${split.config}"
    ApkBundle.Kind.LANGUAGE -> "Language · ${split.config}"
    ApkBundle.Kind.OTHER -> "Config · ${split.config}"
}

@Composable
private fun Progress(title: String, detail: String, progress: Float?, onCancel: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
            val color = MaterialTheme.colorScheme.primary
            val track = MaterialTheme.colorScheme.outlineVariant
            val bar = Modifier.fillMaxWidth().height(6.dp)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress }, modifier = bar, color = color, trackColor = track, strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(modifier = bar, color = color, trackColor = track, strokeCap = StrokeCap.Round)
            }
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- the same private helpers the other module screens carry ------------

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
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
