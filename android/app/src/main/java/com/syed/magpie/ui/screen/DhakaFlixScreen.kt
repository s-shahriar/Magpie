package com.syed.magpie.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syed.magpie.data.DhakaFlix
import com.syed.magpie.data.DhakaFlixAi
import com.syed.magpie.data.DownloadStatus
import com.syed.magpie.data.Gemini
import com.syed.magpie.data.formatBytes
import com.syed.magpie.ui.AiState
import com.syed.magpie.ui.DhakaFlixViewModel
import com.syed.magpie.ui.FolderPage
import com.syed.magpie.ui.Module
import com.syed.magpie.ui.component.DialogAction
import com.syed.magpie.ui.component.MagpieDialog
import uniffi.magpie_core.DfCategory
import uniffi.magpie_core.DfItem
import java.text.DateFormat
import java.util.Date

/** Posters are fetched for this many rows; each is a ~200 KB JPEG. */
private const val MAX_POSTERS = 40

@Composable
fun DhakaFlixScreen(
    vm: DhakaFlixViewModel,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
) {
    BackHandler { if (!vm.back()) onBack() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.snack) {
        val s = vm.snack ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(s.text, actionLabel = s.action, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) onLibrary()
        vm.snack = null
    }

    Box(Modifier.fillMaxSize()) {
        val page = vm.pages.lastOrNull()
        if (page != null) {
            FolderBrowser(page, vm, onBack = { vm.back() })
        } else {
            SearchHome(vm, onBack)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp).padding(bottom = 104.dp),
        ) {
            DownloadingStrip(vm, onLibrary)
            SnackbarHost(snackbar)
        }
    }

    vm.problem?.let { p -> ProblemDialog(p) { vm.problem = null } }
    if (vm.aiOpen) AiSheet(vm)
}

