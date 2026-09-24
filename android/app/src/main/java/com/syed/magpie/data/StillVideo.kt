package com.syed.magpie.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** How the photo sits in the video frame. */
enum class StillFrame(val label: String) {
    /** 1080×1920 with the photo fitted over a blurred copy of itself. */
    Story("Story 9:16"),
    /** The photo's own shape, long side at most 1920. */
    Original("Original shape"),
}

/**
 * Turns one still image into an H.264 MP4 of a chosen length.
 *
 * Facebook shows a photo story for a fixed 15 s but plays a video story for as
 * long as the video runs, so a still packed as a video stays up longer.
 *
 * Frames go through GL onto the encoder's input surface rather than as YUV
 * buffers: that sidesteps every vendor's colour-format and stride quirks, and
 * `eglPresentationTimeANDROID` lets the timestamps be set exactly instead of
 * following the wall clock.
 */
class StillVideo(private val context: Context) {

    suspend fun render(
        source: Uri,
        seconds: Int,
        frame: StillFrame,
        fileName: String,
        texts: List<TextLayer> = emptyList(),
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.Default) {
        require(seconds in 1..MAX_SECONDS) { "Length must be 1–$MAX_SECONDS seconds" }
        val photo = decode(source, LONG_SIDE * 2)
        val (w, h) = supportedSize(targetSize(photo, frame))
        val canvas = compose(photo, w, h)
        photo.recycle()
        drawTexts(canvas, texts)

        val uri = createPending(fileName)
        try {
            encode(canvas, seconds, uri, onProgress)
            markReady(uri)
            uri
        } catch (t: Throwable) {
            // Cancelled or failed: never leave a half-written pending file.
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        } finally {
            canvas.recycle()
        }
    }

    /**
     * The frame without its text, at preview size: the same composition the
     * video gets, so the editor lays text over exactly what will be encoded.
     */
    suspend fun preview(source: Uri, frame: StillFrame): Bitmap = withContext(Dispatchers.Default) {
        val photo = decode(source, PREVIEW_SIDE * 2)
        val (fw, fh) = targetSize(photo, frame)
        val scale = PREVIEW_SIDE.toFloat() / max(fw, fh)
        val out = compose(photo, max(1, (fw * scale).toInt()), max(1, (fh * scale).toInt()))
        photo.recycle()
        out
    }

    private val painter by lazy { StoryTextPainter(context) }

    private fun drawTexts(frame: Bitmap, texts: List<TextLayer>) {
        if (texts.isEmpty()) return
        val c = Canvas(frame)
        texts.forEach { painter.draw(c, it, frame.width.toFloat(), frame.height.toFloat()) }
    }

    // ---- picture -------------------------------------------------------

