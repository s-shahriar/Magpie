package com.syed.magpie.data

import android.content.Context
import androidx.core.content.edit

/**
 * YouTube's settings: what a shared link downloads at, which codecs to lean
 * towards, and the subtitle language last asked for.
 */
class YouTubePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    /** A shared or "Open with" link: [Quality.ASK] shows the picker. */
    var sharedQuality: Quality
        get() = Quality.of(prefs.getString(SHARED, null))
        set(value) = prefs.edit { putString(SHARED, value.name) }

    var codec: Codec
        get() = Codec.of(prefs.getString(CODEC, null))
        set(value) = prefs.edit { putString(CODEC, value.name) }

    /** Audio language to prefer over the original track; blank for original. */
    var audioLang: String
        get() = prefs.getString(AUDIO_LANG, "").orEmpty()
        set(value) = prefs.edit { putString(AUDIO_LANG, value.trim()) }

    /** Last subtitle language picked, so the next video offers it first. */
    var subtitleLang: String
        get() = prefs.getString(SUB_LANG, "").orEmpty()
        set(value) = prefs.edit { putString(SUB_LANG, value) }

    /** When yt-dlp last checked for a newer release, in epoch millis. */
    var lastUpdateCheck: Long
        get() = prefs.getLong(LAST_UPDATE, 0)
        set(value) = prefs.edit { putLong(LAST_UPDATE, value) }

    /** In picker order. `preset` is what the core's `youtube_pick` reads. */
    enum class Quality(val label: String, val preset: String) {
        ASK("Ask every time", "best"),
        BEST("Best available", "best"),
        P2160("Up to 2160p", "2160"),
        P1440("Up to 1440p", "1440"),
        P1080("Up to 1080p", "1080"),
        P720("Up to 720p", "720"),
        P480("Up to 480p", "480"),
        P360("Up to 360p", "360"),
        SMALLEST("Smallest file", "smallest"),
        AUDIO("Audio only", "audio"),
        ;

        companion object {
            fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ASK

            /** The choices a playlist can be downloaded at — all but Ask. */
            val presets get() = entries.filter { it != ASK }
        }
    }

    enum class Codec(val label: String, val blurb: String, val preset: String) {
        COMPAT("Plays everywhere", "H.264 + AAC where offered", "compat"),
        SMALL("Smaller files", "AV1 / VP9 + Opus, about a third smaller", "small"),
        ;

        companion object {
            fun of(name: String?) = entries.firstOrNull { it.name == name } ?: COMPAT
        }
    }

    private companion object {
        const val SHARED = "shared_quality"
        const val CODEC = "codec"
        const val AUDIO_LANG = "audio_lang"
        const val SUB_LANG = "subtitle_lang"
        const val LAST_UPDATE = "ytdlp_checked"
    }
}
