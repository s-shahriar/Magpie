package com.syed.magpie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.Cookies
import com.syed.magpie.ui.component.QualitySheet
import com.syed.magpie.ui.screen.HomeScreen
import com.syed.magpie.ui.screen.LibraryScreen
import com.syed.magpie.ui.screen.LoginScreen
import com.syed.magpie.ui.screen.ModulesScreen
import com.syed.magpie.ui.screen.StillVideoScreen
import com.syed.magpie.ui.screen.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Modules("Modules", Icons.Default.GridView),
    Library("Library", Icons.Default.VideoLibrary),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun MagpieApp(vm: MagpieViewModel, still: StillVideoViewModel) {
    var tab by remember { mutableStateOf(Tab.Modules) }
    var login by remember { mutableStateOf<Cookies.Site?>(null) }

    // A notification tap asks for the queue, whatever tab was last open.
    LaunchedEffect(vm.libraryRequest) {
        if (vm.libraryRequest > 0) tab = Tab.Library
    }
    // A shared link or photo opens its module.
    LaunchedEffect(vm.moduleRequest) {
        if (vm.moduleRequest > 0) tab = Tab.Modules
    }

    val capturing = (vm.probe as? ProbeState.NeedsCapture)?.url
    var captureOpen by remember { mutableStateOf(false) }
    if (captureOpen && capturing != null) {
        com.syed.magpie.ui.screen.CaptureScreen(
            postUrl = capturing,
            onPicked = { items ->
                captureOpen = false
                vm.onCaptured(items, capturing)
            },
            onDone = { captureOpen = false },
        )
        return
    }

    val site = login
    if (site != null) {
        LoginScreen(site) {
            login = null
            // Coming back from a successful sign-in, retry what was asked for.
            if (vm.link.isNotBlank()) vm.fetch()
        }
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (tab) {
            Tab.Modules -> {
                val toHub = { vm.module = null }
                if (vm.module != null) BackHandler(onBack = toHub)
                when (vm.module) {
                    null -> ModulesScreen(onOpen = vm::enterModule)
                    Module.Downloader -> HomeScreen(
                        vm,
                        onSignIn = { login = it },
                        onCapture = { captureOpen = true },
                        onBack = toHub,
                    )
                    Module.StillVideo -> StillVideoScreen(
                        still,
                        onBack = toHub,
                        onOpen = vm::open,
                        onLibrary = {
                            vm.libraryModule = Module.StillVideo
                            tab = Tab.Library
                        },
                    )
                }
            }
            Tab.Library -> LibraryScreen(
                vm,
                still,
                onEditStill = {
                    still.edit(it)
                    vm.openModule(Module.StillVideo)
                },
            )
            Tab.Settings -> SettingsScreen(vm, onSignIn = { login = it })
        }

        NavBar(
            current = tab,
            onSelect = {
                // Tapping Modules again from inside a module goes back to the hub.
                if (it == Tab.Modules && tab == Tab.Modules) vm.module = null
                tab = it
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 26.dp)
                .padding(bottom = 26.dp),
        )
    }

    vm.chooser?.let { info ->
        QualitySheet(
            info = info,
            onPick = { rendition, name -> vm.start(info, rendition, name) },
            onDismiss = vm::dismissChooser,
        )
    }
}

/**
 * Floating dark pill, matching the reference: the bar sits above the content
 * rather than filling the bottom edge, and the active item gets a coral chip.
 */
@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(66.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val active = t == current
                val pad by animateDpAsState(if (active) 18.dp else 0.dp, label = "pill")
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .clickable(onClick = { onSelect(t) })
                        .padding(horizontal = 14.dp + pad, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        t.icon,
                        t.label,
                        Modifier.size(21.dp),
                        tint = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
