package com.klim.voicedatasetcollector.recording

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DatasetExporter {
    fun exportZip(context: Context, destination: Uri) {
        val root = RecordingPaths.recordingsRoot(context)
        val output = context.contentResolver.openOutputStream(destination)
            ?: error("Could not open export destination")

        output.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                root.walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".part") }
                    .forEach { file ->
                        val relative = file.relativeTo(root).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().buffered().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
        }
    }
}
