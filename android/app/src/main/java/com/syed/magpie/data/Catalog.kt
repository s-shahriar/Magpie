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

    /**
     * The renditions this device can actually deliver.
     *
     * A short list now that merging uses Media3's writer rather than the
     * platform's — VP9, AV1 and Opus all go into an MP4 fine. What is left is
     * still worth catching here: the house rule is not to offer a rendition
     * that is known to be blocked, and finding out at the muxer costs the
     * whole download.
     *
     * Only applies when the audio has to be merged in: a muxed stream is saved
     * byte-for-byte and never meets a muxer at all.
     */
    fun usableVideo(info: MediaInfo): List<Rendition> {
        if (info.muxed || info.audio.isEmpty()) return info.video
        // A muxed row carries its own audio and never meets the muxer, so it
        // survives whatever the separate audio track is.
        val (ready, needsMerge) = info.video.partition { it.selfContained }
        if (!info.audio.first().mergeable()) return ready
        return ready + needsMerge.filter { it.mergeable() }
    }

    /** Total bytes for a choice, counting the audio track when one is needed. */
    fun totalBytes(info: MediaInfo, video: Rendition): Long? {
        val v = video.approxBytes?.toLong() ?: return null
        if (video.selfContained || info.muxed || info.audio.isEmpty()) return v
        val a = info.audio.first().approxBytes?.toLong() ?: return v
        return v + a
    }
}

/**
 * Whether MediaMuxer can put this stream in an MP4 beside an audio track.
 *
 * An unknown codec counts as mergeable: the core reads the codec out of a
 * Facebook tag name, and a tag Facebook renames must not take the whole
 * quality list down with it. The muxer stays the backstop for that case.
 */
/** A single file that already holds both tracks — nothing to merge. */
val Rendition.selfContained: Boolean get() = kind == "muxed"

fun Rendition.mergeable(): Boolean = when (codec?.lowercase()) {
    // Everything Media3's Mp4Muxer takes: AV1, H.263, H.264, H.265, MPEG-4 and
    // VP9 video; AAC, AMR, Opus and Vorbis audio. VP8 and FLAC are the ones
    // left outside.
    "vp8", "flac" -> false
    else -> true
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
