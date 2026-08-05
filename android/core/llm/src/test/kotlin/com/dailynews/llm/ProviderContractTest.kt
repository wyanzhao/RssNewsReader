package com.dailynews.llm

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

class ProviderContractTest {
    @Test
    fun `max token truncation fails fast instead of repeating the same capped request`() = runBlocking {
        var calls = 0
        val provider = object : LlmProvider {
            override suspend fun complete(request: LlmRequest): LlmResponse {
                calls += 1
                return LlmResponse("{\"items\":[]}", stopReason = "max_tokens")
            }
        }
        val outcomes = mutableListOf<String>()

        assertFailsWith<LlmProtocolException> {
            StructuredLlm(provider, maxTransientRetries = 2, retryDelay = {})
                .completeObject(
                    LlmRequest("m", "s", "u", 100),
                    onAttempt = { _, _, outcome -> outcomes += outcome },
                )
        }

        assertEquals(1, calls)
        assertEquals(listOf("truncated"), outcomes)
        assertTrue(outcomes.none { "repair" in it })
    }

    @Test
    fun `structured repair counts and observes both physical calls`() = runBlocking {
        val responses = ArrayDeque(listOf(LlmResponse("not json"), LlmResponse("{\"ok\":true}")))
        val provider = object : LlmProvider {
            override suspend fun complete(request: LlmRequest) = responses.removeFirst()
        }
        var count = 0
        val outcomes = mutableListOf<String>()

        val (result, _) = StructuredLlm(provider).completeObject(
            LlmRequest("m", "s", "u", 100),
            beforeAttempt = { count += 1 },
            onAttempt = { _, _, outcome -> outcomes += outcome },
        )

        assertEquals("true", result["ok"].toString())
        assertEquals(2, count)
        assertEquals(listOf("invalid_json", "repair_success"), outcomes)
    }

