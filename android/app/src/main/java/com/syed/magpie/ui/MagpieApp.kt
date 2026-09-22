package com.syed.magpie.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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
import com.syed.magpie.ui.screen.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Fetch("Fetch", Icons.Default.Download),
    Library("Library", Icons.Default.VideoLibrary),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun MagpieApp(vm: MagpieViewModel) {
    var tab by remember { mutableStateOf(Tab.Fetch) }
    var login by remember { mutableStateOf<Cookies.Site?>(null) }

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
            Tab.Fetch -> HomeScreen(vm, onSignIn = { login = it })
            Tab.Library -> LibraryScreen(vm)
            Tab.Settings -> SettingsScreen(vm, onSignIn = { login = it })
        }

        NavBar(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 26.dp)
                .padding(bottom = 26.dp),
        )
    }

    vm.chooser?.let { info ->
        QualitySheet(
            info = info,
            onPick = { vm.start(info, it) },
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
