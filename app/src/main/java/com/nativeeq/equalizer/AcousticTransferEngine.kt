package com.nativeeq.equalizer

import java.util.Locale
import kotlin.math.max

/**
 * Estimates the room/speaker transfer function by comparing the
 * output-stream spectrum (raw) against the mic RTA spectrum, then
 * emits a compensating 5-band offset in millibels.
 */
class AcousticTransferEngine {
    private val rawBands = FloatArray(5) { 0.1f }
    private val micBands = FloatArray(5) { 0.1f }
    private var ambientRumbleFloor = 0.05f

    data class EnvironmentFrame(
        val isNoisyEnvironment: Boolean,
        val rumbleLevel: Int,
        val resonantBand: Int,
        val compensationMb: ShortArray,
        val summaryTag: String,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is EnvironmentFrame &&
                isNoisyEnvironment == other.isNoisyEnvironment &&
                rumbleLevel == other.rumbleLevel &&
                resonantBand == other.resonantBand &&
                compensationMb.contentEquals(other.compensationMb) &&
                summaryTag == other.summaryTag)

        override fun hashCode(): Int =
            ((((isNoisyEnvironment.hashCode() * 31 + rumbleLevel) * 31 +
                resonantBand) * 31 + compensationMb.contentHashCode()) * 31 +
                summaryTag.hashCode())
    }

    fun updateRawTriplet(low: Float, med: Float, high: Float) {
        rawBands[0] = low
        rawBands[1] = 0.65f * low + 0.35f * med
        rawBands[2] = med
        rawBands[3] = 0.4f * med + 0.6f * high
        rawBands[4] = high
    }

    fun evaluateTransfer(latestMic: FloatArray): EnvironmentFrame {
        for (i in 0 until minOf(5, latestMic.size)) {
            micBands[i] = micBands[i] * 0.7f + latestMic[i] * 0.3f
        }
        val rawEnergy = rawBands.sum()
        val micLowEnergy = (micBands[0] + micBands[1]) * 0.5f
        if (rawEnergy < 0.25f || rawBands[0] < 0.15f) {
            ambientRumbleFloor = ambientRumbleFloor * 0.85f + 0.15f * micLowEnergy
        } else {
            ambientRumbleFloor = max(ambientRumbleFloor * 0.98f, 0.04f)
        }
        val isNoisyEnv = ambientRumbleFloor > 0.28f
        val comp = ShortArray(5)
        var maxDelta = -1f
        var peakBand = 1
        for (b in 0 until 5) {
            val delta = micBands[b] - rawBands[b]
            if (delta > maxDelta) {
                maxDelta = delta
                peakBand = b
            }
        }
        val bandNames = arrayOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
        val tag = when {
            isNoisyEnv -> {
                val boost = ((ambientRumbleFloor - 0.25f) * 600f).toInt().coerceIn(80, 320)
                comp[0] = (boost * 0.6f).toInt().toShort()
                comp[1] = (-boost * 0.4f).toInt().toShort()
                comp[2] = boost.toShort()
                comp[3] = (boost * 1.1f).toInt().toShort()
                comp[4] = (boost * 0.5f).toInt().toShort()
                String.format(
                    Locale.US, "CAB/SHOP NOISE (%d%%) -> Vocal Lift +%.1fdB",
                    (ambientRumbleFloor * 100).toInt(), boost / 100f
                )
            }
            maxDelta > 0.18f -> {
                val cut = (-maxDelta * 420f).toInt().coerceIn(-300, -60).toShort()
                comp[peakBand] = cut
                String.format(
                    Locale.US, "H(f) PEAK @ %s (+%d%%) -> Trim %.1fdB",
                    bandNames[peakBand], (100 * maxDelta).toInt(), cut / 100f
                )
            }
            else -> "H(f) LINEAR: Room & Stream Aligned"
        }
        return EnvironmentFrame(
            isNoisyEnv, (ambientRumbleFloor * 100).toInt(), peakBand, comp, tag
        )
    }
}
