package com.dailynews.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiCompatProvider(
    private val config: ProviderConfig,
    private val keySource: ApiKeySource,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmProvider {
    @Volatile private var negotiatedStructuredMode: StructuredMode? = null

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val apiKey = keySource.read(config.apiKeyAlias)
            ?.takeIf { it.isNotBlank() }
            ?: throw LlmTransportException("missing API key for provider ${config.id}")
        val mode = effectiveMode(request)
        val responseFormat = responseFormat(mode, request.responseSchema)
        val routing = config.routing.forTransport(config.type)
        val payload = OpenAiRequest(
            model = request.model,
            messages = buildList {
                add(OpenAiMessage("system", request.system))
                add(OpenAiMessage("user", request.userContent))
            },
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            responseFormat = responseFormat,
            // OpenRouter 要求备选列表把主模型放在第一位。
            models = routing.modelFallbacks.takeIf { it.isNotEmpty() }?.let { listOf(request.model) + it },
            provider = openRouterPreferences(routing),
        )
        val endpoint = ProviderEndpoints.openAi(config.baseUrl)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
                if (config.type.usesOpenRouterProtocol) {
                    header("HTTP-Referer", OpenRouterDefaults.HTTP_REFERER)
                    header("X-Title", OpenRouterDefaults.APP_TITLE)
                    header("X-OpenRouter-Title", OpenRouterDefaults.APP_TITLE)
                }
            }
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val providerLabel = if (config.type.usesOpenRouterProtocol) {
            "OpenRouter provider ${config.id}"
        } else {
            "OpenAI-compatible provider ${config.id}"
        }
        val response = executeLlmHttp(client, httpRequest, providerLabel)
        val body = response.body
        if (!response.isSuccessful) {
                val safeBody = redactProviderText(body.take(500), apiKey)
                fallbackFor(mode, response.code, body)?.let { fallback ->
                    negotiatedStructuredMode = fallback
                    throw StructuredOutputUnsupportedException(
                        mode,
                        fallback,
                        "OpenAI-compatible $mode is unsupported (HTTP ${response.code}): $safeBody",
                    )
                }
                throw LlmTransportException(
                    "OpenAI-compatible HTTP ${response.code}: $safeBody",
                    retryable = response.code == 429 || response.code in 500..599,
                    retryAfterMillis = response.retryAfterMillis,
                )
        }
        val decoded = try {
            json.decodeFromString<OpenAiResponse>(body)
        } catch (error: Exception) {
            throw LlmProtocolException("invalid OpenAI-compatible response", error)
        }
        val choice = decoded.choices.firstOrNull()
            ?: throw LlmProtocolException("OpenAI-compatible response contains no choice")
        val refusal = choice.message.refusal?.takeIf(String::isNotBlank)
        if (refusal != null || choice.finishReason.equals("content_filter", ignoreCase = true)) {
            throw LlmProtocolException("OpenAI-compatible response was refused: ${refusal ?: choice.finishReason}")
        }
        val text = choice.message.content?.takeIf(String::isNotBlank)
            ?: throw LlmProtocolException("OpenAI-compatible response contains no choice text")
        LlmResponse(
            text = text,
            inputTokens = decoded.usage?.promptTokens,
            outputTokens = decoded.usage?.completionTokens,
            stopReason = choice.finishReason,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun effectiveMode(request: LlmRequest): StructuredMode {
        val requested = request.structuredMode.takeUnless { it == StructuredMode.AUTO }
            ?: negotiatedStructuredMode
            ?: config.structuredMode
        return when {
            !request.jsonMode -> StructuredMode.PREFILL
            requested != StructuredMode.AUTO -> requested
            isDeepSeek(request) && config.supportsJsonMode -> StructuredMode.JSON_OBJECT
            request.responseSchema != null && config.supportsJsonMode -> StructuredMode.JSON_SCHEMA
            config.supportsJsonMode -> StructuredMode.JSON_OBJECT
            else -> StructuredMode.PREFILL
        }.let { mode ->
            if (mode == StructuredMode.JSON_SCHEMA && request.responseSchema == null) StructuredMode.JSON_OBJECT else mode
        }
    }

    /** 全默认时返回 null，请求体里连 `provider` 这个键都不会出现。 */
    private fun openRouterPreferences(routing: ProviderRouting): OpenRouterPreferences? {
        val sort = routing.sort.wire
        val requireParameters = true.takeIf { routing.requireParameters }
        if (sort == null && requireParameters == null) return null
        return OpenRouterPreferences(sort, requireParameters)
    }

    private fun isDeepSeek(request: LlmRequest): Boolean =
        request.model.contains("deepseek", ignoreCase = true) ||
            config.baseUrl.contains("deepseek", ignoreCase = true)

    private fun responseFormat(mode: StructuredMode, schema: StructuredOutputSchema?): JsonObject? = when (mode) {
        StructuredMode.JSON_SCHEMA -> buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject {
                put("name", requireNotNull(schema).name)
                put("strict", true)
                put("schema", schema.schema)
            })
        }
        StructuredMode.JSON_OBJECT -> buildJsonObject { put("type", "json_object") }
        StructuredMode.AUTO, StructuredMode.TOOL_USE, StructuredMode.PREFILL -> null
    }

    private fun fallbackFor(mode: StructuredMode, code: Int, body: String): StructuredMode? {
        if (mode in setOf(StructuredMode.PREFILL, StructuredMode.TOOL_USE) || code !in setOf(400, 404, 415, 422)) return null
        val lowered = body.lowercase()
        val parameterMentioned = when (mode) {
            StructuredMode.JSON_SCHEMA, StructuredMode.AUTO ->
                listOf("response_format", "json_schema", "structured output").any(lowered::contains)
            StructuredMode.JSON_OBJECT ->
                listOf("response_format", "json_object", "json mode").any(lowered::contains)
            StructuredMode.TOOL_USE, StructuredMode.PREFILL -> false
        }
        val explicitlyRejected = listOf(
            "unsupported", "not support", "unknown parameter", "not permitted", "not allowed",
            "extra inputs", "unrecognized", "不支持", "未知参数", "不允许",
        ).any(lowered::contains)
        if (!parameterMentioned || !explicitlyRejected) return null
        return when (mode) {
            StructuredMode.JSON_SCHEMA, StructuredMode.AUTO -> StructuredMode.JSON_OBJECT
            StructuredMode.JSON_OBJECT -> StructuredMode.PREFILL
            StructuredMode.TOOL_USE, StructuredMode.PREFILL -> null
        }
    }
}

@Serializable
private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    @SerialName("response_format") val responseFormat: JsonObject? = null,
    /** OpenRouter 扩展；null 时不进入请求体，兼容端点不会看到未知字段。 */
    val models: List<String>? = null,
    val provider: OpenRouterPreferences? = null,
)

@Serializable
private data class OpenRouterPreferences(
    val sort: String? = null,
    @SerialName("require_parameters") val requireParameters: Boolean? = null,
)

@Serializable
private data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    val refusal: String? = null,
)

@Serializable
private data class OpenAiResponse(val choices: List<OpenAiChoice>, val usage: OpenAiUsage? = null)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
)
