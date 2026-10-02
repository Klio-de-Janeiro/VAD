package com.klim.voicedatasetcollector.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Streaming measurements of the original PCM, without an invented SNR estimate. */
class AudioQuality {
    private var count = 0L
    private var squareSum = 0.0
    private var sum = 0.0
    private var peak = 0.0
    private var clipped = 0L

    data class Snapshot(val rmsDbfs: Double, val peakDbfs: Double, val dcOffset: Double,
                        val clippedFraction: Double, val warnings: List<String>)

    fun add(samples: ShortArray) {
        for (sample in samples) {
            val value = sample / 32768.0
            count++; sum += value; squareSum += value * value
            peak = max(peak, abs(value))
            if (abs(sample.toInt()) >= 32760) clipped++
        }
    }

    fun snapshot(): Snapshot {
        val rms = sqrt(squareSum / max(1L, count))
        val dc = sum / max(1L, count)
        val fraction = clipped.toDouble() / max(1L, count)
        val warnings = mutableListOf<String>()
        if (fraction > 0.001) warnings += "clipping"
        if (rms < 0.0031623) warnings += "low_level"
        if (abs(dc) > 0.02) warnings += "dc_offset"
        return Snapshot(db(rms), db(peak), dc, fraction, warnings)
    }

    private fun db(value: Double): Double = 20 * log10(max(value, 1e-6))
}
