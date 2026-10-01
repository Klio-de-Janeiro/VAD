package com.klim.voicedatasetcollector.recording

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingPaths {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fileFormat = SimpleDateFormat("HH-mm-ss_SSS", Locale.US)

    fun recordingsRoot(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(base, "VoiceDatasetCollector/recordings").apply { mkdirs() }
    }

    fun dayDirectory(context: Context, epochMs: Long): File =
        File(recordingsRoot(context), synchronized(dayFormat) { dayFormat.format(Date(epochMs)) })
            .apply { mkdirs() }

    fun baseName(epochMs: Long): String =
        synchronized(fileFormat) { fileFormat.format(Date(epochMs)) }
}
