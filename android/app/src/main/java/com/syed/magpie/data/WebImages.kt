package com.syed.magpie.data

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Thumbnails off the web — YouTube's, for the picker, the playlist and the
 * library. Kept decoded in memory, and a URL asked for by several rows at
 * once is fetched once: a playlist of two hundred scrolls past each
 * thumbnail more than once.
 */
object WebImages {

    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String, maxWidth: Int = 480): ImageBitmap? {
        cache.get(url)?.let { return it }
        val mine = CompletableDeferred<ImageBitmap?>()
        val slot = inFlight.putIfAbsent(url, mine) ?: mine
        if (slot !== mine) return slot.await()
        val image = withContext(Dispatchers.IO) { runCatching { decode(url, maxWidth) }.getOrNull() }
        image?.let { cache.put(url, it) }
        inFlight.remove(url)
        mine.complete(image)
        return image
    }

    private fun decode(url: String, maxWidth: Int): ImageBitmap? {
        val bytes = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000
            readTimeout = 8_000
            try {
                if (responseCode !in 200..299) return null
                inputStream.use { it.readBytes() }
            } finally {
                disconnect()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }
}
