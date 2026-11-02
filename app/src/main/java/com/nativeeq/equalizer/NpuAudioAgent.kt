package com.nativeeq.equalizer

import android.content.Context
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

/**
 * Deterministic closed-loop spectral controller (no ML model — plain control
 * theory). Normalized L/M/H energy ratios are driven toward a target curve;
 * the 24-band log spectrum decides how each correction is split between the
 * two bands that share a blob (60/230Hz, 3.6k/14kHz). Each actuation is
 * followed by a feedback pass that damps overshoot. The
 * corrections are pushed to the real DynamicsProcessing chain through
 * ACTION_APPLY_NPU_CORRECTION.
 */
class NpuAudioAgent(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(true)

    var onTelemetryListener:
        ((ShortArray, String, String, Float, Float, Float) -> Unit)? = null

    @Volatile var transferCompMb = ShortArray(5)
    @Volatile var transferTag = "H(f) LINEAR"
    @Volatile var genreBiasMb = ShortArray(5)

    private var lastHypothesis = "Initializing closed-loop observer"
    @Volatile private var latestLow = 0f
    @Volatile private var latestMed = 0f
    @Volatile private var latestHigh = 0f

    private val targetLow = 0.38f
    private val targetMed = 0.36f
    private val targetHigh = 0.26f

    private val spectrumLock = Any()
    private var spectrumEma: FloatArray? = null
    @Volatile private var lastSpectrumMs = 0L
    private val eqCentersHz = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
    // Which of the 5 EQ bands each spectrum band is nearest to (log distance).
    private val spectrumToEqBand = IntArray(EqualizerService.SPECTRUM_BANDS) { b ->
        val f = EqualizerService.spectrumBandCenterHz(b)
        eqCentersHz.indices.minByOrNull { abs(ln(f / eqCentersHz[it])) } ?: 0
    }

    private val adaptiveGainsMb = FloatArray(5)
    private val smoothedGainsMb = FloatArray(5)

    private var accLow = 0f
    private var accMed = 0f
    private var accHigh = 0f
    private var accSamples = 0
    private var tickCount = 0
    private var loopPhase = 0
    private var preActionLow = 0f
    private var preActionMed = 0f
    private var preActionHigh = 0f

    init {
        startClosedLoop()
    }

    fun pushSpectralFrame(low: Float, med: Float, high: Float) {
        latestLow = low
        latestMed = med
        latestHigh = high
    }

    /** ~4Hz log-spaced spectrum from EqualizerService.onSpectrumListener. */
    fun pushSpectrum(bands: FloatArray) {
        if (bands.size != EqualizerService.SPECTRUM_BANDS) return
        synchronized(spectrumLock) {
            val ema = spectrumEma
            if (ema == null) {
                spectrumEma = bands.copyOf()
            } else {
                for (i in ema.indices) ema[i] = ema[i] * 0.6f + bands[i] * 0.4f
            }
        }
        lastSpectrumMs = android.os.SystemClock.uptimeMillis()
    }

    /** Smoothed spectral context, or null if no fresh frames (capture paused). */
    fun latestSpectrum(): FloatArray? {
        if (android.os.SystemClock.uptimeMillis() - lastSpectrumMs > 2000L) return null
        return synchronized(spectrumLock) { spectrumEma?.copyOf() }
    }

    /** Per-EQ-band mean level (0..1 dB scale) from the spectrum, or null if stale. */
    private fun eqBandLevels(): FloatArray? {
        val spec = latestSpectrum() ?: return null
        val sum = FloatArray(5)
        val cnt = IntArray(5)
        for (b in spec.indices) {
            val e = spectrumToEqBand[b]
            sum[e] += spec[b]
            cnt[e]++
        }
        return FloatArray(5) { if (cnt[it] > 0) sum[it] / cnt[it] else 0f }
    }

    private fun startClosedLoop() {
        executor.execute {
            while (isRunning.get()) {
                try {
                    val l = latestLow
                    val m = latestMed
                    val h = latestHigh
                    val energy = l + m + h
                    if (energy > 0.05f) {
                        accLow += l / energy
                        accMed += m / energy
                        accHigh += h / energy
                        accSamples++
                    }
                    tickCount++
                    var statusHeadline = "NPU LOOP: OBSERVING STREAM"
                    var logLine = ""
                    if (tickCount % 15 == 0) {
                        if (accSamples >= 5) {
                            val avgL = accLow / accSamples
                            val avgM = accMed / accSamples
                            val avgH = accHigh / accSamples
                            accLow = 0f; accMed = 0f; accHigh = 0f; accSamples = 0
                            val errLow = avgL - targetLow
                            val errMed = avgM - targetMed
                            val errHigh = avgH - targetHigh
                            if (loopPhase == 0) {
                                preActionLow = avgL
                                preActionMed = avgM
                                preActionHigh = avgH
                                if (abs(errLow) < 0.05f && abs(errHigh) < 0.05f) {
                                    logLine = "[OBSERVE->LOCKED] Spectrum on target " +
                                        "(L:${(avgL * 100).toInt()}% M:${(avgM * 100).toInt()}% " +
                                        "H:${(avgH * 100).toInt()}%). Holding gains."
                                    statusHeadline = "NPU LOCKED: TARGET CONVERGED"
                                } else {
                                    // Spectral shape: push more of the bass correction into
                                    // whichever of 60/230Hz is driving the error (same for
                                    // 3.6k/14k). 0.1 level diff (~4.5dB) = full 50% shift.
                                    var lowSplit = 0f
                                    var highSplit = 0f
                                    val levels = eqBandLevels()
                                    if (levels != null) {
                                        lowSplit = ((levels[0] - levels[1]) * sign(errLow) * 5f)
                                            .coerceIn(-0.5f, 0.5f)
                                        highSplit = ((levels[4] - levels[3]) * sign(errHigh) * 5f)
                                            .coerceIn(-0.5f, 0.5f)
                                    }
                                    val step0 = (-errLow * 320f * (1f + lowSplit)).coerceIn(-120f, 120f)
                                    val step1 = (-errLow * 180f * (1f - lowSplit)).coerceIn(-90f, 90f)
                                    val step2 = (-errMed * 240f).coerceIn(-100f, 100f)
                                    val step3 = (-errHigh * 220f * (1f - highSplit)).coerceIn(-100f, 100f)
                                    val step4 = (-errHigh * 300f * (1f + highSplit)).coerceIn(-120f, 120f)
                                    adaptiveGainsMb[0] =
                                        (adaptiveGainsMb[0] + step0).coerceIn(-450f, 450f)
                                    adaptiveGainsMb[1] =
                                        (adaptiveGainsMb[1] + step1).coerceIn(-350f, 350f)
                                    adaptiveGainsMb[2] =
                                        (adaptiveGainsMb[2] + step2).coerceIn(-350f, 350f)
                                    adaptiveGainsMb[3] =
                                        (adaptiveGainsMb[3] + step3).coerceIn(-350f, 350f)
                                    adaptiveGainsMb[4] =
                                        (adaptiveGainsMb[4] + step4).coerceIn(-450f, 450f)
                                    lastHypothesis = when {
                                        errLow > 0.06f ->
                                            "Bass +${(100 * errLow).toInt()}% over target -> Stepping 60Hz " +
                                                "(${fmtDb(adaptiveGainsMb[0])})"
                                        errLow < -0.06f ->
                                            "Bass ${(100 * errLow).toInt()}% thin -> Lifting 60Hz " +
                                                "(${fmtDb(adaptiveGainsMb[0])})"
                                        errHigh > 0.06f ->
                                            "Treble +${(100 * errHigh).toInt()}% sharp -> Rolling 14kHz " +
                                                "(${fmtDb(adaptiveGainsMb[4])})"
                                        else ->
                                            "Trimming mid-band balance -> 910Hz " +
                                                "(${fmtDb(adaptiveGainsMb[2])})"
                                    }
                                    statusHeadline =
                                        "NPU ACTUATING: ${lastHypothesis.take(28)}..."
                                    val shapeTag = if (levels != null) {
                                        " | SHAPE 60/230:${(lowSplit * 100).toInt()}% " +
                                            "14k/3.6k:${(highSplit * 100).toInt()}%"
                                    } else {
                                        ""
                                    }
                                    logLine = "[1.ACT] $lastHypothesis | $transferTag$shapeTag"
                                    loopPhase = 1
                                }
                            } else {
                                val improved: Boolean
                                val preErr =
                                    abs(preActionLow - targetLow) + abs(preActionHigh - targetHigh)
                                val postErr = abs(avgL - targetLow) + abs(avgH - targetHigh)
                                improved = postErr <= 0.02f + preErr
                                statusHeadline = if (improved) {
                                    logLine = "[2.FEEDBACK OK] " +
                                        "L:${(preActionLow * 100).toInt()}%->${(avgL * 100).toInt()}% " +
                                        "H:${(preActionHigh * 100).toInt()}%->${(avgH * 100).toInt()}% " +
                                        "| Error dropped to ${(postErr * 100).toInt()}%"
                                    "NPU FEEDBACK: CONVERGING (ERR ${(postErr * 100).toInt()}%)"
                                } else {
                                    for (i in 0 until 5) adaptiveGainsMb[i] *= 0.75f
                                    logLine = "[2.FEEDBACK DAMP] Track shifted " +
                                        "(Err ${(100 * postErr).toInt()}%) -> " +
                                        "Recoil-damping gains by 25%"
                                    "NPU FEEDBACK: OVERSHOOT DAMPED"
                                }
                                loopPhase = 0
                            }
                        } else {
                            statusHeadline = "NPU LOOP: WAITING FOR SIGNAL"
                            logLine = "[IDLE] Signal below floor. Holding current curve."
                        }
                    }
                    val outGains = ShortArray(5)
                    for (i in 0 until 5) {
                        val combinedTarget =
                            (adaptiveGainsMb[i] + transferCompMb[i] + genreBiasMb[i] * 0.5f)
                                .coerceIn(-650f, 650f)
                        smoothedGainsMb[i] = smoothedGainsMb[i] * 0.8f + 0.2f * combinedTarget
                        outGains[i] = smoothedGainsMb[i].toInt().toShort()
                    }
                    onTelemetryListener?.invoke(outGains, statusHeadline, logLine, l, m, h)
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    return@execute
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun fmtDb(mb: Float): String =
        String.format(Locale.US, "%+.1fdB", mb / 100f)

    fun release() {
        isRunning.set(false)
        onTelemetryListener = null
        executor.shutdownNow()
    }
}
