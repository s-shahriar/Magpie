package com.syed.magpie.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.magpie.data.ApkBundle
import com.syed.magpie.data.ApkInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One package at a time: pick it, read what's inside, choose splits, install.
 * Nothing is kept afterwards, so the module has no library.
 */
class InstallerViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Empty : State
        data object Reading : State
        data class Ready(val bundle: ApkBundle, val installed: String?) : State
        data class Installing(val bundle: ApkBundle, val progress: Float) : State
        /** The splits are written; the system's confirm sheet has the say. */
        data class Confirming(val bundle: ApkBundle) : State
        data class Done(val label: String, val packageName: String?, val note: String?) : State
        data class Failed(val message: String, val bundle: ApkBundle?) : State
    }

    var state by mutableStateOf<State>(State.Empty)
        private set

    /** Entry names of the splits that will go in. */
    var chosen by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Whether Android lets Magpie install apps; rechecked on every return. */
    var allowed by mutableStateOf(true)
        private set

    private var job: Job? = null
    private var bundle: ApkBundle? = null

    fun refreshAllowed() {
        allowed = getApplication<Application>().packageManager.canRequestPackageInstalls()
    }

    fun openAllowSettings() {
        val app = getApplication<Application>()
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    fun pick(uri: Uri, fileName: String) {
        job?.cancel()
        release()
        state = State.Reading
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                withContext(Dispatchers.IO) {
                    val b = ApkBundle.open(app, uri, fileName)
                    b to b.packageName?.let { ApkBundle.installedVersion(app, it) }
                }
            }.onSuccess { (b, installed) ->
                bundle = b
                chosen = b.splits.filter { it.suggested || it.required }.map { it.entry }.toSet()
                state = State.Ready(b, installed)
            }.onFailure {
                state = State.Failed(it.message ?: "Could not read $fileName", null)
            }
        }
    }

    fun toggle(split: ApkBundle.Split) {
        if (split.required) return
        chosen = if (split.entry in chosen) chosen - split.entry else chosen + split.entry
    }

    fun install() {
        val b = bundle ?: return
        val splits = b.splits.filter { it.entry in chosen }
        state = State.Installing(b, 0f)
        job = viewModelScope.launch {
            val outcome = runCatching {
                ApkInstaller.install(getApplication(), b, splits) { p ->
                    state = if (p >= 1f) State.Confirming(b) else State.Installing(b, p)
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                ApkInstaller.Outcome.Failed(it.message ?: "Installation failed")
            }
            state = when (outcome) {
                is ApkInstaller.Outcome.Installed ->
                    State.Done(b.label ?: b.fileName, outcome.packageName ?: b.packageName, outcome.obbNote)
                is ApkInstaller.Outcome.Failed -> State.Failed(outcome.message, b)
            }
        }
    }

    /** Stops a copy in progress, or gives up waiting on the confirm sheet. */
    fun cancel() {
        job?.cancel()
        back()
    }

    /** Back to the picked bundle after a failure, to change splits and retry. */
    fun back() {
        val b = bundle ?: return reset()
        val installed = b.packageName?.let { ApkBundle.installedVersion(getApplication(), it) }
        state = State.Ready(b, installed)
    }

    fun reset() {
        job?.cancel()
        release()
        state = State.Empty
    }

    fun launch(packageName: String) {
        val app = getApplication<Application>()
        app.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { app.startActivity(it) } }
    }

    fun canLaunch(packageName: String): Boolean =
        getApplication<Application>().packageManager.getLaunchIntentForPackage(packageName) != null

    private fun release() {
        bundle?.close()
        bundle = null
        chosen = emptySet()
    }

    override fun onCleared() {
        release()
    }
}
