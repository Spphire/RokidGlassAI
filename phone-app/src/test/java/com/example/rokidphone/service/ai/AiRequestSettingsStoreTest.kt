package com.example.rokidphone.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiRequestSettingsStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("rokid_photo_ai", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `normalized clamps invalid values to safe ranges`() {
        val settings = AiRequestSettings(
            reasoningEffort = "extreme",
            textVerbosity = "chatty",
            maxOutputTokens = 5,
            maxImageSidePx = 99,
            jpegQuality = 10,
            timeoutSeconds = 1
        ).normalized()

        assertThat(settings.reasoningEffort).isEqualTo(AiRequestSettingsStore.DEFAULT_REASONING_EFFORT)
        assertThat(settings.textVerbosity).isEqualTo(AiRequestSettingsStore.DEFAULT_TEXT_VERBOSITY)
        assertThat(settings.maxOutputTokens).isEqualTo(AiRequestSettingsStore.MIN_MAX_OUTPUT_TOKENS)
        assertThat(settings.maxImageSidePx).isEqualTo(640)
        assertThat(settings.jpegQuality).isEqualTo(40)
        assertThat(settings.timeoutSeconds).isEqualTo(AiRequestSettingsStore.MIN_TIMEOUT_SECONDS)
    }

    @Test
    fun `saveSettings persists normalized settings`() {
        AiRequestSettingsStore.saveSettings(
            context,
            AiRequestSettings(
                reasoningEffort = "medium",
                textVerbosity = "high",
                maxOutputTokens = 9999,
                maxImageSidePx = 5000,
                jpegQuality = 120,
                timeoutSeconds = 300
            )
        )

        val restored = AiRequestSettingsStore.getSettings(context)

        assertThat(restored.reasoningEffort).isEqualTo("medium")
        assertThat(restored.textVerbosity).isEqualTo("high")
        assertThat(restored.maxOutputTokens).isEqualTo(2_000)
        assertThat(restored.maxImageSidePx).isEqualTo(3_000)
        assertThat(restored.jpegQuality).isEqualTo(100)
        assertThat(restored.timeoutSeconds).isEqualTo(120)
    }

    @Test
    fun `presets use longer timeout for slower quality profiles`() {
        assertThat(AiRequestSettingsStore.FAST_PRESET.timeoutSeconds).isEqualTo(60)
        assertThat(AiRequestSettingsStore.BALANCED_PRESET.timeoutSeconds).isEqualTo(90)
        assertThat(AiRequestSettingsStore.QUALITY_PRESET.timeoutSeconds).isEqualTo(120)
    }

    @Test
    fun `getSettings migrates legacy short timeout to current default`() {
        context.getSharedPreferences("rokid_photo_ai", Context.MODE_PRIVATE)
            .edit()
            .putInt("ai_timeout_seconds", 25)
            .commit()

        val restored = AiRequestSettingsStore.getSettings(context)

        assertThat(restored.timeoutSeconds).isEqualTo(AiRequestSettingsStore.DEFAULT_TIMEOUT_SECONDS)
    }

    @Test
    fun `getSettings migrates legacy tiny output token budget to current default`() {
        context.getSharedPreferences("rokid_photo_ai", Context.MODE_PRIVATE)
            .edit()
            .putInt("ai_max_output_tokens", 100)
            .commit()

        val restored = AiRequestSettingsStore.getSettings(context)

        assertThat(restored.maxOutputTokens).isEqualTo(AiRequestSettingsStore.DEFAULT_MAX_OUTPUT_TOKENS)
    }
}
