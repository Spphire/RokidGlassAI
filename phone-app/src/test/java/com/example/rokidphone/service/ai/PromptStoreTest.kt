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
class PromptStoreTest {
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
    fun `getPrompt migrates legacy default prompt`() {
        context.getSharedPreferences("rokid_photo_ai", Context.MODE_PRIVATE)
            .edit()
            .putString("vision_prompt", "\u5e2e\u6211\u56de\u7b54\u56fe\u4e2d\u7684\u9898\u76ee")
            .commit()

        val prompt = PromptStore.getPrompt(context)

        assertThat(prompt).isEqualTo(CodexRelayConfig.defaultPrompt)
        assertThat(prompt).contains("\u5982\u679c\u662f\u5ba2\u89c2\u9898")
        assertThat(prompt).contains("\u7814\u7a76\u751f")
    }

    @Test
    fun `getPrompt preserves user customized prompt`() {
        val customPrompt = "\u53ea\u56de\u7b54\u7b54\u6848"
        context.getSharedPreferences("rokid_photo_ai", Context.MODE_PRIVATE)
            .edit()
            .putString("vision_prompt", customPrompt)
            .commit()

        assertThat(PromptStore.getPrompt(context)).isEqualTo(customPrompt)
    }
}
