package com.nativeeq.equalizer

/**
 * FOSS build: ML Kit genai-prompt is proprietary (ML Kit Terms of Service) and
 * excluded from this flavor, so the Nano backend is an honest no-op. Chain
 * continues to the bundled TFLite advisor and heuristic tuner.
 */
object NanoBackend {
    fun run(
        prompt: String,
        onToken: (String) -> Unit,
        parse: (String, String) -> LiteRtLmAcousticEngine.LmResponse?,
    ): LiteRtLmAcousticEngine.LmResponse? {
        onToken("[GEMINI-NANO] excluded from FOSS build (proprietary dep)")
        return null
    }
}
