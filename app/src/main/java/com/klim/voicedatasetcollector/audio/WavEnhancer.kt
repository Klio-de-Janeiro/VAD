package com.klim.voicedatasetcollector.audio

import com.klim.voicedatasetcollector.recording.AudioConfig
import com.klim.voicedatasetcollector.recording.WavFileWriter
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Offline CPU enhancement of collector WAVs. Bounded memory, original never modified. */
object WavEnhancer {
    data class Result(val file: File, val samples: Long, val gain: Double, val denoised: Boolean)

    fun enhance(input: File, noisePower: DoubleArray?, normalize: Boolean): Result {
        val writer = WavFileWriter(File(input.parentFile, "clean"), input.nameWithoutExtension, AudioConfig.SAMPLE_RATE)
        try {
            var total = 0L
            RandomAccessFile(input, "r").use { source ->
                val header = ByteArray(44); source.readFully(header)
                fun le(at: Int) = (0..3).fold(0) { v, i -> v or ((header[at + i].toInt() and 255) shl (8 * i)) }
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                    String(header, 36, 4, Charsets.US_ASCII) == "data" &&
                    le(16) == 16 && le(20) == 65537 && le(24) == AudioConfig.SAMPLE_RATE &&
                    le(28) == 32000 && le(32) == 1048578 &&
                    le(40).toLong() == source.length() - 44 && le(40) % 2 == 0) {
                    "Expected collector WAV: PCM16 mono 16 kHz with canonical header"
                }
                val filter = HighPass()
                val denoiser = SpectralDenoiser(noisePower)
                val bytes = ByteArray(denoiser.hop * 2)
                var pending = 0
                var remaining = source.length() - 44
                fun write(values: DoubleArray, length: Int) {
                    writer.write(ShortArray(length) { (values[it] * 32768).roundToInt().coerceIn(-32768, 32767).toShort() })
                    total += length
                }
                while (remaining > 0) {
                    val count = minOf(bytes.size.toLong(), remaining).toInt()
                    source.readFully(bytes, 0, count)
                    val next = DoubleArray(denoiser.hop) { i ->
                        if (i < count / 2) {
                            val sample = ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                            filter.process(sample / 32768.0)
                        } else 0.0
                    }
                    val out = denoiser.process(next)
                    if (pending > 0) write(out, pending)
                    pending = count / 2; remaining -= count
                }
                if (pending > 0) write(denoiser.process(DoubleArray(denoiser.hop)), pending)
            }
            val gain = if (normalize) writer.normalizePeak() else 1.0
            return Result(writer.closeAndFinalize(), total, gain, noisePower != null)
        } catch (e: Exception) {
            writer.abort()
            throw e
        }
    }
}
