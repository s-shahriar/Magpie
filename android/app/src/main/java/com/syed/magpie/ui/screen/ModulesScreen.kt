package com.syed.magpie.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.syed.magpie.ui.Module

/** The first tab's landing page: one card per module, two to a row. */
@Composable
fun ModulesScreen(onOpen: (Module) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("Magpie", style = MaterialTheme.typography.displaySmall)
        Text(
            "What are we making?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Module.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { ModuleCard(it, { onOpen(it) }, Modifier.weight(1f)) }
                // An odd module out keeps its half-width rather than stretching.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun ModuleCard(module: Module, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(0.95f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    module.icon,
                    null,
                    Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(module.label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                module.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Title row inside a module; the arrow goes back to the hub. */
@Composable
fun ModuleHeader(module: Module, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .offset(x = (-10).dp)
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Modules")
        }
        Text(
            module.label,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.offset(x = (-4).dp),
        )
    }
}
