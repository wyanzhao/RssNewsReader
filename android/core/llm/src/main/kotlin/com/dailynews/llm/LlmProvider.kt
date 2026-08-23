package com.dailynews.llm

import kotlinx.serialization.SerialName
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
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
)

@Serializable
data class LlmResponse(
    val text: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val stopReason: String? = null,
)

enum class ProviderType {
    OPENROUTER,
    OPENAI_COMPAT,
    ANTHROPIC,
    ;

    val displayLabel: String get() = when (this) {
        OPENROUTER -> "OpenRouter"
        OPENAI_COMPAT -> "OpenAI"
        ANTHROPIC -> "Anthropic"
    }

    val defaultBaseUrl: String get() = when (this) {
        OPENROUTER -> OpenRouterDefaults.BASE_URL
        OPENAI_COMPAT -> "https://api.openai.com/v1"
        ANTHROPIC -> "https://api.anthropic.com"
    }

    val usesOpenAiCompatApi: Boolean get() = this != ANTHROPIC
    val usesOpenRouterProtocol: Boolean get() = this == OPENROUTER

    fun chatEndpoint(baseUrl: String): String {
        val resolved = baseUrl.trim().ifBlank { defaultBaseUrl }
        return if (usesOpenAiCompatApi) ProviderEndpoints.openAi(resolved) else ProviderEndpoints.anthropic(resolved)
    }

    /**
     * When switching types: if the current URL is blank or still the previous type's
     * official default address, swap in the new type's default; custom proxy addresses
     * stay untouched.
     */
    fun adjustedBaseUrl(previousType: ProviderType, currentBaseUrl: String): String {
        val trimmed = currentBaseUrl.trim()
        return if (trimmed.isEmpty() || trimmed.trimEnd('/') == previousType.defaultBaseUrl.trimEnd('/')) {
            defaultBaseUrl
        } else {
            trimmed
        }
    }

    fun defaultRouting(): ProviderRouting =
        if (this == OPENROUTER) OpenRouterDefaults.ROUTING else ProviderRouting()

    fun defaultSupportsJsonMode(): Boolean = this != ANTHROPIC
}

enum class StructuredMode { AUTO, JSON_SCHEMA, JSON_OBJECT, TOOL_USE, PREFILL }
enum class EditorialRole { EDITOR, DRAFTER }

@Serializable
data class StructuredOutputSchema(val name: String, val schema: JsonObject)

/** Value for OpenRouter's `provider.sort`. `DEFAULT` means the field is not sent at all. */
@Serializable
enum class ProviderSort {
    DEFAULT, THROUGHPUT, PRICE, LATENCY;

    val wire: String? get() = if (this == DEFAULT) null else name.lowercase()
}

/**
 * OpenRouter routing preferences.
 *
 * Cheap models on OpenRouter are often routed to low-throughput providers, and a single
 * Part 1 generation can drag on for hundreds of seconds — raising the local timeout
 * does not save it, because the intermediary gateway has its own timeout for
 * non-streaming long requests. These fields hand the choice back to us: sort by
 * throughput, give the primary model fallbacks, and land only on providers that truly
 * support structured output.
 *
 * Only [ProviderType.OPENROUTER] writes these fields into the request body. The
 * official OpenAI / Anthropic APIs 400 on unknown top-level fields, so the type itself
 * acts as the switch.
 */
@Serializable
data class ProviderRouting(
    /** Fallback models tried in order when the primary model is unavailable or times out. */
    @SerialName("model_fallbacks") val modelFallbacks: List<String> = emptyList(),
    val sort: ProviderSort = ProviderSort.DEFAULT,
    /** Route only to providers that truly support `response_format`, so structured output is not silently ignored. */
    @SerialName("require_parameters") val requireParameters: Boolean = false,
) {
    val isDefault: Boolean get() = modelFallbacks.isEmpty() && sort == ProviderSort.DEFAULT && !requireParameters

    fun normalized(): ProviderRouting =
        copy(modelFallbacks = modelFallbacks.map(String::trim).filter(String::isNotEmpty).distinct())
}

@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKeyAlias: String,
    val supportsJsonMode: Boolean = true,
    val structuredMode: StructuredMode = StructuredMode.AUTO,
    val routing: ProviderRouting = ProviderRouting(),
)

/**
 * Reasoning-effort gears.
 *
 * Wire values align with OpenRouter's unified set: `minimal` / `low` / `medium` /
 * `high` / `xhigh` / `max`. [NONE] omits the field from the request body, for models
 * that do not support reasoning. The user-visible role default is [LOW].
 */
@Serializable
enum class ReasoningEffort {
    NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX;

    /** The lowercase value written into the request body; null for [NONE], meaning the field is omitted. */
    val wire: String? get() = if (this == NONE) null else name.lowercase()

    val displayLabel: String get() = when (this) {
        NONE -> "关闭"
        MINIMAL -> "最低"
        LOW -> "低"
        MEDIUM -> "中"
        HIGH -> "高"
        XHIGH -> "极高"
        MAX -> "最大"
    }

    val menuLabel: String get() = "${displayLabel}（${wire ?: "none"}）"
}

@Serializable
data class RoleModel(
    val providerId: String,
    val model: String,
    val maxTokens: Int,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.LOW,
)

/**
 * Per-role output caps.
 *
 * Both roles default to the allowed range's upper bound [MAX_MAX_TOKENS]: truncation is
 * a hard failure ([wasTruncated] is not retried), so the cap can only be estimated on
 * the generous side. Note that some cheap models have completion caps far below this,
 * and some providers will just return 400 — lower it manually in settings when that
 * happens.
 */
object RoleModelDefaults {
    const val MIN_MAX_TOKENS = 512
    const val MAX_MAX_TOKENS = 65_536
    const val EDITOR_MAX_TOKENS = MAX_MAX_TOKENS
    const val DRAFTER_MAX_TOKENS = MAX_MAX_TOKENS
    val REASONING_EFFORT = ReasoningEffort.LOW
}

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
    /** Milliseconds converted from the server's `Retry-After`. When present, it takes precedence over the local backoff curve. */
    val retryAfterMillis: Long? = null,
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
