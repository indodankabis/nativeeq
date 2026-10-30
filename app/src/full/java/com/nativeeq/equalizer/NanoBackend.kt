package com.nativeeq.equalizer

import kotlinx.coroutines.runBlocking

/**
 * Gemini Nano backend via ML Kit genai-prompt (proprietary — full flavor only).
 * The foss flavor ships a stub so F-Droid builds carry zero non-free deps.
 */
object NanoBackend {
    fun run(
        prompt: String,
        onToken: (String) -> Unit,
        parse: (String, String) -> LiteRtLmAcousticEngine.LmResponse?,
    ): LiteRtLmAcousticEngine.LmResponse? {
        return try {
            runBlocking {
                val model = com.google.mlkit.genai.prompt.Generation.getClient()
                try {
                    val status = model.checkStatus()
                    if (status != com.google.mlkit.genai.common.FeatureStatus.AVAILABLE) {
                        onToken("[GEMINI-NANO] not available on this device (status=$status)")
                        return@runBlocking null
                    }
                    onToken("[GEMINI-NANO] AICore model ready — generating…")
                    val sb = StringBuilder()
                    val request =
                        com.google.mlkit.genai.prompt.GenerateContentRequest.Builder(
                            com.google.mlkit.genai.prompt.TextPart(
                                prompt + LiteRtLmAcousticEngine.RESPONSE_CONTRACT
                            )
                        ).apply {
                            temperature = 0.2f
                            maxOutputTokens = 256
                        }.build()
                    model.generateContent(
                        request,
                        object : com.google.mlkit.genai.common.StreamingCallback {
                            override fun onNewText(text: String) {
                                sb.append(text)
                                onToken(text)
                            }
                        },
                    )
                    parse(sb.toString(), "GEMINI-NANO")
                } finally {
                    try {
                        model.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            onToken("[GEMINI-NANO] unavailable: ${e.message?.take(60)}")
            null
        }
    }
}
