package com.syed.magpie.data

import android.content.Context
import androidx.core.content.edit

/**
 * The Subtitles module's settings: the user's own Gemini key, and the model
 * and batch size the form was last left on.
 *
 * The key is the user's, pasted in once — Magpie ships none, since anything
 * inside a public APK is public. It stays in this app's private preferences
 * and is sent to Google alone.
 */
class SubtitlePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("subtitles", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY, value.trim()) }

    var model: Gemini.Model
        get() = Gemini.Model.of(prefs.getString(MODEL, null))
        set(value) = prefs.edit { putString(MODEL, value.name) }

    var batchSize: Int
        get() = prefs.getInt(BATCH, Subtitles.DEFAULT_BATCH)
        set(value) = prefs.edit { putInt(BATCH, value) }

    var hintColor: Subtitles.HintColor
        get() = Subtitles.HintColor.of(prefs.getString(COLOR, null))
        set(value) = prefs.edit { putString(COLOR, value.name) }

    private companion object {
        const val KEY = "api_key"
        const val MODEL = "model"
        const val BATCH = "batch_size"
        const val COLOR = "hint_color"
    }
}
