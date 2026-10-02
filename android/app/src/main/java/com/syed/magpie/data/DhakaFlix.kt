package com.syed.magpie.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.magpie_core.DfCategory
import uniffi.magpie_core.DfException
import uniffi.magpie_core.DfFolder
import uniffi.magpie_core.DfSearch
import uniffi.magpie_core.dhakaflixCategories
import uniffi.magpie_core.dhakaflixFolder
import uniffi.magpie_core.dhakaflixPoster
import uniffi.magpie_core.dhakaflixSearch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The DhakaFlix module's side of things: the core does the searching and
 * listing, this keeps what only the phone can — downloads, posters, history.
 *
 * Files are plain HTTP on the LAN, so a download is an ordinary job with no
 * audio track and no expiry, saved under its own name to Downloads/Magpie/DhakaFlix.
 */
object DhakaFlix {

    const val SOURCE = "dhakaflix"
    const val FOLDER = "Magpie/DhakaFlix"

    val categories: List<DfCategory> by lazy { dhakaflixCategories() }

    fun category(id: String?): DfCategory =
        categories.firstOrNull { it.id == id } ?: categories.first()

    suspend fun search(categoryId: String, query: String, year: String?): Result<DfSearch> =
        withContext(Dispatchers.IO) {
            runCatching { dhakaflixSearch(categoryId, query, year?.takeIf { it.isNotBlank() }) }
        }

    suspend fun folder(url: String): Result<DfFolder> =
        withContext(Dispatchers.IO) { runCatching { dhakaflixFolder(url) } }

    /** Queue a file. False when the same file is already on its way. */
    fun download(url: String, name: String, size: Long?, category: String): Boolean {
        // The same file can be reached from search ("(" kept) and from its
        // folder listing ("%28"), so compare decoded.
        val same = decoded(url)
        if (DownloadEngine.jobs.value.any {
                it.source == SOURCE && it.status != DownloadStatus.COMPLETED && decoded(it.videoUrl) == same
            }
        ) return false
        DownloadEngine.enqueue(
            sourceUrl = url,
            source = SOURCE,
            title = name.substringBeforeLast('.').ifBlank { name },
            quality = category,
            renditionId = "file",
            videoUrl = url,
            audioUrl = null,
            fileName = fileName(name.substringBeforeLast('.'), name.substringAfterLast('.', "")),
            totalBytes = size,
            mime = mimeFor(name),
            folder = FOLDER,
        )
        return true
    }

    private fun decoded(url: String) =
        runCatching { URLDecoder.decode(url.replace("+", "%2B"), "UTF-8") }.getOrDefault(url)

    /** The server's own name, made safe for MediaStore, extension kept. */
    fun fileName(base: String, extension: String): String {
        val cleaned = base.replace(Regex("""[/\\:*?"<>|]"""), "-").trim().take(150).ifEmpty { "dhakaflix" }
        return if (extension.isBlank()) cleaned else "$cleaned.$extension"
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    fun isSubtitle(name: String) = name.endsWith(".srt", ignoreCase = true)

    /** The folder a file sits in, for a loose file in search results. */
    fun parentOf(url: String): String = url.trimEnd('/').substringBeforeLast('/') + "/"

    /** A URL's path, decoded and without the server: the breadcrumb. */
    fun pathOf(url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "")
        return runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
            .trimEnd('/')
    }

    fun nameOf(url: String): String = pathOf(url).substringAfterLast('/')

    // ---- errors --------------------------------------------------------

    /** A server error, sorted the way the old app's error sheet sorted it. */
    data class Problem(
        val title: String,
        val message: String,
        val endpoint: String?,
        val status: Int?,
        val tips: List<String>,
    )

    fun problemOf(e: Throwable): Problem = when (e) {
        is DfException.Timeout -> Problem(
            "Request timed out",
            "The server took longer than ${e.secs}s to answer.",
            e.endpoint,
            null,
            listOf(
                "The server may be busy — try again in a moment",
                "Check the Wi-Fi is the one on the DhakaFlix network",
            ),
        )
        is DfException.Http -> Problem(
            "Server error",
            when (e.status.toInt()) {
                404 -> "Resource not found"
                403 -> "Access forbidden"
                in 500..599 -> "Internal server error"
                else -> "Server returned error ${e.status}"
            },
            e.endpoint,
            e.status.toInt(),
            listOf("The server may be down for maintenance", "Try again later"),
        )
        is DfException.Network -> Problem(
            "Can't reach the server",
            when {
                e.msg.contains("refused", ignoreCase = true) -> "Connection refused by server"
                e.msg.contains("skipping", ignoreCase = true) -> e.msg
                else -> "Unable to connect — ${e.msg}"
            },
            e.endpoint.ifBlank { null },
            null,
            listOf(
                "DhakaFlix is only reachable on its own network (172.16.50.x)",
                "Check you are on that Wi-Fi, not mobile data",
                "The server may be offline",
            ),
        )
        else -> Problem(
            "Something went wrong",
            e.message ?: e.javaClass.simpleName,
            null,
            null,
            listOf("Try again", "If it keeps happening, the server may have changed"),
        )
    }

    // ---- posters -------------------------------------------------------

    /**
     * Posters are full-size JPEGs of ~200 KB, looked up by reading the title
     * folder's listing. Four at a time, each folder once; a failure is
     * forgotten so scrolling back retries it.
     */
    private val posterGate = Semaphore(4)
    private val posters = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    suspend fun poster(folderUrl: String, knownImage: String? = null): ImageBitmap? {
        posters[folderUrl]?.let { return it.await() }
        val slot = CompletableDeferred<ImageBitmap?>()
        val existing = posters.putIfAbsent(folderUrl, slot)
        if (existing != null) return existing.await()
        val image = runCatching {
            posterGate.withPermit {
                withContext(Dispatchers.IO) {
                    val url = knownImage ?: dhakaflixPoster(folderUrl) ?: return@withContext null
                    decode(url)
                }
            }
        }.getOrNull()
        slot.complete(image)
        if (image == null) posters.remove(folderUrl)
        return image
    }

    /** Downsampled to about thumbnail size: the originals are poster-sized. */
    private fun decode(url: String): ImageBitmap? {
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
        while (bounds.outWidth / (sample * 2) >= 180) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    // ---- search history ------------------------------------------------

    data class Recent(val query: String, val categoryId: String, val at: Long)

    /**
     * The last ten searches that found something. The same query in the same
     * category is one entry, moved to the top; in another category it is a
     * different search and kept.
     */
    class History(context: Context) {
        private val prefs = context.getSharedPreferences("dhakaflix", Context.MODE_PRIVATE)

        fun load(): List<Recent> = runCatching {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Recent(o.getString("query"), o.getString("category"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())

        fun add(query: String, categoryId: String): List<Recent> {
            val next = (
                listOf(Recent(query, categoryId, System.currentTimeMillis())) +
                    load().filterNot { it.query.equals(query, ignoreCase = true) && it.categoryId == categoryId }
                ).take(LIMIT)
            save(next)
            return next
        }

        fun clear() = prefs.edit { remove(KEY) }

        private fun save(list: List<Recent>) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("query", it.query).put("category", it.categoryId).put("at", it.at))
            }
            prefs.edit { putString(KEY, arr.toString()) }
        }

        private companion object {
            const val KEY = "history"
            const val LIMIT = 10
        }
    }
}