    @Test
    fun `openai compatible maps json mode auth and usage`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":4}}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString().trimEnd('/'), "alias", true),
                ApiKeySource { "secret-value" },
                OkHttpClient(),
            )
            val response = provider.complete(LlmRequest("model-a", "system", "user", 321))
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer secret-value", request.getHeader("Authorization"))
            assertTrue("\"response_format\"" in body)
            assertFalse("secret-value" in body)
            assertEquals(11, response.inputTokens)
            assertEquals(4, response.outputTokens)
        }
    }

    @Test
    fun `structured transport retries 429 and provider errors redact keys`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("retry sk-leaked-secret-value"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1/v1/").toString(), "alias", true),
                ApiKeySource { "sk-leaked-secret-value" },
                OkHttpClient(),
            )

            val result = StructuredLlm(provider, retryDelay = {}).completeObject(LlmRequest("m", "s", "u", 32)).first

            assertEquals("true", result["ok"].toString())
            assertEquals("/v1/chat/completions", server.takeRequest().path)
            assertEquals("/v1/chat/completions", server.takeRequest().path)
        }

        val safe = redactProviderText("server echoed sk-super-secret and literal-value", "literal-value")
        assertFalse("super-secret" in safe)
        assertFalse("literal-value" in safe)
    }

    @Test
    fun `authentication failures are never retried`() = runBlocking {
        for (status in listOf(401, 403)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("authentication rejected"))
                server.start()
                val provider = OpenAiCompatProvider(
                    ProviderConfig("auth", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                    ApiKeySource { "secret" },
                    OkHttpClient(),
                )

                val error = assertFailsWith<LlmTransportException> {
                    StructuredLlm(provider, maxTransientRetries = 2, retryDelay = {})
                        .completeObject(LlmRequest("m", "s", "u", 32))
                }

                assertFalse(error.retryable)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `response body timeout is wrapped as retryable transport failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"}}]}""")
                    .setBodyDelay(200, TimeUnit.MILLISECONDS),
            )
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("slow", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build(),
            )

            val error = assertFailsWith<LlmTransportException> {
                provider.complete(LlmRequest("model", "system", "user", 32))
            }

            assertTrue(error.retryable)
            assertTrue("provider slow transport failed" in error.message.orEmpty())
        }
    }

    @Test
    fun `structured layer retries response body timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":false}"}}]}""")
                    .setBodyDelay(200, TimeUnit.MILLISECONDS),
            )
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("slow", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build(),
            )

            val result = StructuredLlm(provider, maxTransientRetries = 1, retryDelay = {})
                .completeObject(LlmRequest("model", "system", "user", 32)).first

            assertEquals("true", result["ok"].toString())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `anthropic response body timeout is wrapped as retryable transport failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("""{"content":[{"type":"text","text":"{\"ok\":true}"}]}""")
                    .setBodyDelay(200, TimeUnit.MILLISECONDS),
            )
            server.start()
            val provider = AnthropicProvider(
                ProviderConfig("anthropic-slow", ProviderType.ANTHROPIC, server.url("").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build(),
            )

            val error = assertFailsWith<LlmTransportException> {
                provider.complete(LlmRequest("model", "system", "user", 32))
            }

            assertTrue(error.retryable)
            assertTrue("Anthropic provider anthropic-slow transport failed" in error.message.orEmpty())
        }
    }

    @Test
    fun `deepseek auto mode uses json object without schema probe`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("deepseek", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )

            provider.complete(structuredRequest().copy(model = "deepseek-v4-flash"))
            val body = server.takeRequest().body.readUtf8()

            assertTrue("\"type\":\"json_object\"" in body)
            assertFalse("\"type\":\"json_schema\"" in body)
        }
    }

    @Test
    fun `provider endpoints accept roots v1 and full endpoints`() {
        assertEquals("https://api.example/v1/chat/completions", ProviderEndpoints.openAi("https://api.example"))
        assertEquals("https://api.example/v1/chat/completions", ProviderEndpoints.openAi("https://api.example/v1/"))
        assertEquals("https://api.example/v1/chat/completions", ProviderEndpoints.openAi("https://api.example/v1/chat/completions"))
        assertEquals("https://api.example/v1/messages", ProviderEndpoints.anthropic("https://api.example/v1/v1"))
    }

    @Test
    fun `provider HTTP exception never exposes configured key`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("bad key literal-secret-value and sk-another-leaked-key"))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "literal-secret-value" },
                OkHttpClient(),
            )

            val error = assertFailsWith<LlmTransportException> { provider.complete(LlmRequest("m", "s", "u", 32)) }

            assertFalse("literal-secret-value" in error.message.orEmpty())
            assertFalse("another-leaked-key" in error.message.orEmpty())
        }
    }

    @Test
    fun `anthropic maps native headers without sampling parameters or assistant prefill`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"{\"x\":1}"}],"stop_reason":"end_turn","usage":{"input_tokens":7,"output_tokens":3}}"""))
            server.start()
            val provider = AnthropicProvider(
                ProviderConfig("a", ProviderType.ANTHROPIC, server.url("").toString().trimEnd('/'), "alias", false),
                ApiKeySource { "anthropic-secret" },
                OkHttpClient(),
            )
            val response = provider.complete(LlmRequest("claude-test", "system", "user", 123))
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/v1/messages", request.path)
            assertEquals("anthropic-secret", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            assertFalse("\"role\":\"assistant\"" in body)
            assertFalse("\"temperature\"" in body)
            assertEquals("{\"x\":1}", response.text)
        }
    }

    @Test
    fun `openai supports every structured mode and strict schema`() = runBlocking {
        StructuredMode.entries.forEach { mode ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}""",
                    ),
                )
                server.start()
                val provider = OpenAiCompatProvider(
                    ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true, mode),
                    ApiKeySource { "secret" },
                    OkHttpClient(),
                )
                val response = provider.complete(structuredRequest())
                val body = server.takeRequest().body.readUtf8()

                assertEquals("{\"x\":1}", response.text)
                when (mode) {
                    StructuredMode.AUTO, StructuredMode.JSON_SCHEMA -> {
                        assertTrue("\"type\":\"json_schema\"" in body)
                        assertTrue("\"strict\":true" in body)
                    }
                    StructuredMode.JSON_OBJECT -> assertTrue("\"type\":\"json_object\"" in body)
                    StructuredMode.TOOL_USE, StructuredMode.PREFILL -> {
                        assertFalse("\"response_format\"" in body)
                        assertFalse("\"role\":\"assistant\"" in body)
                    }
                }
            }
        }
    }

    @Test
    fun `anthropic uses native output format then tool compatibility then plain extraction`() = runBlocking {
        StructuredMode.entries.forEach { mode ->
            MockWebServer().use { server ->
                val usesNativeSchema = mode == StructuredMode.AUTO || mode == StructuredMode.JSON_SCHEMA
                val usesTool = mode == StructuredMode.TOOL_USE
                server.enqueue(
                    MockResponse().setBody(
                        if (usesTool) {
                            """{"content":[{"type":"tool_use","input":{"x":1}}],"stop_reason":"tool_use"}"""
                        } else {
                            """{"content":[{"type":"text","text":"{\"x\":1}"}],"stop_reason":"end_turn"}"""
                        },
                    ),
                )
                server.start()
                val provider = AnthropicProvider(
                    ProviderConfig("a", ProviderType.ANTHROPIC, server.url("").toString(), "alias", true, mode),
                    ApiKeySource { "secret" },
                    OkHttpClient(),
                )
                val response = provider.complete(structuredRequest())
                val body = server.takeRequest().body.readUtf8()

                assertEquals("{\"x\":1}", response.text)
                when {
                    usesNativeSchema -> {
                        assertTrue("\"output_config\"" in body)
                        assertTrue("\"format\"" in body)
                        assertTrue("\"type\":\"json_schema\"" in body)
                        assertFalse("\"input_schema\"" in body)
                    }
                    usesTool -> {
                        assertTrue("\"input_schema\"" in body)
                        assertTrue("\"tool_choice\"" in body)
                    }
                    else -> {
                        assertFalse("\"output_config\"" in body)
                        assertFalse("\"input_schema\"" in body)
                    }
                }
                assertFalse("\"role\":\"assistant\"" in body)
                assertFalse("\"temperature\"" in body)
            }
        }
    }

    @Test
    fun `openai fallback chain reaches plain extraction without fake prefill`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("json_schema response_format unsupported"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("json_object response_format unsupported"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )
            val outcomes = mutableListOf<String>()

            val result = StructuredLlm(provider, maxTransientRetries = 0).completeObject(
                structuredRequest(),
                onAttempt = { _, _, outcome -> outcomes += outcome },
            ).first

            assertEquals("true", result["ok"].toString())
            assertEquals(
                listOf("structured_json_schema_unsupported", "structured_json_object_unsupported", "success"),
                outcomes,
            )
            assertTrue("\"type\":\"json_schema\"" in server.takeRequest().body.readUtf8())
            assertTrue("\"type\":\"json_object\"" in server.takeRequest().body.readUtf8())
            val plainRequest = server.takeRequest().body.readUtf8()
            assertFalse("\"response_format\"" in plainRequest)
            assertFalse("\"role\":\"assistant\"" in plainRequest)
        }
    }

    @Test
    fun `negotiated openai fallback is reused for later calls`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("json_schema response_format unsupported"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}"""))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"x\":2}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )

            StructuredLlm(provider, maxTransientRetries = 0).completeObject(structuredRequest())
            StructuredLlm(provider, maxTransientRetries = 0).completeObject(structuredRequest())

            assertTrue("\"type\":\"json_schema\"" in server.takeRequest().body.readUtf8())
            assertTrue("\"type\":\"json_object\"" in server.takeRequest().body.readUtf8())
            assertTrue("\"type\":\"json_object\"" in server.takeRequest().body.readUtf8())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `anthropic unsupported native schema falls back through tool use to plain extraction`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("output_config json_schema unsupported"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("tool_choice input_schema unsupported"))
            server.enqueue(
                MockResponse().setBody(
                    """{"content":[{"type":"text","text":"{\"x\":1}"}],"stop_reason":"end_turn"}""",
                ),
            )
            server.start()
            val provider = AnthropicProvider(
                ProviderConfig("a", ProviderType.ANTHROPIC, server.url("").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )
            val outcomes = mutableListOf<String>()

            val result = StructuredLlm(provider, maxTransientRetries = 0).completeObject(
                structuredRequest(),
                onAttempt = { _, _, outcome -> outcomes += outcome },
            ).first

            assertEquals("1", result["x"].toString())
            assertEquals(
                listOf("structured_json_schema_unsupported", "structured_tool_use_unsupported", "success"),
                outcomes,
            )
            assertTrue("\"output_config\"" in server.takeRequest().body.readUtf8())
            assertTrue("\"input_schema\"" in server.takeRequest().body.readUtf8())
            val plainRequest = server.takeRequest().body.readUtf8()
            assertFalse("\"role\":\"assistant\"" in plainRequest)
            assertFalse("\"output_config\"" in plainRequest)
        }
    }

    @Test
    fun `anthropic unrelated parameter errors do not masquerade as structured fallback`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("temperature parameter unsupported for this model"))
            server.start()
            val provider = AnthropicProvider(
                ProviderConfig("a", ProviderType.ANTHROPIC, server.url("").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )
            val outcomes = mutableListOf<String>()

            assertFailsWith<LlmTransportException> {
                StructuredLlm(provider, maxTransientRetries = 0).completeObject(
                    structuredRequest().copy(temperature = 0.2),
                    onAttempt = { _, _, outcome -> outcomes += outcome },
                )
            }

            assertEquals(1, server.requestCount)
            assertTrue(outcomes.single().startsWith("failed:"))
        }
    }

    private fun structuredRequest() = LlmRequest(
        "model",
        "system",
        "user",
        128,
        responseSchema = StructuredOutputSchema(
            "test_object",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject { put("x", buildJsonObject { put("type", "integer") }) })
                put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("x")) })
            },
        ),
    )
}
