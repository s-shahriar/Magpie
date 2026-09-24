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
import androidx.core.net.toUri
import com.syed.magpie.data.DownloadEngine
import com.syed.magpie.data.DownloadJob
import com.syed.magpie.data.DownloadProgress
import com.syed.magpie.data.UpdateInfo
import com.syed.magpie.data.UpdateService
import com.syed.magpie.data.safeFileName
import com.syed.magpie.data.selfContained
import uniffi.magpie_core.Rendition
import kotlinx.coroutines.launch
import uniffi.magpie_core.MagpieException
import uniffi.magpie_core.MediaInfo
import java.io.File

/** Where the link box is in its lifecycle. */
sealed interface ProbeState {
    data object Idle : ProbeState
    data object Working : ProbeState
    data class Ready(val info: MediaInfo) : ProbeState
    data class NeedsLogin(val site: Cookies.Site) : ProbeState
    /** Facebook no longer ships the manifest; the player has to be watched. */
    data class NeedsCapture(val url: String) : ProbeState
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
        libraryModule = m
    }

    fun openModule(m: Module) {
        enterModule(m)
        moduleRequest++
    }

    private val updates = UpdateService(app)

    init {
        DownloadEngine.init(app)
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
    fun fetch() {
        val url = link.trim()
        if (url.isEmpty()) return
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

    fun clearLink() {
        link = ""
        probe = ProbeState.Idle
        chooser = null
    }

    fun dismissChooser() { chooser = null }

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
        chooser = info
        probe = ProbeState.Ready(info)
    }

    /** Title scraped from the capture page, when there is one. */
    var capturedTitle: String = ""

    fun pause(id: String) = DownloadEngine.pause(id)

    fun resume(id: String) = DownloadEngine.resume(id)

    fun cancel(id: String) = DownloadEngine.cancel(id)

    fun clearFinished() = DownloadEngine.clearFinished()

    /**
     * Removes the row and the saved video.
     *
     * Only reachable behind a confirmation: unlike everything else in the
     * Library this cannot be undone by downloading again in a few seconds —
     * the file is gone from Downloads.
     */
    fun deleteWithFile(job: DownloadJob) {
        job.outputUri?.let { raw ->
            runCatching {
                getApplication<Application>().contentResolver.delete(raw.toUri(), null, null)
            }
        }
        DownloadEngine.cancel(job.id)
    }

    fun rename(job: DownloadJob, title: String) {
        if (title.isNotBlank()) DownloadEngine.rename(job.id, title.trim())
    }

    fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share video")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(chooser) }
    }

    fun open(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
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
