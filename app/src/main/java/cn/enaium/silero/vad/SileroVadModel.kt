package cn.enaium.silero.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream

class SileroVadModel internal constructor(
    modelFile: File,
    private val windowSize: Int
) : AutoCloseable {

    private val session: OrtSession
    private lateinit var state: Array<Array<FloatArray>>
    private lateinit var context: Array<FloatArray>

    private var lastSr: Int = 0
    private var lastBatchSize: Int = 0

    private val contextSize: Int = windowSize / 8

    companion object {
        private val SAMPLE_RATES = listOf(8000, 16000)

        fun fromStream(
            inputStream: InputStream,
            tempFile: File,
            windowSize: Int
        ): SileroVadModel {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            return SileroVadModel(
                modelFile = tempFile,
                windowSize = windowSize
            )
        }
    }

    init {
        val env = OrtEnvironment.getEnvironment()

        val opts = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(1)
            setIntraOpNumThreads(1)
            addCPU(true)
        }

        session = env.createSession(
            modelFile.absolutePath,
            opts
        )

        resetStates()
    }

    fun resetStates() {
        resetStates(1)
    }

    fun resetStates(batchSize: Int) {
        state = Array(2) {
            Array(batchSize) {
                FloatArray(128)
            }
        }

        context = emptyArray()
        lastSr = 0
        lastBatchSize = 0
    }

    private fun validateInput(
        x: Array<FloatArray>,
        sr: Int
    ): Pair<Array<FloatArray>, Int> {

        var input = x
        var sampleRate = sr

        if (input.size == 1) {
            input = arrayOf(input[0])
        }

        if (input.size > 2) {
            throw IllegalArgumentException(
                "Incorrect audio data dimension: ${input[0].size}"
            )
        }

        if (sampleRate != 16000 && sampleRate % 16000 == 0) {
            val step = sampleRate / 16000

            input = Array(input.size) { i ->
                val current = input[i]

                FloatArray(
                    (current.size + step - 1) / step
                ) { index ->
                    current[index * step]
                }
            }

            sampleRate = 16000
        }

        if (sampleRate !in SAMPLE_RATES) {
            throw IllegalArgumentException(
                "Only supports sample rates $SAMPLE_RATES"
            )
        }

        if (input[0].size < windowSize) {
            throw IllegalArgumentException(
                "Input audio is too short: " +
                    "expected at least $windowSize samples, " +
                    "got ${input[0].size}"
            )
        }

        return Pair(input, sampleRate)
    }

    fun call(
        x: Array<FloatArray>,
        sr: Int
    ): FloatArray {

        val (validatedX, validatedSr) =
            validateInput(x, sr)

        val batchSize = validatedX.size

        when {
            lastSr != 0 && lastSr != validatedSr ->
                resetStates(batchSize)

            lastBatchSize != 0 && lastBatchSize != batchSize ->
                resetStates(batchSize)

            lastBatchSize == 0 ->
                lastBatchSize = batchSize
        }

        if (context.isEmpty()) {
            context = Array(batchSize) {
                FloatArray(contextSize)
            }
        }

        val xWithContext = Array(batchSize) { i ->

            FloatArray(
                contextSize + windowSize
            ).also { arr ->

                System.arraycopy(
                    context[i],
                    0,
                    arr,
                    0,
                    contextSize
                )

                System.arraycopy(
                    validatedX[i],
                    0,
                    arr,
                    contextSize,
                    windowSize
                )
            }
        }

        val env = OrtEnvironment.getEnvironment()

        var inputTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null

        try {

            inputTensor =
                OnnxTensor.createTensor(
                    env,
                    xWithContext
                )

            stateTensor =
                OnnxTensor.createTensor(
                    env,
                    state
                )

            srTensor =
                OnnxTensor.createTensor(
                    env,
                    longArrayOf(validatedSr.toLong())
                )

            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "state" to stateTensor
            )

            outputs = session.run(inputs)

            @Suppress("UNCHECKED_CAST")
            val output =
                outputs.get(0).value as Array<FloatArray>

            @Suppress("UNCHECKED_CAST")
            state =
                outputs.get(1).value
                    as Array<Array<FloatArray>>

            for (i in 0 until batchSize) {

                System.arraycopy(
                    xWithContext[i],
                    xWithContext[i].size - contextSize,
                    context[i],
                    0,
                    contextSize
                )
            }

            lastSr = validatedSr
            lastBatchSize = batchSize

            return output[0]

        } finally {
            inputTensor?.close()
            stateTensor?.close()
            srTensor?.close()
            outputs?.close()
        }
    }

    override fun close() {
        session.close()
    }
}