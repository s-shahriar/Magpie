package com.syed.magpie.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.magpie.data.Catalog
import com.syed.magpie.data.Cookies
import com.syed.magpie.data.DownloadProgress
import com.syed.magpie.data.Downloader
import com.syed.magpie.data.Progress
import com.syed.magpie.data.UpdateInfo
import com.syed.magpie.data.UpdateService
import com.syed.magpie.data.safeFileName
import kotlinx.coroutines.launch
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Rendition
import java.io.File

/** Where the link box is in its lifecycle. */
sealed interface ProbeState {
    data object Idle : ProbeState
    data object Working : ProbeState
    data class Ready(val info: MediaInfo) : ProbeState
    data class NeedsLogin(val site: Cookies.Site) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/** One row in the library. */
data class DownloadJob(
    val id: Long,
    val title: String,
    val quality: String,
    val source: String,
    var progress: Progress? = null,
    var uri: Uri? = null,
    var error: String? = null,
    var task: kotlinx.coroutines.Job? = null,
) {
    val done get() = uri != null
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

    val jobs = mutableStateListOf<DownloadJob>()

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private val downloader = Downloader(app)
    private val updates = UpdateService(app)
    private var nextId = 1L

    // ---- link handling -------------------------------------------------

    fun fetch() {
        val url = link.trim()
        if (url.isEmpty()) return
        val site = Cookies.Site.of(Catalog.serviceFor(url))
        if (site == null) {
            probe = ProbeState.Failed("That link is not supported yet.")
            return
        }
        if (!Cookies.isSignedIn(site)) {
            probe = ProbeState.NeedsLogin(site)
            return
        }
        probe = ProbeState.Working
        viewModelScope.launch {
            Catalog.probe(url)
                .onSuccess {
                    probe = ProbeState.Ready(it)
                    chooser = it
                }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    probe = if (msg.contains("sign-in", true)) {
                        ProbeState.NeedsLogin(site)
                    } else {
                        ProbeState.Failed(msg.ifEmpty { "Could not read that link." })
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

    fun start(info: MediaInfo, rendition: Rendition) {
        chooser = null
        val job = DownloadJob(
            id = nextId++,
            title = info.title,
            quality = rendition.label,
            source = info.source,
        )
        jobs.add(0, job)
        job.task = viewModelScope.launch {
            runCatching {
                downloader.download(info, rendition, safeFileName(info.title, rendition.label)) { p ->
                    val i = jobs.indexOfFirst { it.id == job.id }
                    if (i >= 0) jobs[i] = jobs[i].copy(progress = p)
                }
            }.onSuccess { uri ->
                val i = jobs.indexOfFirst { it.id == job.id }
                if (i >= 0) jobs[i] = jobs[i].copy(uri = uri, progress = null)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                val i = jobs.indexOfFirst { it.id == job.id }
                if (i >= 0) jobs[i] = jobs[i].copy(error = e.message ?: "Download failed", progress = null)
            }
        }
        link = ""
        probe = ProbeState.Idle
    }

    fun cancel(job: DownloadJob) {
        job.task?.cancel()
        jobs.removeAll { it.id == job.id }
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
