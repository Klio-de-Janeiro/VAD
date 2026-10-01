package com.klim.voicedatasetcollector.recording

import java.io.File
import java.io.RandomAccessFile

class WavFileWriter(
    directory: File,
    baseName: String,
    private val sampleRate: Int,
) {
    private val partFile = File(directory, "$baseName.wav.part")
    val finalFile = File(directory, "$baseName.wav")

    private val raf = RandomAccessFile(partFile, "rw")
    private val scratch = ByteArray(AudioConfig.FRAME_SIZE * 2)
    private var dataBytes: Long = 0
    private var closed = false

    init {
        raf.setLength(0)
        writeHeader(0)
    }

    @Synchronized
    fun write(samples: ShortArray) {
        check(!closed) { "Writer is closed" }

        val required = samples.size * 2
        val buffer = if (required <= scratch.size) scratch else ByteArray(required)

        var j = 0
        for (sample in samples) {
            val value = sample.toInt()
            buffer[j++] = (value and 0xFF).toByte()
            buffer[j++] = ((value ushr 8) and 0xFF).toByte()
        }

        raf.write(buffer, 0, required)
        dataBytes += required
    }

    @Synchronized
    fun closeAndFinalize(): File {
        if (closed) return finalFile
        closed = true

        raf.seek(0)
        writeHeader(dataBytes)
        raf.fd.sync()
        raf.close()

        if (finalFile.exists()) finalFile.delete()
        check(partFile.renameTo(finalFile)) {
            "Could not rename ${partFile.name} to ${finalFile.name}"
        }
        return finalFile
    }

    @Synchronized
    fun abort() {
        if (closed) return
        closed = true
        runCatching { raf.close() }
    }

    private fun writeHeader(dataSize: Long) {
        val byteRate = sampleRate * 2
        val riffSize = 36L + dataSize

        writeAscii("RIFF")
        writeLeInt(riffSize.toInt())
        writeAscii("WAVE")

        writeAscii("fmt ")
        writeLeInt(16)
        writeLeShort(1) // PCM
        writeLeShort(1) // mono
        writeLeInt(sampleRate)
        writeLeInt(byteRate)
        writeLeShort(2) // block align
        writeLeShort(16) // bits/sample

        writeAscii("data")
        writeLeInt(dataSize.toInt())
    }

    private fun writeAscii(value: String) {
        raf.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeLeInt(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun writeLeShort(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }
}
