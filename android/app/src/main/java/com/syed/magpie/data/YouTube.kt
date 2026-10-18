package com.syed.magpie.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.magpie_core.Chapter
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Mp4Tags
import uniffi.magpie_core.Rendition
import uniffi.magpie_core.YtFailure
import uniffi.magpie_core.YtPlaylist
import uniffi.magpie_core.YtPrefs
import uniffi.magpie_core.youtubeAudioFor
import uniffi.magpie_core.youtubeFailure
import uniffi.magpie_core.youtubeLink
import uniffi.magpie_core.youtubeMedia
import uniffi.magpie_core.youtubePick
import uniffi.magpie_core.youtubePlaylist
import uniffi.magpie_core.youtubeIsThin
import uniffi.magpie_core.youtubeYtdlpArgs
import java.io.File

/**
 * yt-dlp gave up; [failure] says why in terms the UI can act on. The message
 * is what a failed library row shows, so it says what to do about it too.
 */
class YouTubeException(val failure: YtFailure) : Exception(
    if (failure.kind == "signin") "${failure.message} — sign in to YouTube in Settings" else failure.message,
)

/**
 * YouTube, through yt-dlp running on the phone.
 *
 * yt-dlp only *reads* the link — `-J` prints the formats with their stream
 * URLs already unlocked, its bundled QuickJS solving the player's challenge.
 * The Rust core turns that into renditions, and the bytes then go through the
 * same queue as Facebook and Drive: segmented fetch, Media3 merge, MediaStore.
 * So no ffmpeg, and a YouTube job pauses, resumes and survives a restart
 * exactly like the rest.
 *
 * YouTube changes something every few weeks and yt-dlp follows within days,
 * so yt-dlp updates itself here, without waiting for a Magpie release.
 */
object YouTube {

    const val SOURCE = "youtube"
    /** Under Downloads/. */
    const val FOLDER = "Magpie/YouTube"
    private const val CHANNEL_LIMIT = 200
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val FIRST_RUN_WAIT = 30_000L
    private const val UPDATE_WAIT = 90_000L

