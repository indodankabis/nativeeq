package com.nativeeq.equalizer

import android.media.audiofx.Visualizer

/**
 * Taps the output mix (session 0) for real PCM waveform + FFT frames. Needs RECORD_AUDIO
 * and MODIFY_AUDIO_SETTINGS; some devices refuse session 0, in which case [start] returns
 * false and the views fall back to the 3-band telemetry. Callbacks arrive on the looper
 * of the thread that called [start] (the UI thread here).
 */
class AudioTap(
    private val onWave: (ByteArray) -> Unit,
    private val onFft: (ByteArray, Int) -> Unit,
) {
    private var viz: Visualizer? = null

    val isLive: Boolean get() = viz != null

    fun start(): Boolean {
        if (viz != null) return true
        var v: Visualizer? = null
        return try {
            v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer, waveform: ByteArray, samplingRate: Int) =
                    onWave(waveform)
                override fun onFftDataCapture(vz: Visualizer, fft: ByteArray, samplingRate: Int) =
                    onFft(fft, samplingRate)
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
            viz = v
            true
        } catch (t: Throwable) {
            v?.let { runCatching { it.release() } }
            viz = null
            false
        }
    }

    fun stop() {
        viz?.let { runCatching { it.enabled = false; it.release() } }
        viz = null
    }

    companion object {
        /** Magnitude of FFT bin k (1 until n/2) from Visualizer's packed [Rf0, Rfn/2, Rf1, If1, ...]. */
        fun mag(fft: ByteArray, k: Int): Float {
            val re = fft[2 * k].toFloat()
            val im = fft[2 * k + 1].toFloat()
            return kotlin.math.sqrt(re * re + im * im)
        }

        /** 0..1 level of an FFT magnitude on a 60 dB scale. */
        fun level(m: Float): Float =
            ((20f * kotlin.math.log10(m / 128f + 1e-4f) + 60f) / 60f).coerceIn(0f, 1f)
    }
}
