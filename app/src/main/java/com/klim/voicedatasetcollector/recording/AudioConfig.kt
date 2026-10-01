package com.klim.voicedatasetcollector.recording

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SIZE = 512
    const val FRAME_MS = 32

    const val VAD_THRESHOLD = 0.5f
    const val VAD_MIN_SPEECH_MS = 250
    const val VAD_INTERNAL_MIN_SILENCE_MS = 100

    const val PRE_BUFFER_MS = 1_024
    const val MAX_SEGMENT_MINUTES = 10

    const val PRE_BUFFER_FRAMES = PRE_BUFFER_MS / FRAME_MS
    const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 60 * MAX_SEGMENT_MINUTES
}
