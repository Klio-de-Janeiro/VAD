package com.klim.voicedatasetcollector.recording

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MetadataStore {
    private val lock = Any()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun append(
        audioFile: File,
        startedAtMs: Long,
        durationMs: Long,
    ) {
        val metadata = File(audioFile.parentFile, "metadata.jsonl")
        val json = JSONObject()
            .put("file", audioFile.name)
            .put("start", synchronized(isoFormat) { isoFormat.format(Date(startedAtMs)) })
            .put("duration_ms", durationMs)
            .put("sample_rate", AudioConfig.SAMPLE_RATE)
            .put("channels", 1)
            .put("format", "PCM16_WAV")

        synchronized(lock) {
            FileOutputStream(metadata, true).bufferedWriter().use { writer ->
                writer.append(json.toString())
                writer.newLine()
            }
        }
    }
}
