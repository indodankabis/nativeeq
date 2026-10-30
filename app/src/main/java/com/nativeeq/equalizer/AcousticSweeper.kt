package com.nativeeq.equalizer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * 3-second log-swept sine (20 Hz -> 16 kHz) played through the music stream
 * while the mic RTA accumulates smoothed band energies. The result is a real
 * measured room+speaker response -> per-band correction in millibels.
 */
class AcousticSweeper {
    private val executor = Executors.newSingleThreadExecutor()

    interface SweepCallback {
        fun onProgress(percentage: Int)
        fun onComplete(targetCorrection: ShortArray)
    }

    fun trigger3SecondSweep(micRta: RoomMicAnalyzer, cb: SweepCallback) {
        executor.execute { runSweep(micRta, cb) }
    }

    private fun runSweep(micRta: RoomMicAnalyzer, cb: SweepCallback) {
        val sampleRate = 44100
        val durS = 3
        val totalSamples = sampleRate * durS
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, totalSamples * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Exception) {
            cb.onComplete(ShortArray(5))
            return
        }

        val soundData = ShortArray(totalSamples)
        val fEnd = 16000.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val f = 20.0 * (fEnd / 20.0).pow(t / durS)
            val phase = 2.0 * Math.PI * f * t
            soundData[i] = (sin(phase) * 32000.0).toInt().toShort()
        }
        track.write(soundData, 0, soundData.size)
        track.play()

        val peakResonances = FloatArray(5)
        var accCount = 0
        val tempListener = micRta.onMicBandsListener
        micRta.onMicBandsListener = { bands ->
            accCount++
            for (b in 0 until 5) {
                peakResonances[b] = peakResonances[b] * 0.82f + bands[b] * 0.18f
            }
            cb.onProgress(minOf(accCount * 10, 99))
        }
        try {
            Thread.sleep(3000L)
        } catch (_: Exception) {
        }
        micRta.onMicBandsListener = tempListener
        try {
            track.stop()
            track.release()
        } catch (_: Exception) {
        }
        val correction = ShortArray(5)
        for (b in 0 until 5) {
            val dev = 0.35f - peakResonances[b]
            correction[b] = (500f * dev).toInt().coerceIn(-450, 450).toShort()
        }
        cb.onComplete(correction)
    }
}
