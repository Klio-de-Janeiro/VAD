package com.klim.voicedatasetcollector

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object RecordingActions {

    fun shareWav(
        context: Context,
        file: File
    ) {
        require(file.exists()) {
            "File does not exist: ${file.absolutePath}"
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"

            putExtra(
                Intent.EXTRA_STREAM,
                uri
            )

            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        context.startActivity(
            Intent.createChooser(
                intent,
                "Export WAV"
            )
        )
    }

    fun deleteWav(file: File): Boolean {
        if (!file.exists()) {
            return false
        }

        return file.delete()
    }
}