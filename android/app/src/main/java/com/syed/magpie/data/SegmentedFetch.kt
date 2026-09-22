package com.syed.magpie.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

/**
 * Downloads one file over several connections at once.
 *
 * A single open-ended GET to Google Drive settles at roughly playback speed —
 * measured at ~43 KB/s on device against a 192 Mbps link, which is about fifty
 * minutes for a 137 MB lecture. The throttle applies per response, not per
 * file, so asking for bounded ranges on parallel connections avoids it.
 *
 * The file is preallocated and each worker writes at its own offset through
 * its own handle. Completed chunks are recorded in a sidecar bitmap, so a
 * pause or a killed process resumes by redoing only the chunks that were in
 * flight rather than starting over.
 */
object SegmentedFetch {

    const val CHUNK: Long = 4L * 1024 * 1024
    const val CONNECTIONS = 6
    private const val MIN_FOR_PARALLEL = 8L * 1024 * 1024

    data class Probe(val total: Long?, val acceptsRanges: Boolean)

    /** Asks for one byte: the reply reveals both the full size and range support. */
    suspend fun probe(url: String, cookie: String): Probe = withContext(Dispatchers.IO) {
        runCatching {
            val c = conn(url, cookie, 0, 0)
            try {
                val partial = c.responseCode == HttpURLConnection.HTTP_PARTIAL
                val range = c.getHeaderField("Content-Range")
                val total = range?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    ?: c.contentLengthLong.takeIf { it > 0 }
                Probe(total, partial && total != null)
            } finally {
                c.disconnect()
            }
        }.getOrElse { Probe(null, false) }
    }

    fun worthIt(p: Probe): Boolean = p.acceptsRanges && (p.total ?: 0) >= MIN_FOR_PARALLEL

    /**
     * @param onBytes called with the *delta* of bytes written, from several
     *   threads, so the caller must accumulate atomically.
     */
    suspend fun fetch(
        url: String,
        target: File,
        cookie: String,
        total: Long,
        connections: Int = CONNECTIONS,
        onBytes: (Long) -> Unit,
    ) = coroutineScope {
        val chunks = ceil(total.toDouble() / CHUNK).toInt()
        val marks = ChunkMarks(File(target.parentFile, target.name + ".chunks"), chunks)

        RandomAccessFile(target, "rw").use { it.setLength(total) }

        val done = AtomicLong(marks.completedCount().toLong() * CHUNK)
        onBytes(done.get())

        val cursor = AtomicInteger(0)
        val workers = min(connections, chunks)
        (0 until workers).map {
            async(Dispatchers.IO) {
                while (true) {
                    coroutineContext.ensureActive()
                    val index = cursor.getAndIncrement()
                    if (index >= chunks) break
                    if (marks.isDone(index)) continue

                    val start = index * CHUNK
                    val end = min(start + CHUNK, total) - 1
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(start)
                        val c = conn(url, cookie, start, end)
                        try {
                            if (c.responseCode != HttpURLConnection.HTTP_PARTIAL &&
                                c.responseCode !in 200..299
                            ) {
                                error("Server returned HTTP ${c.responseCode}")
                            }
                            c.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                var written = 0L
                                val want = end - start + 1
                                while (written < want) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    written += n
                                    onBytes(n.toLong())
                                }
                                if (written < want) error("Short chunk $index")
                            }
                        } finally {
                            c.disconnect()
                        }
                    }
                    marks.markDone(index)
                }
            }
        }.awaitAll()

        marks.delete()
    }

    private fun conn(url: String, cookie: String, from: Long, to: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Downloader.UA)
            setRequestProperty("Accept", "*/*")
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            setRequestProperty("Range", "bytes=$from-$to")
        }
}

/**
 * One byte per chunk on disk: 1 means that chunk is complete.
 *
 * Without this a pause would have to discard everything, because the parts
 * already written are scattered through the file rather than being a prefix.
 */
private class ChunkMarks(private val file: File, private val count: Int) {

    private val bits: ByteArray = runCatching {
        if (file.exists() && file.length().toInt() == count) file.readBytes() else ByteArray(count)
    }.getOrElse { ByteArray(count) }

    @Synchronized
    fun isDone(i: Int) = bits.getOrNull(i)?.toInt() == 1

    @Synchronized
    fun markDone(i: Int) {
        if (i in bits.indices) {
            bits[i] = 1
            runCatching { file.writeBytes(bits) }
        }
    }

    @Synchronized
    fun completedCount() = bits.count { it.toInt() == 1 }

    fun delete() {
        runCatching { file.delete() }
    }
}
