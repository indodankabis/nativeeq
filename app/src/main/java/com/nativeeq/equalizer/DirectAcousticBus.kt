package com.nativeeq.equalizer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lock-free single-producer/single-consumer float mailbox between the mic RTA
 * thread, the visualizer FFT callback and the UI. Backed by one direct buffer.
 */
object DirectAcousticBus {
    private const val OFF_RAW_LOW = 0
    private const val OFF_RAW_MED = 1
    private const val OFF_RAW_HIGH = 2
    private const val OFF_MIC_0 = 3
    private const val OFF_DSP_0 = 8
    private const val OFF_EXEC_US = 13

    private val rawBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
    private val floatView = rawBuffer.asFloatBuffer()

    fun writeRawTriplet(low: Float, med: Float, high: Float) {
        floatView.put(OFF_RAW_LOW, low)
        floatView.put(OFF_RAW_MED, med)
        floatView.put(OFF_RAW_HIGH, high)
    }

    fun readRawTriplet(out: FloatArray) {
        out[0] = floatView.get(OFF_RAW_LOW)
        out[1] = floatView.get(OFF_RAW_MED)
        out[2] = floatView.get(OFF_RAW_HIGH)
    }

    fun writeMicBands(
        b0: Float, b1: Float, b2: Float, b3: Float, b4: Float, execMicros: Float,
    ) {
        floatView.put(OFF_MIC_0 + 0, b0)
        floatView.put(OFF_MIC_0 + 1, b1)
        floatView.put(OFF_MIC_0 + 2, b2)
        floatView.put(OFF_MIC_0 + 3, b3)
        floatView.put(OFF_MIC_0 + 4, b4)
        floatView.put(OFF_EXEC_US, execMicros)
    }

    /** @return last Goertzel pass cost in microseconds (real measured value). */
    fun readMicBands(out: FloatArray): Int {
        for (i in 0 until 5) out[i] = floatView.get(OFF_MIC_0 + i)
        return floatView.get(OFF_EXEC_US).toInt()
    }

    fun writeDspGains(gains: ShortArray) {
        for (i in 0 until minOf(5, gains.size)) {
            floatView.put(OFF_DSP_0 + i, gains[i].toFloat())
        }
    }

    fun getDirectBuffer(): ByteBuffer = rawBuffer
}
