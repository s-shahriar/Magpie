package com.syed.magpie.ui.screen

import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syed.magpie.data.StillFrame
import com.syed.magpie.data.StillVideo
import com.syed.magpie.ui.Module
import com.syed.magpie.data.StillStatus
import com.syed.magpie.ui.component.StoryCanvas
import com.syed.magpie.ui.component.StoryEditorScreen
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.ImeAction
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syed.magpie.ui.StillVideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PRESETS = listOf(15, 30, 45, 60, 90, 120)

@Composable
fun StillVideoScreen(
    vm: StillVideoViewModel,
    onBack: () -> Unit,
    onOpen: (android.net.Uri) -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.pick(uri)
    }
    val pickPhoto = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val last = jobs.firstOrNull { it.id == vm.lastJob }
    val scroll = rememberScrollState()
    // The result lands under the floating nav bar; bring it into view.
    val done = last?.status == StillStatus.COMPLETED
    LaunchedEffect(done) { if (done) scroll.animateScrollTo(scroll.maxValue) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        ModuleHeader(Module.StillVideo, onBack)
        Text(
            "Facebook keeps a photo story up for 15 seconds. As a video it stays " +
                "for as long as the video runs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (vm.editing != null) {
            Spacer(Modifier.height(16.dp))
            EditingBanner(onCancel = vm::cancelEdit)
        }

        Spacer(Modifier.height(20.dp))
        val source = vm.source
        if (source == null) PhotoSlot(vm, enabled = true, onPick = pickPhoto)
        else StoryEditorSection(vm, source, onChangePhoto = pickPhoto)

        Spacer(Modifier.height(22.dp))
        Label("Name")
        OutlinedTextField(
            value = vm.title,
            onValueChange = { vm.title = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Still, with today's date") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        Spacer(Modifier.height(22.dp))
        Label("Length")
        LengthPicker(vm.seconds, enabled = true, onChange = vm::setLength)

        Spacer(Modifier.height(22.dp))
        Label("Frame")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StillFrame.entries.forEach { f ->
                Chip(f.label, selected = vm.frame == f, enabled = true) { vm.frame = f }
            }
        }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = vm::make,
            enabled = vm.source != null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) {
            Text(if (vm.editing != null) "Save changes" else "Make ${clock(vm.seconds)} video")
        }

        Spacer(Modifier.height(16.dp))
        if (last != null) {
            when (last.status) {
                StillStatus.COMPLETED -> ResultCard(
                    name = last.fileName,
                    onShare = { vm.share(last) },
                    onPlay = { last.outputUri?.let { onOpen(it.toUri()) } },
                    onLibrary = onLibrary,
                )
                StillStatus.FAILED, StillStatus.STOPPED -> Text(
                    last.error ?: "Stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    LinearProgressIndicator(
                        progress = { last.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (last.status == StillStatus.QUEUED) "Waiting for the one before it…"
                        else "Encoding… ${(last.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun EditingBanner(onCancel: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(start = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Text("Editing a saved video", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saving re-makes it and replaces the old file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/**
 * The photo as it will be framed, with its text on top: drag, pinch and
 * twist on the preview; the controls under it style whichever is selected.
 */
@Composable
private fun StoryEditorSection(vm: StillVideoViewModel, source: android.net.Uri, onChangePhoto: () -> Unit) {
    val base by produceState<ImageBitmap?>(null, source, vm.frame) {
        value = null
        value = vm.preview(source, vm.frame)?.asImageBitmap()
    }

    val image = base
    if (vm.editorOpen && image != null) {
        StoryEditorScreen(
            base = image,
            layers = vm.texts,
            selected = vm.selected,
            typeRequest = vm.typeRequest,
            onSelect = vm::selectText,
            onChange = vm::updateText,
            onAdd = vm::addText,
            onDuplicate = vm::duplicateText,
            onDelete = vm::deleteText,
            onClose = vm::closeEditor,
        )
    }

    // The form shows the story as it stands; editing happens full screen.
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (image == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
        } else {
            // Fit the frame inside the column's width and a fixed height, so
            // a 9:16 story stays on screen with its controls below it.
            val aspect = image.width.toFloat() / image.height
            val w = minOf(maxWidth, 460.dp * aspect)
            Box(Modifier.size(w, w / aspect)) {
                StoryCanvas(
                    base = image,
                    layers = vm.texts,
                    selected = null,
                    onSelect = {},
                    onChange = {},
                    onEdit = {},
                    modifier = Modifier.matchParentSize(),
                )
                // Any tap opens the editor; the canvas here is only a view.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { if (vm.texts.isEmpty()) vm.addText() else vm.openEditor() },
                )
                if (vm.texts.isNotEmpty()) {
                    Text(
                        "Tap to edit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onChangePhoto, shape = MaterialTheme.shapes.small) {
            Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Photo")
        }
        Button(
            onClick = vm::addText,
            enabled = image != null,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Default.TextFields, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add text")
        }
    }
}

@Composable
private fun PhotoSlot(vm: StillVideoViewModel, enabled: Boolean, onPick: () -> Unit) {
    val context = LocalContext.current
    val source = vm.source
    val thumb by produceState<ImageBitmap?>(null, source) {
        value = source?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        val long = maxOf(info.size.width, info.size.height)
                        if (long > 900) decoder.setTargetSampleSize(long / 900)
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onPick),
    ) {
        val image = thumb
        if (image != null) {
            Box {
                Image(
                    image,
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelMedium,
                    // Inverse, not coral: it has to read over any photo.
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    null,
                    Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text("Choose a photo", style = MaterialTheme.typography.titleMedium)
                Text(
                    "From your gallery",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LengthPicker(seconds: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(clock(seconds), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    "of ${clock(StillVideo.MAX_SECONDS)} max",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            Slider(
                value = seconds.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = 1f..StillVideo.MAX_SECONDS.toFloat(),
                enabled = enabled,
                colors = SliderDefaults.colors(
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEach { p ->
                    Chip(
                        if (p < 60) "${p}s" else clock(p),
                        selected = seconds == p,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        padding = 0.dp,
                    ) { onChange(p) }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(name: String, onShare: () -> Unit, onPlay: () -> Unit, onLibrary: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Saved to Downloads/Magpie", style = MaterialTheme.typography.titleMedium)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onShare,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                OutlinedButton(onClick = onPlay, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLibrary) { Text("Library") }
            }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    padding: Dp = 12.dp,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = padding, vertical = 9.dp),
    )
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

private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
