package com.example.rokidphone.service.ai

import android.content.Context

object PromptStore {
    private const val PREFS_NAME = "rokid_photo_ai"
    private const val KEY_PROMPT = "vision_prompt"

    private val legacyDefaultPrompts = setOf(
        "\u5e2e\u6211\u56de\u7b54\u56fe\u4e2d\u7684\u9898\u76ee",
        "\u752f\ue1b6\u6c9c\u934c\u70b5\u74df\u934f\u53e5\u8151\u9428\u52ec\u5a42\u936b\ufffd"
    )

    fun getPrompt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPrompt = prefs.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() }
        if (storedPrompt == null || storedPrompt in legacyDefaultPrompts) {
            prefs.edit().putString(KEY_PROMPT, CodexRelayConfig.defaultPrompt).apply()
            return CodexRelayConfig.defaultPrompt
        }
        return storedPrompt
    }

    fun savePrompt(context: Context, prompt: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROMPT, prompt.ifBlank { CodexRelayConfig.defaultPrompt })
            .apply()
    }
}
