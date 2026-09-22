package com.syed.magpie.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.magpie_core.MediaInfo
import uniffi.magpie_core.Rendition
import uniffi.magpie_core.probe as rustProbe
import uniffi.magpie_core.serviceFor as rustServiceFor

/** Thin wrapper over the Rust core, keeping FFI calls off the main thread. */
object Catalog {

    suspend fun probe(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val service = rustServiceFor(url)
            val cookie = Cookies.Site.of(service)?.let { Cookies.headerFor(it) }.orEmpty()
            rustProbe(url, cookie)
        }
    }

    fun serviceFor(url: String): String? = rustServiceFor(url)

    /** Total bytes for a choice, counting the audio track when one is needed. */
    fun totalBytes(info: MediaInfo, video: Rendition): Long? {
        val v = video.approxBytes?.toLong() ?: return null
        if (info.muxed || info.audio.isEmpty()) return v
        val a = info.audio.first().approxBytes?.toLong() ?: return v
        return v + a
    }
}

fun Rendition.sizeLabel(): String = approxBytes?.let {
    (if (exactSize) "" else "~") + formatBytes(it.toLong())
} ?: "size unknown"

fun formatBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val u = listOf("B", "KB", "MB", "GB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "${v.toInt()} ${u[i]}" else String.format("%.1f %s", v, u[i])
}

fun formatDuration(secs: Long): String = when {
    secs <= 0 -> ""
    secs < 3600 -> "${secs / 60}m"
    else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
}
