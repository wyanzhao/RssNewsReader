package com.dailynews.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface LlmProvider {
    suspend fun complete(request: LlmRequest): LlmResponse
}

@Serializable
data class LlmRequest(
    val model: String,
    val system: String,
    val userContent: String,
    val maxTokens: Int,
    val temperature: Double? = null,
    val jsonMode: Boolean = true,
    val assistantPrefill: String? = null,
    val structuredMode: StructuredMode = StructuredMode.AUTO,
    val responseSchema: StructuredOutputSchema? = null,
)

@Serializable
data class LlmResponse(
    val text: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val stopReason: String? = null,
)

enum class ProviderType { OPENAI_COMPAT, ANTHROPIC }
enum class StructuredMode { AUTO, JSON_SCHEMA, JSON_OBJECT, TOOL_USE, PREFILL }
enum class EditorialRole { EDITOR, DRAFTER }

@Serializable
data class StructuredOutputSchema(val name: String, val schema: JsonObject)

@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKeyAlias: String,
    val supportsJsonMode: Boolean = true,
    val structuredMode: StructuredMode = StructuredMode.AUTO,
)

@Serializable
data class RoleModel(
    val providerId: String,
    val model: String,
    val maxTokens: Int,
)

@Serializable
data class RoleModelMapping(
    val editor: RoleModel,
    val drafter: RoleModel,
)

fun interface ApiKeySource {
    fun read(alias: String): String?
}

class LlmTransportException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : RuntimeException(message, cause)
class LlmProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class StructuredOutputUnsupportedException(
    val mode: StructuredMode,
    val fallbackMode: StructuredMode,
    message: String,
) : RuntimeException(message)

fun LlmResponse.wasTruncated(): Boolean = (
    stopReason
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
    ) in setOf("length", "max_tokens", "max_output_tokens")
