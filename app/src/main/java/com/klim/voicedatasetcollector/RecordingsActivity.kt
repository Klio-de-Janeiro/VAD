package com.klim.voicedatasetcollector

import android.content.ActivityNotFoundException
import android.content.Intent
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
import com.klim.voicedatasetcollector.recording.RecordingPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        listView = findViewById(R.id.recordingsList)
        emptyText = findViewById(R.id.emptyText)
        val backButton: MaterialButton = findViewById(R.id.backButton)

        backButton.setOnClickListener { finish() }
        loadRecordings()
    }

    private fun loadRecordings() {
        val files = RecordingPaths.recordingsRoot(this)
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .toList()

        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        listView.adapter = RecordingAdapter(files)

        listView.setOnItemClickListener { _, _, position, _ ->
            openAudio(files[position])
        }
    }

    private fun openAudio(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )

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

    private inner class RecordingAdapter(
        private val files: List<File>
    ) : ArrayAdapter<File>(this, R.layout.item_recording, files) {

        private val dateFormat = SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", Locale.getDefault())

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_recording, parent, false)

            val file = files[position]
            view.findViewById<TextView>(R.id.recordingName).text = file.name
            view.findViewById<TextView>(R.id.recordingMeta).text = getString(
                R.string.recording_meta,
                dateFormat.format(Date(file.lastModified())),
                formatSize(file.length())
            )
            return view
        }

        private fun formatSize(bytes: Long): String =
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
