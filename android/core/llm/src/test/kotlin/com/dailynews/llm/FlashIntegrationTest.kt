package com.dailynews.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.*

class FlashIntegrationTest {
    @Test
    fun `switching a saved official endpoint uses new preset but preserves a custom proxy`() {
        assertEquals(ProviderType.DEEPSEEK.defaultBaseUrl, ProviderType.DEEPSEEK.adjustedBaseUrl(
            ProviderType.OPENROUTER, ProviderType.OPENROUTER.chatEndpoint(""),
        ))
        assertEquals("https://proxy.example/v1", ProviderType.ZAI.adjustedBaseUrl(ProviderType.DEEPSEEK, "https://proxy.example/v1"))
    }

    private val schema = StructuredOutputSchema("test", buildJsonObject { put("type", "object") })

    @Test
    fun `official presets resolve correct endpoints and survive serialization`() {
        for ((type, endpoint) in listOf(
            ProviderType.DEEPSEEK to "https://api.deepseek.com/v1/chat/completions",
            ProviderType.ZAI to "https://api.z.ai/api/paas/v4/chat/completions",
        )) {
            assertEquals(endpoint, type.chatEndpoint(""))
            assertTrue(type.defaultModel.isNotEmpty())
            val config = ProviderConfig("p", type, type.defaultBaseUrl, "alias")
            assertEquals(config, Json.decodeFromString<ProviderConfig>(Json.encodeToString(ProviderConfig.serializer(), config)))
        }
    }

    @Test
    fun `deepseek explicitly disables thinking or sends supported effort with JSON object`() = runBlocking<Unit> {
        for (effort in listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)) {
            val body = requestBody(ProviderType.DEEPSEEK, "deepseek-v4-flash", effort)
            assertEquals(if (effort == ReasoningEffort.NONE) "disabled" else "enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(effort.wire, body["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse("reasoning" in body)
            assertFalse("provider" in body)
        }
    }

    @Test
    fun `glm supports only low high max and rejects unsupported effort before HTTP`() = runBlocking<Unit> {
        for (effort in listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)) {
            val body = requestBody(ProviderType.ZAI, "glm-5.3-flash", effort)
            assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(effort.wire, body["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        MockWebServer().use { server ->
            server.start()
            val provider = provider(server, ProviderType.ZAI)
            for (effort in listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.MEDIUM, ReasoningEffort.XHIGH)) {
                assertFailsWith<IllegalArgumentException> { provider.complete(LlmRequest("glm-5.3-flash", "s", "u", 4096, reasoningEffort = effort)) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `official thinking fields never leak to other compatible protocols`() = runBlocking<Unit> {
        for (type in listOf(ProviderType.OPENROUTER, ProviderType.OPENAI_COMPAT)) {
            val body = requestBody(type, "glm-5.3-flash", ReasoningEffort.LOW)
            assertFalse("thinking" in body)
            if (type == ProviderType.OPENROUTER) {
                assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertFalse("reasoning_effort" in body)
            } else assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `JSON compatibility succeeds only for exact typed fields without repair`() = runBlocking<Unit> {
        for (text in listOf("not json", "```json\n{\"ok\":true,\"message\":\"连接成功\"}\n```", "{\"ok\":\"true\",\"message\":\"连接成功\"}", "{\"ok\":true}", "{\"ok\":true,\"message\":\"连接成功\",\"extra\":1}")) {
            var calls = 0
            val provider = object : LlmProvider {
                override suspend fun complete(request: LlmRequest): LlmResponse { calls++; return LlmResponse(text) }
            }
            assertFailsWith<LlmProtocolException> { testJsonCompatibility(provider, "m", ReasoningEffort.LOW) }
            assertEquals(1, calls)
        }
        var captured: LlmRequest? = null
        val provider = object : LlmProvider {
            override suspend fun complete(request: LlmRequest): LlmResponse {
                captured = request
                return LlmResponse("{\"ok\":true,\"message\":\"连接成功\"}")
            }
        }
        assertTrue(testJsonCompatibility(provider, "m", ReasoningEffort.HIGH).contains("JSON 校验通过"))
        assertEquals(ReasoningEffort.HIGH, captured!!.reasoningEffort)
        assertNotNull(captured!!.responseSchema)
    }

    @Test
    fun `JSON probe does not hide truncation or unsupported format with retries`() = runBlocking<Unit> {
        for (truncated in listOf(true, false)) {
            var calls = 0
            val provider = object : LlmProvider {
                override suspend fun complete(request: LlmRequest): LlmResponse {
                    calls++
                    if (!truncated) throw StructuredOutputUnsupportedException(StructuredMode.JSON_SCHEMA, StructuredMode.JSON_OBJECT, "unsupported")
                    return LlmResponse("{\"ok\":true,\"message\":\"连接成功\"}", stopReason = "length")
                }
            }
            assertFails { testJsonCompatibility(provider, "m", ReasoningEffort.LOW) }
            assertEquals(1, calls)
        }
    }

    private fun provider(server: MockWebServer, type: ProviderType) = OpenAiCompatProvider(
        ProviderConfig("test", type, server.url("/v1").toString(), "test-alias"),
        ApiKeySource { "fixture-key" }, OkHttpClient(),
    )

    private suspend fun requestBody(type: ProviderType, model: String, effort: ReasoningEffort): JsonObject = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}]}"""))
        server.start()
        provider(server, type).complete(LlmRequest(model, "s", "u", 4096, responseSchema = schema, reasoningEffort = effort))
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }
}
