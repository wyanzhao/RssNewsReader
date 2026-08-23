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
     * 切换类型时：若当前 URL 为空或仍是上一种类型的官方默认地址，就换成新类型的默认；
     * 自定义代理地址保持不动。
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

/** OpenRouter `provider.sort` 取值。`DEFAULT` 表示根本不发这个字段。 */
@Serializable
enum class ProviderSort {
    DEFAULT, THROUGHPUT, PRICE, LATENCY;

    val wire: String? get() = if (this == DEFAULT) null else name.lowercase()
}

/**
 * OpenRouter 路由偏好。
 *
 * 便宜模型在 OpenRouter 上常被路由到低吞吐的提供商，一次 Part 1 生成就能拖到几百
 * 秒——本地把超时调大救不回来，中间网关对非流式长请求还有它自己的超时。这些字段
 * 把选择权交回给我们：按吞吐排序、给主模型备选、只落到真正支持结构化输出的提供商。
 *
 * 只有 [ProviderType.OPENROUTER] 才会把这些字段写进请求体。OpenAI / Anthropic
 * 官方 API 看到未知顶层字段会 400，所以类型本身就是开关。
 */
@Serializable
data class ProviderRouting(
    /** 主模型不可用或超时时依次尝试的备选模型。 */
    @SerialName("model_fallbacks") val modelFallbacks: List<String> = emptyList(),
    val sort: ProviderSort = ProviderSort.DEFAULT,
    /** 只路由到真正支持 `response_format` 的提供商，避免结构化输出被静默忽略。 */
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

@Serializable
data class RoleModel(
    val providerId: String,
    val model: String,
    val maxTokens: Int,
)

/**
 * 角色输出上限。
 *
 * 两个角色都默认取允许区间上限 [MAX_MAX_TOKENS]：截断是硬失败（[wasTruncated] 不重试），
 * 上限只能往宽里估。注意部分便宜模型的 completion 上限远低于此，有的提供商会直接 400，
 * 遇到时在设置里手动调低。
 */
object RoleModelDefaults {
    const val MIN_MAX_TOKENS = 512
    const val MAX_MAX_TOKENS = 65_536
    const val EDITOR_MAX_TOKENS = MAX_MAX_TOKENS
    const val DRAFTER_MAX_TOKENS = MAX_MAX_TOKENS
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
    /** 服务端 `Retry-After` 换算成的毫秒数。有值时它优先于本地退避曲线。 */
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
