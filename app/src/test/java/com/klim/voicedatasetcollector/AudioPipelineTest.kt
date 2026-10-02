package com.klim.voicedatasetcollector

import com.klim.voicedatasetcollector.audio.AudioQuality
import com.klim.voicedatasetcollector.audio.HighPass
import com.klim.voicedatasetcollector.audio.NoiseEstimator
import com.klim.voicedatasetcollector.audio.SpectralDenoiser
import com.klim.voicedatasetcollector.audio.WavEnhancer
import com.klim.voicedatasetcollector.recording.AudioConfig
import com.klim.voicedatasetcollector.recording.SegmentRecorder
import com.klim.voicedatasetcollector.recording.WavFileWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

class AudioPipelineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun pcm(file: File): ShortArray {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 8, b.getInt(4))
        assertEquals(bytes.size - 44, b.getInt(40))
        return ShortArray((bytes.size - 44) / 2) { b.getShort(44 + it * 2) }
    }
    private fun wav(name: String, samples: ShortArray): File {
        val w = WavFileWriter(temp.root, name, 16_000); w.write(samples)
        return w.closeAndFinalize()
    }

    @Test fun stftReconstructsSignalWithoutLatencyOrBoundaryLoss() {
        val d = SpectralDenoiser(null)
        val random = Random(13)
        val input = DoubleArray(256 * 16) { random.nextDouble() - 0.5 }
        val output = mutableListOf<Double>()
        for (i in input.indices step 256) {
            val result = d.process(input.copyOfRange(i, i + 256))
            if (i > 0) output.addAll(result.toList())
        }
        output.addAll(d.process(DoubleArray(256)).toList())
        assertArrayEquals(input, output.toDoubleArray(), 1e-10)
    }

    @Test fun highPassRejectsDcAndPreservesVoiceBand() {
        val dc = HighPass()
        repeat(16_000) { dc.process(0.2) }
        assertEquals(0.0, dc.process(0.2), 1e-9)
        val hp = HighPass()
        var energy = 0.0
        repeat(16_000) { i -> val y = hp.process(0.1 * sin(2 * PI * 1000 * i / 16_000)); if (i > 8000) energy += y * y }
        assertTrue(sqrt(energy / 7999) > 0.069)
    }

    @Test fun profileDoesNotLearnSpeech() {
        val noise = NoiseEstimator()
        repeat(200) { noise.observe(ShortArray(512) { 2000 }, 0.8f) }
        assertNull(noise.snapshot())
        repeat(30) { noise.observe(ShortArray(512), 0.0f) }
        assertNotNull(noise.snapshot())
    }

    @Test fun steadyNoiseIsReducedWithBoundedAttenuation() {
        val rng = Random(42)
        val estimator = NoiseEstimator()
        repeat(200) { estimator.observe(ShortArray(512) { (rng.nextGaussian() * 900).toInt().toShort() }, 0.0f) }
        val d = SpectralDenoiser(estimator.snapshot())
        var before = 0.0; var after = 0.0
        repeat(300) { frame ->
            val input = DoubleArray(256) { rng.nextGaussian() * 900 / 32768 }
            val out = d.process(input)
            if (frame > 20) { before += input.sumOf { it * it }; after += out.sumOf { it * it } }
        }
        val ratio = sqrt(after / before)
        assertTrue("Noise RMS ratio $ratio", ratio in 0.25..0.85)
        println("Stationary noise RMS ratio: $ratio")
    }

    @Test fun enhancementPreservesEverySampleCountAndOriginalBytes() {
        for (size in listOf(1, 255, 256, 257, 511, 512, 513, 16001)) {
            val source = wav("length_$size", ShortArray(size) { (9000 * sin(2 * PI * it / 16)).toInt().toShort() })
            val original = source.readBytes()
            val result = WavEnhancer.enhance(source, null, false)
            assertEquals(size.toLong(), result.samples)
            assertEquals(size, pcm(result.file).size)
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun normalizationHasCeilingAndBoostLimit() {
        val source = wav("quiet", ShortArray(16000) { (1000 * sin(2 * PI * it / 16)).toInt().toShort() })
        val result = WavEnhancer.enhance(source, null, true)
        assertTrue(result.gain <= 1.995263)
        assertTrue(pcm(result.file).maxOf { abs(it.toInt()) } < 2500)
        val loud = wav("loud", ShortArray(16000) { if (it % 16 < 8) 32767 else -32768 })
        assertTrue(pcm(WavEnhancer.enhance(loud, null, true).file).maxOf { abs(it.toInt()) } <= 29205)
    }

    @Test fun digitalSilenceStaysSilent() {
        val result = WavEnhancer.enhance(wav("zero", ShortArray(16000)), DoubleArray(512), true)
        assertEquals(1.0, result.gain, 0.0)
        assertTrue(pcm(result.file).all { it == 0.toShort() })
    }

    @Test fun invalidWavDoesNotModifyInput() {
        val file = File(temp.root, "bad.wav").apply { writeText("invalid audio") }
        val before = file.readBytes()
        assertThrows(Exception::class.java) { WavEnhancer.enhance(file, null, false) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun writerRefusesToOverwriteExistingRecordings() {
        val first = wav("same", shortArrayOf(1, 2, -32768))
        val before = first.readBytes()
        assertThrows(IllegalStateException::class.java) { WavFileWriter(temp.root, "same", 16000) }
        assertArrayEquals(before, first.readBytes())
    }

    @Test fun interruptedRawCanBeRecoveredAndRecoveryIsIdempotent() {
        val samples = shortArrayOf(-32768, 32767, 25, 0)
        val writer = WavFileWriter(temp.root, "interrupted", 16000)
        writer.write(samples); writer.abort()
        val part = File(temp.root, "interrupted.wav.part")
        val recovered = WavFileWriter.recoverPart(part)!!
        assertArrayEquals(samples, pcm(recovered))
        assertNull(WavFileWriter.recoverPart(part))
    }

    @Test fun qualityFlagsClippingDcAndSilenceWithoutNan() {
        val clipped = AudioQuality().apply { add(ShortArray(1000) { 32767 }) }.snapshot()
        assertTrue("clipping" in clipped.warnings && "dc_offset" in clipped.warnings)
        val silent = AudioQuality().apply { add(ShortArray(1000)) }.snapshot()
        assertTrue("low_level" in silent.warnings)
        assertTrue(silent.rmsDbfs.isFinite())
    }

    @Test fun elevenMinuteConversationSplitsAtTenMinutesWithoutMissingOrDuplicatedPcm() {
        val saved = mutableListOf<SegmentRecorder.Completed>()
        val recorder = SegmentRecorder({ _, suffix -> WavFileWriter(temp.root, suffix, 16000) }, saved::add)
        val frames = (11 * 60 * 16000) / 512
        for (f in 0 until frames) {
            recorder.accept(ShortArray(512) { ((f * 512 + it) % 60001 - 30000).toShort() }, true, f * 32L, 5000)
        }
        recorder.finish()
        assertEquals(2, saved.size)
        assertEquals(9_600_000L, saved[0].samples)
        assertEquals("max_duration", saved[0].endReason)
        assertEquals(saved[0].conversationId, saved[1].conversationId)
        assertEquals(2, saved[1].partIndex)
        assertEquals(9_600_000L, saved[1].offsetSamples)
        var position = 0
        for (part in saved) for (sample in pcm(part.file)) {
            assertEquals(((position % 60001) - 30000).toShort(), sample); position++
        }
        assertEquals(frames * 512, position)
    }

    @Test fun stoppingExactlyAtLimitDoesNotCreateEmptyPart() {
        val saved = mutableListOf<SegmentRecorder.Completed>()
        val recorder = SegmentRecorder({ _, name -> WavFileWriter(temp.root, name, 16000) }, saved::add, 1024, 1)
        repeat(2) { recorder.accept(ShortArray(512), true, it * 32L, 5000) }
        recorder.finish()
        assertEquals(1, saved.size)
    }

    @Test fun silenceCounterSurvivesDurationBoundary() {
        val saved = mutableListOf<SegmentRecorder.Completed>()
        val recorder = SegmentRecorder({ _, name -> WavFileWriter(temp.root, name, 16000) }, saved::add, 1024, 1)
        recorder.accept(ShortArray(512), true, 0, 64)
        recorder.accept(ShortArray(512), false, 32, 64)
        recorder.accept(ShortArray(512), false, 64, 64)
        assertEquals(2, saved.size)
        assertEquals("silence", saved.last().endReason)
        assertEquals(512L, saved.last().samples)
    }

    @Test fun prerollIsIncludedOnceAndNewSpeechHasNewConversation() {
        val saved = mutableListOf<SegmentRecorder.Completed>()
        val recorder = SegmentRecorder({ _, name -> WavFileWriter(temp.root, name, 16000) }, saved::add, 4096, 2)
        recorder.accept(ShortArray(512) { 1 }, false, 0, 32)
        recorder.accept(ShortArray(512) { 2 }, true, 32, 32)
        recorder.accept(ShortArray(512) { 3 }, false, 64, 32)
        recorder.accept(ShortArray(512) { 4 }, true, 96, 32)
        recorder.finish()
        assertArrayEquals(ShortArray(1536) { (it / 512 + 1).toShort() }, pcm(saved[0].file))
        assertEquals(0L, saved[0].startedAtMs)
        assertNotEquals(saved[0].conversationId, saved[1].conversationId)
    }
}
