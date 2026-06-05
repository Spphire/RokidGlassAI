package com.example.rokidphone.service.ai

import com.example.rokidphone.BuildConfig

interface VisionRelayConfig {
    val baseUrl: String
    val apiKey: String
    val model: String
    val defaultPrompt: String
}

object CodexRelayConfig : VisionRelayConfig {
    override val baseUrl: String = BuildConfig.CODEX_RELAY_URL.trimEnd('/')
    override val apiKey: String = BuildConfig.CODEX_RELAY_API_KEY
    override val model: String = BuildConfig.CODEX_RELAY_MODEL
    override val defaultPrompt: String = BuildConfig.DEFAULT_VISION_PROMPT
}
