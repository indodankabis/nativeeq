package com.nativeeq.equalizer

import kotlin.math.abs

/**
 * Windowed spectral-flux classifier. Emits an archetype only on change so the
 * caller can push new MBC envelope settings exactly once per detection.
 */
class AcousticGenreClassifier {
    private var prevLow = 0f
    private var prevMed = 0f
    private var prevHigh = 0f
    private var fluxAccum = 0f
    private var lowAccum = 0f
    private var medAccum = 0f
    private var highAccum = 0f
    private var count = 0

    var currentArchetype = AcousticArchetype.DYNAMIC_ACOUSTIC
        private set

    enum class AcousticArchetype(
        val label: String,
        val attackMs: Float,
        val releaseMs: Float,
        val ratio: Float,
        val thresholdDb: Float,
        val eqBiasMb: ShortArray,
    ) {
        SPOKEN_VOCAL(
            "SPOKEN_VOCAL (Fast Voice Leveler)",
            5f, 50f, 3.5f, -16f, shortArrayOf(-220, -80, 180, 160, -60)
        ),
        SUB_TRANSIENT(
            "SUB_TRANSIENT (Punchy Kick Envelope)",
            28f, 45f, 2.8f, -10f, shortArrayOf(180, 80, -40, 80, 140)
        ),
        BRICKWALL_DENSE(
            "BRICKWALL_DENSE (Open Headroom / Low Ratio)",
            18f, 90f, 1.4f, -6f, shortArrayOf(100, -90, 60, 80, 120)
        ),
        DYNAMIC_ACOUSTIC(
            "DYNAMIC_ACOUSTIC (Transparent Studio Glue)",
            15f, 70f, 2f, -12f, shortArrayOf(40, 60, 40, 20, 60)
        ),
    }

    /** @return the newly-detected archetype on change, else null. */
    fun ingestFrame(low: Float, med: Float, high: Float): AcousticArchetype? {
        val energy = low + med + high
        if (energy < 0.06f) return null
        val flux = abs(low - prevLow) + abs(med - prevMed) + abs(high - prevHigh)
        prevLow = low; prevMed = med; prevHigh = high
        fluxAccum += flux
        lowAccum += low / energy
        medAccum += med / energy
        highAccum += high / energy
        count++
        if (count < 25) return null
        val avgFlux = fluxAccum / count
        val avgL = lowAccum / count
        val avgM = medAccum / count
        fluxAccum = 0f; lowAccum = 0f; medAccum = 0f; highAccum = 0f; count = 0
        val detected = when {
            avgM > 0.46f && avgL < 0.28f -> AcousticArchetype.SPOKEN_VOCAL
            avgL > 0.44f && avgFlux > 0.12f -> AcousticArchetype.SUB_TRANSIENT
            avgFlux < 0.06f && energy > 0.45f -> AcousticArchetype.BRICKWALL_DENSE
            else -> AcousticArchetype.DYNAMIC_ACOUSTIC
        }
        val changed = detected != currentArchetype
        currentArchetype = detected
        return if (changed) detected else null
    }
}
