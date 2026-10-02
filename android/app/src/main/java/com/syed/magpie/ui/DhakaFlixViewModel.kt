package com.syed.magpie.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.magpie.data.DhakaFlix
import com.syed.magpie.data.DhakaFlixAi
import com.syed.magpie.data.DownloadEngine
import com.syed.magpie.data.SubtitlePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.magpie_core.DfCategory
import uniffi.magpie_core.DfException
import uniffi.magpie_core.DfFolder
import uniffi.magpie_core.DfItem
import uniffi.magpie_core.DfSearch

/** One level of the folder browser. */
data class FolderPage(
    val url: String,
    val name: String,
    val category: String,
    val folder: DfFolder? = null,
    val loading: Boolean = true,
)

sealed interface AiState {
    data object Idle : AiState
    data object Asking : AiState
    data class Found(val matches: List<DhakaFlixAi.Match>) : AiState
    data class Failed(val message: String) : AiState
}

/** A one-line message at the bottom, optionally with an action. */
data class Snack(val text: String, val action: String? = null, val id: Long = System.nanoTime())

class DhakaFlixViewModel(app: Application) : AndroidViewModel(app) {

    private val history = DhakaFlix.History(app)
    private val prefs = SubtitlePrefs(app)

    val categories: List<DfCategory> = DhakaFlix.categories
    val jobs = DownloadEngine.jobs

    // ---- the form ------------------------------------------------------

    /** All Categories by default: searching everything is the common case. */
    var category by mutableStateOf(categories.first())
        private set
    var query by mutableStateOf("")
    var year by mutableStateOf("")

    var searching by mutableStateOf(false)
        private set
    var results by mutableStateOf<DfSearch?>(null)
        private set

    /** Input that cannot be searched as typed: shown under the form. */
    var hint by mutableStateOf<String?>(null)
        private set

    /** A real server failure, shown as a dialog. */
    var problem by mutableStateOf<DhakaFlix.Problem?>(null)

    var snack by mutableStateOf<Snack?>(null)

    var recent by mutableStateOf(history.load())
        private set

    /**
     * Each search gets a number, and only the latest one may land. The core's
     * call cannot be interrupted, so a search overtaken by a newer one is not
     * cancelled — its answer is simply dropped.
     */
    private var generation = 0

    fun selectCategory(c: DfCategory) {
        if (c.id == category.id) return
        category = c
        // A search still running belongs to the old category; let it fall.
        generation++
        searching = false
        // The query and year stay: switching category is usually a retry of
        // the same title somewhere else.
        results = null
        hint = null
    }

