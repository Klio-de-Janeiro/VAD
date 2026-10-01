package com.klim.voicedatasetcollector.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.enaium.silero.vad.SileroVad
import cn.enaium.silero.vad.config.SampleRate
import com.klim.voicedatasetcollector.MainActivity
import com.klim.voicedatasetcollector.R
import kotlin.math.max

class RecordingService : Service() {

    private var worker: Thread? = null
    @Volatile private var stopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var endSilenceMs = RecordingSettings.DEFAULT_END_SILENCE_MS

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecorder()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SETTINGS -> {
                val requested = intent.getIntExtra(
                    EXTRA_END_SILENCE_MS,
                    RecordingSettings.getEndSilenceMs(this)
                )
                endSilenceMs = if (requested in RecordingSettings.allowedSilenceMs) {
                    requested
                } else {
                    RecordingSettings.DEFAULT_END_SILENCE_MS
                }
                if (worker?.isAlive == true) {
                    refreshNotification()
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startRecorder()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested = true
        worker?.interrupt()
        worker = null
        releaseWakeLock()
        markActive(this, false)
        super.onDestroy()
    }

    private fun startRecorder() {
        if (worker?.isAlive == true) return

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission is missing")
            markActive(this, false)
            stopSelf()
            return
        }

        endSilenceMs = RecordingSettings.getEndSilenceMs(this)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )

        acquireWakeLock()
        stopRequested = false
        markActive(this, true)

        worker = Thread({ recordingLoop() }, "voice-dataset-recorder").also { it.start() }
    }

    private fun stopRecorder() {
        stopRequested = true
        worker?.interrupt()
        markActive(this, false)

        // Do not destroy the Service before the recorder thread has finalized
        // the current WAV header. The worker's finally block stops the service.
        if (worker?.isAlive != true) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun recordingLoop() {
        var audioRecord: AudioRecord? = null
        var vad: SileroVad? = null
        var writer: WavFileWriter? = null

        var segmentSamples = 0L
        var segmentStartMs = 0L
        var silenceMs = 0

        val ring = PcmRingBuffer(AudioConfig.PRE_BUFFER_FRAMES)

        try {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBufferBytes > 0) { "Invalid AudioRecord min buffer: $minBufferBytes" }

            val bufferBytes = max(minBufferBytes, AudioConfig.FRAME_SIZE * 2 * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord initialization failed"
            }

            vad = SileroVad(
                applicationContext,
                SampleRate.SAMPLE_RATE_16K,
                AudioConfig.VAD_MIN_SPEECH_MS,
                AudioConfig.VAD_INTERNAL_MIN_SILENCE_MS,
                AudioConfig.VAD_THRESHOLD
            )
            vad.reset()

            val frame = ShortArray(AudioConfig.FRAME_SIZE)
            audioRecord.startRecording()
            Log.i(TAG, "Recording loop started")

            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                if (!readExactly(audioRecord, frame)) break

                val speech = vad.isSpeech(frame)

                if (writer == null) {
                    ring.push(frame)

                    if (speech) {
                        val preRoll = ring.snapshot()
                        val preSamples = preRoll.sumOf { it.size }
                        segmentStartMs = System.currentTimeMillis() -
                            (preSamples * 1000L / AudioConfig.SAMPLE_RATE)

                        val directory = RecordingPaths.dayDirectory(this, segmentStartMs)
                        writer = WavFileWriter(
                            directory,
                            RecordingPaths.baseName(segmentStartMs),
                            AudioConfig.SAMPLE_RATE
                        )

                        preRoll.forEach { writer.write(it) }
                        segmentSamples = preSamples.toLong()
                        silenceMs = 0
                        ring.clear()

                        Log.i(TAG, "Speech started: ${writer.finalFile.name}")
                    }

                    continue
                }

                writer.write(frame)
                segmentSamples += frame.size

                if (speech) {
                    silenceMs = 0
                } else {
                    silenceMs += AudioConfig.FRAME_MS
                }

                val reachedSilence = silenceMs >= endSilenceMs
                val reachedMaxLength = segmentSamples >= AudioConfig.MAX_SEGMENT_SAMPLES

                if (reachedSilence || reachedMaxLength) {
                    val completed = writer.closeAndFinalize()
                    val durationMs = segmentSamples * 1000L / AudioConfig.SAMPLE_RATE
                    MetadataStore.append(completed, segmentStartMs, durationMs)

                    Log.i(TAG, "Saved ${completed.absolutePath}, duration=${durationMs}ms")

                    writer = null
                    segmentSamples = 0
                    segmentStartMs = 0
                    silenceMs = 0
                    ring.clear()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Recorder failed", t)
        } finally {
            runCatching {
                if (writer != null) {
                    val completed = writer.closeAndFinalize()
                    val durationMs = segmentSamples * 1000L / AudioConfig.SAMPLE_RATE
                    if (segmentSamples > 0) {
                        MetadataStore.append(completed, segmentStartMs, durationMs)
                    }
                }
            }.onFailure {
                Log.e(TAG, "Could not finalize last WAV", it)
                runCatching { writer?.abort() }
            }

            runCatching {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            runCatching { audioRecord?.release() }
            runCatching { vad?.close() }

            worker = null
            releaseWakeLock()
            markActive(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "Recording loop stopped")
        }
    }

    private fun readExactly(audioRecord: AudioRecord, target: ShortArray): Boolean {
        var offset = 0
        while (offset < target.size && !stopRequested) {
            val count = audioRecord.read(
                target,
                offset,
                target.size - offset,
                AudioRecord.READ_BLOCKING
            )
            if (count <= 0) {
                Log.e(TAG, "AudioRecord.read returned $count")
                return false
            }
            offset += count
        }
        return offset == target.size
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Voice Dataset Collector")
        .setContentText("Listening • silence cutoff ${endSilenceMs / 1000}s • screen may be off")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            0,
            "STOP",
            PendingIntent.getService(
                this,
                1,
                Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()


    private fun refreshNotification() {
        if (worker?.isAlive != true) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the microphone collector is active"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:VoiceDatasetRecorder"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock?.isHeld == true) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.klim.voicedatasetcollector.START"
        const val ACTION_STOP = "com.klim.voicedatasetcollector.STOP"
        const val ACTION_UPDATE_SETTINGS = "com.klim.voicedatasetcollector.UPDATE_SETTINGS"
        const val EXTRA_END_SILENCE_MS = "end_silence_ms"

        private const val TAG = "VoiceRecorderService"
        private const val CHANNEL_ID = "voice_dataset_recording"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS = "voice_dataset_collector"
        private const val KEY_ACTIVE = "active"

        fun isMarkedActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false)

        fun markActive(context: Context, active: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, active)
                .apply()
        }
    }
}
