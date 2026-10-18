package com.syed.magpie.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.magpie.data.Catalog
import com.syed.magpie.data.Cookies
import com.syed.magpie.data.DhakaFlix
import androidx.core.net.toUri
import com.syed.magpie.data.DownloadEngine
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadProgress
import com.syed.magpie.data.Episodes
import com.syed.magpie.data.UpdateInfo
import com.syed.magpie.data.UpdateService
import com.syed.magpie.data.YouTube
import com.syed.magpie.data.YouTubeException
import com.syed.magpie.data.YouTubePrefs
import com.syed.magpie.data.safeFileName
import com.syed.magpie.data.selfContained
import uniffi.magpie_core.Rendition
import kotlinx.coroutines.launch
import uniffi.magpie_core.MagpieException
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.SubTrack
import uniffi.magpie_core.YtPlaylist
import uniffi.magpie_core.youtubeLink
import java.io.File

/** Where the link box is in its lifecycle. */
sealed interface ProbeState {
    data object Idle : ProbeState
    data object Working : ProbeState
    data class Ready(val info: MediaInfo) : ProbeState
    data class NeedsLogin(val site: Cookies.Site) : ProbeState
    /** Facebook no longer ships the manifest; the player has to be watched. */
    data class NeedsCapture(val url: String) : ProbeState
    /** A YouTube playlist or channel, to pick entries from. */
    data class Listed(val list: YtPlaylist) : ProbeState
    /** YouTube search results for what was typed. */
    data class Searched(val query: String, val results: YtPlaylist) : ProbeState
    data class Failed(val message: String) : ProbeState
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: DownloadProgress) : UpdateState
    data class ReadyToInstall(val file: File, val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class MagpieViewModel(app: Application) : AndroidViewModel(app) {

    var link by mutableStateOf("")
    var probe by mutableStateOf<ProbeState>(ProbeState.Idle)
        private set
    var chooser by mutableStateOf<MediaInfo?>(null)

    /** The YouTube playlist being picked from, while its sheet is open. */
    var playlist by mutableStateOf<YtPlaylist?>(null)

    /**
     * What a slow probe is doing, when it is not just reading the link:
     * YouTube's first run unpacks Python and updates yt-dlp.
     */
    var workingNote by mutableStateOf<String?>(null)
        private set

    val youtubePrefs: YouTubePrefs get() = YouTube.prefs()

    /** The queue lives in the engine so it survives this ViewModel. */
    val jobs = DownloadEngine.jobs

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /**
     * Bumped whenever something outside the UI — a tap on a download
     * notification — asks for the queue. A counter rather than a flag so two
     * taps in a row both land, even if the user has since moved tabs.
     */
    var libraryRequest by mutableStateOf(0)
        private set

    fun showLibrary() { libraryRequest++ }

    /** The module open in the first tab; null is the hub. */
    var module by mutableStateOf<Module?>(null)

    /** Like [libraryRequest], for an intent that lands inside a module. */
    var moduleRequest by mutableStateOf(0)
        private set

    /** Which module's library the Library tab shows. */
    var libraryModule by mutableStateOf(Module.Downloader)

    /** Opens a module; its library becomes the one the Library tab shows. */
    fun enterModule(m: Module) {
        module = m
        if (m.hasLibrary) libraryModule = m
    }

    fun openModule(m: Module) {
        enterModule(m)
        moduleRequest++
    }

    private val updates = UpdateService(app)

    init {
        DownloadEngine.init(app)
    }

    // ---- yt-dlp --------------------------------------------------------

    var ytdlpVersion by mutableStateOf<String?>(null)
        private set
    var ytdlpUpdating by mutableStateOf(false)
        private set
    var ytdlpMessage by mutableStateOf<String?>(null)
        private set

    fun refreshYtdlpVersion() { ytdlpVersion = YouTube.version() }

    fun updateYtdlp() {
        if (ytdlpUpdating) return
        ytdlpUpdating = true
        ytdlpMessage = null
        viewModelScope.launch {
            val before = YouTube.version()
            YouTube.update()
                .onSuccess {
                    ytdlpVersion = it
                    ytdlpMessage = if (it == before) "Already the newest" else "Updated to $it"
                }
                .onFailure { ytdlpMessage = it.message ?: "Update failed" }
            ytdlpUpdating = false
        }
    }

    // ---- link handling -------------------------------------------------

    /**
     * Resolve the link.
     *
     * Deliberately does **not** check for a session first. Plenty of Drive
     * links are shared publicly and resolve with no cookies at all; demanding a
     * sign-in before even trying made the app refuse links that work fine in a
     * private browser window. Whatever cookies exist are sent, and sign-in is
     * only offered when the extractor reports it is actually needed.
     */
    fun fetch(auto: Boolean = false) {
        autoStart = auto
        val url = link.trim()
        if (url.isEmpty()) return
        if (YouTube.isLink(url)) {
            fetchYouTube(url)
            return
        }
        if (YouTube.isSearch(url)) {
            autoStart = false
            search(url)
            return
        }
        val site = Cookies.Site.of(Catalog.serviceFor(url))
        if (site == null) {
            probe = ProbeState.Failed("That link is not supported yet.")
            return
        }
        probe = ProbeState.Working
        viewModelScope.launch {
            Catalog.probe(url)
                .onSuccess {
                    // Everything on offer may be a codec this device cannot
                    // merge. Better to say so now than after a gigabyte.
                    if (Catalog.usableVideo(it).isEmpty()) {
                        probe = ProbeState.Failed(
                            "This video is only offered in a format Android " +
                                "cannot merge with its audio track.",
                        )
                        return@onSuccess
                    }
                    if (autoStart) {
                        startBest(it)
                        return@onSuccess
                    }
                    probe = ProbeState.Ready(it)
                    chooser = it
                }
                .onFailure { e ->
                    probe = when (e) {
                        // A live Facebook session that still finds nothing means
                        // the page withheld the manifest, not that the user is
                        // signed out — capture from the player instead.
                        is MagpieException.AuthRequired ->
                            if (site == Cookies.Site.FACEBOOK && Cookies.isSignedIn(site)) {
                                ProbeState.NeedsCapture(url)
                            } else {
                                ProbeState.NeedsLogin(site)
                            }
                        is MagpieException.NoMedia ->
                            if (site == Cookies.Site.FACEBOOK) {
                                ProbeState.NeedsCapture(url)
                            } else {
                                ProbeState.Failed("No video on that page.")
                            }
                        is MagpieException.Unsupported ->
                            ProbeState.Failed("That link is not supported yet.")
                        is MagpieException.Network ->
                            ProbeState.Failed("Network problem — ${e.msg}")
                        is MagpieException.Parse -> ProbeState.Failed(e.msg)
                        else -> ProbeState.Failed(e.message ?: "Could not read that link.")
                    }
                }
        }
    }

    /**
     * YouTube goes through yt-dlp rather than the core's probe. A shared link
     * downloads at the quality set in Settings, or opens the picker when that
     * is "Ask"; a playlist always opens its list, since which entries to take
     * is not something a default can decide.
     */
    private fun fetchYouTube(url: String) {
        val target = youtubeLink(url) ?: return
        // A new link replaces whatever picker the last one left open.
        chooser = null
        playlist = null
        probe = ProbeState.Working
        workingNote = null
        val note: (String) -> Unit = { workingNote = it }
        viewModelScope.launch {
            try {
                if (target.videoUrl == null) {
                    autoStart = false
                    val list = YouTube.playlist(url, note)
                    playlist = list
                    probe = ProbeState.Listed(list)
                    return@launch
                }
                val info = YouTube.video(url, note)
                val preset = youtubePrefs.sharedQuality
                if (autoStart && preset != YouTubePrefs.Quality.ASK) {
                    autoStart = false
                    startYouTubePreset(info, preset)
                    return@launch
                }
                autoStart = false
                probe = ProbeState.Ready(info)
                chooser = info
            } catch (e: YouTubeException) {
                val f = e.failure
                probe = when (f.kind) {
                    "signin" -> if (Cookies.isSignedIn(Cookies.Site.YOUTUBE)) {
                        ProbeState.Failed("${f.message}. The signed-in account cannot open it either.")
                    } else {
                        ProbeState.NeedsLogin(Cookies.Site.YOUTUBE)
                    }
                    "network" -> ProbeState.Failed("Network problem — ${f.message}")
                    "outdated" -> ProbeState.Failed(
                        "yt-dlp could not read this one: ${f.message}. " +
                            "Settings → YouTube → Update yt-dlp may fix it.",
                    )
                    else -> ProbeState.Failed(f.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                probe = ProbeState.Failed(e.message ?: "Could not read that link.")
            } finally {
                workingNote = null
            }
        }
    }

    /** The results sheet, while it is open. */
    var searchResults by mutableStateOf<ProbeState.Searched?>(null)

    /** Searches YouTube; [more] asks for twenty further results. */
    fun search(query: String, more: Boolean = false) {
        val count = if (more) (searchResults?.results?.entries?.size ?: 0) + 20 else 20
        if (!more) {
            chooser = null
            playlist = null
            probe = ProbeState.Working
        }
        searching = true
        viewModelScope.launch {
            try {
                val found = YouTube.search(query, count) { workingNote = it }
                val state = ProbeState.Searched(query, found)
                searchResults = state
                probe = state
            } catch (e: YouTubeException) {
                if (!more) {
                    probe = ProbeState.Failed(
                        if (e.failure.kind == "network") "Network problem — ${e.failure.message}" else e.failure.message,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!more) probe = ProbeState.Failed(e.message ?: "Search failed.")
            } finally {
                searching = false
                workingNote = null
            }
        }
    }

    /** True while a search, or a "more results", is running. */
    var searching by mutableStateOf(false)
        private set

    /** A search result tapped: read it like a pasted link. */
    fun openResult(url: String) {
        searchResults = null
        link = url
        fetch()
    }

    fun clearLink() {
        link = ""
        probe = ProbeState.Idle
        chooser = null
        playlist = null
        searchResults = null
    }

    fun dismissChooser() { chooser = null }

    fun dismissPlaylist() { playlist = null }

    /** From the picker of a video that sits inside a list: show the list. */
    fun openPlaylist(info: MediaInfo) {
        val url = info.playlistUrl ?: return
        chooser = null
        link = url
        autoStart = false
        fetchYouTube(url)
    }

    // ---- downloads -----------------------------------------------------

    /** [name] is whatever the picker was left showing — see QualitySheet. */
    fun start(info: MediaInfo, rendition: Rendition, name: String = info.title) {
        chooser = null
        val chosen = name.trim().ifEmpty { info.title }
        // A progressive MP4 brings its own audio; only a DASH rendition needs
        // the separate track fetched and merged alongside it.
        val audio = if (rendition.selfContained || info.muxed || info.audio.isEmpty()) {
            null
        } else {
            info.audio.first()
        }
        DownloadEngine.enqueue(
            sourceUrl = link.trim().ifEmpty { info.mediaId },
            source = info.source,
            title = chosen,
            quality = rendition.label,
            renditionId = rendition.id,
            videoUrl = rendition.url,
            audioUrl = audio?.url,
            fileName = safeFileName(chosen, rendition.label),
            totalBytes = Catalog.totalBytes(info, rendition),
        )
        link = ""
        probe = ProbeState.Idle
    }

    /**
     * A YouTube choice from its picker. [video] is null for audio only;
     * [audio] is null when the video carries its own sound.
     */
    fun startYouTube(info: MediaInfo, video: Rendition?, audio: Rendition?, name: String, subtitle: SubTrack?) {
        chooser = null
        val title = name.trim().ifEmpty { info.title }
        DownloadEngine.enqueue(YouTube.job(info, video, audio, title, subtitle))
        subtitle?.let { youtubePrefs.subtitleLang = it.lang }
        link = ""
        probe = ProbeState.Idle
    }

    private fun startYouTubePreset(info: MediaInfo, quality: YouTubePrefs.Quality) {
        val pick = YouTube.pick(info, quality.preset)
        if (pick == null) {
            probe = ProbeState.Ready(info)
            chooser = info
            return
        }
        startYouTube(info, pick.video, pick.audio, info.title, null)
        val label = pick.video?.label ?: pick.audio?.label.orEmpty()
        android.widget.Toast.makeText(
            getApplication(), "Downloading ${info.title} · $label", android.widget.Toast.LENGTH_SHORT,
        ).show()
        showLibrary()
    }

    /**
     * Queues the chosen entries of a playlist. Each is looked up only when
     * its turn comes, so a hundred-video course queues instantly and none of
     * its links has expired by the time it starts.
     */
    fun queuePlaylist(
        list: YtPlaylist,
        ids: Set<String>,
        quality: YouTubePrefs.Quality,
        numbered: Boolean,
        subtitleLang: String?,
    ) {
        val chosen = list.entries.filter { it.id in ids && it.available }
        if (chosen.isEmpty()) return
        val width = list.entries.size.toString().length.coerceAtLeast(2)
        val jobs = chosen.map { e ->
            val title = if (numbered) "${e.index.toString().padStart(width, '0')} - ${e.title}" else e.title
            YouTube.lazyJob(e.url, e.id, title, quality, list.title, subtitleLang)
        }
        DownloadEngine.enqueueAll(jobs)
        playlist = null
        link = ""
        probe = ProbeState.Idle
        android.widget.Toast.makeText(
            getApplication(),
            "Queued ${jobs.size} from ${list.title}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        showLibrary()
    }

    /** Turn streams seen on the wire into a normal quality choice. */
    fun onCaptured(items: List<com.syed.magpie.ui.screen.Captured>, sourceUrl: String) {
        if (items.isEmpty()) return
        val videos = items.filterNot { it.facts.isAudio }
            .sortedBy { it.approxBytes ?: Long.MAX_VALUE }
        val audios = items.filter { it.facts.isAudio }
            .sortedBy { it.approxBytes ?: Long.MAX_VALUE }
        if (videos.isEmpty()) return

        fun rendition(c: com.syed.magpie.ui.screen.Captured) = Rendition(
            id = c.facts.tag,
            label = c.facts.label,
            kind = if (c.facts.isAudio) "audio" else "video",
            width = null,
            height = null,
            bitrate = c.facts.bitrate,
            approxBytes = c.approxBytes?.toULong(),
            exactSize = false,
            mime = null,
            // Captured streams carry the same vencode tag the page does, so
            // the core can name the codec here too — without it the VP9 rows
            // would sail past the filter and die at the muxer.
            codec = c.facts.codec,
            url = c.url,
        )

        val info = MediaInfo(
            source = "facebook",
            mediaId = videos.first().facts.videoId,
            title = capturedTitle.ifBlank { "facebook-" + videos.first().facts.videoId },
            durationSecs = videos.first().facts.durationSecs,
            thumbnail = null,
            video = videos.map(::rendition),
            audio = audios.map(::rendition),
            muxed = audios.isEmpty(),
        )
        link = sourceUrl
        // Same gate as the probe path: a capture that only caught a VP9
        // ladder would otherwise open an empty picker.
        if (Catalog.usableVideo(info).isEmpty()) {
            probe = ProbeState.Failed(
                "This video is only offered in a format Android cannot merge " +
                    "with its audio track.",
            )
            return
        }
        if (autoStart) {
            startBest(info)
            return
        }
        chooser = info
        probe = ProbeState.Ready(info)
    }

    /**
     * Set by a link shared in from another app: the download starts at the
     * best quality on offer, with no picker. Survives a sign-in or a capture
     * in between, and is spent by the download it starts.
     */
    var autoStart by mutableStateOf(false)
        private set

    private fun startBest(info: MediaInfo) {
        autoStart = false
        val best = Catalog.usableVideo(info).maxWithOrNull(
            compareBy<Rendition>({ it.height?.toInt() ?: 0 }, { it.approxBytes ?: 0u }, { it.bitrate }),
        ) ?: return
        start(info, best)
        android.widget.Toast.makeText(
            getApplication(), "Downloading ${info.title} · ${best.label}", android.widget.Toast.LENGTH_SHORT,
        ).show()
        showLibrary()
    }

    /** Title scraped from the capture page, when there is one. */
    var capturedTitle: String = ""

    fun pause(id: String) = DownloadEngine.pause(id)

    fun resume(id: String) = DownloadEngine.resume(id)

    fun cancel(id: String) = DownloadEngine.cancel(id)

    /** Clears one library's finished rows: DhakaFlix's, or everything else's. */
    fun clearFinished(dhakaflix: Boolean) {
        DownloadEngine.clearFinished { (it.source == DhakaFlix.SOURCE) == dhakaflix }
        // DhakaFlix's "Done" holds failed rows too, so clearing it clears them.
        if (dhakaflix) DownloadEngine.clearFailed { it.source == DhakaFlix.SOURCE }
    }

    /**
     * Removes the row and the saved video.
     *
     * Only reachable behind a confirmation: unlike everything else in the
     * Library this cannot be undone by downloading again in a few seconds —
     * the file is gone from Downloads.
     */
    fun deleteWithFile(job: DownloadJob) {
        listOfNotNull(job.outputUri, job.subtitleUri).forEach { raw ->
            runCatching {
                getApplication<Application>().contentResolver.delete(raw.toUri(), null, null)
            }
        }
        DownloadEngine.cancel(job.id)
    }

    /** DhakaFlix: queue the episode after [job]'s, and say what happened. */
    fun nextEpisode(job: DownloadJob) {
        viewModelScope.launch {
            val text = when (val r = Episodes.queueNext(job)) {
                is Episodes.Next.Queued ->
                    (if (r.waiting) "Queued: " else "Downloading: ") + r.name.substringBeforeLast('.')
                is Episodes.Next.AllQueued -> "Already in the list, up to ${r.upTo.substringBeforeLast('.')}"
                Episodes.Next.Last -> "That's the last episode on the server"
                is Episodes.Next.Failed -> r.message
            }
            android.widget.Toast.makeText(getApplication(), text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun rename(job: DownloadJob, title: String) {
        if (title.isNotBlank()) DownloadEngine.rename(job.id, title.trim())
    }

    fun share(uri: Uri, mime: String = "video/mp4") {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share video")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(chooser) }
    }

    fun open(uri: Uri, mime: String = "video/mp4") {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = getApplication<Application>()
        runCatching { app.startActivity(intent) }.onFailure {
            // An .mkv or an .srt has fewer takers than an MP4; say so rather
            // than leaving the tap to do nothing.
            android.widget.Toast.makeText(app, "No app on this phone can open this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---- updates -------------------------------------------------------

    fun checkForUpdate() {
        update = UpdateState.Checking
        viewModelScope.launch {
            runCatching { updates.checkForUpdate() }
                .onSuccess {
                    update = if (it.available) UpdateState.Available(it)
                    else UpdateState.UpToDate(it.currentVersion)
                }
                .onFailure { update = UpdateState.Failed(it.message ?: "Check failed") }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            runCatching {
                updates.download(info) { update = UpdateState.Downloading(it) }
            }.onSuccess { update = UpdateState.ReadyToInstall(it, info) }
                .onFailure { update = UpdateState.Failed(it.message ?: "Download failed") }
        }
    }

    fun install(file: File) {
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { update = UpdateState.Failed(it.message ?: "Could not open the installer") }
    }

    fun dismissUpdate() { update = UpdateState.Idle }
}
