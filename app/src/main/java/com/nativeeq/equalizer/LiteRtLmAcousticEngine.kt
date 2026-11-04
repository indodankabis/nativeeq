package com.nativeeq.equalizer

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Prompt-tune / session-analysis engine with a real backend chain:
 *
 *   1. LiteRT-LM  — a downloaded .litertlm model (Gemma) running through
 *      com.google.ai.edge.litertlm with real token streaming.
 *   2. Gemini Nano — com.google.mlkit:genai-prompt via AICore when the device
 *      reports FeatureStatus.AVAILABLE (no download needed).
 *   3. Bundled TFLite advisor — a real trained linear regressor
 *      (telemetry features -> 5 band gains) via org.tensorflow.lite.
 *   4. Heuristic keyword tuner — always available, clearly labeled.
 *
 * Every response is tagged with the backend that actually produced it.
 */
class LiteRtLmAcousticEngine(private val context: Context) {

    data class LmResponse(
        val reasoning: String,
        val recommendedState: Int,
        val targetGains: ShortArray,
        val backendTag: String = "HEURISTIC",
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is LmResponse &&
                reasoning == other.reasoning &&
                recommendedState == other.recommendedState &&
                targetGains.contentEquals(other.targetGains) &&
                backendTag == other.backendTag)

        override fun hashCode(): Int =
            (((reasoning.hashCode() * 31 + recommendedState) * 31 +
                targetGains.contentHashCode()) * 31 + backendTag.hashCode())
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val released = AtomicBoolean(false)
    private val modelManager = ModelManager(context)

    private var advisor: org.tensorflow.lite.Interpreter? = null
    private var advisorFailed = false

    fun getModelManager(): ModelManager = modelManager

    // ---------- public API ----------

    /**
     * Prompt-tune: natural-language EQ intent -> 5 band gains.
     * Runs async on the engine executor; callbacks fire on that thread.
     */
    fun evaluateNaturalPromptAsync(
        promptText: String,
        currentGains: ShortArray,
        onToken: (String) -> Unit = {},
        onComplete: (LmResponse) -> Unit,
    ) {
        executor.execute {
            val lm = runLmPrompt(buildTunePrompt(promptText, currentGains), onToken)
            if (lm != null) {
                onComplete(lm)
            } else {
                val resp = heuristicTune(promptText, currentGains)
                onComplete(resp)
            }
        }
    }

    /** Synchronous keyword tuner — always available, honest label. */
    fun evaluateNaturalPrompt(promptText: String, currentGains: ShortArray): LmResponse =
        heuristicTune(promptText, currentGains)

    /**
     * Session analysis: telemetry summary -> reasoning + suggested gains.
     */
    fun inferStreaming(
        summary: TelemetryDbHelper.SessionSummary,
        routeKey: String,
        onToken: (String) -> Unit,
        onComplete: (LmResponse) -> Unit,
    ) {
        executor.execute {
            val lm = runLmPrompt(buildAnalysisPrompt(summary, routeKey), onToken)
            if (lm != null) {
                onComplete(lm)
                return@execute
            }
            // Real bundled advisor model (linear regressor, trained offline).
            val advisorGains = inferAdvisorGains(summary)
            val resp = heuristicAnalysis(summary, routeKey, advisorGains)
            onToken("[${resp.backendTag}] ${resp.reasoning.take(80)}")
            onComplete(resp)
        }
    }

