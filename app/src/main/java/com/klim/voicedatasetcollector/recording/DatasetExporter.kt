package com.klim.voicedatasetcollector.recording

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DatasetExporter {
    @Volatile var exporting = false
        private set

    @Synchronized
    fun exportZip(context: Context, destination: Uri) {
        check(!RecordingService.isMarkedActive(context)) { "Stop recording before exporting" }
        exporting = true
        try {
            val root = RecordingPaths.recordingsRoot(context)
            val output = context.contentResolver.openOutputStream(destination)
                ?: error("Could not open export destination")
            output.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    synchronized(MetadataStore.lock) {
                        root.walkTopDown()
                            .filter { it.isFile && it.extension !in setOf("part", "bak", "new") }
                            .forEach { file ->
                                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                                zip.closeEntry()
                            }
                    }
                }
            }
        } finally { exporting = false }
    }
}
