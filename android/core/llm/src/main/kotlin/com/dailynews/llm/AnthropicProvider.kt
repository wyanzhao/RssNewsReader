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

class AnthropicProvider(
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
        val schema = request.responseSchema
        val usesNativeSchema = mode == StructuredMode.JSON_SCHEMA && schema != null
        val usesTool = mode == StructuredMode.TOOL_USE && schema != null
        val payload = AnthropicRequest(
            model = request.model,
            system = request.system,
            messages = listOf(AnthropicMessage("user", request.userContent)),
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            outputConfig = if (usesNativeSchema) {
                AnthropicOutputConfig(AnthropicJsonFormat(type = "json_schema", schema = schema.schema))
            } else {
                null
            },
            tools = if (usesTool) listOf(AnthropicTool(TOOL_NAME, "Return the requested JSON object.", schema.schema)) else null,
            toolChoice = if (usesTool) buildJsonObject { put("type", "tool"); put("name", TOOL_NAME) } else null,
        )
        val endpoint = ProviderEndpoints.anthropic(config.baseUrl)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = executeLlmHttp(client, httpRequest, "Anthropic provider ${config.id}")
        val body = response.body
        if (!response.isSuccessful) {
                val safeBody = redactProviderText(body.take(500), apiKey)
                fallbackFor(mode, response.code, body)?.let { fallback ->
                    negotiatedStructuredMode = fallback
                    throw StructuredOutputUnsupportedException(
                        mode,
                        fallback,
                        "Anthropic $mode is unsupported (HTTP ${response.code}): $safeBody",
                    )
                }
                throw LlmTransportException(
                    "Anthropic HTTP ${response.code}: $safeBody",
                    retryable = response.code == 429 || response.code in 500..599,
                    retryAfterMillis = response.retryAfterMillis,
                )
        }
        val decoded = try {
            json.decodeFromString<AnthropicResponse>(body)
        } catch (error: Exception) {
            throw LlmProtocolException("invalid Anthropic response", error)
        }
        val toolInput = decoded.content.firstOrNull { block -> block.type == "tool_use" }?.input
        val content = toolInput?.let(json::encodeToString)
            ?: decoded.content.filter { block -> block.type == "text" }.joinToString("") { block -> block.text }
        if (decoded.stopReason.equals("refusal", ignoreCase = true)) {
            throw LlmProtocolException("Anthropic response was refused")
        }
        if (content.isBlank()) throw LlmProtocolException("Anthropic response contains no text block")
        LlmResponse(
            text = content,
            inputTokens = decoded.usage?.inputTokens,
            outputTokens = decoded.usage?.outputTokens,
            stopReason = decoded.stopReason,
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val TOOL_NAME = "emit_json"
    }

    private fun effectiveMode(request: LlmRequest): StructuredMode {
        val requested = request.structuredMode.takeUnless { it == StructuredMode.AUTO }
            ?: negotiatedStructuredMode
            ?: config.structuredMode
        return when {
            !request.jsonMode -> StructuredMode.PREFILL
            requested == StructuredMode.AUTO && request.responseSchema != null -> StructuredMode.JSON_SCHEMA
            requested == StructuredMode.AUTO -> StructuredMode.PREFILL
            requested == StructuredMode.JSON_SCHEMA && request.responseSchema == null -> StructuredMode.PREFILL
            requested == StructuredMode.TOOL_USE && request.responseSchema == null -> StructuredMode.PREFILL
            else -> requested
        }
    }

    private fun fallbackFor(mode: StructuredMode, code: Int, body: String): StructuredMode? {
        if (code !in setOf(400, 404, 415, 422)) return null
        val lowered = body.lowercase()
        val parameterMentioned = when (mode) {
            StructuredMode.JSON_SCHEMA ->
                listOf("output_config", "json_schema", "structured output").any(lowered::contains)
            StructuredMode.TOOL_USE ->
                listOf("tool_choice", "input_schema", "tools.", "tool use").any(lowered::contains)
            StructuredMode.AUTO, StructuredMode.JSON_OBJECT, StructuredMode.PREFILL -> false
        }
        val explicitlyRejected = listOf(
            "unsupported", "not support", "unknown", "not permitted", "not allowed", "extra inputs",
            "unrecognized", "不支持", "未知参数", "不允许",
        ).any(lowered::contains)
        if (!parameterMentioned || !explicitlyRejected) return null
        return when (mode) {
            StructuredMode.JSON_SCHEMA -> StructuredMode.TOOL_USE
            StructuredMode.TOOL_USE -> StructuredMode.PREFILL
            StructuredMode.AUTO, StructuredMode.JSON_OBJECT, StructuredMode.PREFILL -> null
        }
    }
}

@Serializable
private data class AnthropicRequest(
    val model: String,
    val system: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    val tools: List<AnthropicTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonObject? = null,
)

@Serializable
private data class AnthropicOutputConfig(val format: AnthropicJsonFormat)

@Serializable
private data class AnthropicJsonFormat(
    val type: String,
    val schema: JsonObject,
)

@Serializable
private data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContent>,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicContent(val type: String, val text: String = "", val input: JsonObject? = null)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
)
