package com.example.rokidphone.service.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CodexRelayVisionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CodexRelayVisionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CodexRelayVisionClient(
            TestRelayConfig(baseUrl = server.url("/").toString().trimEnd('/'))
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `analyze sends responses vision request with selected settings`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"output_text":"B. one sentence"}""")
        )
        val progressStages = mutableListOf<AiRequestStage>()

        val result = client.analyze(
            imageData = byteArrayOf(1, 2, 3, 4),
            prompt = "answer this image",
            requestSettings = AiRequestSettings(
                reasoningEffort = "medium",
                textVerbosity = "high",
                maxOutputTokens = 321,
                maxImageSidePx = 2200,
                jpegQuality = 90,
                timeoutSeconds = 75
            ),
            onProgress = { progressStages.add(it.stage) }
        )

        assertThat(result.getOrThrow()).isEqualTo("B. one sentence")
        assertThat(progressStages).containsAtLeast(
            AiRequestStage.OPTIMIZING_IMAGE,
            AiRequestStage.ENCODING_IMAGE,
            AiRequestStage.BUILDING_REQUEST,
            AiRequestStage.WAITING_FOR_RELAY,
            AiRequestStage.PARSING_RESPONSE,
            AiRequestStage.COMPLETED
        ).inOrder()

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/responses")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key")

        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("test-model")
        assertThat(body.getInt("max_output_tokens")).isEqualTo(321)
        assertThat(body.getJSONObject("reasoning").getString("effort")).isEqualTo("medium")
        assertThat(body.getJSONObject("text").getString("verbosity")).isEqualTo("high")

        val content = body.getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
        assertThat(content.getJSONObject(0).getString("type")).isEqualTo("input_text")
        assertThat(content.getJSONObject(0).getString("text")).isEqualTo("answer this image")
        assertThat(content.getJSONObject(1).getString("type")).isEqualTo("input_image")
        assertThat(content.getJSONObject(1).getString("image_url"))
            .startsWith("data:image/jpeg;base64,")
    }

    @Test
    fun `analyze falls back to default prompt and parses nested output`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "output": [
                        {
                          "content": [
                            {"type": "output_text", "text": "nested answer"}
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val result = client.analyze(
            imageData = byteArrayOf(9, 8, 7),
            prompt = "",
            requestSettings = AiRequestSettings(reasoningEffort = "not-real", timeoutSeconds = 1)
        )

        assertThat(result.getOrThrow()).isEqualTo("nested answer")

        val requestBody = JSONObject(server.takeRequest().body.readUtf8())
        val content = requestBody.getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
        assertThat(content.getJSONObject(0).getString("text")).isEqualTo("default prompt")
        assertThat(requestBody.getJSONObject("reasoning").getString("effort")).isEqualTo("minimal")
    }

    @Test
    fun `analyze parses chat completions style choices response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "choices answer"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val result = client.analyze(byteArrayOf(1, 2, 3), "prompt")

        assertThat(result.getOrThrow()).isEqualTo("choices answer")
    }

    @Test
    fun `analyze returns failure for relay error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"message":"server busy"}}""")
        )

        val result = client.analyze(byteArrayOf(1), "prompt")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("HTTP 500")
    }

    @Test
    fun `analyze retries transient relay error and returns success`() = runTest {
        client = CodexRelayVisionClient(
            config = TestRelayConfig(baseUrl = server.url("/").toString().trimEnd('/')),
            retryDelaysMs = listOf(0L, 0L)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setBody("""{"error":{"message":"bad gateway"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"output_text":"recovered answer"}""")
        )
        val waitingDetails = mutableListOf<String>()

        val result = client.analyze(
            imageData = byteArrayOf(1, 2, 3),
            prompt = "prompt",
            onProgress = {
                if (it.stage == AiRequestStage.WAITING_FOR_RELAY) {
                    waitingDetails.add(it.detail)
                }
            }
        )

        assertThat(result.getOrThrow()).isEqualTo("recovered answer")
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(waitingDetails.any { it.contains("attempt=1/3") }).isTrue()
        assertThat(waitingDetails.any { it.contains("attempt=2/3") }).isTrue()
    }

    @Test
    fun `analyze falls back to next provider when primary fails`() = runTest {
        val fallbackServer = MockWebServer()
        fallbackServer.start()
        try {
            client = CodexRelayVisionClient(
                config = TestRelayConfig(
                    providers = listOf(
                        VisionRelayProvider(
                            name = "primary",
                            baseUrl = server.url("/").toString().trimEnd('/'),
                            apiKey = "primary-key",
                            model = "primary-model"
                        ),
                        VisionRelayProvider(
                            name = "fallback",
                            baseUrl = fallbackServer.url("/").toString().trimEnd('/'),
                            apiKey = "fallback-key",
                            model = "fallback-model"
                        )
                    )
                ),
                retryDelaysMs = emptyList()
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"error":{"message":"primary down"}}""")
            )
            fallbackServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"output_text":"fallback answer"}""")
            )

            val result = client.analyze(byteArrayOf(1, 2, 3), "prompt")

            assertThat(result.getOrThrow()).isEqualTo("fallback answer")
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(fallbackServer.requestCount).isEqualTo(1)
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer primary-key")
            val fallbackRequest = fallbackServer.takeRequest()
            assertThat(fallbackRequest.getHeader("Authorization")).isEqualTo("Bearer fallback-key")
            val fallbackBody = JSONObject(fallbackRequest.body.readUtf8())
            assertThat(fallbackBody.getString("model")).isEqualTo("fallback-model")
        } finally {
            fallbackServer.shutdown()
        }
    }

    @Test
    fun `analyze returns failure after retryable relay errors are exhausted`() = runTest {
        client = CodexRelayVisionClient(
            config = TestRelayConfig(baseUrl = server.url("/").toString().trimEnd('/')),
            retryDelaysMs = listOf(0L, 0L)
        )
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(502)
                    .setBody("""{"error":{"message":"bad gateway"}}""")
            )
        }

        val result = client.analyze(byteArrayOf(1), "prompt")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("HTTP 502")
        assertThat(result.exceptionOrNull()?.message).contains("after 3 attempts")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `analyze rejects empty image before sending relay request`() = runTest {
        val result = client.analyze(ByteArray(0), "prompt")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Image data is empty")
        assertThat(server.requestCount).isEqualTo(0)
    }

    private class TestRelayConfig(
        baseUrl: String = "https://example.invalid",
        apiKey: String = "test-api-key",
        model: String = "test-model",
        override val defaultPrompt: String = "default prompt",
        providers: List<VisionRelayProvider>? = null
    ) : VisionRelayConfig {
        override val providers: List<VisionRelayProvider> = providers ?: listOf(
            VisionRelayProvider(
                name = "test",
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model
            )
        )
    }
}