// ---- search --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHome(vm: DhakaFlixViewModel, onBack: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val results = vm.results

    // Pulling down runs the search again, as the old app's home did.
    PullToRefreshBox(
        isRefreshing = vm.searching,
        onRefresh = { vm.search() },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 170.dp),
    ) {
        item {
            Spacer(Modifier.height(26.dp))
            ModuleHeader(Module.DhakaFlix, onBack)
            Spacer(Modifier.height(12.dp))
            Label("Browse by category")
            Spacer(Modifier.height(8.dp))
            CategoryCard(vm.category, vm.categories.size) { picking = true }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconFor(vm.category.icon),
                    null,
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    vm.category.hint,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Label("Search")
            Spacer(Modifier.height(8.dp))
            SearchForm(vm, onHistory = { showHistory = true })
        }

        vm.hint?.let { h ->
            item {
                Text(
                    h,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (results != null) {
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (results.truncated) "Showing top ${results.items.size} results — refine your search"
                    else "Found ${results.items.size} result${if (results.items.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(results.items, key = { _, it -> it.url + it.label }) { index, item ->
                ResultRow(
                    item,
                    showPoster = index < MAX_POSTERS,
                    onOpen = { vm.open(item) },
                    onDownload = { vm.download(item) },
                    onCopied = { vm.snack = com.syed.magpie.ui.Snack("Link copied") },
                )
            }
        }
    }
    }

    if (picking) {
        CategorySheet(vm.categories, vm.category, onPick = { vm.selectCategory(it); picking = false }) {
            picking = false
        }
    }
    if (showHistory) {
        HistorySheet(
            vm.recent,
            onPick = { showHistory = false; vm.searchFromHistory(it) },
            onClear = { vm.clearHistory(); showHistory = false },
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun SearchForm(vm: DhakaFlixViewModel, onHistory: () -> Unit) {
    OutlinedTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Movie / series name…") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.LiveTv, null) },
        trailingIcon = {
            Row {
                if (vm.recent.isNotEmpty()) {
                    IconButton(onClick = onHistory) { Icon(Icons.Default.History, "Recent searches") }
                }
                if (vm.query.isNotBlank()) {
                    IconButton(onClick = vm::openAi) {
                        Icon(Icons.Default.AutoAwesome, "Ask Gemini", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        shape = MaterialTheme.shapes.medium,
        colors = fieldColors(),
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (vm.category.supportsYear) {
            OutlinedTextField(
                value = vm.year,
                onValueChange = { v -> vm.year = v.filter(Char::isDigit).take(4) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Year (optional)") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                shape = MaterialTheme.shapes.medium,
                colors = fieldColors(),
            )
            Spacer(Modifier.width(10.dp))
        }
        Button(
            onClick = { vm.search() },
            enabled = !vm.searching,
            modifier = Modifier.height(56.dp).let { if (vm.category.supportsYear) it else it.fillMaxWidth() },
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) {
            if (vm.searching) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondary,
                )
            } else {
                Icon(Icons.Default.Search, null)
            }
            if (!vm.category.supportsYear) {
                Spacer(Modifier.width(8.dp))
                Text(if (vm.searching) "Searching…" else "Search")
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- categories ----------------------------------------------------------

private fun colorOf(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

private fun iconFor(name: String): ImageVector = when (name) {
    "apps" -> Icons.Default.Apps
    "movie_creation" -> Icons.Default.MovieCreation
    "animation" -> Icons.Default.Animation
    "tv" -> Icons.Default.Tv
    "public" -> Icons.Default.Public
    else -> Icons.Default.Movie
}

@Composable
private fun CategoryTile(c: DfCategory, size: Int = 40) {
    val tint = colorOf(c.color)
    Box(
        Modifier.size(size.dp).clip(MaterialTheme.shapes.small).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconFor(c.icon), null, Modifier.size((size * 0.55).dp), tint = tint)
    }
}

@Composable
private fun CategoryCard(c: DfCategory, count: Int, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(c)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$count categories available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ExpandMore, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    all: List<DfCategory>,
    current: DfCategory,
    onPick: (DfCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("Select category", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            all.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        val selected = c.id == current.id
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable { onPick(c) },
                        ) {
                            Box {
                                Column(Modifier.padding(12.dp)) {
                                    CategoryTile(c, 36)
                                    Spacer(Modifier.height(8.dp))
                                    Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2)
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        "Selected",
                                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- results and folders ------------------------------------------------

@Composable
private fun ResultRow(
    item: DfItem,
    showPoster: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showPoster && item.isFolder) {
                Poster(item.url, null, 44, 66)
            } else {
                Box(
                    Modifier.size(44.dp).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            item.isFolder -> Icons.Default.Folder
                            DhakaFlix.isSubtitle(item.name) -> Icons.Default.Subtitles
                            else -> Icons.Default.Movie
                        },
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    item.sizeBytes?.let { formatBytes(it.toLong()) },
                    item.modifiedMs?.let {
                        (if (item.isFolder) "Added " else "Modified ") +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
                    },
                )
                if (item.label != null || meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.label?.let { Badge(it) }
                        if (item.label != null && meta.isNotEmpty()) Spacer(Modifier.width(8.dp))
                        if (meta.isNotEmpty()) {
                            Text(
                                meta.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (item.isFolder) {
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(item.url))
                    onCopied()
                }) { Icon(Icons.Default.Link, "Copy link", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledTonalIconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download") }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

@Composable
private fun Poster(folderUrl: String, knownImage: String?, width: Int, height: Int) {
    val image by produceState<ImageBitmap?>(null, folderUrl, knownImage) {
        value = DhakaFlix.poster(folderUrl, knownImage)
    }
    Box(
        Modifier.size(width.dp, height.dp).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = image
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderBrowser(page: FolderPage, vm: DhakaFlixViewModel, onBack: () -> Unit) {
    val folder = page.folder
    val folders = folder?.items?.count { it.isFolder } ?: 0
    val files = (folder?.items?.size ?: 0) - folders

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 170.dp),
    ) {
        item {
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.offset(x = (-10).dp).size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(page.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (folder != null) {
                        Text(
                            if (folder.items.isEmpty()) "Empty"
                            else "$folders folder${if (folders == 1) "" else "s"} · $files file${if (files == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (folder?.poster != null) {
                        Poster(page.url, folder.poster, 60, 90)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(
                            page.category.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            DhakaFlix.pathOf(page.url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        when {
            page.loading -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading folder contents…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            folder == null -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Could not open this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = vm::reload) { Text("Try again") }
                }
            }
            folder.items.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.SearchOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("No media found", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No video or subtitle files in this folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> items(folder.items, key = { it.url }) { item ->
                ResultRow(
                    item,
                    showPoster = false,
                    onOpen = { if (item.isFolder) vm.open(item) },
                    onDownload = { vm.download(item, page.category) },
                    onCopied = { vm.snack = com.syed.magpie.ui.Snack("Link copied") },
                )
            }
        }
    }
}

// ---- the running-downloads strip ----------------------------------------

/**
 * Downloads keep going while the user browses on, so the module says so:
 * how many are moving, their combined progress and speed. A tap opens the
 * library.
 */
@Composable
private fun DownloadingStrip(vm: DhakaFlixViewModel, onLibrary: () -> Unit) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val moving = jobs.filter { it.source == DhakaFlix.SOURCE && it.status == DownloadStatus.DOWNLOADING }
    if (moving.isEmpty()) return
    val pct = (moving.map { it.fraction }.average() * 100).toInt()
    val speed = moving.sumOf { it.bytesPerSecond }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(50)).clickable(onClick = onLibrary),
    ) {
        Box {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(pct / 100f)
                        .background(Color.White.copy(alpha = 0.18f)),
                )
            }
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Downloading, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Downloading ${moving.size} file${if (moving.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$pct%" + if (speed > 0) " · ${formatBytes(speed)}/s" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(Icons.Default.ChevronRight, null)
            }
        }
    }
}

// ---- dialogs and sheets --------------------------------------------------

@Composable
private fun ProblemDialog(p: DhakaFlix.Problem, onDismiss: () -> Unit) {
    val message = buildString {
        append(p.message)
        p.endpoint?.let { append("\n\n").append(it) }
        p.status?.let { append("\nHTTP ").append(it) }
        append("\n\n")
        append(p.tips.joinToString("\n") { "• $it" })
    }
    MagpieDialog(
        title = p.title,
        message = message,
        primary = DialogAction("Got it", onClick = onDismiss),
        onDismiss = onDismiss,
        dismissLabel = "Close",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    recent: List<DhakaFlix.Recent>,
    onPick: (DhakaFlix.Recent) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        MagpieDialog(
            title = "Clear history?",
            message = "Every recent search will be forgotten.",
            primary = DialogAction("Clear", destructive = true) { confirming = false; onClear() },
            onDismiss = { confirming = false },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent searches", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirming = true }) { Icon(Icons.Default.DeleteSweep, "Clear history") }
            }
            Spacer(Modifier.height(8.dp))
            recent.forEach { r ->
                val c = DhakaFlix.categories.firstOrNull { it.id == r.categoryId }
                val tint = c?.let { colorOf(it.color) } ?: MaterialTheme.colorScheme.outline
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium)
                        .clickable { onPick(r) },
                ) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(tint))
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c?.name ?: "Unknown",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(tint.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    ago(r.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(r.query, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun ago(at: Long): String {
    val mins = (System.currentTimeMillis() - at) / 60_000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        mins < 48 * 60 -> "Yesterday"
        mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(Date(at))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSheet(vm: DhakaFlixViewModel) {
    ModalBottomSheet(onDismissRequest = vm::closeAi) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI movie finder", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "“${vm.query.trim()}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gemini.Model.entries.forEach { m ->
                    FilterChip(
                        selected = vm.aiModel == m,
                        onClick = { vm.aiModel = m },
                        label = { Text(m.short) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            when (val s = vm.ai) {
                AiState.Idle, AiState.Asking -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Asking Gemini…")
                }
                is AiState.Failed -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = vm::askAi) { Text("Retry") }
                }
                is AiState.Found -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${s.matches.size} match${if (s.matches.size == 1) "" else "es"} found — tap to fill in",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = vm::askAi) { Icon(Icons.Default.Refresh, "Ask again") }
                    }
                    s.matches.forEach { m -> AiMatchCard(m) { vm.pickMatch(m) } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AiMatchCard(m: DhakaFlixAi.Match, onPick: () -> Unit) {
    val c = m.categoryId?.let { id -> DhakaFlix.categories.firstOrNull { it.id == id } }
    val tint = c?.let { colorOf(it.color) } ?: MaterialTheme.colorScheme.outline
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .alpha(if (c == null) 0.5f else 1f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = c != null, onClick = onPick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tint))
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text("${DhakaFlixAi.emojiFor(m.industry)}  ${m.title}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(m.year, m.industry, if (m.isSeries) "TV Series" else "Movie").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        c == null -> "Not in library"
                        !c.supportsYear -> "${c.name} · no year needed"
                        m.year != null -> "${c.name} · sets year to ${m.year}"
                        else -> "${c.name} · year unknown"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (c == null) MaterialTheme.colorScheme.error else tint,
                )
            }
            if (c != null) {
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                )
            }
        }
    }
}
