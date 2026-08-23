package com.dailynews.llm

import java.net.URI

/**
 * OpenRouter 作为一等 provider 时的默认值。
 *
 * 官方 chat/completions 是 OpenAI 兼容的，但网关另有一套路由字段和归因头。
 * 选了 OpenRouter 就应该直接带上它们，而不是让用户先填兼容端点再去翻三个
 * 「其他端点请保持默认」的隐藏控件。
 */
object OpenRouterDefaults {
    const val BASE_URL = "https://openrouter.ai/api/v1"
    const val HTTP_REFERER = "https://github.com/wyanzhao/RssNewsReader"
    const val APP_TITLE = "DailyNews"
    val ROUTING = ProviderRouting(
        sort = ProviderSort.THROUGHPUT,
        requireParameters = true,
    )

    fun looksLikeHost(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase() ?: return false
        return host == "openrouter.ai" || host.endsWith(".openrouter.ai")
    }
}

/**
 * 把旧的「OPENAI_COMPAT + openrouter.ai URL」提升为 OPENROUTER。
 *
 * 路由全默认的旧配置会带上吞吐排序和 require_parameters——这正是当时不得不
 * 手开、而多数人没开的那两个开关。已经显式配过的路由原样保留。
 */
fun ProviderConfig.canonicalize(): ProviderConfig {
    val openRouterFromCompat = type == ProviderType.OPENAI_COMPAT && OpenRouterDefaults.looksLikeHost(baseUrl)
    if (!openRouterFromCompat && type != ProviderType.OPENROUTER) {
        return if (routing.isDefault) this else copy(routing = ProviderRouting())
    }
    val nextRouting = if (routing.isDefault) OpenRouterDefaults.ROUTING else routing.normalized()
    return copy(
        type = ProviderType.OPENROUTER,
        supportsJsonMode = true,
        routing = nextRouting,
    )
}

fun List<ProviderConfig>.canonicalizeProviders(): List<ProviderConfig> = map { it.canonicalize() }

/**
 * 请求体里实际发出的路由。非 OpenRouter 一律清空；OpenRouter 在用户没改过时
 * 使用 [OpenRouterDefaults.ROUTING]。
 */
fun ProviderRouting.forTransport(type: ProviderType): ProviderRouting {
    if (type != ProviderType.OPENROUTER) return ProviderRouting()
    val normalized = normalized()
    return if (normalized.isDefault) OpenRouterDefaults.ROUTING else normalized
}
