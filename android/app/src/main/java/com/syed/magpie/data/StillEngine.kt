package com.syed.magpie.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Still → Video's own queue and library, kept apart from [DownloadEngine] so
 * the two lists never mix.
 *
 * A process-wide singleton for the same reason the download engine is one:
 * an encode keeps going while the user moves between screens. One job at a
 * time — the hardware encoder is a single shared unit, and two encodes at
 * once only make both slower.
 */
object StillEngine {

    private val _jobs = MutableStateFlow<List<StillJob>>(emptyList())
    val jobs: StateFlow<List<StillJob>> = _jobs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var running: Pair<String, Job>? = null

    /** Ids whose photo is still being copied in, before their row exists. */
    private val copying = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private lateinit var appContext: Context
    private lateinit var encoder: StillVideo
    private lateinit var file: File
    private lateinit var tmp: File
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        encoder = StillVideo(appContext)
        file = File(appContext.filesDir, "stills.json")
        tmp = File(appContext.filesDir, "stills.json.tmp")
        _jobs.value = runCatching {
            if (file.exists()) StillJob.listFromJson(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        sweepPhotos()
        // Anything still queued when the process died picks up where it was.
        pump()
    }

    // ---- create / update -----------------------------------------------

    /** Queues a new video. Returns its id at once; the photo copy is async. */
    fun create(photo: Uri, title: String, seconds: Int, frame: StillFrame, texts: List<TextLayer>): String {
        val id = UUID.randomUUID().toString()
        copying += id
        scope.launch {
            val copy = runCatching { keepPhoto(id, photo) }.also { copying -= id }.getOrElse { e ->
                update { it + StillJob(id, title, "", seconds, frame, texts, status = StillStatus.FAILED, error = "Could not read the photo: ${e.message}") }
                return@launch
            }
            update { it + StillJob(id, title, copy.path, seconds, frame, texts) }
            pump()
        }
        return id
    }

    /**
     * Changes a job. A rename alone on a finished video is only a rename;
     * anything that alters the picture re-encodes it, and the new file
     * replaces the old one only once it is safely written.
     */
    fun edit(
        id: String,
        photo: Uri?,
        title: String,
        seconds: Int,
        frame: StillFrame,
        texts: List<TextLayer>,
    ) = scope.launch {
        val job = current(id) ?: return@launch
        val reencode = photo != null || seconds != job.seconds || frame != job.frame ||
            texts != job.texts ||
            job.status != StillStatus.COMPLETED
        if (!reencode) {
            rename(id, title)
            return@launch
        }
        stopRunning(id)
        val path = if (photo != null) {
            runCatching { keepPhoto(id, photo).path }.getOrElse { e ->
                patch(id) { it.copy(status = StillStatus.FAILED, error = "Could not read the photo: ${e.message}") }
                return@launch
            }
        } else {
            job.photo
        }
        patch(id) {
            it.copy(
                title = title, photo = path, seconds = seconds, frame = frame, texts = texts,
                status = StillStatus.QUEUED, progress = 0f, error = null,
            )
        }
        pump()
    }

    /** Renames the row and, for a finished video, the file on disk. */
    fun rename(id: String, title: String) = scope.launch {
        val job = current(id) ?: return@launch
        val renamed = job.copy(title = title)
        job.outputUri?.let { uri ->
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, renamed.fileName)
                }
                appContext.contentResolver.update(uri.toUri(), values, null, null)
            }
        }
        patch(id) { it.copy(title = title) }
    }

    fun stop(id: String) = scope.launch {
        stopRunning(id)
        patch(id) {
            if (it.status == StillStatus.COMPLETED) it
            else it.copy(status = StillStatus.STOPPED, progress = 0f)
        }
        pump()
    }

    fun retry(id: String) {
        patch(id) { it.copy(status = StillStatus.QUEUED, progress = 0f, error = null) }
        pump()
    }

    // ---- delete --------------------------------------------------------

    /** Drops the row and its photo copy; a saved video stays in Downloads. */
    fun remove(id: String) = scope.launch {
        stopRunning(id)
        update { list -> list.filterNot { it.id == id } }
        sweepPhotos()
        pump()
    }

    /** Drops the row and deletes the saved video too. */
    fun deleteWithFile(id: String) = scope.launch {
        stopRunning(id)
        current(id)?.outputUri?.let { uri ->
            runCatching { appContext.contentResolver.delete(uri.toUri(), null, null) }
        }
        update { list -> list.filterNot { it.id == id } }
        sweepPhotos()
        pump()
    }

    /** Clears finished rows without touching the saved files. */
    fun clearFinished() {
        update { list -> list.filterNot { it.status == StillStatus.COMPLETED } }
        sweepPhotos()
    }

    // ---- the pump ------------------------------------------------------

    private fun pump() {
        scope.launch {
            lock.withLock {
                if (running != null) return@withLock
                val next = _jobs.value
                    .filter { it.status == StillStatus.QUEUED }
                    .minByOrNull { it.createdAt } ?: return@withLock
                running = next.id to launchJob(next)
            }
        }
    }

    private fun launchJob(job: StillJob) = scope.launch {
        patch(job.id) { it.copy(status = StillStatus.ENCODING, progress = 0f, error = null) }
        try {
            val uri = encoder.render(
                Uri.fromFile(File(job.photo)), job.seconds, job.frame, job.fileName, job.texts,
            ) { p -> patch(job.id) { it.copy(progress = p) } }
            // An edit re-encodes into a fresh file; the old one goes only now
            // that the new one exists.
            val old = job.outputUri
            if (old != null && old != uri.toString()) {
                runCatching { appContext.contentResolver.delete(old.toUri(), null, null) }
                // Written while the old file still held the name, MediaStore
                // called it "… (1).mp4"; take the clean name back.
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, job.fileName)
                    }
                    appContext.contentResolver.update(uri, values, null, null)
                }
            }
            val size = runCatching {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()
            patch(job.id) {
                it.copy(
                    status = StillStatus.COMPLETED, progress = 1f,
                    outputUri = uri.toString(), sizeBytes = size,
                )
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Throwable) {
            patch(job.id) {
                it.copy(status = StillStatus.FAILED, error = e.message ?: "Could not make the video")
            }
        } finally {
            lock.withLock { if (running?.first == job.id) running = null }
            pump()
        }
    }

    private suspend fun stopRunning(id: String) {
        val job = lock.withLock {
            running?.takeIf { it.first == id }?.second.also { if (it != null) running = null }
        }
        job?.cancelAndJoin()
    }

    // ---- state ---------------------------------------------------------

    private fun current(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun patch(id: String, f: (StillJob) -> StillJob) {
        update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    @Synchronized
    private fun update(f: (List<StillJob>) -> List<StillJob>) {
        val previous = _jobs.value
        val next = f(previous)
        _jobs.value = next
        // Progress ticks change nothing worth keeping across a restart.
        val progressOnly = next.size == previous.size &&
            next.zip(previous).all { (a, b) -> a == b.copy(progress = a.progress) }
        if (progressOnly) return
        runCatching {
            tmp.writeText(StillJob.listToJson(next))
            tmp.renameTo(file)
        }
    }

    // ---- photo copies --------------------------------------------------

    private fun photosDir() = File(appContext.filesDir, "stills").apply { mkdirs() }

    private fun keepPhoto(id: String, source: Uri): File {
        val out = File(photosDir(), "$id.img")
        val part = File(photosDir(), "$id.img.part")
        appContext.contentResolver.openInputStream(source).use { input ->
            checkNotNull(input) { "no access" }
            part.outputStream().use { input.copyTo(it) }
        }
        check(part.renameTo(out)) { "could not store it" }
        return out
    }

    /** Deletes photo copies no job points at any more. */
    private fun sweepPhotos() {
        val live = _jobs.value.map { it.id }.toSet() + synchronized(copying) { copying.toSet() }
        runCatching {
            photosDir().listFiles()
                ?.filterNot { f -> live.any { f.name.startsWith(it) } }
                ?.forEach { it.delete() }
        }
    }
}
