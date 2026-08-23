package com.dailynews.llm

import java.net.URI

/**
 * Defaults when OpenRouter is used as a first-class provider.
 *
 * The official chat/completions is OpenAI-compatible, but the gateway has its own set
 * of routing fields and attribution headers. Choosing OpenRouter should ship them
 * directly, instead of making the user fill in the compat endpoint first and then dig
 * through three hidden controls labeled "please keep the other endpoints at their
 * defaults".
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
 * Promotes legacy "OPENAI_COMPAT + openrouter.ai URL" configs to OPENROUTER.
 *
 * Old configs with all-default routing pick up throughput sorting and
 * require_parameters — exactly the two switches that had to be enabled by hand back
 * then and that most people never enabled. Explicitly configured routing is kept as-is.
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
 * The routing actually sent in the request body. Cleared for anything other than
 * OpenRouter; OpenRouter uses [OpenRouterDefaults.ROUTING] when the user has not
 * changed it.
 */
fun ProviderRouting.forTransport(type: ProviderType): ProviderRouting {
    if (type != ProviderType.OPENROUTER) return ProviderRouting()
    val normalized = normalized()
    return if (normalized.isDefault) OpenRouterDefaults.ROUTING else normalized
}
