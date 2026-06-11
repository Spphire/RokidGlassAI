package com.example.rokidphone.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnowledgeBaseRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `loadProfiles reads generated software and french knowledge bases`() = runTest {
        val profiles = KnowledgeBaseRepository.loadProfiles(context)

        assertThat(profiles.map { it.id }).containsAtLeast(
            KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID,
            KnowledgeBaseStore.FRENCH_ID
        )
        assertThat(
            profiles.first { it.id == KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID }.chunkCount
        ).isGreaterThan(0)
        assertThat(
            profiles.first { it.id == KnowledgeBaseStore.FRENCH_ID }.chunkCount
        ).isGreaterThan(0)
    }

    @Test
    fun `buildPrompt returns original prompt when knowledge base is disabled`() = runTest {
        val prompt = KnowledgeBaseRepository.buildPrompt(
            context = context,
            basePrompt = "answer the image",
            knowledgeBaseId = KnowledgeBaseStore.NONE_ID
        )

        assertThat(prompt.prompt).isEqualTo("answer the image")
        assertThat(prompt.profile).isNull()
        assertThat(prompt.contextChars).isEqualTo(0)
    }

    @Test
    fun `buildPrompt injects selected knowledge base context`() = runTest {
        val prompt = KnowledgeBaseRepository.buildPrompt(
            context = context,
            basePrompt = "answer this software testing question",
            knowledgeBaseId = KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID
        )

        assertThat(prompt.profile?.id).isEqualTo(KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID)
        assertThat(prompt.contextChars).isGreaterThan(0)
        assertThat(prompt.prompt).contains("Software Engineering")
        assertThat(prompt.prompt).contains("知识库片段")
    }
}
