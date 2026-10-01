package com.klim.voicedatasetcollector.recording

import java.util.ArrayDeque

class PcmRingBuffer(private val maxFrames: Int) {
    private val frames = ArrayDeque<ShortArray>(maxFrames)

    fun push(frame: ShortArray) {
        if (frames.size >= maxFrames) {
            frames.removeFirst()
        }
        frames.addLast(frame.copyOf())
    }

    fun snapshot(): List<ShortArray> = frames.map { it.copyOf() }

    fun clear() = frames.clear()

    fun sampleCount(): Int = frames.sumOf { it.size }
}
