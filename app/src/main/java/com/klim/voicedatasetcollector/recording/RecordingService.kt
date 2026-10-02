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
import android.media.AudioManager
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
import com.klim.voicedatasetcollector.audio.NoiseEstimator
import com.klim.voicedatasetcollector.audio.WavEnhancer
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class RecordingService : Service() {
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var recorder: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var endSilenceMs = RecordingSettings.DEFAULT_END_SILENCE_MS
    @Volatile private var splitCount = 0
    private val processingIssues = AtomicInteger(0)

    override fun onCreate() { super.onCreate(); createNotificationChannel() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecorder()
            ACTION_UPDATE_SETTINGS -> {
                endSilenceMs = RecordingSettings.getEndSilenceMs(this)
                if (worker?.isAlive == true) refreshNotification() else stopSelf()
            }
            ACTION_START -> startRecorder()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        runCatching { recorder?.stop() }
        // The worker owns WAV finalization and wake-lock release.
        if (worker?.isAlive != true) { active = false; stopping = false; releaseWakeLock() }
        super.onDestroy()
    }

    private fun startRecorder() {
        if (worker?.isAlive == true) return
        if (DatasetExporter.exporting) {
            statusMessage = "Дождитесь завершения экспорта"; stopSelf(); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusMessage = "Нет разрешения на микрофон"; stopSelf(); return
        }
        try {
            endSilenceMs = RecordingSettings.getEndSilenceMs(this)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            acquireWakeLock()
            stopRequested = false; active = true; stopping = false; splitCount = 0
            processingIssues.set(0)
            statusMessage = "Запуск микрофона…"
            worker = Thread({ recordingLoop() }, "voice-dataset-recorder").also { it.start() }
        } catch (e: Exception) {
            statusMessage = "Не удалось запустить запись: ${e.message}"
            active = false; releaseWakeLock(); stopSelf()
        }
    }

    private fun stopRecorder() {
        stopRequested = true
        stopping = active
        statusMessage = "Сохранение и завершение обработки…"
        runCatching { recorder?.stop() }
        if (worker?.isAlive != true) {
            active = false; stopping = false
            releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun recordingLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        var vad: SileroVad? = null
        var segments: SegmentRecorder? = null
        var failed = false
        val enhance = RecordingSettings.getEnhance(this)
        val normalize = RecordingSettings.getNormalize(this)
        val cleaner = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2)) { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "wav-enhancer")
        }
        val noise = NoiseEstimator()
        try {
            val root = RecordingPaths.recordingsRoot(this)
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".wav.part") && it.parentFile?.name != "clean" }
                .forEach { part -> WavFileWriter.recoverPart(part)?.let(MetadataStore::recovered) }
            check(root.usableSpace >= MIN_FREE_BYTES) { "Мало свободного места (нужно 128 MiB)" }
            val manager = getSystemService(AudioManager::class.java)
            val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            var audioSource = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            recorder = runCatching { createAudioRecord(audioSource) }.getOrElse {
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                createAudioRecord(audioSource)
            }
            val sourceName = if (audioSource == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "VOICE_RECOGNITION"
            vad = SileroVad(applicationContext, SampleRate.SAMPLE_RATE_16K,
                AudioConfig.VAD_MIN_SPEECH_MS, AudioConfig.VAD_INTERNAL_MIN_SILENCE_MS, AudioConfig.VAD_THRESHOLD)
            segments = SegmentRecorder(
                createWriter = { start, suffix -> WavFileWriter(RecordingPaths.dayDirectory(this, start),
                    "${RecordingPaths.baseName(start)}_$suffix", AudioConfig.SAMPLE_RATE) },
                onCompleted = { segment ->
                    MetadataStore.append(segment, sourceName, enhance, noise.observations)
                    if (enhance) submitEnhancement(cleaner, segment, noise.snapshot(), normalize)
                    if (segment.endReason == "max_duration") {
                        splitCount++
                        statusMessage = "Диалог достиг 10 минут: часть ${segment.partIndex} сохранена, запись продолжается"
                        refreshNotification()
                    }
                })
            val frame = ShortArray(AudioConfig.FRAME_SIZE)
            val capture = recorder!!
            capture.startRecording()
            check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон не запустился" }
            statusMessage = "Идёт запись • оригинал${if (enhance) " + очищенная копия" else ""} • части до 10 мин"
            var frames = 0L
            var anchorMs = 0L
            while (!stopRequested) {
                if (!readExactly(capture, frame)) break
                if (frames == 0L) anchorMs = System.currentTimeMillis() - AudioConfig.FRAME_MS
                val speech = vad.isSpeech(frame)
                if (enhance) noise.observe(frame, vad.lastProbability)
                segments.accept(frame, speech, anchorMs + frames * AudioConfig.FRAME_MS, endSilenceMs)
                frames++
                if (frames % 9_375 == 0L) wakeLock?.acquire(15 * 60_000L)
                if (frames % 156 == 0L) check(root.usableSpace >= MIN_FREE_BYTES) { "Запись остановлена: мало свободного места" }
            }
        } catch (e: Exception) {
            failed = true
            statusMessage = "Ошибка записи: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, statusMessage, e)
        } finally {
            runCatching { segments?.finish(if (failed) "error" else "user_stop") }.onFailure {
                failed = true; statusMessage = "Ошибка сохранения: ${it.message}. Исходные .part сохранены."
                Log.e(TAG, statusMessage, it)
            }
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
            runCatching { vad?.close() }
            stopping = true
            cleaner.shutdown()
            // Keep the foreground service alive while the last already-saved WAV is enhanced.
            while (!cleaner.isTerminated) {
                try { cleaner.awaitTermination(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            if (!failed) statusMessage = if (processingIssues.get() == 0) "Остановлено • файлы сохранены"
                else "Оригиналы сохранены • проблем с очисткой: ${processingIssues.get()} (см. список записей)"
            worker = null; active = false; stopping = false
            releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun submitEnhancement(executor: ThreadPoolExecutor, segment: SegmentRecorder.Completed,
                                  noise: DoubleArray?, normalize: Boolean) {
        try {
            executor.execute {
                val state = try {
                    val result = WavEnhancer.enhance(segment.file, noise, normalize)
                    JSONObject().put("status", "complete").put("file", "clean/${result.file.name}")
                        .put("algorithm", "highpass70_stft_wiener_v1").put("noise_profile_available", result.denoised)
                        .put("normalization_gain", result.gain).put("samples", result.samples)
                } catch (e: Exception) {
                    processingIssues.incrementAndGet()
                    Log.e(TAG, "Enhancement failed; raw preserved", e)
                    JSONObject().put("status", "failed").put("error", e.message)
                }
                runCatching { MetadataStore.updateProcessing(segment.file, state) }
                    .onFailure { Log.e(TAG, "Could not update processing metadata", it) }
            }
        } catch (_: RejectedExecutionException) {
            processingIssues.incrementAndGet()
            MetadataStore.updateProcessing(segment.file, JSONObject().put("status", "skipped_busy"))
        }
    }

    private fun createAudioRecord(source: Int): AudioRecord {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Разрешение на микрофон отозвано")
        }
        val minBytes = AudioRecord.getMinBufferSize(AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "Устройство не поддерживает запись PCM16 mono 16 kHz" }
        val audio = AudioRecord(source, AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBytes, AudioConfig.SAMPLE_RATE * 2))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("AudioRecord initialization failed") }
        return audio
    }

    private fun readExactly(audio: AudioRecord, target: ShortArray): Boolean {
        var offset = 0
        while (offset < target.size && !stopRequested) {
            val count = audio.read(target, offset, target.size - offset, AudioRecord.READ_BLOCKING)
            if (count <= 0) {
                if (stopRequested) return false
                error("AudioRecord.read: $count; проверьте доступ к микрофону")
            }
            offset += count
        }
        return offset == target.size
    }
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Voice Dataset Collector")
        .setContentText("Запись • тишина ${endSilenceMs / 1000}с • лимит 10 мин • разбиений $splitCount")
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
            acquire(15 * 60_000L)
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
        private const val MIN_FREE_BYTES = 128L * 1024 * 1024
        @Volatile private var active = false
        @Volatile var stopping = false
            private set
        @Volatile var statusMessage = "Остановлено"
            private set
        fun isMarkedActive(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = active
    }
}