    private lateinit var appContext: Context
    private val prefs by lazy { YouTubePrefs(appContext) }
    private val setup = Mutex()
    /** The update in flight, shared by everyone who asks for one. */
    @Volatile private var updateJob: Deferred<Result<String?>>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // Started with the app, so the first link pasted does not wait on it.
        scope.launch { runCatching { start() } }
    }

    /**
     * Readies Python and yt-dlp. Unpacking happens once per install; after
     * that this is a quick check, but Android restarts the process whenever
     * it likes, so it runs again on every launch.
     */
    private suspend fun start(onNote: (String) -> Unit = {}) = setup.withLock {
        if (ready) return@withLock
        // Only the real first run unpacks anything worth announcing.
        if (!File(appContext.noBackupFilesDir, "youtubedl-android/packages/python").isDirectory) {
            onNote("Preparing YouTube support — first time only…")
        }
        try {
            YoutubeDL.getInstance().init(appContext)
        } catch (e: YoutubeDLException) {
            throw YouTubeException(YtFailure("other", "Could not start yt-dlp: ${e.message}"))
        }
        ready = true
    }

    fun prefs(): YouTubePrefs = prefs

    fun isLink(url: String) = youtubeLink(url) != null

    /**
     * Makes sure yt-dlp is running, and is not the year-old copy inside the APK.
     *
     * @param onNote what to tell the user while it takes long enough to notice.
     */
    private suspend fun ensureReady(onNote: (String) -> Unit) = withContext(Dispatchers.IO) {
        start(onNote)
        // Never updated: the copy inside the APK is too old to trust with
        // YouTube, so this one waits. After that, once a day in the background.
        if (YoutubeDL.getInstance().version(appContext) == null) {
            // Waited for, but not forever: the library reads GitHub's release
            // list with no timeout, and a stalled request hung the first link
            // indefinitely. Past this the bundled copy is tried, and the
            // update carries on in the background.
            onNote("Updating yt-dlp…")
            update(FIRST_RUN_WAIT)
        } else if (System.currentTimeMillis() - prefs.lastUpdateCheck > DAY) {
            startUpdate()
        }
    }

    /**
     * Fetches the newest yt-dlp release and returns the version now
     * installed, waiting at most [wait]. A slower update is not abandoned —
     * it finishes in the background, and the next caller joins it rather
     * than starting another.
     */
    suspend fun update(wait: Long = UPDATE_WAIT): Result<String?> =
        withTimeoutOrNull(wait) { startUpdate().await() }
            ?: Result.failure(java.io.IOException("GitHub is not answering — the update will keep trying in the background"))

    @Synchronized
    private fun startUpdate(): Deferred<Result<String?>> =
        updateJob?.takeIf { it.isActive } ?: scope.async {
            runCatching {
                start()
                YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel._STABLE)
                prefs.lastUpdateCheck = System.currentTimeMillis()
                YoutubeDL.getInstance().version(appContext)
            }
        }.also { updateJob = it }

    /** The installed yt-dlp version, or null before the first update. */
    fun version(): String? = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()

    /** One video, to choose a quality from. */
    suspend fun video(url: String, onNote: (String) -> Unit = {}): MediaInfo {
        val link = youtubeLink(url) ?: throw YouTubeException(YtFailure("other", "Not a YouTube link"))
        val target = link.videoUrl ?: throw YouTubeException(YtFailure("other", "That link is a playlist"))
        // The fast read first; a failed or cut-down one gets the other
        // clients, and whichever saw more is kept.
        val first = runCatching { youtubeMedia(run(target, listOf("--no-playlist"), onNote)) }
        val firstErr = first.exceptionOrNull()
        val usable = first.getOrNull()?.takeIf { !youtubeIsThin(it) }
        val info = usable ?: if (firstErr is YouTubeException && firstErr.failure.kind == "network") {
            throw firstErr
        } else {
            runCatching { youtubeMedia(run(target, listOf("--no-playlist"), onNote, fallback = true)) }
                .getOrElse { second -> first.getOrNull() ?: throw second }
        }
        return info.copy(playlistUrl = link.playlistUrl)
    }

    /** A playlist, mix or channel, flat: titles and ids, no streams yet. */
    suspend fun playlist(url: String, onNote: (String) -> Unit = {}): YtPlaylist {
        val link = youtubeLink(url) ?: throw YouTubeException(YtFailure("other", "Not a YouTube link"))
        val target = link.playlistUrl ?: throw YouTubeException(YtFailure("other", "That link is a single video"))
        val args = mutableListOf("--yes-playlist", "--flat-playlist")
        if (link.isChannel) args += listOf("--playlist-end", CHANNEL_LIMIT.toString())
        return youtubePlaylist(run(target, args, onNote))
    }

    /**
     * A YouTube search: the top [count] results, flat. yt-dlp answers a
     * search with the same shape as a playlist, so the core reads it as one.
     */
    suspend fun search(query: String, count: Int = 20, onNote: (String) -> Unit = {}): YtPlaylist =
        youtubePlaylist(run("ytsearch$count:${query.trim()}", listOf("--yes-playlist", "--flat-playlist"), onNote))

    /** Anything in the link box that is not a link is a search. */
    fun isSearch(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && !t.contains("://") && !t.startsWith("www.") &&
            !Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(t)
    }

    /**
     * Runs `yt-dlp -J`. A failure that reads like yt-dlp falling behind
     * YouTube gets one fresh yt-dlp and a second try, unless it was updated
     * within the hour and the update is not the answer.
     */
    private suspend fun run(
        url: String,
        extra: List<String>,
        onNote: (String) -> Unit,
        fallback: Boolean = false,
    ): String {
        ensureReady(onNote)
        return try {
            exec(url, extra, fallback)
        } catch (e: YouTubeException) {
            val stale = System.currentTimeMillis() - prefs.lastUpdateCheck > 60 * 60 * 1000L
            if (e.failure.kind != "outdated" || !stale) throw e
            onNote("Updating yt-dlp…")
            update()
            exec(url, extra, fallback)
        }
    }

    private suspend fun exec(url: String, extra: List<String>, fallback: Boolean = false): String = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            if (!com.syed.magpie.BuildConfig.DEBUG) addOption("--no-warnings")
            // Without one the library passes --no-cache-dir, and the player
            // cache the plugin keeps would be thrown away every run. App
            // files rather than the cache dir, which Android may empty.
            addOption("--cache-dir", File(appContext.filesDir, "ytdlp-cache").absolutePath)
            val cookies = cookieFile()
            youtubeYtdlpArgs(fallback, signedIn = cookies != null).chunked(2).forEach { (k, v) -> addOption(k, v) }
            plugins?.let { addOption("--plugin-dirs", it.absolutePath) }
            cookies?.let { addOption("--cookies", it.absolutePath) }
            extra.forEach { addOption(it) }
            if (com.syed.magpie.BuildConfig.DEBUG) addOption("-v")
        }
        try {
            val response = YoutubeDL.getInstance().execute(request)
            if (com.syed.magpie.BuildConfig.DEBUG) {
                android.util.Log.d("MagpieYtdlp", "${response.elapsedTime} ms for $url")
                response.err.lineSequence()
                    .filter { l -> listOf("Plugin dir", "player", "jsc", "Downloading", "cache", "ERROR", "WARNING").any { it in l } }
                    .forEach { android.util.Log.d("MagpieYtdlp", it.take(200)) }
            }
            response.out
        } catch (e: YoutubeDLException) {
            throw YouTubeException(youtubeFailure(e.message.orEmpty()))
        } catch (e: InterruptedException) {
            throw kotlinx.coroutines.CancellationException("yt-dlp interrupted")
        }
    }

    /**
     * Magpie's yt-dlp plugin (assets/ytdlp/magpie_cache.py), which keeps
     * YouTube's player script between runs — without it every read fetched
     * and preprocessed several megabytes of JavaScript, most of the wait.
     * Copied out once per process so an app update brings its new version.
     * yt-dlp looks for packages one level inside the directory it is given.
     */
    private val plugins: File? by lazy {
        runCatching {
            val root = File(appContext.filesDir, "ytdlp-plugins")
            val file = File(root, "magpie/yt_dlp_plugins/extractor/magpie_cache.py")
            file.parentFile!!.mkdirs()
            appContext.assets.open("ytdlp/magpie_cache.py").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            root
        }.getOrNull()
    }

    /**
     * The YouTube session from the WebView, for yt-dlp. Rewritten each run so
     * a sign-out takes effect at once; deleted when there is no session, so a
     * signed-out jar never sends half a cookie set.
     */
    private fun cookieFile(): File? {
        val file = File(appContext.filesDir, "youtube-cookies.txt")
        val text = Cookies.netscapeFor(Cookies.Site.YOUTUBE)
        if (text == null || !Cookies.isSignedIn(Cookies.Site.YOUTUBE)) {
            file.delete()
            return null
        }
        file.writeText(text)
        return file
    }

    // ---- choosing --------------------------------------------------------

    fun corePrefs(quality: String, audioLang: String? = null) = YtPrefs(
        quality = quality,
        codec = prefs.codec.preset,
        audioLang = audioLang ?: prefs.audioLang.ifBlank { null },
    )

    fun pick(info: MediaInfo, quality: String) = youtubePick(info, corePrefs(quality))

    /** The audio the picker pairs with [video], in [audioLang] when given. */
    fun audioFor(info: MediaInfo, video: Rendition?, audioLang: String? = null): Rendition? =
        youtubeAudioFor(info, video, corePrefs("best", audioLang))

    // ---- jobs -----------------------------------------------------------

    /** Where a job's file is saved: the playlist's own folder when it has one. */
    fun folderFor(playlist: String?): String =
        playlist?.let { "$FOLDER/${it.replace(Regex("""[/\\:*?"<>|]"""), "-").trim().take(60)}" } ?: FOLDER

    /** The file name: the video's own extension for an audio-only download. */
    fun fileName(title: String, quality: String, audio: Rendition?, audioOnly: Boolean): String {
        val ext = when {
            !audioOnly -> "mp4"
            audio?.mime == "audio/webm" -> "webm"
            else -> "m4a"
        }
        return safeFileName(title, quality, ext)
    }

    /** A job built from a choice made in the picker. */
    fun job(
        info: MediaInfo,
        video: Rendition?,
        audio: Rendition?,
        title: String,
        subtitle: uniffi.magpie_core.SubTrack?,
    ): DownloadJob {
        val audioOnly = video == null
        val main = video ?: audio ?: error("Nothing chosen")
        val quality = if (audioOnly) main.label else video!!.label
        val total = (main.approxBytes?.toLong() ?: 0) +
            (if (!audioOnly) audio?.approxBytes?.toLong() ?: 0 else 0)
        return DownloadJob(
            id = java.util.UUID.randomUUID().toString(),
            sourceUrl = "https://www.youtube.com/watch?v=${info.mediaId}",
            source = SOURCE,
            title = title,
            quality = quality,
            renditionId = listOfNotNull(main.id, audio?.id.takeIf { !audioOnly }).joinToString("+"),
            videoUrl = main.url,
            audioUrl = if (audioOnly) null else audio?.url,
            fileName = fileName(title, quality, audio, audioOnly),
            totalBytes = total.takeIf { it > 0 },
            mime = if (audioOnly) main.mime ?: "audio/mp4" else "video/mp4",
            folder = FOLDER,
            userAgent = main.userAgent,
            subtitleLang = subtitle?.lang,
            subtitleAuto = subtitle?.auto ?: false,
            subtitleUrl = subtitle?.url,
            subtitleExt = subtitle?.ext,
            thumbnail = info.thumbnail,
            meta = metaOf(info),
        )
    }

    /** What [tagsFor] needs later, kept on the job as JSON. */
    private fun metaOf(info: MediaInfo): String = org.json.JSONObject().apply {
        put("artist", info.uploader ?: org.json.JSONObject.NULL)
        put("date", info.uploadDate ?: org.json.JSONObject.NULL)
        put("chapters", org.json.JSONArray().apply {
            info.chapters.forEach { put(org.json.JSONArray().put(it.startMs.toLong()).put(it.title)) }
        })
    }.toString()

    /**
     * Title, channel, date and chapters for [job]'s finished file — the
     * title being whatever the user named it. [cover] goes in as cover art.
     */
    fun tagsFor(job: DownloadJob, cover: ByteArray?): Mp4Tags? {
        val m = runCatching { org.json.JSONObject(job.meta ?: return null) }.getOrNull() ?: return null
        val list = m.optJSONArray("chapters") ?: org.json.JSONArray()
        return Mp4Tags(
            title = job.title,
            artist = m.optString("artist").takeIf { it.isNotEmpty() && !m.isNull("artist") },
            date = m.optString("date").takeIf { it.isNotEmpty() && !m.isNull("date") },
            chapters = (0 until list.length()).map {
                val c = list.getJSONArray(it)
                Chapter(startMs = c.getLong(0).toULong(), title = c.getString(1))
            },
            coverJpeg = cover,
        )
    }

    /**
     * A playlist entry, queued unresolved: [DownloadEngine] calls [prepare]
     * when its turn comes. Audio-only entries cannot know their extension
     * yet, and are named once resolved.
     */
    fun lazyJob(
        url: String,
        videoId: String,
        title: String,
        quality: YouTubePrefs.Quality,
        playlist: String?,
        subtitleLang: String?,
    ) = DownloadJob(
        id = java.util.UUID.randomUUID().toString(),
        sourceUrl = url,
        source = SOURCE,
        title = title,
        quality = quality.label,
        renditionId = "",
        videoUrl = "",
        audioUrl = null,
        fileName = "",
        totalBytes = null,
        mime = if (quality == YouTubePrefs.Quality.AUDIO) "audio/mp4" else "video/mp4",
        folder = folderFor(playlist),
        preset = quality.preset,
        subtitleLang = subtitleLang,
        thumbnail = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
    )

    /**
     * Makes sure a job's stream URLs will last the download.
     *
     * YouTube's URLs carry an `expire=` stamp a few hours out and are tied to
     * the IP they were issued to, so a job queued in a playlist, paused
     * overnight, or moved from Wi-Fi to mobile data needs fresh ones. The
     * formats stay the same — the ids are kept on the job — so bytes already
     * on disk are still the right bytes and the download carries on.
     *
     * @return the job with live URLs, and whether its formats changed (in
     *   which case any partial bytes belong to something else).
     */
    suspend fun prepare(job: DownloadJob, force: Boolean): Pair<DownloadJob, Boolean> {
        val live = job.videoUrl.isNotEmpty() && !expiresSoon(job.videoUrl) &&
            (job.audioUrl == null || !expiresSoon(job.audioUrl))
        if (live && !force) return job to false

        val info = video(job.sourceUrl)
        val ids = job.renditionId.split('+').filter { it.isNotEmpty() }
        val all = info.video + info.audio
        val known = ids.mapNotNull { id -> all.firstOrNull { it.id == id } }

        val (video, audio) = if (ids.isNotEmpty() && known.size == ids.size) {
            val v = known.firstOrNull { it.kind != "audio" }
            v to (if (v == null) known.first() else known.getOrNull(1))
        } else {
            // Never resolved, or YouTube dropped the format: apply the preset,
            // or the nearest thing to the quality chosen before.
            val quality = job.preset ?: job.quality.filter { it.isDigit() }.take(4).ifEmpty { "best" }
            val p = pick(info, quality) ?: throw YouTubeException(YtFailure("unavailable", "No format to download"))
            p.video to p.audio
        }
        val audioOnly = video == null
        val main = video ?: audio ?: throw YouTubeException(YtFailure("unavailable", "No format to download"))
        val newIds = listOfNotNull(main.id, audio?.id.takeIf { !audioOnly }).joinToString("+")
        val quality = main.label
        val subtitle = job.subtitleLang?.let { lang ->
            info.subtitles.firstOrNull { it.lang == lang && it.auto == job.subtitleAuto }
                ?: info.subtitles.firstOrNull { it.lang == lang }
        }
        val total = (main.approxBytes?.toLong() ?: 0) +
            (if (!audioOnly) audio?.approxBytes?.toLong() ?: 0 else 0)

        val resolved = job.copy(
            renditionId = newIds,
            quality = quality,
            videoUrl = main.url,
            audioUrl = if (audioOnly) null else audio?.url,
            userAgent = main.userAgent,
            totalBytes = total.takeIf { it > 0 } ?: job.totalBytes,
            mime = if (audioOnly) main.mime ?: "audio/mp4" else "video/mp4",
            fileName = job.fileName.ifEmpty { fileName(job.title, quality, audio, audioOnly) },
            subtitleUrl = subtitle?.url ?: job.subtitleUrl,
            subtitleExt = subtitle?.ext ?: job.subtitleExt,
            subtitleAuto = subtitle?.auto ?: job.subtitleAuto,
            meta = metaOf(info),
        )
        return resolved to (ids.isNotEmpty() && newIds != job.renditionId)
    }

    /** Within half an hour of the `expire=` stamp, or past it. */
    private fun expiresSoon(url: String): Boolean {
        val stamp = Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: return false
        return stamp * 1000 - System.currentTimeMillis() < 30 * 60 * 1000L
    }
}