    /** Decoded upright (EXIF applied), in software so Canvas can draw it. */
    private fun decode(source: Uri, bound: Int): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, source)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > bound) {
                val scale = bound.toFloat() / long
                decoder.setTargetSize(
                    (info.size.width * scale).toInt(),
                    (info.size.height * scale).toInt(),
                )
            }
        }
    }

    private fun targetSize(photo: Bitmap, frame: StillFrame): Pair<Int, Int> = when (frame) {
        StillFrame.Story -> 1080 to 1920
        StillFrame.Original -> {
            val scale = min(1f, LONG_SIDE.toFloat() / max(photo.width, photo.height))
            align(photo.width * scale) to align(photo.height * scale)
        }
    }

    /** Photo fitted and centred over a dimmed, heavily blurred copy of itself. */
    private fun compose(photo: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // A cheap blur: shrink to a few dozen pixels and let bilinear
        // filtering smear it back up. Both ways go in 4× steps; one big jump
        // either way leaves visible blocks.
        val cover = max(w.toFloat() / photo.width, h.toFloat() / photo.height)
        val coverW = photo.width * cover
        val coverH = photo.height * cover
        var blur = photo
        while (max(blur.width, blur.height) > 32) blur = rescale(blur, 0.25f, photo)
        while (max(blur.width, blur.height) < 512) blur = rescale(blur, 4f, photo)
        c.drawBitmap(
            blur,
            null,
            RectF((w - coverW) / 2, (h - coverH) / 2, (w + coverW) / 2, (h + coverH) / 2),
            paint,
        )
        blur.recycle()
        c.drawColor(Color.argb(90, 0, 0, 0))

        val fit = min(w.toFloat() / photo.width, h.toFloat() / photo.height)
        val fitW = photo.width * fit
        val fitH = photo.height * fit
        c.drawBitmap(
            photo,
            Rect(0, 0, photo.width, photo.height),
            RectF((w - fitW) / 2, (h - fitH) / 2, (w + fitW) / 2, (h + fitH) / 2),
            paint,
        )
        return out
    }

    /** Scales with filtering, recycling the input unless it is [keep]. */
    private fun rescale(b: Bitmap, by: Float, keep: Bitmap): Bitmap {
        val out = Bitmap.createScaledBitmap(
            b, max(1, (b.width * by).toInt()), max(1, (b.height * by).toInt()), true,
        )
        if (b !== keep) b.recycle()
        return out
    }

    /**
     * Shrinks the frame until the device's H.264 encoder accepts it. Portrait
     * 1080×1920 is fine on anything recent, but some encoders only take the
     * landscape orientation of their maximum size.
     */
    private fun supportedSize(size: Pair<Int, Int>): Pair<Int, Int> {
        val codec = MediaCodec.createEncoderByType(MIME)
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
            var (w, h) = size
            repeat(8) {
                if (caps.isSizeSupported(w, h)) return w to h
                w = align(w * 0.8f)
                h = align(h * 0.8f)
            }
            return align(720f) to align(1280f)
        } finally {
            codec.release()
        }
    }

    // ---- encoding ------------------------------------------------------

    private suspend fun encode(picture: Bitmap, seconds: Int, uri: Uri, onProgress: (Float) -> Unit) {
        val w = picture.width
        val h = picture.height
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            // Offline work: without these the encoder paces itself to real
            // time and a two-minute video takes minutes to make.
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val fd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: error("Could not open the output file")
        var muxer: MediaMuxer? = null
        var gl: Gl? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            gl = Gl(input)
            gl.upload(picture)
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val drain = Drain(codec, muxer)
            val frames = seconds * FPS
            for (i in 0 until frames) {
                coroutineContext.ensureActive()
                drain.run(endOfStream = false)
                gl.draw()
                gl.present(i * 1_000_000_000L / FPS)
                if (i % FPS == 0) onProgress(i.toFloat() / frames)
            }
            // One frame past the end, so the last real frame is held for its
            // full 1/FPS and the file runs exactly `seconds` long.
            gl.draw()
            gl.present(frames * 1_000_000_000L / FPS)
            codec.signalEndOfInputStream()
            drain.run(endOfStream = true)
            onProgress(1f)
        } finally {
            gl?.release()
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            fd.close()
        }
    }

    /** Moves encoded samples from the codec into the muxer. */
    private class Drain(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1

        fun run(endOfStream: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buf = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** Just enough EGL/GLES to put one texture on the encoder's surface. */
    private class Gl(surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val window: EGLSurface
        private val program: Int
        private val quad: ByteBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(QUAD) }

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
            check(count[0] > 0) { "No recordable EGL config" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            window = EGL14.eglCreateWindowSurface(
                display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent failed" }
            program = link(VERTEX, FRAGMENT)
        }

        fun upload(bitmap: Bitmap) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glViewport(0, 0, bitmap.width, bitmap.height)

            GLES20.glUseProgram(program)
            val stride = 4 * 4
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            quad.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, stride, quad)
            GLES20.glEnableVertexAttribArray(pos)
            quad.position(2 * 4)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, stride, quad)
            GLES20.glEnableVertexAttribArray(uv)
        }

        fun draw() = GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        fun present(nanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, window, nanos)
            EGL14.eglSwapBuffers(display, window)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "GL link failed: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "GL compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }

        private companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142

            // x, y, u, v — v flipped, since GL's origin is bottom-left.
            val QUAD = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            )
            const val VERTEX = """
                attribute vec4 aPos;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() { gl_Position = aPos; vUv = aUv; }
            """
            const val FRAGMENT = """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex;
                void main() { gl_FragColor = texture2D(uTex, vUv); }
            """
        }
    }

    // ---- output --------------------------------------------------------

    private fun createPending(fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Magpie")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the output file")
    }

    private fun markReady(uri: Uri) {
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }

    companion object {
        /** The longest story this module will make. */
        const val MAX_SECONDS = 120

        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        // Nothing moves, so frames past the first only cost size: every
        // P-frame carries ~800 bytes of overhead even when it says "same as
        // before". 15 fps halves that and still plays as ordinary video.
        private const val FPS = 15
        // Keyframes are where a real photo's bytes go (a few hundred KB each),
        // and nobody scrubs a still, so they are spaced far apart.
        private const val KEYFRAME_SECONDS = 10
        // A still costs almost nothing after the first frame, so this mostly
        // buys a crisp keyframe; Facebook re-encodes whatever it is given.
        private const val BITRATE = 4_000_000
        private const val LONG_SIDE = 1920
        private const val PREVIEW_SIDE = 960

        /** Encoders are happiest with dimensions in whole 16-pixel blocks. */
        private fun align(v: Float): Int = max(16, (v.toInt() / 16) * 16)
    }
}
