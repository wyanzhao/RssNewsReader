package com.dailynews.llm

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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

    /**
     * 429 曾经用 250ms 退避——对限流等于立刻再撞一次窗口，三次尝试在不到一秒内烧光。
     * 服务端说了等多久就等多久。
     */
    @Test
    fun `429 backoff honours Retry-After instead of the local curve`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("slow down"))
            server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )
            val waits = mutableListOf<Long>()

            StructuredLlm(provider, retryDelay = { waits += it }).completeObject(LlmRequest("m", "s", "u", 32))

            // 第一次照服务端的 7 秒；第二次没有 Retry-After，退回本地曲线的第二档。
            assertEquals(listOf(7_000L, 2_000L), waits)
        }
    }

    @Test
    fun `retry after longer than the cap is truncated`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "86400").setBody("come back tomorrow"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}"""))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )
            val waits = mutableListOf<Long>()

            StructuredLlm(provider, retryDelay = { waits += it }).completeObject(LlmRequest("m", "s", "u", 32))

            assertEquals(listOf(MAX_RETRY_AFTER_MILLIS), waits)
        }
    }

    @Test
    fun `openai compatible never ships openrouter routing or attribution headers`() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}"""
            server.enqueue(MockResponse().setBody(body))
            server.start()
            OpenAiCompatProvider(
                ProviderConfig(
                    "p",
                    ProviderType.OPENAI_COMPAT,
                    server.url("/v1").toString(),
                    "alias",
                    true,
                    routing = ProviderRouting(listOf("backup"), ProviderSort.THROUGHPUT, requireParameters = true),
                ),
                ApiKeySource { "secret" },
                OkHttpClient(),
            ).complete(LlmRequest("model-a", "s", "u", 32))
            val recorded = server.takeRequest()
            val payload = recorded.body.readUtf8()
            assertFalse("\"models\"" in payload)
            assertFalse("\"provider\"" in payload)
            assertEquals(null, recorded.getHeader("HTTP-Referer"))
            assertEquals(null, recorded.getHeader("X-Title"))
        }
    }

    @Test
    fun `openrouter ships default routing headers and explicit fallbacks`() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}"""
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setBody(body))
            server.start()
            val url = server.url("/v1").toString()
            fun provider(routing: ProviderRouting) = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENROUTER, url, "alias", true, routing = routing),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )

            provider(ProviderRouting()).complete(LlmRequest("model-a", "s", "u", 32))
            val defaults = server.takeRequest()
            val defaultBody = defaults.body.readUtf8()
            assertEquals(OpenRouterDefaults.HTTP_REFERER, defaults.getHeader("HTTP-Referer"))
            assertEquals(OpenRouterDefaults.APP_TITLE, defaults.getHeader("X-Title"))
            assertEquals(OpenRouterDefaults.APP_TITLE, defaults.getHeader("X-OpenRouter-Title"))
            assertFalse("\"models\"" in defaultBody)
            assertTrue("\"sort\":\"throughput\"" in defaultBody, defaultBody)
            assertTrue("\"require_parameters\":true" in defaultBody, defaultBody)

            provider(
                ProviderRouting(listOf(" backup-a ", "", "backup-b"), ProviderSort.THROUGHPUT, requireParameters = true),
            ).complete(LlmRequest("model-a", "s", "u", 32))
            val routed = server.takeRequest().body.readUtf8()

            // 主模型必须排在备选列表第一位，空白项被清掉。
            assertTrue("\"models\":[\"model-a\",\"backup-a\",\"backup-b\"]" in routed, routed)
            assertTrue("\"sort\":\"throughput\"" in routed, routed)
            assertTrue("\"require_parameters\":true" in routed, routed)
        }
    }

    /**
     * 看门狗与 WorkManager 的停止都靠协程取消。此前 provider 用阻塞的 `execute()`，
     * 于是取消要等 socket 自己返回（最长 callTimeout，默认 1200 秒）——20 分钟的
     * 看门狗实际上停不掉任何东西。这条断言的是「取消在 socket 超时之前就生效」。
     */
    @Test
    fun `cancelling the caller aborts an in-flight provider call`() = runBlocking {
        MockWebServer().use { server ->
            // 收下请求但永不回应：客户端会一直挂在 socket 上，正是"慢供应商"的形状。
            // 用 NO_RESPONSE 而不是 setBodyDelay，否则 MockWebServer 关不掉挂起的响应体。
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("slow", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                // 读超时远长于我们等待的时间：如果取消无效，这个测试只能靠超时收场。
                OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build(),
            )

            val elapsed = measureTimeMillis {
                val job = launch(Dispatchers.IO) { provider.complete(LlmRequest("m", "s", "u", 32)) }
                while (server.requestCount == 0) delay(10)
                job.cancelAndJoin()
            }

            assertTrue(elapsed < 10_000, "取消应立即生效，实际耗时 ${elapsed}ms —— 说明调用仍不可中断")
        }
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

    @Test
    fun `openai compatible ships reasoning_effort and omits none`() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}"""
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setBody(body))
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )

            provider.complete(LlmRequest("model-a", "s", "u", 32, reasoningEffort = ReasoningEffort.LOW))
            val withEffort = server.takeRequest().body.readUtf8()
            assertTrue("\"reasoning_effort\":\"low\"" in withEffort, withEffort)
            assertFalse("\"reasoning\":" in withEffort, withEffort)

            provider.complete(LlmRequest("model-a", "s", "u", 32, reasoningEffort = ReasoningEffort.NONE))
            val omitted = server.takeRequest().body.readUtf8()
            assertFalse("reasoning" in omitted, omitted)
        }
    }

    @Test
    fun `openrouter ships reasoning object not top-level reasoning_effort`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"x\":1}"},"finish_reason":"stop"}]}"""))
            server.start()
            OpenAiCompatProvider(
                ProviderConfig("p", ProviderType.OPENROUTER, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "secret" },
                OkHttpClient(),
            ).complete(LlmRequest("model-a", "s", "u", 32, reasoningEffort = ReasoningEffort.MEDIUM))
            val payload = server.takeRequest().body.readUtf8()

            assertTrue("\"reasoning\":{\"effort\":\"medium\"}" in payload, payload)
            assertFalse("reasoning_effort" in payload, payload)
        }
    }

    @Test
    fun `anthropic ships output_config effort and beta header`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"{\"x\":1}"}],"stop_reason":"end_turn"}"""))
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"{\"x\":1}"}],"stop_reason":"end_turn"}"""))
            server.start()
            val provider = AnthropicProvider(
                ProviderConfig("a", ProviderType.ANTHROPIC, server.url("").toString(), "alias", false),
                ApiKeySource { "secret" },
                OkHttpClient(),
            )

            provider.complete(LlmRequest("claude-test", "system", "user", 123, reasoningEffort = ReasoningEffort.LOW))
            val withEffort = server.takeRequest()
            val withEffortBody = withEffort.body.readUtf8()
            assertEquals("effort-2025-11-24", withEffort.getHeader("anthropic-beta"))
            assertTrue("\"effort\":\"low\"" in withEffortBody, withEffortBody)
            assertTrue("\"output_config\"" in withEffortBody, withEffortBody)

            provider.complete(LlmRequest("claude-test", "system", "user", 123, reasoningEffort = ReasoningEffort.NONE))
            val omitted = server.takeRequest()
            val omittedBody = omitted.body.readUtf8()
            assertEquals(null, omitted.getHeader("anthropic-beta"))
            assertFalse("\"effort\"" in omittedBody, omittedBody)
            assertFalse("\"output_config\"" in omittedBody, omittedBody)
        }
    }

    @Test
    fun `anthropic keeps json schema format when effort is set`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"{\"x\":1}"}],"stop_reason":"end_turn"}"""))
            server.start()
            AnthropicProvider(
                ProviderConfig("a", ProviderType.ANTHROPIC, server.url("").toString(), "alias", true, StructuredMode.JSON_SCHEMA),
                ApiKeySource { "secret" },
                OkHttpClient(),
            ).complete(structuredRequest().copy(reasoningEffort = ReasoningEffort.HIGH))
            val body = server.takeRequest().body.readUtf8()

            assertTrue("\"output_config\"" in body, body)
            assertTrue("\"type\":\"json_schema\"" in body, body)
            assertTrue("\"effort\":\"high\"" in body, body)
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
