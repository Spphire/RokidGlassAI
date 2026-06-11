package com.example.rokidphone.service.ai

import com.example.rokidphone.BuildConfig

data class VisionRelayProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val normalizedBaseUrl: String = baseUrl.trimEnd('/')
}

interface VisionRelayConfig {
    val providers: List<VisionRelayProvider>
    val defaultPrompt: String

    val baseUrl: String
        get() = providers.firstOrNull()?.normalizedBaseUrl.orEmpty()
    val apiKey: String
        get() = providers.firstOrNull()?.apiKey.orEmpty()
    val model: String
        get() = providers.firstOrNull()?.model.orEmpty()
}

object CodexRelayConfig : VisionRelayConfig {
    override val providers: List<VisionRelayProvider> = listOf(
        VisionRelayProvider(
            name = "Primary",
            baseUrl = BuildConfig.CODEX_RELAY_URL,
            apiKey = BuildConfig.CODEX_RELAY_API_KEY,
            model = BuildConfig.CODEX_RELAY_MODEL
        ),
        VisionRelayProvider(
            name = "Fallback",
            baseUrl = BuildConfig.CODEX_RELAY_FALLBACK_URL,
            apiKey = BuildConfig.CODEX_RELAY_FALLBACK_API_KEY,
            model = BuildConfig.CODEX_RELAY_FALLBACK_MODEL.ifBlank { BuildConfig.CODEX_RELAY_MODEL }
        )
    ).filter { it.normalizedBaseUrl.isNotBlank() }
    override val defaultPrompt: String = BuildConfig.DEFAULT_VISION_PROMPT
}
