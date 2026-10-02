package cn.enaium.silero.vad

import android.content.Context
import cn.enaium.silero.vad.config.SampleRate
import java.io.File

class SileroVad(
    context: Context,
    sampleRate: SampleRate,
    minSpeechDurationMs: Int,
    minSilenceDurationMs: Int,
    private val startThreshold: Float = 0.5f
) : AutoCloseable {

    private val model: SileroVadModel

    private val samplingRate: Int =
        sampleRate.value

    private val windowSize: Int =
        if (samplingRate >= 16000) 512 else 256

    private val maxSpeechFramesCount: Int =
        (
            samplingRate * minSpeechDurationMs / 1000 +
                windowSize - 1
            ) / windowSize

    private val maxSilenceFramesCount: Int =
        (
            samplingRate * minSilenceDurationMs / 1000 +
                windowSize - 1
            ) / windowSize

    var lastProbability: Float = 0f
        private set

    private var speechFramesCount = 0
    private var silenceFramesCount = 0
    private var lastSpeechState = false

    init {

        val modelFile =
            File(
                context.cacheDir,
                "silero_vad.onnx"
            )

        model =
            SileroVadModel.fromStream(
                context.assets.open("silero_vad.onnx"),
                modelFile,
                windowSize
            )
    }

    fun reset() {
        model.resetStates()

        speechFramesCount = 0
        silenceFramesCount = 0
        lastSpeechState = false
    }

    fun isSpeech(
        audioData: ShortArray
    ): Boolean {

        val floatData =
            FloatArray(audioData.size) { i ->

                audioData[i]
                    .toInt()
                    .toFloat() / 32768.0f
            }

        return isSpeech(floatData)
    }

    fun isSpeech(
        audioData: FloatArray
    ): Boolean {

        val probability =
            model.call(
                arrayOf(audioData),
                samplingRate
            )[0]

        lastProbability = probability
        return isContinuousSpeech(
            probability >= startThreshold
        )
    }

    private fun isContinuousSpeech(
        speech: Boolean
    ): Boolean {

        if (speech) {

            if (
                speechFramesCount <=
                maxSpeechFramesCount
            ) {
                speechFramesCount++
            }

            if (
                speechFramesCount >
                maxSpeechFramesCount
            ) {

                silenceFramesCount = 0
                lastSpeechState = true

                return true
            }

        } else {

            if (
                silenceFramesCount <=
                maxSilenceFramesCount
            ) {
                silenceFramesCount++
            }

            if (
                silenceFramesCount >
                maxSilenceFramesCount
            ) {

                speechFramesCount = 0
                lastSpeechState = false

                return false

            } else if (
                speechFramesCount >
                maxSpeechFramesCount
            ) {

                return true
            }
        }

        return lastSpeechState
    }

    override fun close() {
        reset()
        model.close()
    }
}