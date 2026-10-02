package com.klim.voicedatasetcollector.recording

import android.content.Context

object RecordingSettings {
    const val DEFAULT_END_SILENCE_MS = 5_000

    val allowedSilenceMs = setOf(3_000, 5_000, 8_000, 12_000)

    fun getEnhance(context: Context): Boolean = prefs(context).getBoolean("enhance", true)
    fun getNormalize(context: Context): Boolean = prefs(context).getBoolean("normalize", false)
    fun setEnhance(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enhance", value).apply() }
    fun setNormalize(context: Context, value: Boolean) { prefs(context).edit().putBoolean("normalize", value).apply() }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "voice_dataset_settings"
    private const val KEY_END_SILENCE_MS = "end_silence_ms"

    fun getEndSilenceMs(context: Context): Int {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_END_SILENCE_MS, DEFAULT_END_SILENCE_MS)
        return if (value in allowedSilenceMs) value else DEFAULT_END_SILENCE_MS
    }

    fun setEndSilenceMs(context: Context, value: Int) {
        require(value in allowedSilenceMs) { "Unsupported silence timeout: $value" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_END_SILENCE_MS, value)
            .apply()
    }
}
