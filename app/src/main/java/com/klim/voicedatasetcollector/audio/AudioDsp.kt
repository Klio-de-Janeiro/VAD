package com.klim.voicedatasetcollector.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** In-place radix-2 FFT; inverse includes division by N. */
internal object Fft {
    private val twiddles = ConcurrentHashMap<Int, Pair<DoubleArray, DoubleArray>>()
    fun transform(real: DoubleArray, imag: DoubleArray, inverse: Boolean = false) {
        val n = real.size
        require(n == imag.size && n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        val (cosines, sines) = twiddles.getOrPut(n) {
            Pair(DoubleArray(n / 2) { cos(2 * PI * it / n) }, DoubleArray(n / 2) { sin(2 * PI * it / n) })
        }
        var length = 2
        while (length <= n) {
            for (start in 0 until n step length) {
                for (k in 0 until length / 2) {
                    val c = cosines[k * n / length]
                    val s = sines[k * n / length] * if (inverse) 1 else -1
                    val a = start + k; val b = a + length / 2
                    val r = real[b] * c - imag[b] * s
                    val im = real[b] * s + imag[b] * c
                    real[b] = real[a] - r; imag[b] = imag[a] - im
                    real[a] += r; imag[a] += im
                }
            }
            length *= 2
        }
        if (inverse) for (i in 0 until n) { real[i] /= n; imag[i] /= n }
    }
}

/** Second-order Butterworth high-pass at 70 Hz, preserving most voice fundamentals. */
class HighPass(sampleRate: Int = 16_000) {
    private val k = tan(PI * 70.0 / sampleRate)
    private val norm = 1.0 / (1.0 + sqrt(2.0) * k + k * k)
    private val a1 = 2.0 * (k * k - 1.0) * norm
    private val a2 = (1.0 - sqrt(2.0) * k + k * k) * norm
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(value: Double): Double {
        val out = norm * value + z1
        z1 = -2 * norm * value - a1 * out + z2
        z2 = norm * value - a2 * out
        return out
    }
}

/** Noise power learned only in sustained VAD-negative intervals. */
class NoiseEstimator {
    private val filter = HighPass()
    private val window = DoubleArray(SIZE) { sqrt(0.5 - 0.5 * cos(2 * PI * it / SIZE)) }
    private val power = DoubleArray(SIZE)
    private var quietFrames = 0
    var observations = 0
        private set

    fun observe(frame: ShortArray, speechProbability: Float) {
        require(frame.size == SIZE)
        val real = DoubleArray(SIZE) { filter.process(frame[it] / 32768.0) * window[it] }
        quietFrames = if (speechProbability < 0.25f) quietFrames + 1 else 0
        if (quietFrames < 16) return
        val imag = DoubleArray(SIZE)
        Fft.transform(real, imag)
        for (i in power.indices) {
            val measured = real[i] * real[i] + imag[i] * imag[i]
            power[i] = if (observations == 0) measured else 0.95 * power[i] + 0.05 * measured
        }
        observations++
    }

    fun snapshot(): DoubleArray? = if (observations >= 8) power.copyOf() else null

    companion object { const val SIZE = 512 }
}

/** 50%-overlap STFT, square-root Hann windows, at most 12 dB attenuation. */
class SpectralDenoiser(private val noisePower: DoubleArray?) {
    private val n = NoiseEstimator.SIZE
    val hop = n / 2
    private val window = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
    private val previous = DoubleArray(hop)
    private val tail = DoubleArray(hop)
    private val gains = DoubleArray(n) { 1.0 }

    init { require(noisePower == null || noisePower.size == n) }

    /** Returns the previous hop. First output is padding; flush once with a zero hop. */
    fun process(input: DoubleArray): DoubleArray {
        require(input.size == hop)
        val real = DoubleArray(n) { (if (it < hop) previous[it] else input[it - hop]) * window[it] }
        val imag = DoubleArray(n)
        input.copyInto(previous)
        Fft.transform(real, imag)
        if (noisePower != null) {
            val desired = DoubleArray(n) { i ->
                val power = real[i] * real[i] + imag[i] * imag[i]
                sqrt((1.0 - 0.9 * noisePower[i] / max(power, 1e-12)).coerceIn(0.0625, 1.0))
            }
            for (i in 0 until n) {
                val smooth = (desired[(i + n - 1) % n] + 2 * desired[i] + desired[(i + 1) % n]) / 4
                gains[i] = 0.6 * gains[i] + 0.4 * smooth
                real[i] *= gains[i]; imag[i] *= gains[i]
            }
        }
        Fft.transform(real, imag, inverse = true)
        return DoubleArray(hop) { i ->
            val sample = real[i] * window[i] + tail[i]
            tail[i] = real[i + hop] * window[i + hop]
            sample
        }
    }
}
