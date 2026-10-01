package com.klim.voicedatasetcollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.klim.voicedatasetcollector.recording.DatasetExporter
import com.klim.voicedatasetcollector.recording.DatasetStats
import com.klim.voicedatasetcollector.recording.RecordingPaths
import com.klim.voicedatasetcollector.recording.RecordingService
import com.klim.voicedatasetcollector.recording.RecordingSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var silenceSummary: TextView
    private lateinit var silenceGroup: MaterialButtonToggleGroup
    private lateinit var audioDurationText: TextView
    private lateinit var fileCountText: TextView
    private lateinit var storageText: TextView
    private lateinit var exportButton: MaterialButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasMicrophonePermission()) {
            startRecordingService()
        } else {
            statusText.text = getString(R.string.status_mic_denied)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) exportDataset(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        silenceSummary = findViewById(R.id.silenceSummary)
        silenceGroup = findViewById(R.id.silenceGroup)
        audioDurationText = findViewById(R.id.audioDurationText)
        fileCountText = findViewById(R.id.fileCountText)
        storageText = findViewById(R.id.storageText)
        exportButton = findViewById(R.id.exportButton)

        val infoText: TextView = findViewById(R.id.infoText)
        val pathText: TextView = findViewById(R.id.pathText)
        val appSettingsButton: MaterialButton = findViewById(R.id.appSettingsButton)
        val recordingsButton: MaterialButton = findViewById(R.id.recordingsButton)

        infoText.text = getString(R.string.audio_config_summary)
        pathText.text = getString(
            R.string.recordings_path,
            RecordingPaths.recordingsRoot(this).absolutePath
        )

        setupSilenceControls()

        startStopButton.setOnClickListener {
            if (RecordingService.isMarkedActive(this)) {
                stopRecordingService()
            } else {
                requestPermissionsAndStart()
            }
        }

        recordingsButton.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }

        exportButton.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            exportLauncher.launch("VoiceDatasetCollector_$timestamp.zip")
        }

        appSettingsButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        refreshUi()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        refreshStats()
    }

    private fun setupSilenceControls() {
        val selectedMs = RecordingSettings.getEndSilenceMs(this)
        val selectedId = when (selectedMs) {
            3_000 -> R.id.silence3Button
            8_000 -> R.id.silence8Button
            12_000 -> R.id.silence12Button
            else -> R.id.silence5Button
        }
        silenceGroup.check(selectedId)
        updateSilenceSummary(selectedMs)

        silenceGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val ms = when (checkedId) {
                R.id.silence3Button -> 3_000
                R.id.silence8Button -> 8_000
                R.id.silence12Button -> 12_000
                else -> 5_000
            }

            RecordingSettings.setEndSilenceMs(this, ms)
            updateSilenceSummary(ms)

            if (RecordingService.isMarkedActive(this)) {
                startService(
                    Intent(this, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_UPDATE_SETTINGS
                        putExtra(RecordingService.EXTRA_END_SILENCE_MS, ms)
                    }
                )
            }
        }
    }

    private fun updateSilenceSummary(ms: Int) {
        silenceSummary.text = getString(R.string.silence_summary, ms / 1000)
    }

    private fun requestPermissionsAndStart() {
        val missing = mutableListOf<String>()

        if (!hasMicrophonePermission()) missing += Manifest.permission.RECORD_AUDIO

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isEmpty()) {
            startRecordingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        RecordingService.markActive(this, true)
        refreshUi()
    }

    private fun stopRecordingService() {
        startService(
            Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
        )
        RecordingService.markActive(this, false)
        refreshUi()
        refreshStats()
    }

    private fun refreshUi() {
        val active = RecordingService.isMarkedActive(this)
        statusText.text = if (active) getString(R.string.status_active) else getString(R.string.status_stopped)
        startStopButton.text = if (active) getString(R.string.stop) else getString(R.string.start)

        val color = ContextCompat.getColor(
            this,
            if (active) R.color.stop_red else R.color.neon_blue
        )
        startStopButton.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun refreshStats() {
        Thread {
            val stats = DatasetStats.calculate(this)
            runOnUiThread {
                audioDurationText.text = formatDuration(stats.audioDurationMs)
                fileCountText.text = stats.wavFiles.toString()
                storageText.text = formatBytes(stats.wavBytes)
            }
        }.start()
    }

    private fun exportDataset(uri: Uri) {
        exportButton.isEnabled = false
        exportButton.text = getString(R.string.exporting)

        Thread {
            val result = runCatching { DatasetExporter.exportZip(this, uri) }
            runOnUiThread {
                exportButton.isEnabled = true
                exportButton.text = getString(R.string.export_dataset)
                Toast.makeText(
                    this,
                    if (result.isSuccess) R.string.export_done else R.string.export_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1024.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }
}