    fun search(openSingleFolder: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) {
            hint = "Type a movie or series name"
            return
        }
        hint = null
        results = null
        searching = true
        val gen = ++generation
        val cat = category
        val y = year.trim().takeIf { cat.supportsYear }
        viewModelScope.launch {
            val outcome = DhakaFlix.search(cat.id, q, y)
            if (gen != generation) return@launch
            searching = false
            outcome
                .onSuccess { found ->
                    if (found.failedSources.isNotEmpty()) {
                        snack = Snack("Partial results: couldn't reach ${found.failedSources.joinToString(", ")}")
                    }
                    if (found.items.isEmpty()) {
                        hint = "No results for “$q”" + (y?.takeIf { it.isNotEmpty() }?.let { " in $it" } ?: "")
                        return@onSuccess
                    }
                    results = found
                    recent = history.add(q, cat.id)
                    // Re-running a search from history straight into its one
                    // folder saves a tap on the common "carry on watching" case.
                    val only = found.items.singleOrNull()
                    if (openSingleFolder && only != null && only.isFolder) open(only)
                }
                .onFailure { e ->
                    when (e) {
                        is DfException.Input -> hint = e.msg
                        else -> problem = DhakaFlix.problemOf(e)
                    }
                }
        }
    }

    fun searchFromHistory(r: DhakaFlix.Recent) {
        category = DhakaFlix.category(r.categoryId)
        query = r.query
        // History keeps no year; a stale one from the form would narrow it.
        year = ""
        search(openSingleFolder = true)
    }

    fun clearHistory() {
        history.clear()
        recent = emptyList()
    }

    // ---- browsing ------------------------------------------------------

    var pages by mutableStateOf<List<FolderPage>>(emptyList())
        private set

    fun open(item: DfItem) {
        // A loose file in the results opens the folder it sits in.
        val url = if (item.isFolder) item.url else DhakaFlix.parentOf(item.url)
        val name = if (item.isFolder) item.name else DhakaFlix.nameOf(url)
        // Inside a folder, keep the label of the page it was opened from; from
        // All Categories, a result's own badge ("Hindi Movies") says more.
        val label = pages.lastOrNull()?.category
            ?: item.label?.takeIf { category.id == "all" }
            ?: category.name
        val page = FolderPage(url, name, label)
        pages = pages + page
        load(page)
    }

    private fun load(page: FolderPage) {
        viewModelScope.launch {
            val outcome = DhakaFlix.folder(page.url)
            outcome
                .onSuccess { f ->
                    // "Season 1" has no poster of its own; the show above it does.
                    val inherited = pages.takeWhile { it.url != page.url }.lastOrNull()?.folder?.poster
                    val shown = if (f.poster == null && inherited != null) f.copy(poster = inherited) else f
                    replace(page.url) { it.copy(folder = shown, loading = false) }
                }
                .onFailure { e ->
                    replace(page.url) { it.copy(loading = false) }
                    problem = DhakaFlix.problemOf(e)
                }
        }
    }

    fun reload() {
        val top = pages.lastOrNull() ?: return
        replace(top.url) { it.copy(loading = true) }
        load(top)
    }

    private fun replace(url: String, f: (FolderPage) -> FolderPage) {
        pages = pages.map { if (it.url == url) f(it) else it }
    }

    /** False when there was no folder to leave — back goes to the hub. */
    fun back(): Boolean {
        if (pages.isEmpty()) return false
        pages = pages.dropLast(1)
        return true
    }

    // ---- downloads -----------------------------------------------------

    var viewRequest by mutableIntStateOf(0)
        private set

    fun download(item: DfItem, categoryName: String = category.name) {
        // A download that starts at once needs no message: the strip at the
        // bottom appears with it. Only waiting or a duplicate is news.
        snack = when (DhakaFlix.download(item.url, item.name, item.sizeBytes?.toLong(), categoryName)) {
            DhakaFlix.Queued.STARTED -> null
            DhakaFlix.Queued.WAITING -> Snack("Queued — starts when one of the four finishes", action = "View")
            DhakaFlix.Queued.DUPLICATE -> Snack("Already downloading", action = "View")
        }
    }

    fun onSnackAction() { viewRequest++ }

    // ---- AI search -----------------------------------------------------

    var aiOpen by mutableStateOf(false)
        private set
    var aiModel by mutableStateOf(DhakaFlixAi.Model.FLASH_35)
    var ai by mutableStateOf<AiState>(AiState.Idle)
        private set

    fun openAi() {
        if (query.isBlank()) {
            hint = "Type a movie or series name first"
            return
        }
        aiOpen = true
        askAi()
    }

    private var aiGeneration = 0

    fun askAi() {
        val q = query.trim()
        val model = aiModel
        ai = AiState.Asking
        val gen = ++aiGeneration
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { DhakaFlixAi.identify(prefs.apiKey, model, q) }
            }
            // Only the latest question may answer, and only into an open sheet.
            if (!aiOpen || gen != aiGeneration) return@launch
            ai = outcome.fold(
                onSuccess = { if (it.isEmpty()) AiState.Failed("No matches found. Try a different title.") else AiState.Found(it) },
                onFailure = { AiState.Failed(it.message ?: "Failed to reach Gemini. Check your connection.") },
            )
        }
    }

    /** Fills in the category and year; the query stays as typed. */
    fun pickMatch(m: DhakaFlixAi.Match) {
        val id = m.categoryId ?: return
        val c = DhakaFlix.category(id)
        selectCategory(c)
        // A series category has no year field; a year left behind there would
        // quietly narrow the next movie search.
        year = if (c.supportsYear) m.year.orEmpty() else ""
        closeAi()
    }

    fun closeAi() {
        aiGeneration++
        aiOpen = false
        ai = AiState.Idle
    }

    val hasKey: Boolean get() = prefs.apiKey.isNotBlank()
}
