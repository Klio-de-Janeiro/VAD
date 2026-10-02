package com.klim.voicedatasetcollector.recording

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Writes PCM16 mono WAV through a .part file. Never replaces an existing recording. */
class WavFileWriter(directory: File, baseName: String, private val sampleRate: Int) {
    private val partFile = File(directory, "$baseName.wav.part")
    val finalFile = File(directory, "$baseName.wav")
    private val raf: RandomAccessFile
    private var dataBytes = 0L
    private var closed = false
    private var finalized = false
    private var peak = 0

    init {
        require(sampleRate > 0)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        check(!finalFile.exists() && partFile.createNewFile()) { "Recording already exists: $baseName" }
        raf = RandomAccessFile(partFile, "rw")
        try { writeHeader(raf, sampleRate, 0) } catch (e: Exception) { raf.close(); throw e }
    }

    @Synchronized
    fun write(samples: ShortArray) {
        check(!closed)
        require(dataBytes + samples.size * 2L <= 0xffffffffL - 36L) { "WAV exceeds RIFF limit" }
        val buffer = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = samples[i].toInt()
            peak = maxOf(peak, abs(value))
            buffer[2 * i] = value.toByte()
            buffer[2 * i + 1] = (value shr 8).toByte()
        }
        raf.write(buffer)
        dataBytes += buffer.size
    }

    /** Two-pass peak normalization to -1 dBFS, gain capped at +6 dB; no silent-signal boost. */
    @Synchronized
    fun normalizePeak(): Double {
        check(!closed)
        val gain = if (peak < 104) 1.0 else min(1.995262, 29204.0 / peak)
        if (gain == 1.0) return gain
        val buffer = ByteArray(8192)
        raf.seek(44)
        var remaining = dataBytes
        while (remaining > 0) {
            val size = minOf(buffer.size.toLong(), remaining).toInt()
            val position = raf.filePointer
            raf.readFully(buffer, 0, size)
            for (i in 0 until size step 2) {
                val value = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort()
                val scaled = (value * gain).roundToInt().coerceIn(-32768, 32767)
                buffer[i] = scaled.toByte(); buffer[i + 1] = (scaled shr 8).toByte()
            }
            raf.seek(position); raf.write(buffer, 0, size)
            remaining -= size
        }
        return gain
    }

    @Synchronized
    fun closeAndFinalize(): File {
        if (finalized) return finalFile
        check(!closed) { "Writer closed without finalizing; .part remains recoverable" }
        try {
            raf.seek(0); writeHeader(raf, sampleRate, dataBytes); raf.fd.sync()
        } finally {
            closed = true
            raf.close()
        }
        check(!finalFile.exists() && partFile.renameTo(finalFile)) { "Cannot finalize ${partFile.name}" }
        finalized = true
        return finalFile
    }

    @Synchronized
    fun abort() {
        if (!closed) { closed = true; raf.close() }
    }

    companion object {
        /** Recovers our raw canonical WAV after an interrupted recording; leaves unknown files alone. */
        fun recoverPart(part: File): File? {
            if (!part.name.endsWith(".wav.part") || part.length() < 46) return null
            val destination = File(part.parentFile, part.name.removeSuffix(".part"))
            if (destination.exists()) return null
            RandomAccessFile(part, "rw").use { file ->
                val header = ByteArray(44); file.readFully(header)
                fun ascii(start: Int, size: Int) = String(header, start, size, Charsets.US_ASCII)
                fun le(start: Int) = (0..3).fold(0) { v, i -> v or ((header[start + i].toInt() and 255) shl (8 * i)) }
                if (ascii(0, 4) != "RIFF" || ascii(8, 8) != "WAVEfmt " ||
                    ascii(36, 4) != "data" || le(16) != 16 || le(20) != 65537 ||
                    le(24) != AudioConfig.SAMPLE_RATE || le(28) != AudioConfig.SAMPLE_RATE * 2 ||
                    le(32) != 1048578) return null
                val bytes = (file.length() - 44) / 2 * 2
                if (bytes > 0xffffffffL - 36L) return null
                file.setLength(44 + bytes); file.seek(0)
                writeHeader(file, AudioConfig.SAMPLE_RATE, bytes); file.fd.sync()
            }
            return if (part.renameTo(destination)) destination else null
        }

        private fun writeHeader(file: RandomAccessFile, rate: Int, dataSize: Long) {
            fun ascii(value: String) = file.write(value.toByteArray(Charsets.US_ASCII))
            fun int(value: Long) { for (i in 0..3) file.write((value shr (8 * i)).toInt() and 255) }
            fun short(value: Int) { file.write(value and 255); file.write((value shr 8) and 255) }
            ascii("RIFF"); int(36 + dataSize); ascii("WAVEfmt "); int(16)
            short(1); short(1); int(rate.toLong()); int(rate * 2L); short(2); short(16)
            ascii("data"); int(dataSize)
        }
    }
}
