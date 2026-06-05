package com.example.rokidphone.service.ai

import android.content.Context

data class AiRequestSettings(
    val reasoningEffort: String = AiRequestSettingsStore.DEFAULT_REASONING_EFFORT,
    val textVerbosity: String = AiRequestSettingsStore.DEFAULT_TEXT_VERBOSITY,
    val maxOutputTokens: Int = AiRequestSettingsStore.DEFAULT_MAX_OUTPUT_TOKENS,
    val maxImageSidePx: Int = AiRequestSettingsStore.DEFAULT_MAX_IMAGE_SIDE_PX,
    val jpegQuality: Int = AiRequestSettingsStore.DEFAULT_JPEG_QUALITY,
    val timeoutSeconds: Int = AiRequestSettingsStore.DEFAULT_TIMEOUT_SECONDS
) {
    fun normalized(): AiRequestSettings {
        return copy(
            reasoningEffort = reasoningEffort
                .takeIf { it in AiRequestSettingsStore.REASONING_EFFORTS }
                ?: AiRequestSettingsStore.DEFAULT_REASONING_EFFORT,
            textVerbosity = textVerbosity
                .takeIf { it in AiRequestSettingsStore.TEXT_VERBOSITIES }
                ?: AiRequestSettingsStore.DEFAULT_TEXT_VERBOSITY,
            maxOutputTokens = maxOutputTokens.coerceIn(
                AiRequestSettingsStore.MIN_MAX_OUTPUT_TOKENS,
                AiRequestSettingsStore.MAX_OUTPUT_TOKENS
            ),
            maxImageSidePx = maxImageSidePx.coerceIn(640, 3_000),
            jpegQuality = jpegQuality.coerceIn(40, 100),
            timeoutSeconds = timeoutSeconds.coerceIn(
                AiRequestSettingsStore.MIN_TIMEOUT_SECONDS,
                AiRequestSettingsStore.MAX_TIMEOUT_SECONDS
            )
        )
    }
}

object AiRequestSettingsStore {
    const val DEFAULT_REASONING_EFFORT = "minimal"
    const val DEFAULT_TEXT_VERBOSITY = "low"
    const val DEFAULT_MAX_OUTPUT_TOKENS = 700
    const val MIN_MAX_OUTPUT_TOKENS = 300
    const val MAX_OUTPUT_TOKENS = 2_000
    const val DEFAULT_MAX_IMAGE_SIDE_PX = 1800
    const val DEFAULT_JPEG_QUALITY = 82
    const val DEFAULT_TIMEOUT_SECONDS = 75
    const val MIN_TIMEOUT_SECONDS = 60
    const val MAX_TIMEOUT_SECONDS = 120
    const val DEFAULT_MAX_UPLOAD_IMAGE_BYTES = 900_000

    val REASONING_EFFORTS = listOf("minimal", "low", "medium", "high")
    val TEXT_VERBOSITIES = listOf("low", "medium", "high")

    val FAST_PRESET = AiRequestSettings(
        maxOutputTokens = 300,
        timeoutSeconds = 60
    )
    val BALANCED_PRESET = AiRequestSettings(
        reasoningEffort = "low",
        textVerbosity = "medium",
        maxOutputTokens = 700,
        maxImageSidePx = 1800,
        jpegQuality = 86,
        timeoutSeconds = 90
    )
    val QUALITY_PRESET = AiRequestSettings(
        reasoningEffort = "medium",
        textVerbosity = "medium",
        maxOutputTokens = 1_000,
        maxImageSidePx = 2200,
        jpegQuality = 90,
        timeoutSeconds = 120
    )

    private const val PREFS_NAME = "rokid_photo_ai"
    private const val KEY_REASONING_EFFORT = "ai_reasoning_effort"
    private const val KEY_TEXT_VERBOSITY = "ai_text_verbosity"
    private const val KEY_MAX_OUTPUT_TOKENS = "ai_max_output_tokens"
    private const val KEY_MAX_IMAGE_SIDE_PX = "ai_max_image_side_px"
    private const val KEY_JPEG_QUALITY = "ai_jpeg_quality"
    private const val KEY_TIMEOUT_SECONDS = "ai_timeout_seconds"

    fun getSettings(context: Context): AiRequestSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AiRequestSettings(
            reasoningEffort = prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT)
                ?: DEFAULT_REASONING_EFFORT,
            textVerbosity = prefs.getString(KEY_TEXT_VERBOSITY, DEFAULT_TEXT_VERBOSITY)
                ?: DEFAULT_TEXT_VERBOSITY,
            maxOutputTokens = migrateLegacyMaxOutputTokens(
                prefs.getInt(KEY_MAX_OUTPUT_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS)
            ),
            maxImageSidePx = prefs.getInt(KEY_MAX_IMAGE_SIDE_PX, DEFAULT_MAX_IMAGE_SIDE_PX),
            jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY),
            timeoutSeconds = migrateLegacyTimeout(
                prefs.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
            )
        ).normalized()
    }

    fun saveSettings(context: Context, settings: AiRequestSettings) {
        val normalized = settings.normalized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REASONING_EFFORT, normalized.reasoningEffort)
            .putString(KEY_TEXT_VERBOSITY, normalized.textVerbosity)
            .putInt(KEY_MAX_OUTPUT_TOKENS, normalized.maxOutputTokens)
            .putInt(KEY_MAX_IMAGE_SIDE_PX, normalized.maxImageSidePx)
            .putInt(KEY_JPEG_QUALITY, normalized.jpegQuality)
            .putInt(KEY_TIMEOUT_SECONDS, normalized.timeoutSeconds)
            .apply()
    }

    private fun migrateLegacyTimeout(timeoutSeconds: Int): Int {
        return if (timeoutSeconds in LEGACY_SHORT_TIMEOUT_SECONDS) {
            DEFAULT_TIMEOUT_SECONDS
        } else {
            timeoutSeconds
        }
    }

    private fun migrateLegacyMaxOutputTokens(maxOutputTokens: Int): Int {
        return if (maxOutputTokens in LEGACY_LOW_MAX_OUTPUT_TOKENS) {
            DEFAULT_MAX_OUTPUT_TOKENS
        } else {
            maxOutputTokens
        }
    }

    private val LEGACY_SHORT_TIMEOUT_SECONDS = setOf(25, 45)
    private val LEGACY_LOW_MAX_OUTPUT_TOKENS = 1..160
}
