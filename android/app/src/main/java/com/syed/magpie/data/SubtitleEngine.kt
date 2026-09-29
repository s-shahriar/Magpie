package com.syed.magpie.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.syed.magpie.service.SubtitleNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The Subtitles module's queue and library, kept apart from the other
 * engines so the lists never mix.
 *
 * One job at a time, for the same reason there is one download service:
 * every request shares one free-tier allowance, and two films at once would
 * only trip the rate limit sooner. Each finished batch is written down
 * before the next one is asked for, so a stop, a crash, or a spent daily
 * quota costs nothing already paid for.
 */
object SubtitleEngine {

    private val _jobs = MutableStateFlow<List<SubtitleJob>>(emptyList())
    val jobs: StateFlow<List<SubtitleJob>> = _jobs.asStateFlow()

    /**
     * Each job's running log: every batch, what came back, what was kept,
     * every wait and every error, with a few of the hinted lines so the
     * quality can be judged while it runs. Kept beside the job's other
     * files, so a finished film's log is still there to read.
     */
    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    private const val TAG = "MagpieSubtitles"
    private const val LOG_LIMIT = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var running: Pair<String, Job>? = null

    private lateinit var appContext: Context
    private lateinit var prefs: SubtitlePrefs
    private lateinit var notifier: SubtitleNotifier
    private lateinit var file: File
    private lateinit var tmp: File
    private var started = false