    /**
     * Free-form generation (visualizer flavors). [onComplete] gets the raw reply and its
     * backend tag, or (null, "RANDOM") when no LM is usable. Fires on the engine thread.
     */
    fun generateRawAsync(
        prompt: String,
        accept: (String) -> Boolean,
        onComplete: (String?, String) -> Unit,
    ) {
        try {
            executor.execute {
                var raw: String? = null
                var tag = "RANDOM"
                val grab: (String, String) -> LmResponse? = { r, t -> if (accept(r)) { raw = r; tag = t }; null }
                runLiteRtLm(prompt, {}, "", grab)
                if (raw == null) NanoBackend.run(prompt, {}, grab, "")
                onComplete(raw, tag)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(null, "RANDOM")
        }
    }

    fun release() {
        released.set(true)
        try {
            advisor?.close()
        } catch (_: Exception) {
        }
        modelManager.release()
        executor.shutdownNow()
    }

    // ---------- backend chain ----------

    /**
     * Tries the real LM backends in order. Returns null when none is usable
     * so the caller can fall back to honest local logic.
     */
    private fun runLmPrompt(prompt: String, onToken: (String) -> Unit): LmResponse? {
        runLiteRtLm(prompt, onToken)?.let { return it }
        runNanoPrompt(prompt, onToken)?.let { return it }
        return null
    }

    private fun runLiteRtLm(
        prompt: String,
        onToken: (String) -> Unit,
        contract: String = RESPONSE_CONTRACT,
        parse: (String, String) -> LmResponse? = ::parseLmOutput,
    ): LmResponse? {
        val info = modelManager.anyModelPresent() ?: return null
        return try {
            onToken("[LITERT-LM] Loading ${info.displayName}…")
            runBlocking {
                val config = com.google.ai.edge.litertlm.EngineConfig(
                    modelPath = modelManager.modelFile(info).absolutePath,
                    backend = com.google.ai.edge.litertlm.Backend.GPU(),
                    cacheDir = context.cacheDir.absolutePath,
                )
                com.google.ai.edge.litertlm.Engine(config).use { engine ->
                    engine.initialize()
                    engine.createConversation().use { convo ->
                        val sb = StringBuilder()
                        convo.sendMessageAsync(
                            prompt + contract,
                            object : com.google.ai.edge.litertlm.MessageCallback {
                                override fun onMessage(
                                    message: com.google.ai.edge.litertlm.Message,
                                ) {
                                    val t = messageText(message)
                                    if (t.isNotEmpty()) {
                                        sb.append(t)
                                        onToken(t)
                                    }
                                }

                                override fun onDone() {}

                                override fun onError(throwable: Throwable) {
                                    onToken("[LITERT-LM] error: ${throwable.message}")
                                }
                            },
                        )
                        parse(sb.toString(), "LITERT-LM")
                    }
                }
            }
        } catch (e: Throwable) {
            onToken("[LITERT-LM] unavailable: ${e.message?.take(60)}")
            null
        }
    }

    // Gemini Nano backend lives in flavor source sets: src/full has the real
    // genai-prompt client, src/foss a stub (ML Kit is proprietary — F-Droid).
    private fun runNanoPrompt(prompt: String, onToken: (String) -> Unit): LmResponse? =
        NanoBackend.run(prompt, onToken, ::parseLmOutput)

    private fun messageText(message: com.google.ai.edge.litertlm.Message): String {
        return try {
            message.contents.contents
                .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                .joinToString("") { it.text }
        } catch (_: Throwable) {
            message.toString()
        }
    }

    // ---------- response parsing ----------

    /**
     * Extract {"gains_mb":[…5 ints…],"reasoning":"…"} from model output.
     * Falls back to grabbing any 5 integers in a bracketed array.
     */
    private fun parseLmOutput(raw: String, tag: String): LmResponse? {
        if (raw.isBlank()) return null
        try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) {
                val json = org.json.JSONObject(raw.substring(start, end + 1))
                val arr = json.optJSONArray("gains_mb")
                if (arr != null && arr.length() >= 5) {
                    val gains = ShortArray(5) {
                        arr.optInt(it, 0).coerceIn(-900, 900).toShort()
                    }
                    val reason = json.optString(
                        "reasoning",
                        "LM-suggested curve applied."
                    )
                    return LmResponse(reason.take(160), -1, gains, tag)
                }
            }
        } catch (_: Exception) {
        }
        // Loose fallback: first array of >=5 integers.
        val m = Regex("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]")
            .find(raw)
        if (m != null) {
            val gains = ShortArray(5) {
                (m.groupValues[it + 1].toIntOrNull() ?: 0).coerceIn(-900, 900).toShort()
            }
            return LmResponse(
                "LM output parsed: gains applied directly.", -1, gains, tag
            )
        }
        return null
    }

    // ---------- prompts ----------

    private fun buildTunePrompt(promptText: String, currentGains: ShortArray): String =
        """You are an audio equalizer DSP controller. A 5-band parametric EQ has bands
60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz. Gains are in millibels, range -900..900.
Current gains: [${currentGains.joinToString(",")}].
User request: "$promptText".
Return ONLY a JSON object with new band gains and a one-line explanation.""".trimIndent()

    private fun buildAnalysisPrompt(
        summary: TelemetryDbHelper.SessionSummary, routeKey: String,
    ): String =
        """You are an audio equalizer DSP controller analyzing live audio telemetry.
Bands: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz (millibels, -900..900).
Telemetry (last 5 min): samples=${summary.totalSamples}, avgLow=${"%.2f".format(summary.avgLow)},
avgMid=${"%.2f".format(summary.avgMid)}, avgHigh=${"%.2f".format(summary.avgHigh)},
mudAlerts=${summary.mudAlerts}, harshAlerts=${summary.harshAlerts},
compressionAlerts=${summary.compressionAlerts}, route=$routeKey.
Suggest corrective gains. Return ONLY a JSON object.""".trimIndent()

    companion object {
        internal const val RESPONSE_CONTRACT =
            """ Respond with exactly one JSON object, no markdown:
{"gains_mb":[b0,b1,b2,b3,b4],"reasoning":"one sentence"}"""
    }

    // ---------- honest fallbacks ----------

    /**
     * Bundled linear advisor (eq_optimizer_v16.tflite): a real trained
     * [1,7]->[1,5] regression mapping telemetry features to band gains.
     */
    private fun inferAdvisorGains(
        summary: TelemetryDbHelper.SessionSummary,
    ): ShortArray? {
        if (advisorFailed) return null
        if (advisor == null) {
            try {
                val bytes = context.assets.open("eq_optimizer_v16.tflite").use { input ->
                    val out = ByteArrayOutputStream()
                    input.copyTo(out)
                    out.toByteArray()
                }
                val buf = java.nio.ByteBuffer.allocateDirect(bytes.size)
                    .order(java.nio.ByteOrder.nativeOrder())
                buf.put(bytes)
                buf.rewind()
                advisor = org.tensorflow.lite.Interpreter(buf)
            } catch (_: Exception) {
                advisorFailed = true
                return null
            }
        }
        return try {
            val total = maxOf(summary.totalSamples.toFloat(), 1f)
            val input = arrayOf(
                floatArrayOf(
                    summary.avgLow,
                    summary.avgMid,
                    summary.avgHigh,
                    summary.mudAlerts / total,
                    summary.harshAlerts / total,
                    summary.compressionAlerts / total,
                    (total / 2000f).coerceAtMost(1f),
                )
            )
            val output = Array(1) { FloatArray(5) }
            advisor?.run(input, output)
            ShortArray(5) { output[0][it].coerceIn(-800f, 800f).toInt().toShort() }
        } catch (_: Exception) {
            advisorFailed = true
            null
        }
    }

    private fun heuristicAnalysis(
        summary: TelemetryDbHelper.SessionSummary,
        routeKey: String,
        advisorGains: ShortArray?,
    ): LmResponse {
        val lowP = (summary.avgLow * 100).toInt()
        val midP = (summary.avgMid * 100).toInt()
        val highP = (summary.avgHigh * 100).toInt()
        val (reasoning, state, gains) = when {
            summary.mudAlerts > summary.totalSamples * 0.35 || lowP > 58 ->
                Triple(
                    "Low-end masking in 60-230Hz ($lowP%). Cut sub-mud (-2.0dB) & lifted vocal mids (+3.0dB).",
                    9, shortArrayOf(-200, 0, 300, 100, 0)
                )
            summary.harshAlerts > summary.totalSamples * 0.3 || highP > 40 ->
                Triple(
                    "Sibilance in 3.6k-14kHz ($highP%). Rolled off treble (-1.0dB) & warmed 230Hz.",
                    10, shortArrayOf(-100, 200, 300, 0, -100)
                )
            summary.compressionAlerts > summary.totalSamples * 0.35 ->
                Triple(
                    "Heavy dynamic limiting detected. Engaging V-shape transient expansion.",
                    8, shortArrayOf(500, 200, 0, 200, 500)
                )
            lowP < 24 ->
                Triple(
                    "Thin sub-bass ($lowP%). Boosted 60Hz fundamental (+4.5dB) and 230Hz warmth.",
                    3, shortArrayOf(450, 200, 0, 0, 0)
                )
            else ->
                Triple(
                    "Spectrum balanced (L:$lowP% M:$midP% H:$highP%). Holding flat reference.",
                    0, shortArrayOf(0, 0, 0, 0, 0)
                )
        }
        return if (advisorGains != null) {
            LmResponse(reasoning, state, advisorGains, "TFLITE-ADVISOR")
        } else {
            LmResponse(reasoning, state, gains, "HEURISTIC")
        }
    }

    private fun heuristicTune(promptText: String, currentGains: ShortArray): LmResponse {
        val p = promptText.lowercase(java.util.Locale.US)
        val gains = currentGains.clone()
        val notes = ArrayList<String>()
        fun has(vararg keys: String) = keys.any { p.contains(it) }
        if (has("flat", "reset", "clean")) {
            gains.fill(0)
            notes.add("Reset all 5 bands to 0.0dB reference")
        }
        if (has("sub", "kick", "punch", "thump", "bass")) {
            val cut = has("less", "cut", "reduce", "too much")
            val delta = if (cut) -250 else 280
            gains[0] = (gains[0] + delta).coerceIn(-900, 900).toShort()
            gains[1] = (gains[1] + delta / 2).coerceIn(-900, 900).toShort()
            notes.add(
                if (cut) "Cut 60-230Hz bass (-2.5dB)" else "Boosted 60Hz sub-punch (+2.8dB)"
            )
        }
        if (has("mud", "boomy", "boxy", "muffled")) {
            gains[1] = (gains[1] - 260).coerceIn(-900, 900).toShort()
            gains[2] = (gains[2] + 140).coerceIn(-900, 900).toShort()
            notes.add("Scooped 230Hz mud (-2.6dB) & opened 910Hz mids")
        }
        if (has("vocal", "voice", "speech", "dialog", "clear")) {
            gains[0] = (gains[0] - 120).coerceIn(-900, 900).toShort()
            gains[2] = (gains[2] + 240).coerceIn(-900, 900).toShort()
            gains[3] = (gains[3] + 200).coerceIn(-900, 900).toShort()
            notes.add("Elevated 910Hz-3.6kHz vocal presence (+2.4dB)")
        }
        if (has("warm", "smooth", "analog", "tube")) {
            gains[1] = (gains[1] + 180).coerceIn(-900, 900).toShort()
            gains[3] = (gains[3] - 120).coerceIn(-900, 900).toShort()
            gains[4] = (gains[4] - 160).coerceIn(-900, 900).toShort()
            notes.add("Added 230Hz warmth (+1.8dB) & softened upper treble")
        }
        if (has("harsh", "ear", "fatigue", "sharp", "sibilan")) {
            gains[3] = (gains[3] - 240).coerceIn(-900, 900).toShort()
            gains[4] = (gains[4] - 220).coerceIn(-900, 900).toShort()
            notes.add("Attenuated 3.6k-14kHz harshness (-2.4dB)")
        }
        if (has("air", "crisp", "bright", "sparkle", "treble")) {
            val cut = has("less", "cut")
            val delta = if (cut) -220 else 250
            gains[3] = (gains[3] + delta / 2).coerceIn(-900, 900).toShort()
            gains[4] = (gains[4] + delta).coerceIn(-900, 900).toShort()
            notes.add(
                if (cut) "Trimmed 14kHz air (-2.2dB)" else "Lifted 14kHz harmonic air (+2.5dB)"
            )
        }
        if (notes.isEmpty()) {
            gains[0] = (gains[0] + 150).coerceIn(-900, 900).toShort()
            gains[2] = (gains[2] + 100).coerceIn(-900, 900).toShort()
            gains[4] = (gains[4] + 150).coerceIn(-900, 900).toShort()
            notes.add("Applied balanced loudness contour (+1.5dB)")
        }
        return LmResponse(
            "PROMPT APPLIED (keyword tuner): " + notes.joinToString("; "),
            EqualizerService.currentState, gains, "HEURISTIC"
        )
    }
}
