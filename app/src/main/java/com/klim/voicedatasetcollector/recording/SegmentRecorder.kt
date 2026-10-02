package com.klim.voicedatasetcollector.recording

import com.klim.voicedatasetcollector.audio.AudioQuality
import java.io.File
import java.util.UUID

/** Owns VAD segmentation on the capture thread; a duration split never waits for a new speech onset. */
class SegmentRecorder(
    private val createWriter: (Long, String) -> WavFileWriter,
    private val onCompleted: (Completed) -> Unit,
    private val maxSamples: Long = AudioConfig.MAX_SEGMENT_SAMPLES.toLong(),
    private val preBufferFrames: Int = AudioConfig.PRE_BUFFER_FRAMES
) {
    data class Completed(val file: File, val startedAtMs: Long, val samples: Long,
                         val conversationId: String, val partIndex: Int, val offsetSamples: Long,
                         val endReason: String, val quality: AudioQuality.Snapshot)

    private val ring = PcmRingBuffer(preBufferFrames)
    private var writer: WavFileWriter? = null
    private var quality = AudioQuality()
    private var segmentStartMs = 0L
    private var samples = 0L
    private var silenceSamples = 0L
    private var conversationId = ""
    private var partIndex = 0
    private var offsetSamples = 0L
    private var continuation = false

    init {
        require(preBufferFrames > 0)
        require(maxSamples >= preBufferFrames.toLong() * AudioConfig.FRAME_SIZE)
        require(maxSamples % AudioConfig.FRAME_SIZE == 0L)
    }

    fun accept(frame: ShortArray, speech: Boolean, frameStartMs: Long, endSilenceMs: Int) {
        require(frame.size == AudioConfig.FRAME_SIZE && endSilenceMs > 0)
        if (writer == null && !continuation) {
            ring.push(frame)
            if (!speech) return
            conversationId = UUID.randomUUID().toString()
            partIndex = 1; offsetSamples = 0; silenceSamples = 0
            val preRoll = ring.snapshot()
            open(frameStartMs - (preRoll.sumOf { it.size } - frame.size) * 1000L / AudioConfig.SAMPLE_RATE)
            preRoll.forEach(::write)
            ring.clear()
        } else {
            if (writer == null) open(frameStartMs)
            write(frame)
        }
        silenceSamples = if (speech) 0 else silenceSamples + frame.size
        when {
            silenceSamples * 1000 >= endSilenceMs.toLong() * AudioConfig.SAMPLE_RATE -> complete("silence")
            samples >= maxSamples -> complete("max_duration")
        }
    }

    fun finish(reason: String = "user_stop") {
        if (writer != null) complete(reason)
        continuation = false
        ring.clear()
    }

    private fun open(startedAtMs: Long) {
        segmentStartMs = startedAtMs
        writer = createWriter(startedAtMs, "${conversationId.take(8)}_p${partIndex.toString().padStart(3, '0')}")
        quality = AudioQuality(); samples = 0; continuation = false
    }

    private fun write(frame: ShortArray) {
        writer!!.write(frame)
        quality.add(frame)
        samples += frame.size
    }

    private fun complete(reason: String) {
        val current = writer ?: return
        val file = try { current.closeAndFinalize() } catch (e: Exception) {
            current.abort(); writer = null; throw e
        }
        val result = Completed(file, segmentStartMs, samples, conversationId, partIndex,
            offsetSamples, reason, quality.snapshot())
        writer = null
        offsetSamples += samples
        samples = 0
        continuation = reason == "max_duration"
        if (continuation) partIndex++ else { silenceSamples = 0; ring.clear() }
        onCompleted(result)
    }
}
