package com.syed.magpie.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.syed.magpie.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Story typefaces. Bangla falls back to the system font in all but Galada. */
enum class StoryFont(val label: String, @param:FontRes val res: Int) {
    Classic("Classic", R.font.plus_jakarta_sans_bold),
    Strong("Strong", R.font.story_anton),
    Script("Script", R.font.story_pacifico),
    Elegant("Elegant", R.font.story_dm_serif),
    Typewriter("Typewriter", R.font.story_courier_prime),
    Marker("Marker", R.font.story_permanent_marker),
    Bangla("বাংলা", R.font.story_galada),
}

/** What sits behind or around the letters. */
enum class TextLook(val label: String) {
    Plain("Plain"),
    Solid("Solid"),
    Soft("Soft"),
    Outline("Outline"),
    Neon("Neon"),
}

enum class StoryAlign { Left, Center, Right }

/**
 * One piece of text on the story.
 *
 * Every measure is a fraction of the frame — position of the frame's width
 * and height, size of its width — so the same layer draws identically on the
 * small preview and in the 1080×1920 video.
 */
data class TextLayer(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val font: StoryFont = StoryFont.Classic,
    val color: Int = Color.WHITE,
    val look: TextLook = TextLook.Plain,
    val align: StoryAlign = StoryAlign.Center,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 0.08f,
    val rotation: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("font", font.name)
        put("color", color)
        put("look", look.name)
        put("align", align.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("size", size.toDouble())
        put("rotation", rotation.toDouble())
    }

    companion object {
        /** Keeps a pinch from shrinking text to nothing or past the frame. */
        const val MIN_SIZE = 0.025f
        const val MAX_SIZE = 0.3f

        fun fromJson(o: JSONObject) = TextLayer(
            id = o.optString("id", UUID.randomUUID().toString()),
            text = o.optString("text"),
            font = runCatching { StoryFont.valueOf(o.optString("font")) }.getOrDefault(StoryFont.Classic),
            color = o.optInt("color", Color.WHITE),
            look = runCatching { TextLook.valueOf(o.optString("look")) }.getOrDefault(TextLook.Plain),
            align = runCatching { StoryAlign.valueOf(o.optString("align")) }.getOrDefault(StoryAlign.Center),
            x = o.optDouble("x", 0.5).toFloat(),
            y = o.optDouble("y", 0.5).toFloat(),
            size = o.optDouble("size", 0.08).toFloat(),
            rotation = o.optDouble("rotation", 0.0).toFloat(),
        )

        fun listToJson(list: List<TextLayer>) = JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray?): List<TextLayer> =
            if (arr == null) emptyList() else (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

/**
 * Draws [TextLayer]s onto an Android canvas. The preview and the encoder both
 * come through here, which is what makes the preview trustworthy.
 */
class StoryTextPainter(private val context: Context) {

    private val faces = mutableMapOf<StoryFont, Typeface>()

    private fun face(font: StoryFont): Typeface = faces.getOrPut(font) {
        runCatching { ResourcesCompat.getFont(context, font.res) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }

    /** The laid-out text and the box it fills, before rotation. */
    class Measured(val layout: StaticLayout, val paint: TextPaint, val pad: Float) {
        val width: Float get() = layout.width + pad * 2
        val height: Float get() = layout.height + pad * 2
    }

    fun measure(layer: TextLayer, frameW: Float): Measured {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face(layer.font)
            textSize = layer.size * frameW
        }
        val text = layer.text.ifEmpty { " " }
        val widest = text.split('\n').maxOf { paint.measureText(it) }
        val width = ceil(min(max(widest, 1f), frameW * WRAP)).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(
                when (layer.align) {
                    StoryAlign.Left -> Layout.Alignment.ALIGN_NORMAL
                    StoryAlign.Center -> Layout.Alignment.ALIGN_CENTER
                    StoryAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setIncludePad(false)
            .build()
        return Measured(layout, paint, paint.textSize * PAD)
    }

    fun draw(canvas: Canvas, layer: TextLayer, frameW: Float, frameH: Float) {
        if (layer.text.isBlank()) return
        val m = measure(layer, frameW)
        val layout = m.layout
        val paint = m.paint
        val size = paint.textSize

        canvas.save()
        canvas.translate(layer.x * frameW, layer.y * frameH)
        canvas.rotate(layer.rotation)
        canvas.translate(-layout.width / 2f, -layout.height / 2f)

        when (layer.look) {
            TextLook.Plain -> {
                // A faint shadow, so white text survives a white sky.
                paint.color = layer.color
                paint.setShadowLayer(size * 0.08f, 0f, size * 0.03f, Color.argb(110, 0, 0, 0))
                layout.draw(canvas)
            }
            TextLook.Solid -> {
                boxes(canvas, layout, size, layer.color)
                paint.color = contrast(layer.color)
                layout.draw(canvas)
            }
            TextLook.Soft -> {
                boxes(canvas, layout, size, Color.argb(140, 0, 0, 0))
                paint.color = layer.color
                layout.draw(canvas)
            }
            TextLook.Outline -> {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = size * 0.14f
                paint.color = contrast(layer.color)
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = layer.color
                layout.draw(canvas)
            }
            TextLook.Neon -> {
                // A wide halo in the colour, a tight one over it, and a core
                // washed towards white — how a lit tube actually reads.
                paint.color = layer.color
                paint.setShadowLayer(size * 0.45f, 0f, 0f, layer.color)
                layout.draw(canvas)
                paint.setShadowLayer(size * 0.15f, 0f, 0f, layer.color)
                paint.color = ColorUtils.blendARGB(layer.color, Color.WHITE, 0.65f)
                layout.draw(canvas)
            }
        }
        canvas.restore()
    }

    /**
     * One rounded box per line, hugging that line's width — the story-app
     * look, rather than a single rectangle round the whole paragraph.
     */
    private fun boxes(canvas: Canvas, layout: StaticLayout, size: Float, color: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val padX = size * 0.3f
        val padY = size * 0.1f
        val radius = size * 0.28f
        for (i in 0 until layout.lineCount) {
            val left = layout.getLineLeft(i)
            val right = layout.getLineRight(i)
            if (right - left < 1f) continue
            canvas.drawRoundRect(
                RectF(left - padX, layout.getLineTop(i) - padY, right + padX, layout.getLineBottom(i) + padY),
                radius, radius, fill,
            )
        }
    }

    companion object {
        /** Lines wrap at this fraction of the frame width. */
        const val WRAP = 0.86f
        private const val PAD = 0.35f

        /** Black on light colours, white on dark ones. */
        fun contrast(color: Int): Int =
            if (ColorUtils.calculateLuminance(color) > 0.55) Color.BLACK else Color.WHITE
    }
}
