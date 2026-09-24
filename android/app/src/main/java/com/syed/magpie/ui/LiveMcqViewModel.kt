package com.syed.magpie.ui

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.magpie.data.Cookies
import com.syed.magpie.data.LiveMcq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The LiveMCQ export form and whatever the last run left behind. */
class LiveMcqViewModel(app: Application) : AndroidViewModel(app) {

    private val api = LiveMcq(app)

    /** Which of the two scopes the form is on. */
    var newOnly by mutableStateOf(true)

    /** Free text so the field can be emptied while typing. */
    var count by mutableStateOf("20")

    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf<LiveMcq.Progress?>(null)
        private set
    var result by mutableStateOf<LiveMcq.Export?>(null)
        private set
    var peek by mutableStateOf<LiveMcq.Peek?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var signedOut by mutableStateOf(false)
        private set

    /** Highest id taken by any previous export; null before the first one. */
    var baseline by mutableStateOf(api.lastExported())
        private set

    /** The files in Downloads/live_fav, newest first. */
    val saved = mutableStateListOf<LiveMcq.Saved>()

    var loading by mutableStateOf(false)
        private set

    /**
     * A system consent dialog the screen still has to show.
     *
     * Files the old Termux script wrote belong to Termux, so Android will not
     * let Magpie delete or rename one until the user has said yes to it.
     */
    var consent by mutableStateOf<IntentSender?>(null)
        private set

    val signedIn: Boolean get() = Cookies.isSignedIn(Cookies.Site.LIVEMCQ)

    private val wanted: Int get() = count.toIntOrNull()?.coerceIn(1, 5000) ?: 20

    /** A first export has nothing to be new since, so it starts as a count. */
    init {
        newOnly = baseline != null
        refresh()
    }

    private companion object {
        /** The quiz app's LiveMCQ admin panel. */
        const val ADMIN = "https://general-quiz-delta.vercel.app/admin"
    }

    fun export() = perform {
        val scope = if (newOnly) LiveMcq.Scope.New else LiveMcq.Scope.Newest(wanted)
        api.export(scope) { p ->
            viewModelScope.launch(Dispatchers.Main.immediate) { progress = p }
        }.also {
            result = it
            baseline = api.lastExported()
            refresh()
        }
    }

    /** The sanity check from the sync notes: how many the account holds now. */
    fun check() = perform { peek = api.peek() }

    fun forget() {
        api.forget()
        baseline = null
        newOnly = false
    }

    // ---- the saved files -----------------------------------------------

    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            val found = runCatching { api.saved() }.getOrDefault(emptyList())
            saved.clear()
            saved.addAll(found)
            loading = false
        }
    }

    fun delete(file: LiveMcq.Saved) = change { api.delete(file.uri) }

    fun rename(file: LiveMcq.Saved, name: String) {
        if (name.isBlank()) return
        change { api.rename(file.uri, name.trim()) }
    }

    /** Runs a change, and surfaces the system's ask when one is needed. */
    private fun change(work: suspend () -> LiveMcq.Change) {
        viewModelScope.launch {
            when (val outcome = runCatching { work() }.getOrNull()) {
                is LiveMcq.Change.NeedsConsent -> consent = outcome.sender
                else -> refresh()
            }
        }
    }

    /** Called once the system dialog has been answered, either way. */
    fun consentAnswered() {
        consent = null
        refresh()
    }

    // ---- getting it into the quiz app ------------------------------------

    /**
     * Opens the admin panel in the browser, where the file picker and the
     * Google sign-in both work. Neither does inside a WebView: Google refuses
     * OAuth from an embedded browser, so the panel could not be unlocked here.
     */
    fun upload() = open(ADMIN)

    /** The share sheet, for getting a file off the phone entirely. */
    fun send(file: LiveMcq.Saved) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, file.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(Intent.createChooser(send, "Send favourites"))
    }

    /** Opens a saved file in whatever can read JSON. */
    fun view(file: LiveMcq.Saved) {
        start(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(file.uri, "application/json")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun open(url: String) = start(Intent(Intent.ACTION_VIEW, url.toUri()))

    private fun start(intent: Intent) {
        runCatching {
            getApplication<Application>()
                .startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dismissError() {
        error = null
        signedOut = false
    }

    /**
     * One at a time, with the failure shown rather than thrown.
     *
     * A dead session is its own state: the screen swaps the button for a
     * sign-in prompt instead of printing HTTP 401 at the user.
     */
    private fun perform(work: suspend () -> Unit) {
        if (running) return
        running = true
        error = null
        signedOut = false
        result = null
        progress = null
        viewModelScope.launch {
            runCatching { work() }.onFailure {
                when (it) {
                    is LiveMcq.NotSignedIn -> signedOut = true
                    else -> error = it.message ?: "Could not reach LiveMCQ"
                }
            }
            running = false
            progress = null
        }
    }
}
