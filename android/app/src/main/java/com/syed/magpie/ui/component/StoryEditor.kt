package com.syed.magpie.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syed.magpie.data.StoryAlign
import com.syed.magpie.data.StoryFont
import com.syed.magpie.data.StoryTextPainter
import com.syed.magpie.data.TextLayer
import com.syed.magpie.data.TextLook
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Story colours: the neutrals, the app's coral, then a bright spread. */
val StoryColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFA371, 0xFFFFD84D, 0xFFFF4D4D, 0xFFFF6FB5,
    0xFFA06CFF, 0xFF4DA3FF, 0xFF2ED3B7, 0xFF6BD66B, 0xFFF4F1EB, 0xFF8B5E3C,
).map { it.toInt() }

private val families = mutableMapOf<StoryFont, FontFamily>()
fun StoryFont.family(): FontFamily = families.getOrPut(this) { FontFamily(Font(res)) }

/**
 * The story as it will be encoded: the text-less frame with every layer
 * drawn by the same painter the encoder uses.
 *
 * One finger on a layer drags it; two fingers anywhere scale and rotate the
 * selected one. It snaps to the centre lines and to square angles, and the
 * guides show while it does. Tap selects, tap on nothing deselects, double
 * tap opens the text for editing.
 */
@Composable
fun StoryCanvas(
    base: ImageBitmap,
    layers: List<TextLayer>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onChange: (TextLayer) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = remember { StoryTextPainter(context) }
    val slop = with(LocalDensity.current) { 14.dp.toPx() }
    var guideX by remember { mutableStateOf(false) }
    var guideY by remember { mutableStateOf(false) }

    val currentLayers by rememberUpdatedState(layers)
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    val edit by rememberUpdatedState(onEdit)

    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .pointerInput(Unit) {
                var lastTapId: String? = null
                var lastTapAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val hit = currentLayers.lastOrNull { contains(painter, it, down.position, w, h, slop) }?.id
                    if (hit != null) {
                        if (hit != currentSelected) select(hit)
                        down.consume()
                    }
                    var target: String? = hit
                    // Unsnapped values, so a snap never sticks: the layer
                    // follows the finger and only its drawn position locks.
                    var raw: TextLayer? = null
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers == 0) break
                        if (target == null && fingers >= 2) target = currentSelected
                        val id = target
                        if (id == null) {
                            // Not ours: leave it to the page's scroll.
                            if (event.changes.any { (it.position - down.position).getDistance() > slop }) moved = true
                            continue
                        }
                        val layer = currentLayers.firstOrNull { it.id == id } ?: break
                        val r = raw ?: layer.also { raw = it }
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val turn = event.calculateRotation()
                        if (pan != Offset.Zero || zoom != 1f || turn != 0f) {
                            moved = true
                            val next = r.copy(
                                x = (r.x + pan.x / w).coerceIn(0f, 1f),
                                y = (r.y + pan.y / h).coerceIn(0f, 1f),
                                size = (r.size * zoom).coerceIn(TextLayer.MIN_SIZE, TextLayer.MAX_SIZE),
                                rotation = r.rotation + turn,
                            )
                            raw = next
                            val snapX = abs(next.x - 0.5f) < SNAP
                            val snapY = abs(next.y - 0.5f) < SNAP
                            guideX = snapX
                            guideY = snapY
                            change(
                                next.copy(
                                    x = if (snapX) 0.5f else next.x,
                                    y = if (snapY) 0.5f else next.y,
                                    rotation = snapAngle(next.rotation),
                                ),
                            )
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    guideX = false
                    guideY = false
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (hit == null) {
                            select(null)
                        } else if (hit == lastTapId && now - lastTapAt < DOUBLE_TAP_MS) {
                            edit(hit)
                            lastTapId = null
                            return@awaitEachGesture
                        }
                        lastTapId = hit
                        lastTapAt = now
                    }
                }
            },
    ) {
        Image(base, null, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawIntoCanvas { c -> layers.forEach { painter.draw(c.nativeCanvas, it, w, h) } }

            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            if (guideX) drawLine(accent, Offset(w / 2, 0f), Offset(w / 2, h), 2.dp.toPx(), pathEffect = dash)
            if (guideY) drawLine(accent, Offset(0f, h / 2), Offset(w, h / 2), 2.dp.toPx(), pathEffect = dash)

            layers.firstOrNull { it.id == selected }?.let { layer ->
                val m = painter.measure(layer, w)
                val center = Offset(layer.x * w, layer.y * h)
                rotate(layer.rotation, pivot = center) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = center - Offset(m.width / 2, m.height / 2),
                        size = Size(m.width, m.height),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = dash),
                    )
                }
            }
        }
    }
}

private const val SNAP = 0.015f
private const val DOUBLE_TAP_MS = 320L

/** Locks to the nearest quarter turn within a few degrees of it. */
private fun snapAngle(deg: Float): Float {
    val quarter = (deg / 90f).roundToInt() * 90f
    return if (abs(deg - quarter) < 4f) quarter else deg
}

/** Whether [p] lands on [layer], rotation included, with a finger's slack. */
private fun contains(
    painter: StoryTextPainter,
    layer: TextLayer,
    p: Offset,
    w: Float,
    h: Float,
    slack: Float,
): Boolean {
    val m = painter.measure(layer, w)
    val d = p - Offset(layer.x * w, layer.y * h)
    val a = Math.toRadians(-layer.rotation.toDouble())
    val lx = d.x * cos(a) - d.y * sin(a)
    val ly = d.x * sin(a) + d.y * cos(a)
    return abs(lx) <= m.width / 2 + slack && abs(ly) <= m.height / 2 + slack
}

