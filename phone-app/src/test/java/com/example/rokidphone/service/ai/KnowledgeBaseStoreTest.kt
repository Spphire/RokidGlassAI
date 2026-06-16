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
class KnowledgeBaseStoreTest {
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
    fun `default selection uses auto knowledge base mode`() {
        assertThat(KnowledgeBaseStore.getSelectedKnowledgeBaseId(context))
            .isEqualTo(KnowledgeBaseStore.AUTO_ID)
    }

    @Test
    fun `selection persists french knowledge base`() {
        KnowledgeBaseStore.saveSelectedKnowledgeBaseId(context, KnowledgeBaseStore.FRENCH_ID)

        assertThat(KnowledgeBaseStore.getSelectedKnowledgeBaseId(context))
            .isEqualTo(KnowledgeBaseStore.FRENCH_ID)
    }

    @Test
    fun `invalid selection falls back to auto mode`() {
        KnowledgeBaseStore.saveSelectedKnowledgeBaseId(context, "missing")

        assertThat(KnowledgeBaseStore.getSelectedKnowledgeBaseId(context))
            .isEqualTo(KnowledgeBaseStore.AUTO_ID)
    }
}
