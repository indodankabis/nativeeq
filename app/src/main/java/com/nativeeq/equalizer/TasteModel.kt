package com.nativeeq.equalizer

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On-device "personal" advisor tier: ridge regression from the 24-band
 * spectral context to the 5 EQ gains the user actually settled on, fit from
 * tuning_events. Kept rows teach the proposed gains; reverted rows teach the
 * final gains the user moved to instead. Closed-form, ~1ms for 1k rows.
 */
class TasteModel private constructor(
    private val weights: Array<FloatArray>, // [features+1][5]
    val samples: Int,
    val fitRmseDb: Float,
    val baselineRmseDb: Float,
) {
    fun predict(spectrum: FloatArray): ShortArray {
        val x = features(spectrum)
        return ShortArray(OUT) { o ->
            var y = 0f
            for (f in x.indices) y += x[f] * weights[f][o]
            (EqualizerService.quantizeGainMb(y)).coerceIn(-MAX_MB, MAX_MB).toInt().toShort()
        }
    }

    companion object {
        const val MIN_SAMPLES = 6
        private const val OUT = 5
        private const val LAMBDA = 0.5f
        private const val REVERTED_WEIGHT = 0.6f
        private const val MAX_MB = 900f
        private val IN = EqualizerService.SPECTRUM_BANDS

        /** Capture runs while the UI is visible even with nothing playing; such frames carry no context. */
        fun isSilent(spectrum: FloatArray?): Boolean =
            spectrum == null || (spectrum.maxOrNull() ?: 0f) < SILENCE_LEVEL

        private const val SILENCE_LEVEL = 0.05f

        private fun features(spectrum: FloatArray): FloatArray =
            FloatArray(IN + 1) { if (it < IN) spectrum[it] else 1f }

        class Sample(val x: FloatArray, val y: FloatArray, val w: Float)

        fun samplesFrom(tuples: List<TelemetryDbHelper.TrainingTuple>): List<Sample> =
            tuples.mapNotNull { t ->
                if (t.spectrum.size != IN || isSilent(t.spectrum)) return@mapNotNull null
                when {
                    t.kept -> Sample(features(t.spectrum), t.proposedGainsMb, 1f)
                    // A never-applied proposal's "final" gains are just whatever replaced it.
                    t.finalGainsMb != null && t.appliedAt != null -> Sample(features(t.spectrum), t.finalGainsMb, REVERTED_WEIGHT)
                    else -> null
                }
            }

        fun fit(tuples: List<TelemetryDbHelper.TrainingTuple>): TasteModel? {
            val data = samplesFrom(tuples)
            if (data.size < MIN_SAMPLES) return null
            val n = IN + 1
            // Normal equations (XᵀWX + λI) B = XᵀWY; bias column unregularized.
            val a = Array(n) { DoubleArray(n + OUT) }
            for (s in data) {
                for (i in 0 until n) {
                    val wx = s.w * s.x[i].toDouble()
                    for (j in 0 until n) a[i][j] += wx * s.x[j]
                    for (o in 0 until OUT) a[i][n + o] += wx * s.y[o]
                }
            }
            for (i in 0 until IN) a[i][i] += LAMBDA.toDouble()
            if (!solveInPlace(a, n)) return null
            val weights = Array(n) { i -> FloatArray(OUT) { a[i][n + it].toFloat() } }

            val mean = FloatArray(OUT)
            var wSum = 0f
            for (s in data) { for (o in 0 until OUT) mean[o] += s.w * s.y[o]; wSum += s.w }
            for (o in 0 until OUT) mean[o] /= wSum
            var se = 0.0
            var seBase = 0.0
            for (s in data) for (o in 0 until OUT) {
                var p = 0f
                for (f in 0 until n) p += s.x[f] * weights[f][o]
                se += s.w * ((p - s.y[o]) / 100.0).let { it * it }
                seBase += s.w * ((mean[o] - s.y[o]) / 100.0).let { it * it }
            }
            val denom = wSum * OUT
            return TasteModel(
                weights, data.size,
                sqrt(se / denom).toFloat(), sqrt(seBase / denom).toFloat(),
            )
        }

        /** Gauss-Jordan with partial pivoting on an augmented [n x (n+k)] matrix. */
        private fun solveInPlace(a: Array<DoubleArray>, n: Int): Boolean {
            val cols = a[0].size
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
                if (abs(a[p][c]) < 1e-9) return false
                val tmp = a[c]; a[c] = a[p]; a[p] = tmp
                val inv = 1.0 / a[c][c]
                for (j in c until cols) a[c][j] *= inv
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = a[r][c]
                    if (f == 0.0) continue
                    for (j in c until cols) a[r][j] -= f * a[c][j]
                }
            }
            return true
        }
    }
}
