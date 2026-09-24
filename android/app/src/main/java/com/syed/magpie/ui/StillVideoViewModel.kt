package com.syed.magpie.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import com.syed.magpie.data.StillEngine
import com.syed.magpie.data.StillFrame
import com.syed.magpie.data.StillJob
import com.syed.magpie.data.StoryFont
import com.syed.magpie.data.StoryAlign
import com.syed.magpie.data.TextLayer
import com.syed.magpie.data.TextLook
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import com.syed.magpie.data.StillVideo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Still → Video form, plus the library actions on its jobs. The jobs
 * themselves live in [StillEngine] so an encode outlives this ViewModel.
 */
class StillVideoViewModel(app: Application) : AndroidViewModel(app) {

    val jobs = StillEngine.jobs

    /** What the form shows: a freshly picked photo, or an edited job's copy. */
    var source by mutableStateOf<Uri?>(null)
        private set
    var title by mutableStateOf("")
    var seconds by mutableIntStateOf(45)
        private set
    var frame by mutableStateOf(StillFrame.Story)

    /** Text on the story, bottom layer first. */
    val texts = mutableStateListOf<TextLayer>()

    /** The layer the style controls act on. */
    var selected by mutableStateOf<String?>(null)

    /** Whether the full-screen story editor is open. */
    var editorOpen by mutableStateOf(false)
        private set

    /** Bumped to ask the editor for the keyboard on the selected layer. */
    var typeRequest by mutableIntStateOf(0)
        private set

    private val previewer = StillVideo(app)

    /** The frame without text, for the editor; null if it cannot be read. */
    suspend fun preview(uri: Uri, frame: StillFrame): Bitmap? =
        runCatching { previewer.preview(uri, frame) }.getOrNull()

    /** The job being edited; null when the form makes a new video. */
    var editing by mutableStateOf<String?>(null)
        private set

    /** The job the form last sent off, whose progress it shows. */
    var lastJob by mutableStateOf<String?>(null)
        private set

    /** Set when the photo was re-picked while editing. */
    private var newPhoto = false

    init {
        StillEngine.init(app)
    }

    fun pick(uri: Uri) {
        source = uri
        newPhoto = true
        lastJob = null
    }

    fun setLength(value: Int) {
        seconds = value.coerceIn(1, StillVideo.MAX_SECONDS)
    }

    fun make() {
        val uri = source ?: return
        val name = title.trim().ifEmpty { defaultTitle() }
        val id = editing
        if (id != null) {
            StillEngine.edit(id, uri.takeIf { newPhoto }, name, seconds, frame, texts.toList())
            lastJob = id
        } else {
            lastJob = StillEngine.create(uri, name, seconds, frame, texts.toList())
        }
        selected = null
        editing = null
        newPhoto = false
        title = ""
    }

    /** Loads a job back into the form. */
    fun edit(job: StillJob) {
        editing = job.id
        source = Uri.fromFile(File(job.photo))
        title = job.title
        seconds = job.seconds
        frame = job.frame
        texts.clear()
        texts.addAll(job.texts)
        selected = null
        newPhoto = false
        lastJob = null
    }

    fun cancelEdit() {
        editing = null
        source = null
        title = ""
        texts.clear()
        selected = null
        newPhoto = false
    }

    // ---- text layers ---------------------------------------------------

    /**
     * Adds a layer, styled like the last one, and opens the editor on it with
     * the keyboard up. It exists from the start, so the canvas draws it as
     * it is typed; one still empty when the editor closes is dropped.
     */
    fun addText() {
        val last = texts.lastOrNull()
        val layer = TextLayer(
            text = "",
            font = last?.font ?: StoryFont.Classic,
            color = last?.color ?: android.graphics.Color.WHITE,
            look = last?.look ?: TextLook.Plain,
            align = last?.align ?: StoryAlign.Center,
        )
        texts.add(layer)
        selected = layer.id
        editorOpen = true
        typeRequest++
    }

    /** Opens the editor, on [id] when given; [type] also raises the keyboard. */
    fun openEditor(id: String? = selected, type: Boolean = false) {
        if (id != null) selectText(id)
        editorOpen = true
        if (type && id != null) typeRequest++
    }

    fun closeEditor() {
        texts.removeAll { it.text.isBlank() }
        if (texts.none { it.id == selected }) selected = null
        editorOpen = false
    }

    fun updateText(layer: TextLayer) {
        val i = texts.indexOfFirst { it.id == layer.id }
        if (i >= 0) texts[i] = layer
    }

    /** Selecting a layer also lifts it to the top, as story editors do. */
    fun selectText(id: String?) {
        selected = id
        val i = texts.indexOfFirst { it.id == id }
        if (i >= 0 && i != texts.lastIndex) texts.add(texts.removeAt(i))
    }

    fun deleteText(id: String) {
        texts.removeAll { it.id == id }
        if (selected == id) selected = null
    }

    fun duplicateText(id: String) {
        val src = texts.firstOrNull { it.id == id } ?: return
        val copy = src.copy(
            id = java.util.UUID.randomUUID().toString(),
            x = (src.x + 0.04f).coerceAtMost(0.95f),
            y = (src.y + 0.04f).coerceAtMost(0.95f),
        )
        texts.add(copy)
        selected = copy.id
    }

    // ---- library -------------------------------------------------------

    fun stop(id: String) { StillEngine.stop(id) }
    fun retry(id: String) = StillEngine.retry(id)
    fun rename(id: String, name: String) {
        if (name.isNotBlank()) StillEngine.rename(id, name.trim())
    }
    fun remove(id: String) { StillEngine.remove(id) }
    fun deleteWithFile(id: String) { StillEngine.deleteWithFile(id) }
    fun clearFinished() = StillEngine.clearFinished()

    /** Straight to the share sheet — Facebook's "Story" target is in there. */
    fun share(job: StillJob) {
        val uri = job.outputUri?.toUri() ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share video")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(chooser) }
    }

    private fun defaultTitle() =
        "Still " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
}
