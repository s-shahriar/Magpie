package com.syed.magpie.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Your LiveMCQ favourites, pulled into a JSON file in Downloads.
 *
 * This replaces a Termux script that read the LiveMCQ app's own API token out
 * of `/data/data/com.livemcq.livemcq/shared_prefs/` — which needs root. No
 * root is needed here, because the API accepts two kinds of credential:
 *
 *     WWW-Authenticate: Token          (the app's long-lived token)
 *     Vary: ..., Cookie, ...           (a plain Django session)
 *
 * That `Vary: Cookie` only appears when no `Authorization` header is sent, and
 * never on a non-API 404 — the session is read solely as a second way to
 * authenticate. So signing in through the WebView is enough, and the cookie
 * jar the app already keeps does the rest.
 *
 * Nothing here talks to the LiveMCQ *app*; it is the same public endpoint the
 * website's own pages use, with your own session.
 */
class LiveMcq(private val context: Context) {

    /** How much to take. */
    sealed interface Scope {
        /** Everything added since the last export — the usual run. */
        data object New : Scope

        /** The newest [count] favourites, whatever was taken before. */
        data class Newest(val count: Int) : Scope
    }

    data class Progress(val page: Int, val pages: Int, val questions: Int)

    data class Export(
        val uri: Uri,
        val fileName: String,
        val count: Int,
        /** Highest `favorite_id` in the file; the next run starts above it. */
        val newest: String?,
    )

    /** What the account holds right now, without downloading any of it. */
    data class Peek(val total: Int, val pages: Int, val newest: String?)

    /** One file sitting in Downloads, from this app or from the old script. */
    data class Saved(
        val uri: Uri,
        val fileName: String,
        val bytes: Long,
        val savedAt: Long,
        /** Questions inside, or null if the file could not be read as one. */
        val count: Int?,
    )

    /**
     * The outcome of changing a file, which Magpie may not be allowed to do
     * on its own.
     *
     * Ownership of a MediaStore row can outlive the app that made it — a
     * restore, or a file adopted from elsewhere — and the system then wants the
     * user asked first. That ask is an activity result, so it has to travel
     * back up to the screen rather than being answered here.
     */
    sealed interface Change {
        data object Done : Change
        data class NeedsConsent(val sender: IntentSender) : Change
    }

    class NotSignedIn : IOException("Sign in to LiveMCQ first")

    /** The session is live but the account has nothing new. */
    class NothingNew : IOException("No new favourites since the last export")

    suspend fun peek(): Peek = withContext(Dispatchers.IO) {
        val body = fetch(1)
        val list = body.optJSONArray("question_list") ?: JSONArray()
        Peek(
            total = pagination(body)?.optInt("total_results", 0) ?: 0,
            pages = pages(body),
            newest = list.optJSONObject(0)?.let { str(it, "favorite_id") }?.ifBlank { null },
        )
    }

    /**
     * Walks pages newest-first until [scope] is satisfied, then writes the file.
     *
     * Favourites come back in descending `favorite_id` order, so "everything
     * new" is simply "keep going until an id we have already seen" — no need
     * to fetch the ~100 pages behind it.
     */
    suspend fun export(
        scope: Scope,
        onProgress: (Progress) -> Unit,
    ): Export = withContext(Dispatchers.IO) {
        val baseline = if (scope is Scope.New) lastExported() else null
        val wanted = (scope as? Scope.Newest)?.count

        val taken = mutableListOf<JSONObject>()
        var page = 1
        var pages = 1
        var atBaseline = false

        while (true) {
            val body = fetch(page)
            pages = pages(body)
            val list = body.optJSONArray("question_list") ?: JSONArray()

            for (i in 0 until list.length()) {
                val q = list.optJSONObject(i) ?: continue
                if (baseline != null && !newer(str(q, "favorite_id"), baseline)) {
                    atBaseline = true
                    break
                }
                taken += q
                if (wanted != null && taken.size >= wanted) break
            }
            onProgress(Progress(page, pages, taken.size))

            val enough = atBaseline ||
                (wanted != null && taken.size >= wanted) ||
                list.length() == 0 ||
                page >= pages
            if (enough) break
            page++
        }

        if (taken.isEmpty()) throw NothingNew()
        write(taken)
    }

    // ---- the API -------------------------------------------------------

    private fun fetch(page: Int): JSONObject {
        val cookie = Cookies.headerFor(Cookies.Site.LIVEMCQ)
        if (!cookie.contains("sessionid=")) throw NotSignedIn()

        // Deliberately no Authorization header: the API tries Token auth first
        // and stops there, so an empty or stale one would mask a good session.
        val c = (URL("$API?page=$page").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://livemcq.com/app/")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            when (val code = c.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> throw NotSignedIn()
                else -> throw IOException("LiveMCQ answered HTTP $code")
            }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun pagination(body: JSONObject) = body.optJSONObject("pagination")

    /** The field has been spelled both ways; either is better than guessing 1. */
    private fun pages(body: JSONObject): Int {
        val p = pagination(body) ?: return 1
        return maxOf(p.optInt("total_pages", 0), p.optInt("num_pages", 0), 1)
    }

    // ---- the file ------------------------------------------------------

    private fun write(items: List<JSONObject>): Export {
        val arr = JSONArray()
        items.forEach { arr.put(slim(it)) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "livefav_$stamp.json"
        val uri = publish(name, arr.toString())
        val newest = items.firstOrNull()?.let { str(it, "favorite_id") }?.ifBlank { null }
        newest?.let { remember(it) }
        return Export(uri, name, items.size, newest)
    }

    /**
     * One question, trimmed to the fields the quiz admin panel reads.
     *
     * `options` keeps its *position*: `answer` is a 1-based index into
     * `option1..option5`, so dropping an empty slot from the middle would shift
     * every letter after it and silently hand the wrong answer to the importer.
     * Only trailing blanks go — LiveMCQ pads every question out to five.
     */
    private fun slim(q: JSONObject): JSONObject {
        val options = (1..5).map { str(q, "option$it") }.dropLastWhile { it.isBlank() }
        return JSONObject().apply {
            put("favorite_id", str(q, "favorite_id"))
            put("slug", str(q, "slug"))
            put("question", str(q, "question"))
            put("options", JSONArray(options))
            put("answer", q.optInt("answer", 0))
            put("explanation", str(q, "exp"))
        }
    }

    /**
     * A string field, with JSON null read as empty.
     *
     * [JSONObject.optString] would hand back the four characters `null` for a
     * null option, which the importer would then store as a real answer.
     */
    private fun str(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    /** Same folder the Termux script used, so the browser picker feels familiar. */
    private fun publish(fileName: String, body: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file")
        resolver.openOutputStream(uri).use {
            checkNotNull(it) { "Could not open the file" }
            it.write(body.toByteArray())
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // ---- the files already saved ---------------------------------------

    /**
     * Magpie's own exports, newest first.
     *
     * MediaStore is the only record kept: a list of its own would drift the
     * moment a file was deleted from Files, and would not survive a reinstall.
     *
     * Only Magpie's files come back. Downloads is not a media collection, so
     * without "All files access" the system shows an app nothing but its own
     * rows — the JSON the old Termux script wrote into this same folder is
     * there on disk and simply invisible from here.
     */
    suspend fun saved(): List<Saved> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Saved>()
        val columns = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%$FOLDER%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val at = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(id),
                    )
                    out += Saved(
                        uri = uri,
                        fileName = c.getString(name).orEmpty(),
                        bytes = c.getLong(size),
                        // MediaStore counts seconds; everything else here is millis.
                        savedAt = c.getLong(at) * 1000L,
                        count = countIn(uri),
                    )
                }
            }
        }
        out
    }

    /** How many questions a file holds; null if it is not one of ours. */
    private fun countIn(uri: Uri): Int? = runCatching {
        context.contentResolver.openInputStream(uri).use { stream ->
            val text = stream?.bufferedReader()?.readText() ?: return null
            when (val parsed = org.json.JSONTokener(text).nextValue()) {
                is JSONArray -> parsed.length()
                is JSONObject -> parsed.optJSONArray("question_list")?.length()
                else -> null
            }
        }
    }.getOrNull()

    suspend fun delete(uri: Uri): Change = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(uri, null, null)
            Change.Done
        } catch (e: SecurityException) {
            Change.NeedsConsent(consentTo(listOf(uri), deleting = true))
        }
    }

    suspend fun rename(uri: Uri, name: String): Change = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, tidy(name))
        }
        try {
            context.contentResolver.update(uri, values, null, null)
            Change.Done
        } catch (e: SecurityException) {
            Change.NeedsConsent(consentTo(listOf(uri), deleting = false))
        }
    }

    private fun consentTo(uris: List<Uri>, deleting: Boolean): IntentSender {
        val resolver = context.contentResolver
        return if (deleting) {
            MediaStore.createDeleteRequest(resolver, uris).intentSender
        } else {
            MediaStore.createWriteRequest(resolver, uris).intentSender
        }
    }

    /** Keeps the name something MediaStore will take, and still a .json. */
    private fun tidy(name: String): String {
        val base = name.replace(Regex("""[/\\:*?"<>|]"""), "-")
            .removeSuffix(".json")
            .trim()
            .take(90)
            .ifEmpty { "livefav" }
        return "$base.json"
    }

    // ---- the baseline --------------------------------------------------

    private fun prefs() = context.getSharedPreferences("livemcq", Context.MODE_PRIVATE)

    fun lastExported(): String? = prefs().getString(KEY_NEWEST, null)

    private fun remember(id: String) {
        prefs().edit().putString(KEY_NEWEST, id).apply()
    }

    /** Forgets the mark, so the next "new since" run starts from scratch. */
    fun forget() {
        prefs().edit().remove(KEY_NEWEST).apply()
    }

    /** Ids are numeric, but a text compare beats crashing if that ever changes. */
    private fun newer(id: String, baseline: String): Boolean {
        val a = id.toLongOrNull()
        val b = baseline.toLongOrNull()
        return if (a != null && b != null) a > b else id > baseline
    }

    private companion object {
        const val API = "https://livemcq.com/api/v1/central-favorite-list/"
        const val KEY_NEWEST = "newest"
        const val FOLDER = "live_fav"
    }
}
