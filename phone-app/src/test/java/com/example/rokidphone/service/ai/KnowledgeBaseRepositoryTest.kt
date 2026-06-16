package com.example.rokidphone.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KnowledgeBaseRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("rokid_photo_ai", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

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

    @Test
    fun `auto selection prefers software engineering for software query`() = runTest {
        val choice = KnowledgeBaseRepository.selectBestKnowledgeBase(
            context = context,
            query = "software engineering risk analysis"
        )

        assertThat(choice?.profile?.id).isEqualTo(KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID)
        assertThat(choice?.matchScore).isGreaterThan(0.0)
    }

    @Test
    fun `auto prompt uses software engineering when query is specific`() = runTest {
        val prompt = KnowledgeBaseRepository.buildPrompt(
            context = context,
            basePrompt = "请回答软件工程期末题：风险识别、风险分析、风险应对分别是什么？",
            knowledgeBaseId = KnowledgeBaseStore.AUTO_ID
        )

        assertThat(prompt.profile?.id).isEqualTo(KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID)
        assertThat(prompt.prompt).contains("Software Engineering")
        assertThat(prompt.prompt).contains("知识库片段")
    }

    @Test
    fun `auto selection prefers french for french query`() = runTest {
        val choice = KnowledgeBaseRepository.selectBestKnowledgeBase(
            context = context,
            query = "TCF TEF compréhension écrite vocabulaire"
        )

        assertThat(choice?.profile?.id).isEqualTo(KnowledgeBaseStore.FRENCH_ID)
        assertThat(choice?.matchScore).isGreaterThan(0.0)
    }

    @Test
    fun `auto prompt mixes knowledge bases when query is too generic`() = runTest {
        val prompt = KnowledgeBaseRepository.buildPrompt(
            context = context,
            basePrompt = "answer the image",
            knowledgeBaseId = KnowledgeBaseStore.AUTO_ID
        )

        assertThat(prompt.profile?.id).isEqualTo(KnowledgeBaseStore.AUTO_ID)
        assertThat(prompt.profile?.name).isEqualTo("Auto Knowledge Mix")
        assertThat(prompt.contextChars).isGreaterThan(0)
        assertThat(prompt.prompt).contains("Software Engineering")
        assertThat(prompt.prompt).contains("French TCF/TEF")
    }

    @Test
    fun `auto prompt treats default vision instructions as too generic`() = runTest {
        val prompt = KnowledgeBaseRepository.buildPrompt(
            context = context,
            basePrompt = "帮我回答图中的题目：如果是客观题，仅给出正确选项以及一句话的解释",
            knowledgeBaseId = KnowledgeBaseStore.AUTO_ID
        )

        assertThat(prompt.profile?.id).isEqualTo(KnowledgeBaseStore.AUTO_ID)
        assertThat(prompt.prompt).contains("Software Engineering")
        assertThat(prompt.prompt).contains("French TCF/TEF")
    }

    @Test
    fun `latin aliases match whole terms without iso false positives`() = runTest {
        val prompt = KnowledgeBaseRepository.buildPrompt(
            context = context,
            basePrompt = "Vision document stakeholder analysis use case relationship",
            knowledgeBaseId = KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID
        )

        assertThat(prompt.prompt).contains("03 软件需求")
        assertThat(prompt.prompt).doesNotContain("08 软件质量管理")
    }

    @Test
    fun `software retrieval routes exam topics to matching course sections`() = runTest {
        val cases = listOf(
            "某选课系统 5 月启动、9 月必须上线，团队没做过微服务。请设计合适的软件过程并说明理由。" to "02 软件过程",
            "比较 RUP 和 Scrum 在迭代、角色、文档和风险控制上的差异。" to "02 软件过程",
            "什么是需求可验证性？请举例说明如何把不可验证需求改写成可验证需求。" to "03 软件需求",
            "在线考试系统中老师、学生、单点登录系统分别对应哪些用例？include 关系应该怎么表达？" to "online-exam-usecase",
            "解释高内聚低耦合，并说明它们如何影响软件设计质量。" to "04 软件设计",
            "软件项目估算中 COCOMO 模型关注哪些输入和输出？适合解决什么问题？" to "5-14_估算案例分析",
            "AI for SE 和 SE for AI 分别是什么意思？请结合软件工程新进展说明。" to "11 软件工程新进展"
        )

        cases.forEach { (query, expectedMarker) ->
            val prompt = KnowledgeBaseRepository.buildPrompt(
                context = context,
                basePrompt = query,
                knowledgeBaseId = KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID
            )

            assertThat(prompt.prompt).contains(expectedMarker)
        }
    }
}
