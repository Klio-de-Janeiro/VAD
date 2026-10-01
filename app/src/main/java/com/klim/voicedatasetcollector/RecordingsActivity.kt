package com.klim.voicedatasetcollector

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.klim.voicedatasetcollector.recording.RecordingPaths
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView

    private lateinit var selectionBar: View
    private lateinit var selectedCountText: TextView
    private lateinit var shareSelectedButton: MaterialButton
    private lateinit var deleteSelectedButton: MaterialButton
    private lateinit var cancelSelectionButton: MaterialButton

    private var files: List<File> = emptyList()
    private val selectedFiles = linkedSetOf<File>()
    private var selectionMode = false
    private var adapter: RecordingAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        listView = findViewById(R.id.recordingsList)
        emptyText = findViewById(R.id.emptyText)

        selectionBar = findViewById(R.id.selectionBar)
        selectedCountText = findViewById(R.id.selectedCountText)
        shareSelectedButton = findViewById(R.id.shareSelectedButton)
        deleteSelectedButton = findViewById(R.id.deleteSelectedButton)
        cancelSelectionButton = findViewById(R.id.cancelSelectionButton)

        val backButton: MaterialButton = findViewById(R.id.backButton)

        backButton.setOnClickListener {
            if (selectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        shareSelectedButton.setOnClickListener {
            shareFiles(selectedFiles.toList())
        }

        deleteSelectedButton.setOnClickListener {
            confirmDelete(selectedFiles.toList())
        }

        cancelSelectionButton.setOnClickListener {
            exitSelectionMode()
        }

        loadRecordings()
    }

    private fun loadRecordings() {
        files = RecordingPaths.recordingsRoot(this)
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .toList()

        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE

        adapter = RecordingAdapter(files)
        listView.adapter = adapter

        updateSelectionUi()
    }

    private fun enterSelectionMode(file: File) {
        selectionMode = true
        selectedFiles.add(file)
        updateSelectionUi()
        adapter?.notifyDataSetChanged()
    }

    private fun toggleSelection(file: File) {
        if (!selectionMode) {
            selectionMode = true
        }

        if (!selectedFiles.add(file)) {
            selectedFiles.remove(file)
        }

        if (selectedFiles.isEmpty()) {
            selectionMode = false
        }

        updateSelectionUi()
        adapter?.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedFiles.clear()
        updateSelectionUi()
        adapter?.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        selectionBar.visibility =
            if (selectionMode && selectedFiles.isNotEmpty()) View.VISIBLE else View.GONE

        selectedCountText.text = "Selected: ${selectedFiles.size}"

        val hasSelection = selectedFiles.isNotEmpty()
        shareSelectedButton.isEnabled = hasSelection
        deleteSelectedButton.isEnabled = hasSelection
    }

    private fun openAudio(file: File) {
        val uri = fileUri(file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/wav")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_audio_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareFiles(filesToShare: List<File>) {
        val existing = filesToShare.filter { it.exists() }
        if (existing.isEmpty()) {
            Toast.makeText(this, "No files to share", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList(existing.map(::fileUri))

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newUri(
                    contentResolver,
                    "Voice Dataset WAV",
                    uris.first()
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val clip = ClipData.newUri(
                contentResolver,
                "Voice Dataset WAV files",
                uris.first()
            )
            uris.drop(1).forEach { uri ->
                clip.addItem(ClipData.Item(uri))
            }

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/wav"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        try {
            startActivity(
                Intent.createChooser(
                    intent,
                    if (uris.size == 1) "Share WAV" else "Share ${uris.size} WAV files"
                )
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No application can receive WAV files", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(filesToDelete: List<File>) {
        val existing = filesToDelete.filter { it.exists() }
        if (existing.isEmpty()) {
            Toast.makeText(this, "No files to delete", Toast.LENGTH_SHORT).show()
            return
        }

        val title = if (existing.size == 1) {
            "Delete recording?"
        } else {
            "Delete ${existing.size} recordings?"
        }

        val message = if (existing.size == 1) {
            "${existing.first().name}\n\nThe WAV file will be permanently deleted. Its entry will also be removed from metadata.jsonl."
        } else {
            "The selected WAV files will be permanently deleted. Their entries will also be removed from metadata.jsonl."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE") { _, _ ->
                deleteFilesAndCleanMetadata(existing)
            }
            .show()
    }

    private fun deleteFilesAndCleanMetadata(filesToDelete: List<File>) {
        val deletedByDirectory = linkedMapOf<File, MutableSet<String>>()
        var deletedCount = 0
        var failedCount = 0

        filesToDelete.forEach { file ->
            val parent = file.parentFile

            if (file.delete()) {
                deletedCount++

                if (parent != null) {
                    deletedByDirectory
                        .getOrPut(parent) { linkedSetOf() }
                        .add(file.name)
                }
            } else {
                failedCount++
            }
        }

        deletedByDirectory.forEach { (directory, deletedNames) ->
            cleanMetadataJsonl(directory, deletedNames)
        }

        exitSelectionMode()
        loadRecordings()

        val message = buildString {
            append("Deleted: $deletedCount")
            if (failedCount > 0) {
                append(" · Failed: $failedCount")
            }
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun cleanMetadataJsonl(
        directory: File,
        deletedFileNames: Set<String>
    ) {
        val metadataFile = File(directory, "metadata.jsonl")
        if (!metadataFile.exists()) return

        val keptLines = metadataFile
            .readLines()
            .filter { line ->
                if (line.isBlank()) {
                    false
                } else {
                    try {
                        val json = JSONObject(line)
                        json.optString("file") !in deletedFileNames
                    } catch (_: Exception) {
                        // Do not destroy malformed/unknown metadata lines.
                        true
                    }
                }
            }

        metadataFile.writeText(
            if (keptLines.isEmpty()) {
                ""
            } else {
                keptLines.joinToString(
                    separator = "\n",
                    postfix = "\n"
                )
            }
        )
    }

    private fun fileUri(file: File): Uri =
        FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )

    private inner class RecordingAdapter(
        private val items: List<File>
    ) : ArrayAdapter<File>(
        this@RecordingsActivity,
        R.layout.item_recording,
        items
    ) {

        private val dateFormat =
            SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", Locale.getDefault())

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_recording, parent, false)

            val file = items[position]

            val indicator: View = view.findViewById(R.id.selectionIndicator)
            val name: TextView = view.findViewById(R.id.recordingName)
            val meta: TextView = view.findViewById(R.id.recordingMeta)
            val shareButton: MaterialButton = view.findViewById(R.id.shareButton)
            val deleteButton: MaterialButton = view.findViewById(R.id.deleteButton)

            name.text = file.name
            meta.text = getString(
                R.string.recording_meta,
                dateFormat.format(Date(file.lastModified())),
                formatSize(file.length())
            )

            val isSelected = selectedFiles.contains(file)

            indicator.setBackgroundResource(
                if (isSelected) {
                    R.drawable.selection_circle_selected
                } else {
                    R.drawable.selection_circle_unselected
                }
            )

            indicator.contentDescription =
                if (isSelected) "Selected" else "Not selected"

            indicator.setOnClickListener {
                toggleSelection(file)
            }

            view.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(file)
                } else {
                    openAudio(file)
                }
            }

            view.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode(file)
                } else {
                    toggleSelection(file)
                }
                true
            }

            shareButton.setOnClickListener {
                shareFiles(listOf(file))
            }

            deleteButton.setOnClickListener {
                confirmDelete(listOf(file))
            }

            return view
        }

        private fun formatSize(bytes: Long): String =
            if (bytes < 1024L * 1024L) {
                String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            }
    }
}