    /** Breathing room between requests; the reply's own wait overrides it. */
    private const val PACE_MS = 4_000L

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs = SubtitlePrefs(appContext)
        notifier = SubtitleNotifier(appContext).also { it.ensureChannels() }
        file = File(appContext.filesDir, "subtitles.json")
        tmp = File(appContext.filesDir, "subtitles.json.tmp")
        _jobs.value = runCatching {
            if (file.exists()) SubtitleJob.listFromJson(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        sweepFiles()
        // Finished jobs keep only their log; working copies from before that
        // rule are cleared here.
        _jobs.value.filter { it.status == SubtitleStatus.COMPLETED }.forEach {
            runCatching { File(it.source).delete(); progressFile(it.id).delete() }
        }
        _logs.value = _jobs.value.associate { job ->
            job.id to runCatching { logFile(job.id).readLines() }.getOrDefault(emptyList())
        }
        // A job paused for the quota picks itself up once the day has turned.
        _jobs.value.filter { it.status == SubtitleStatus.PAUSED }.forEach { scheduleResume(it) }
        pump()
    }

    // ---- looking before starting ---------------------------------------

    /** What the form shows once a file is picked, before anything is sent. */
    data class Preview(val fileName: String, val format: SubFormat, val lengths: List<Int>) {
        val cues: Int get() = lengths.size
        fun requests(batchSize: Int) = Subtitles.cuts(lengths, batchSize).size
    }

    /** Reads and parses the picked file. Throws when it cannot be read. */
    fun inspect(uri: Uri, fileName: String): Preview = inspectText(read(uri), fileName)

    /** The same look, at text already in memory: subtitles read out of a film. */
    fun inspectText(text: String, fileName: String): Preview {
        val parsed = SubtitleFile.parse(text, SubFormat.of(fileName))
        return Preview(fileName, parsed.format, parsed.cues.map { it.text.length })
    }

    // ---- create / control ----------------------------------------------

    /** Queues a picked subtitle file. Returns the row's id at once. */
    fun create(uri: Uri, fileName: String, model: Gemini.Model, batchSize: Int): String =
        create(fileName, model, batchSize) { read(uri) }

    /** Queues subtitles already in memory, as read out of a film. Nothing touches the cache. */
    fun createFromText(text: String, fileName: String, model: Gemini.Model, batchSize: Int): String =
        create(fileName, model, batchSize) { text }

    /**
     * The job keeps its own working copy of the text while it is unfinished,
     * so a pause for the daily quota can pick up tomorrow. It goes as soon as
     * the result is saved.
     */
    private fun create(fileName: String, model: Gemini.Model, batchSize: Int, text: () -> String): String {
        val id = UUID.randomUUID().toString()
        scope.launch {
            val copy = runCatching { keepSource(id, text()) }.getOrElse { e ->
                update {
                    it + SubtitleJob(
                        id, fileName, "", model, batchSize, cues = 0, batches = 0,
                        status = SubtitleStatus.FAILED, error = "Could not read the file: ${e.message}",
                    )
                }
                return@launch
            }
            val parsed = runCatching { SubtitleFile.parse(copy.readText(), SubFormat.of(fileName)) }.getOrNull()
            val count = parsed?.count ?: 0
            if (count == 0) {
                update {
                    it + SubtitleJob(
                        id, fileName, copy.path, model, batchSize, cues = 0, batches = 0,
                        status = SubtitleStatus.FAILED, error = "No subtitle lines found in that file",
                    )
                }
                return@launch
            }
            val batches = Subtitles.batches(parsed!!.cues, batchSize).size
            update { it + SubtitleJob(id, fileName, copy.path, model, batchSize, count, batches) }
            pump()
        }
        return id
    }

    fun stop(id: String) = scope.launch {
        // Shown at once; the running request is cut rather than waited for.
        patch(id) {
            if (it.status == SubtitleStatus.COMPLETED) it
            else it.copy(status = SubtitleStatus.STOPPED, line = "Stopped", resumeAt = null)
        }
        stopRunning(id)
        patch(id) {
            if (it.status == SubtitleStatus.COMPLETED) it
            else it.copy(status = SubtitleStatus.STOPPED, line = "Stopped", resumeAt = null)
        }
        notifier.clearProgress()
        pump()
    }

    /** Picks a stopped, paused or failed job back up from its next batch. */
    /**
     * Picks a stopped, paused or failed job back up from its next batch.
     * With [model], it carries on with a different model — Flash turned away
     * as overloaded can finish on Flash-Lite without redoing what is done.
     */
    fun resume(id: String, model: Gemini.Model? = null) {
        val job = current(id) ?: return
        if (!job.status.resumable) return
        notifier.clear(job)
        if (model != null && model != job.model) log(id, "Switching to ${model.label}")
        patch(id) {
            it.copy(
                status = SubtitleStatus.QUEUED, error = null, resumeAt = null, line = null,
                model = model ?: it.model,
            )
        }
        pump()
    }

    // ---- delete --------------------------------------------------------

    /** Drops the row and its working files; a saved subtitle stays in Downloads. */
    fun remove(id: String) = scope.launch {
        stopRunning(id)
        current(id)?.let { notifier.clear(it) }
        update { list -> list.filterNot { it.id == id } }
        _logs.value = _logs.value - id
        sweepFiles()
        pump()
    }

    /** Drops the row and deletes the saved file too. */
    fun deleteWithFile(id: String) = scope.launch {
        stopRunning(id)
        current(id)?.let { job ->
            notifier.clear(job)
            job.outputUri?.let { uri -> runCatching { appContext.contentResolver.delete(uri.toUri(), null, null) } }
        }
        update { list -> list.filterNot { it.id == id } }
        _logs.value = _logs.value - id
        sweepFiles()
        pump()
    }

    /**
     * Renames a finished job's film name — the row and the saved file, which
     * keeps its `_translated` marker. A job that has not finished keeps its
     * name: the source copy is named after it.
     */
    fun rename(id: String, base: String) = scope.launch {
        val job = current(id) ?: return@launch
        if (job.status != SubtitleStatus.COMPLETED || base.isBlank()) return@launch
        val ext = job.fileName.substringAfterLast('.', "srt")
        val fileName = safeSubtitleName(base, ext)
        job.outputUri?.let { uri ->
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, Subtitles.outputName(fileName))
                }
                appContext.contentResolver.update(uri.toUri(), values, null, null)
            }
        }
        patch(id) { it.copy(fileName = fileName) }
    }

    fun clearFinished() {
        update { list -> list.filterNot { it.status == SubtitleStatus.COMPLETED } }
        sweepFiles()
    }

    // ---- the pump ------------------------------------------------------

    private fun pump() {
        scope.launch {
            lock.withLock {
                if (running != null) return@withLock
                val next = _jobs.value
                    .filter { it.status == SubtitleStatus.QUEUED }
                    .minByOrNull { it.createdAt } ?: return@withLock
                running = next.id to launchJob(next)
            }
        }
    }

    /** Thrown inside a run to end it without failing it. */
    private class Paused : Exception()

    private fun launchJob(job: SubtitleJob) = scope.launch {
        val key = prefs.apiKey
        if (key.isBlank()) {
            patch(job.id) { it.copy(status = SubtitleStatus.FAILED, error = "Add your Gemini API key in Settings") }
            finish(job.id)
            return@launch
        }
        var state = loadProgress(job.id)
        try {
            val parsed = SubtitleFile.parse(File(job.source).readText(), job.format)
            val batches = Subtitles.batches(parsed.cues, job.batchSize)
            log(
                job.id,
                if (state.done.isEmpty()) "Start · ${parsed.count} lines · ${batches.size} batches · " +
                    "${job.model.label} · ${job.batchSize} lines per request"
                else "Resume · ${state.done.size} of ${batches.size} batches already done · " +
                    "${state.hints.size} hints kept",
            )
            patch(job.id) {
                it.copy(
                    status = SubtitleStatus.RUNNING, batches = batches.size, error = null, resumeAt = null,
                    doneBatches = state.done.size, hinted = state.hints.size,
                    line = "Starting",
                )
            }

            batches.forEachIndexed { i, batch ->
                if (i in state.done) return@forEachIndexed
                if (state.done.isNotEmpty() || i > 0) delay(PACE_MS)
                status(job.id, SubtitleStatus.RUNNING, "Batch ${i + 1} of ${batches.size} · ${state.hints.size} hints so far")
                log(job.id, "Batch ${i + 1}/${batches.size} · lines ${batch.first().n}–${batch.last().n} · sending")
                state = when (val got = ask(job, key, batch)) {
                    is Got.Reply -> {
                        report(job.id, "Batch ${i + 1}", batch, got.reply)
                        state.absorb(batch, got.reply.lines) + i
                    }
                    is Got.Skipped -> {
                        log(job.id, "Batch ${i + 1} skipped (${got.why}) · its ${batch.size} lines get a second look")
                        val owed = batch.map { it.n }
                        state.copy(done = state.done + i, leftover = state.leftover + owed, fromSkips = state.fromSkips + owed)
                    }
                }
                saveProgress(job.id, state)
                patch(job.id) { it.copy(doneBatches = state.done.size, hinted = state.hints.size) }
            }

            // One more go at what was lost to a merge or a bad reply, all
            // together. After that, what is still missing stays as it was.
            if (state.leftover.isNotEmpty() && !state.swept) {
                val left = parsed.cues.filter { it.n in state.leftover }
                val skipped = left.count { it.n in state.fromSkips }
                val refused = left.size - skipped
                log(
                    job.id,
                    "Second look at ${left.size} lines · " + listOfNotNull(
                        skipped.takeIf { it > 0 }?.let { "$it from a skipped batch" },
                        refused.takeIf { it > 0 }?.let { "$it the model changed or lost" },
                    ).joinToString(", "),
                )
                for (batch in Subtitles.batches(left, job.batchSize)) {
                    delay(PACE_MS)
                    status(job.id, SubtitleStatus.RUNNING, "Second look at ${left.size} lines")
                    state = when (val got = ask(job, key, batch)) {
                        is Got.Reply -> {
                            report(job.id, "Second look", batch, got.reply)
                            state.absorb(batch, got.reply.lines, sweeping = true)
                        }
                        is Got.Skipped -> state.also { log(job.id, "Second look skipped (${got.why})") }
                    }
                    saveProgress(job.id, state)
                }
                state = state.copy(swept = true)
                saveProgress(job.id, state)
            }

            status(job.id, SubtitleStatus.RUNNING, "Saving")
            // Colour goes on only as the file goes out: what is stored per
            // batch stays plain, so changing the colour and running a film
            // again never asks the model for anything.
            val final = if (prefs.hintColor.on) {
                state.hints.mapValues { (_, t) -> Subtitles.colorize(t, prefs.hintColor, job.format) }
            } else state.hints
            val uri = publish(current(job.id) ?: job, parsed.render(final))
            patch(job.id) {
                it.copy(
                    status = SubtitleStatus.COMPLETED, doneBatches = it.batches,
                    hinted = state.hints.size, skipped = state.leftover.size,
                    outputUri = uri.toString(), line = null, error = null, resumeAt = null,
                )
            }
            notifier.clearProgress()
            // The film is in Downloads now; the working copy and the batch
            // record have done their job. Only the log stays.
            runCatching {
                File(job.source).delete()
                progressFile(job.id).delete()
            }
            current(job.id)?.let {
                log(
                    job.id,
                    "Done · ${state.hints.size} hints in ${parsed.count} lines · " +
                        "${state.leftover.size} left as they were · ${it.requests} requests · saved ${it.outputName}",
                )
                notifier.saved(it)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (p: Paused) {
            notifier.clearProgress()
            current(job.id)?.let {
                log(job.id, "Paused · ${it.line ?: "daily quota used up"}")
                notifier.stopped(it)
                scheduleResume(it)
            }
        } catch (e: Throwable) {
            // Never a blank reason: an exception without a message still has
            // a type, and the first frame of ours says where it came from.
            // The root cause, not a wrapper like ExceptionInInitializerError.
            val root = generateSequence(e) { it.cause }.last()
            val reason = root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
            val where = e.stackTrace.firstOrNull { it.className.startsWith("com.syed.magpie") }
            Log.e(TAG, "Job ${job.fileName} failed", e)
            log(job.id, "FAILED · ${root.javaClass.simpleName}: $reason" + (where?.let { " · at ${it.fileName}:${it.lineNumber}" } ?: ""))
            patch(job.id) {
                it.copy(status = SubtitleStatus.FAILED, error = reason, line = null)
            }
            notifier.clearProgress()
            current(job.id)?.let { notifier.stopped(it) }
        } finally {
            finish(job.id)
        }
    }

    private suspend fun finish(id: String) {
        lock.withLock { if (running?.first == id) running = null }
        pump()
    }

    private sealed interface Got {
        data class Reply(val reply: Gemini.Reply) : Got
        data class Skipped(val why: String) : Got
    }

    /**
     * One batch, with the retry policy the failures earned:
     *  - a per-minute 429 waits exactly as long as Google says, three times;
     *  - a daily 429 pauses the job until the reset — no countdowns, no retries;
     *  - a 5xx or a dropped connection tries twice more, at 10 s and 30 s;
     *  - a reply that will not parse gets one more attempt;
     *  - a bad key fails the job outright.
     * When the retries are spent the batch is skipped, and its lines get the
     * second look at the end rather than being lost.
     */
    private suspend fun ask(job: SubtitleJob, key: String, batch: List<Cue>): Got {
        var waits = 0
        var outages = 0
        var garbled = 0
        while (true) {
            patch(job.id) { it.copy(requests = it.requests + 1) }
            try {
                return Got.Reply(call(key, job.model, batch))
            } catch (e: Gemini.RateLimited) {
                if (e.daily) {
                    val at = maxOf(System.currentTimeMillis() + e.retryAfterMs, Gemini.nextDailyReset())
                    patch(job.id) {
                        it.copy(
                            status = SubtitleStatus.PAUSED, resumeAt = at,
                            line = "Today's free requests are used up · resumes ${whenText(at)}",
                        )
                    }
                    throw Paused()
                }
                if (++waits > 3) return Got.Skipped("rate limited")
                val wait = e.retryAfterMs.coerceIn(2_000L, 120_000L) + 2_000L
                log(job.id, "Rate limited (per minute) · waiting ${wait / 1000}s · try ${waits + 1}")
                countdown(job.id, wait)
            } catch (e: Gemini.Overloaded) {
                // Busy three times running is not this batch's problem: the
                // next would meet the same. Stop and keep what is done,
                // rather than skipping through the film to an empty file.
                if (++outages > 2) throw Unavailable(
                    "${job.model.label} is overloaded right now. Try again later, " +
                        "or resume on another model from the Library.",
                )
                log(job.id, "Google is busy: ${e.message} · retrying in ${if (outages == 1) 10 else 30}s")
                countdown(job.id, if (outages == 1) 10_000L else 30_000L, "Google is busy")
            } catch (e: Gemini.BadReply) {
                log(job.id, "Unreadable reply: ${e.message}" + if (garbled == 0) " · asking again" else "")
                if (++garbled > 1) return Got.Skipped(e.message ?: "bad reply")
            } catch (e: Gemini.Rejected) {
                log(job.id, "Rejected by Google: ${e.message}")
                throw e
            } catch (e: IOException) {
                // A Stop disconnects the request; that is not a network fault.
                currentCoroutineContext().ensureActive()
                val why = e.message ?: e.javaClass.simpleName
                if (++outages > 2) throw Unavailable("Could not reach Google: $why")
                log(job.id, "Connection problem: $why · retrying in ${if (outages == 1) 10 else 30}s")
                countdown(job.id, if (outages == 1) 10_000L else 30_000L, "No connection")
            }
        }
    }

    /** The model's reply, abandoned the moment the job is cancelled. */
    private suspend fun call(key: String, model: Gemini.Model, batch: List<Cue>): Gemini.Reply = coroutineScope {
        val open = AtomicReference<HttpURLConnection?>()
        val guard = launch {
            try {
                awaitCancellation()
            } finally {
                open.get()?.disconnect()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) { Gemini.annotate(key, model, batch) { open.set(it) } }
        } finally {
            guard.cancel()
        }
    }

    /** The model cannot be reached for now; the job stops with its progress kept. */
    private class Unavailable(message: String) : Exception(message)

    private suspend fun countdown(id: String, ms: Long, why: String = "Rate limited") {
        val until = System.currentTimeMillis() + ms
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            status(id, SubtitleStatus.WAITING, "$why · retrying in ${(left + 999) / 1000}s")
            delay(minOf(left, 1_000L))
        }
    }

    private fun scheduleResume(job: SubtitleJob) {
        val at = job.resumeAt ?: return
        scope.launch {
            delay(maxOf(0L, at - System.currentTimeMillis()))
            if (current(job.id)?.status == SubtitleStatus.PAUSED) resume(job.id)
        }
    }

    private fun whenText(at: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val today = java.util.Calendar.getInstance()
        val time = "%d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        return if (cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) "at $time"
        else "tomorrow at $time"
    }

    /** Takes a reply into the progress: accepted lines in, the rest owed. */
    private fun SubtitleProgress.absorb(batch: List<Cue>, lines: Map<Int, String>, sweeping: Boolean = false): SubtitleProgress {
        val hints = this.hints.toMutableMap()
        val owed = this.leftover.toMutableSet()
        for (cue in batch) {
            val back = lines[cue.n]
            if (back != null && Hints.accepts(cue.text, back)) {
                if (Hints.hasHint(back)) hints[cue.n] = back.replace("\r", "")
                owed -= cue.n
            } else if (!sweeping) {
                owed += cue.n
            }
        }
        return copy(hints = hints, leftover = owed)
    }

    private operator fun SubtitleProgress.plus(batch: Int) = copy(done = done + batch)

    /**
     * One line on what a reply held, and a few of its hints: the check a
     * person would make by eye, written down for every batch.
     */
    private fun report(id: String, label: String, batch: List<Cue>, reply: Gemini.Reply) {
        var hinted = 0
        var plain = 0
        var missing = 0
        val refused = mutableListOf<Int>()
        val samples = mutableListOf<String>()
        for (cue in batch) {
            val back = reply.lines[cue.n]
            when {
                back == null -> missing++
                !Hints.accepts(cue.text, back) -> refused += cue.n
                Hints.hasHint(back) -> {
                    hinted++
                    if (samples.size < 4) samples += "#${cue.n} " + back.replace("\n", " / ")
                }
                else -> plain++
            }
        }
        log(
            id,
            "$label · back in ${"%.1f".format(reply.millis / 1000.0)}s · ${reply.lines.size}/${batch.size} lines · " +
                "$hinted hinted · $plain unchanged" +
                (if (missing > 0) " · $missing missing" else "") +
                (if (refused.isNotEmpty()) " · ${refused.size} refused (#${refused.take(5).joinToString(", #")})" else "") +
                " · tokens ${reply.promptTokens} in / ${reply.responseTokens} out",
        )
        samples.forEach { log(id, "   $it") }
    }

    /** Appends to a job's log: the screen's panel, the job's file, and logcat. */
    private fun log(id: String, line: String) {
        val stamped = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $line"
        Log.i(TAG, stamped)
        synchronized(this) {
            val current = _logs.value[id].orEmpty()
            _logs.value = _logs.value + (id to (current + stamped).takeLast(LOG_LIMIT))
        }
        runCatching { logFile(id).appendText(stamped + "\n") }
    }

    private fun logFile(id: String) = File(dir(), "$id.log")

    // ---- files ---------------------------------------------------------

    /** A name MediaStore accepts: no path characters, and never empty. */
    private fun safeSubtitleName(base: String, ext: String): String {
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().trimEnd('.')
        return (clean.ifEmpty { "subtitles" }) + "." + ext
    }

    private fun read(uri: Uri): String =
        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("no access")

    private fun dir() = File(appContext.filesDir, "subtitles").apply { mkdirs() }

    private fun keepSource(id: String, text: String): File {
        val out = File(dir(), "$id.src")
        val part = File(dir(), "$id.src.part")
        part.writeText(text)
        check(part.renameTo(out)) { "could not store it" }
        return out
    }

    private fun progressFile(id: String) = File(dir(), "$id.progress.json")

    private fun loadProgress(id: String): SubtitleProgress =
        progressFile(id).takeIf { it.exists() }?.let { SubtitleProgress.fromJson(it.readText()) } ?: SubtitleProgress()

    private fun saveProgress(id: String, p: SubtitleProgress) {
        val f = progressFile(id)
        val part = File(f.path + ".tmp")
        part.writeText(p.toJson())
        part.renameTo(f)
    }

    /** Deletes working files no job points at any more. */
    private fun sweepFiles() {
        val live = _jobs.value.map { it.id }.toSet()
        runCatching {
            dir().listFiles()
                ?.filterNot { f -> live.any { f.name.startsWith(it) } }
                ?.forEach { it.delete() }
        }
    }

    /** Writes the finished file to Downloads/Magpie, replacing an earlier run's. */
    private fun publish(job: SubtitleJob, body: String): Uri {
        val resolver = appContext.contentResolver
        job.outputUri?.let { runCatching { resolver.delete(it.toUri(), null, null) } }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, job.outputName)
            put(MediaStore.Downloads.MIME_TYPE, job.format.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Magpie")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file")
        resolver.openOutputStream(uri).use {
            checkNotNull(it) { "Could not open the file" }
            it.write(body.toByteArray())
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // ---- state ---------------------------------------------------------

    private suspend fun stopRunning(id: String) {
        val job = lock.withLock {
            running?.takeIf { it.first == id }?.second.also { if (it != null) running = null }
        }
        job?.cancelAndJoin()
    }

    private fun status(id: String, status: SubtitleStatus, line: String) {
        patch(id) { it.copy(status = status, line = line) }
        current(id)?.let { notifier.progress(it) }
    }

    private fun current(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun patch(id: String, f: (SubtitleJob) -> SubtitleJob) {
        update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    @Synchronized
    private fun update(f: (List<SubtitleJob>) -> List<SubtitleJob>) {
        val previous = _jobs.value
        val next = f(previous)
        _jobs.value = next
        // Status lines and request counts tick every few seconds, and RUNNING
        // and WAITING trade places on every countdown; none of that is worth
        // a write. Anything a restart would need is.
        val cosmetic = next.size == previous.size && next.zip(previous).all { (a, b) ->
            a == b.copy(line = a.line, requests = a.requests, status = a.status) &&
                (a.status == b.status || (a.status.active && b.status.active))
        }
        if (cosmetic) return
        runCatching {
            tmp.writeText(SubtitleJob.listToJson(next))
            tmp.renameTo(file)
        }
    }
}
