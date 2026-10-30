package com.nativeeq.equalizer

import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real-time mic analyzer: 5 parallel Goertzel filters at the EQ band centers
 * over 16 kHz mono PCM. Emits smoothed normalized band energies plus the
 * measured per-pass cost in microseconds through DirectAcousticBus.
 */
class RoomMicAnalyzer {
    var onMicBandsListener: ((FloatArray) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    private val freqs = floatArrayOf(60f, 230f, 910f, 3600f, 7500f)
    private val sampleRate = 16000
    private val coeffs = FloatArray(5) {
        (cos(freqs[it] * 2.0 * Math.PI / sampleRate) * 2.0).toFloat()
    }
    private val smoothedMic = FloatArray(5)
    private val normScale = 3.0517578E-5f // 1/32768

    fun start() {
        if (isRunning.getAndSet(true)) return
        executor.execute { runLoop() }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun runLoop() {
        val minBuf = max(
            AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ),
            1024
        )
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
        } catch (_: Exception) {
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            isRunning.set(false)
            cleanup()
            return
        }
        try {
            audioRecord?.startRecording()
        } catch (_: Exception) {
            isRunning.set(false)
            cleanup()
            return
        }
        val buf = ShortArray(1024)
        while (isRunning.get()) {
            val read = audioRecord?.read(buf, 0, buf.size) ?: 0
            if (read > 64) {
                val t0 = System.nanoTime()
                for (b in 0 until 5) {
                    val c = coeffs[b]
                    var q1 = 0f
                    var q2 = 0f
                    for (i in 0 until read) {
                        val q0 = buf[i] * normScale + c * q1 - q2
                        q2 = q1
                        q1 = q0
                    }
                    val magSq = max(q1 * q1 + q2 * q2 - q1 * q2 * c, 0f)
                    val norm = (sqrt(magSq) / 1.8f).coerceIn(0f, 1f)
                    smoothedMic[b] = smoothedMic[b] * 0.65f + 0.35f * norm
                }
                val execUs = (System.nanoTime() - t0) / 1000f
                DirectAcousticBus.writeMicBands(
                    smoothedMic[0], smoothedMic[1], smoothedMic[2],
                    smoothedMic[3], smoothedMic[4], execUs
                )
                onMicBandsListener?.invoke(smoothedMic)
            }
            try {
                Thread.sleep(35L)
            } catch (_: InterruptedException) {
                break
            }
        }
        cleanup()
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    fun stop() {
        isRunning.set(false)
    }
}
