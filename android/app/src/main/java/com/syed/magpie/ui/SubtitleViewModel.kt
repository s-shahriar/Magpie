package com.syed.magpie.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syed.magpie.data.Gemini
import com.syed.magpie.data.SubtitleEngine
import com.syed.magpie.data.SubtitleJob
import com.syed.magpie.data.SubtitlePrefs
import com.syed.magpie.data.Subtitles
import com.syed.magpie.data.VideoSubs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Subtitles form, plus the library actions on its jobs. The jobs live in
 * [SubtitleEngine] so a film keeps going while the user moves on.
 */
class SubtitleViewModel(app: Application) : AndroidViewModel(app) {

    val jobs = SubtitleEngine.jobs

    /** Every job's running log, by job id. */
    val logs = SubtitleEngine.logs

    private val prefs = SubtitlePrefs(app)

    /** The key as saved; the Settings field edits a draft and calls [saveKey]. */
    var apiKey by mutableStateOf(prefs.apiKey)
        private set

    var model by mutableStateOf(prefs.model)
        private set
    var batchSize by mutableIntStateOf(prefs.batchSize)
        private set
    var hintColor by mutableStateOf(prefs.hintColor)
        private set

    /** The picked file, read and counted; null until one is chosen. */
    var picked by mutableStateOf<Picked?>(null)
        private set
    var reading by mutableStateOf(false)
        private set
    var pickError by mutableStateOf<String?>(null)
        private set

    /** Text tracks a picked film carries, when it carries more than one. */
    var trackChoices by mutableStateOf<List<VideoSubs.TextTrack>?>(null)
        private set

    /** 0..1 while a film's subtitles are being pulled out; null otherwise. */
    var extracting by mutableFloatStateOf(-1f)
        private set

    /** The job the form last sent off, whose progress it shows. */
    var lastJob by mutableStateOf<String?>(null)
        private set

    /** The film a subtitle file came out of, when it was picked as a video. */
    /**
     * What the form is about to send: a subtitle file by its URI, or the
     * text read out of a film, held in memory and never written to cache.
     */
    data class Picked(
        val uri: Uri?,
        val preview: SubtitleEngine.Preview,
        val fromVideo: String? = null,
        val text: String? = null,
    )

    val hasKey: Boolean get() = apiKey.isNotBlank()

    init {
        SubtitleEngine.init(app)
        // Leftovers from earlier sessions; nothing running wants them.
        VideoSubs.sweepCache(app)
    }

    fun saveKey(key: String) {
        prefs.apiKey = key
        apiKey = prefs.apiKey
    }

    fun chooseModel(m: Gemini.Model) {
        model = m
        prefs.model = m
    }

    fun chooseBatch(size: Int) {
        batchSize = size
        prefs.batchSize = size
    }

    fun chooseColor(color: Subtitles.HintColor) {
        hintColor = color
        prefs.hintColor = color
    }

    fun pick(uri: Uri, fileName: String) {
        reading = true
        pickError = null
        lastJob = null
        trackChoices = null
        picked = null
        if (VideoSubs.isVideoName(fileName)) {
            pickVideo(uri, fileName)
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SubtitleEngine.inspect(uri, fileName) } }
            result.onSuccess { preview ->
                if (preview.cues == 0) {
                    pickError = "No subtitle lines found in that file"
                } else {
                    picked = Picked(uri, preview)
                }
            }.onFailure {
                pickError = it.message ?: "Could not read that file"
            }
            reading = false
        }
    }

    /** A film was picked: find its text tracks and read the one to use. */
    private fun pickVideo(uri: Uri, fileName: String) {
        video = uri to fileName
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { VideoSubs.tracks(getApplication(), uri, fileName) }
            }
            found.onSuccess { tracks ->
                when (tracks.size) {
                    1 -> extract(uri, fileName, tracks.first())
                    // More than one is the user's call to make: language is
                    // the whole difference between them.
                    else -> {
                        trackChoices = tracks
                        reading = false
                    }
                }
            }.onFailure {
                reading = false
                pickError = when (it) {
                    is VideoSubs.NoSubtitles -> it.message ?: "No subtitles in that video"
                    else -> "Could not open that video: ${it.message}"
                }
            }
        }
    }

    /** The film whose tracks are on offer, kept for when one is chosen. */
    private var video: Pair<Uri, String>? = null

    /** The chosen track of a many-tracked film. */
    fun chooseTrack(track: VideoSubs.TextTrack) {
        val v = video ?: return
        trackChoices = null
        reading = true
        viewModelScope.launch { extract(v.first, v.second, track) }
    }

    private fun extract(uri: Uri, videoName: String, track: VideoSubs.TextTrack) {
        viewModelScope.launch {
            extracting = 0f
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val srt = VideoSubs.extract(getApplication(), uri, track) { p ->
                        // Written from the reading thread; snapshots take care
                        // of the hop to composition.
                        extracting = p
                    }
                    // Named after the film; kept in memory until the job takes it.
                    val name = videoName.substringBeforeLast('.').ifBlank { "film" } + ".srt"
                    srt to SubtitleEngine.inspectText(srt, name)
                }
            }
            extracting = -1f
            reading = false
            done.onSuccess { (srt, preview) ->
                if (preview.cues == 0) {
                    pickError = "The subtitles in that video came out empty"
                } else {
                    picked = Picked(null, preview, fromVideo = videoName, text = srt)
                }
            }.onFailure {
                pickError = "Could not read the subtitles: ${it.message}"
            }
        }
    }

    fun start() {
        val p = picked ?: return
        lastJob = when {
            p.text != null -> SubtitleEngine.createFromText(p.text, p.preview.fileName, model, batchSize)
            p.uri != null -> SubtitleEngine.create(p.uri, p.preview.fileName, model, batchSize)
            else -> return
        }
        picked = null
    }

    // ---- library actions ---------------------------------------------

    fun stop(id: String) { SubtitleEngine.stop(id) }
    fun resume(id: String) = SubtitleEngine.resume(id)
    fun resumeOn(id: String, model: Gemini.Model) = SubtitleEngine.resume(id, model)
    fun remove(id: String) { SubtitleEngine.remove(id) }
    fun deleteWithFile(id: String) { SubtitleEngine.deleteWithFile(id) }
    fun clearFinished() = SubtitleEngine.clearFinished()
    fun rename(id: String, base: String) { SubtitleEngine.rename(id, base) }

    /** Opens the saved file in whatever reads text; players pick it up from the folder. */
    fun open(job: SubtitleJob) {
        val uri = job.outputUri?.toUri() ?: return
        start(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun share(job: SubtitleJob) {
        val uri = job.outputUri?.toUri() ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(job.format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(Intent.createChooser(send, "Share subtitles"))
    }

    private fun start(intent: Intent) {
        runCatching {
            getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