// ---- style controls ----------------------------------------------------

/** Everything that styles the selected layer, under the preview. */
@Composable
fun TextStyleBar(
    layer: TextLayer,
    onChange: (TextLayer) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Default.Edit, "Edit text", onEdit)
            ToolButton(alignIcon(layer.align), "Alignment", onClick = { onChange(layer.copy(align = layer.align.next())) })
            ToolButton(Icons.Default.ContentCopy, "Duplicate", onDuplicate)
            Spacer(Modifier.weight(1f))
            ToolButton(Icons.Default.Delete, "Delete text", onClick = onDelete, tint = MaterialTheme.colorScheme.error)
        }
        FontRow(layer.font) { onChange(layer.copy(font = it)) }
        ColorRow(layer.color) { onChange(layer.copy(color = it)) }
        LookRow(layer.look) { onChange(layer.copy(look = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = layer.size,
                onValueChange = { onChange(layer.copy(size = it)) },
                valueRange = TextLayer.MIN_SIZE..TextLayer.MAX_SIZE,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
            )
            Text("A", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(end = 8.dp).size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(20.dp), tint = tint) }
    }
}

private fun alignIcon(a: StoryAlign) = when (a) {
    StoryAlign.Left -> Icons.AutoMirrored.Filled.FormatAlignLeft
    StoryAlign.Center -> Icons.Default.FormatAlignCenter
    StoryAlign.Right -> Icons.AutoMirrored.Filled.FormatAlignRight
}

private fun StoryAlign.next() = StoryAlign.entries[(ordinal + 1) % StoryAlign.entries.size]

/** Each font's chip is set in that font, so the row is its own preview. */
@Composable
fun FontRow(current: StoryFont, dark: Boolean = false, onPick: (StoryFont) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StoryFont.entries.forEach { f ->
            val on = f == current
            Text(
                f.label,
                fontFamily = f.family(),
                fontSize = 15.sp,
                maxLines = 1,
                color = when {
                    on -> MaterialTheme.colorScheme.onPrimary
                    dark -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        when {
                            on -> MaterialTheme.colorScheme.primary
                            dark -> Color.White.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        },
                    )
                    .clickable { onPick(f) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun ColorRow(current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoryColors.forEach { c ->
            val on = c == current
            Box(
                Modifier
                    .size(if (on) 34.dp else 30.dp)
                    .clip(CircleShape)
                    .border(
                        if (on) 3.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                        CircleShape,
                    )
                    .padding(if (on) 4.dp else 0.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
fun LookRow(current: TextLook, dark: Boolean = false, onPick: (TextLook) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextLook.entries.forEach { l ->
            val on = l == current
            Text(
                l.label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    on -> MaterialTheme.colorScheme.onPrimary
                    dark -> Color.White
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        when {
                            on -> MaterialTheme.colorScheme.primary
                            dark -> Color.White.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        },
                    )
                    .clickable { onPick(l) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- text entry --------------------------------------------------------

/**
 * Full-screen typing over a dimmed backdrop, in the layer's own font and
 * colour, with font, look and colour pickers riding above the keyboard.
 * Done with nothing typed removes the layer.
 */
@Composable
fun TextEntry(layer: TextLayer, onDone: (TextLayer) -> Unit, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf(layer) }
    var value by remember {
        mutableStateOf(TextFieldValue(layer.text, TextRange(layer.text.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { draft = draft.copy(align = draft.align.next()) }) {
                    Icon(alignIcon(draft.align), "Alignment", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onDone(draft.copy(text = value.text.trimEnd())) },
                    shape = MaterialTheme.shapes.small,
                ) { Text("Done") }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(
                        fontFamily = draft.font.family(),
                        fontSize = 34.sp,
                        color = entryColor(draft),
                        textAlign = when (draft.align) {
                            StoryAlign.Left -> TextAlign.Start
                            StoryAlign.Center -> TextAlign.Center
                            StoryAlign.Right -> TextAlign.End
                        },
                        background = entryBackground(draft),
                    ),
                    cursorBrush = SolidColor(Color(draft.color)),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (value.text.isEmpty()) {
                                Text(
                                    "Type something",
                                    fontFamily = draft.font.family(),
                                    fontSize = 34.sp,
                                    color = Color.White.copy(alpha = 0.4f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            FontRow(draft.font, dark = true) { draft = draft.copy(font = it) }
            Spacer(Modifier.height(10.dp))
            LookRow(draft.look, dark = true) { draft = draft.copy(look = it) }
            Spacer(Modifier.height(10.dp))
            ColorRow(draft.color) { draft = draft.copy(color = it) }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** An approximation of the look while typing; the canvas shows the real one. */
private fun entryColor(l: TextLayer): Color =
    if (l.look == TextLook.Solid) Color(StoryTextPainter.contrast(l.color)) else Color(l.color)

private fun entryBackground(l: TextLayer): Color = when (l.look) {
    TextLook.Solid -> Color(l.color)
    TextLook.Soft -> Color.Black.copy(alpha = 0.55f)
    else -> Color.Transparent
}
