package com.syed.magpie.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uniffi.magpie_core.DfItem
import java.io.File
import java.net.URLDecoder

/**
 * "Download next episode" for DhakaFlix.
 *
 * Queuing an episode from a folder already has the folder's listing in hand,
 * so that listing is kept here and the next episode comes out of it with no
 * request at all. Only the step past a season's last episode goes back to
 * the server, for the series folder and then the next season's.
 *
 * A listing lives exactly as long as some DhakaFlix download from its folder
 * is still in the list: [prune] runs whenever a row leaves, and drops every
 * folder nothing points at any more.
 */
object Episodes {

    data class Entry(val name: String, val url: String, val size: Long?)

    sealed interface Next {
        data class Queued(val name: String, val waiting: Boolean) : Next
        /** Every later episode is already in the list. */
        data class AllQueued(val upTo: String) : Next
        data object Last : Next
        data class Failed(val message: String) : Next
    }

    /** Folder (decoded) → its video files, in server order. */
    private val folders = mutableMapOf<String, List<Entry>>()
    private lateinit var file: File

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "dhakaflix-episodes.json")
        runCatching {
            if (!file.exists()) return@runCatching
            val root = JSONObject(file.readText())
            root.keys().forEach { key ->
                val arr = root.getJSONArray(key)
                folders[key] = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Entry(o.getString("n"), o.getString("u"), o.optLong("s", -1).takeIf { s -> s >= 0 })
                }
            }
        }
    }

    /** Is this file named like an episode? Gates the menu item; no request. */
    fun isEpisode(name: String): Boolean = number(name) != null

    /** Keeps the listing of the folder [items] came from. */
    @Synchronized
    fun remember(folderUrl: String, items: List<DfItem>) {
        val videos = items.filter { !it.isFolder && isVideo(it.name) }
            .map { Entry(it.name, it.url, it.sizeBytes?.toLong()) }
        if (videos.none { isEpisode(it.name) }) return
        folders[key(folderUrl)] = videos
        save()
    }

    /** Forgets every folder with no DhakaFlix job left in it. */
    @Synchronized
    fun prune(jobs: List<DownloadJob>) {
        if (folders.isEmpty()) return
        val live = jobs.filter { it.source == DhakaFlix.SOURCE }.map { key(DhakaFlix.parentOf(it.videoUrl)) }.toSet()
        if (folders.keys.removeAll { it !in live }) save()
    }

    /**
     * Queues the first episode after [job]'s that isn't already in the list:
     * later in the same folder, else the first of the next season.
     */
    suspend fun queueNext(job: DownloadJob): Next {
        val current = number(DhakaFlix.nameOf(job.videoUrl))
            ?: return Next.Failed("This file isn't named like an episode.")
        val folder = DhakaFlix.parentOf(job.videoUrl)
        val listing = listing(folder).getOrElse { return Next.Failed(DhakaFlix.problemOf(it).title) }

        val inList = DownloadEngine.jobs.value
            .filter { it.source == DhakaFlix.SOURCE }
            .map { decode(it.videoUrl) }
            .toSet()
        val template = template(DhakaFlix.nameOf(job.videoUrl))

        // Later episodes in this folder, earliest first; where a folder holds
        // two releases of each, the one named like the current file wins.
        val later = after(listing, current, template)
        val pick = later.firstOrNull { decode(it.url) !in inList }
        if (pick != null) return queue(pick, job)
        if (later.isNotEmpty()) return Next.AllQueued(later.last().name)

        // End of the season: the series folder, then the next season in it.
        val season = current.season ?: seasonOf(DhakaFlix.nameOf(folder)) ?: return Next.Last
        val series = DhakaFlix.parentOf(folder)
        val seasons = DhakaFlix.folder(series).getOrElse { return Next.Failed(DhakaFlix.problemOf(it).title) }
            .items.filter { it.isFolder && seasonOf(it.name) == season + 1 }
        if (seasons.isEmpty()) return Next.Last
        // "Season 2 [Hindi+English]" follows "Season 1 [Hindi+English]",
        // not its Spanish twin.
        val here = seasonTemplate(DhakaFlix.nameOf(folder))
        val nextFolder = seasons.firstOrNull { seasonTemplate(it.name) == here } ?: seasons.first()
        val nextListing = listing(nextFolder.url).getOrElse { return Next.Failed(DhakaFlix.problemOf(it).title) }
        val first = after(nextListing, Number(season + 1, 0), template).firstOrNull { decode(it.url) !in inList }
            ?: return Next.Last
        return queue(first, job)
    }

    private fun queue(entry: Entry, from: DownloadJob): Next =
        when (DhakaFlix.download(entry.url, entry.name, entry.size, from.quality)) {
            DhakaFlix.Queued.STARTED -> Next.Queued(entry.name, waiting = false)
            DhakaFlix.Queued.WAITING -> Next.Queued(entry.name, waiting = true)
            DhakaFlix.Queued.DUPLICATE -> Next.AllQueued(entry.name)
        }

    /** The kept listing, or a fresh one that is then kept too. */
    private suspend fun listing(folderUrl: String): Result<List<Entry>> {
        synchronized(this) { folders[key(folderUrl)] }?.let { return Result.success(it) }
        return DhakaFlix.folder(folderUrl).map { f ->
            remember(folderUrl, f.items)
            f.items.filter { !it.isFolder && isVideo(it.name) }.map { Entry(it.name, it.url, it.sizeBytes?.toLong()) }
        }
    }

    internal fun after(listing: List<Entry>, current: Number, template: String): List<Entry> =
        listing.mapNotNull { e -> number(e.name)?.let { e to it } }
            .filter { (_, n) -> (n.season ?: current.season) == current.season && n.episode > current.episode }
            .groupBy { it.second.episode }
            .toSortedMap()
            .values
            .map { same -> (same.firstOrNull { template(it.first.name) == template } ?: same.first()).first }

    // ---- names ---------------------------------------------------------

    data class Number(val season: Int?, val episode: Int)

    private val SXE = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,4})\b""")
    private val NXN = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val EP = Regex("""(?i)\b(?:Episode|Ep)[ ._-]?(\d{1,4})\b""")
    private val SEASON = Regex("""(?i)\b(?:Season[ ._-]?|S)(\d{1,2})\b""")

    fun number(name: String): Number? {
        SXE.find(name)?.let { return Number(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        NXN.find(name)?.let { return Number(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        EP.find(name)?.let { return Number(null, it.groupValues[1].toInt()) }
        return null
    }

    internal fun seasonOf(folderName: String): Int? =
        SEASON.find(folderName)?.groupValues?.get(1)?.toInt()

    /** The name with its episode number taken out: a release's signature. */
    private fun template(name: String) = name.replace(SXE, "#").replace(NXN, "#").replace(EP, "#")

    internal fun seasonTemplate(name: String) = name.replace(SEASON, "#").trim()

    private fun isVideo(name: String) = DhakaFlix.mimeFor(name).startsWith("video/")

    private fun decode(url: String) =
        runCatching { URLDecoder.decode(url.replace("+", "%2B"), "UTF-8") }.getOrDefault(url)

    private fun key(folderUrl: String) = decode(folderUrl).trimEnd('/') + "/"

    private fun save() {
        if (!::file.isInitialized) return
        runCatching {
            if (folders.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val root = JSONObject()
            folders.forEach { (k, list) ->
                root.put(k, JSONArray().apply {
                    list.forEach { put(JSONObject().put("n", it.name).put("u", it.url).put("s", it.size ?: -1)) }
                })
            }
            file.writeText(root.toString())
        }
    }
}
