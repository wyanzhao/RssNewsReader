package com.dailynews.llm

import java.net.URI

fun redactProviderText(value: String, vararg secrets: String?): String {
    var redacted = value.replace(
        Regex("(?i)\\b(?:sk|rk|pk)-[A-Za-z0-9_-]{6,}"),
        "<redacted-api-key>",
    )
    secrets.filterNotNull().filter { it.length >= 4 }.distinct().sortedByDescending(String::length).forEach { secret ->
        redacted = redacted.replace(secret, "<redacted-api-key>")
    }
    return redacted
}

object ProviderEndpoints {
    fun openAi(baseUrl: String): String = endpoint(baseUrl, "chat/completions")
    fun anthropic(baseUrl: String): String = endpoint(baseUrl, "messages")

    private fun endpoint(baseUrl: String, suffix: String): String {
        var base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "provider base URL must use http or https" }
        while (base.endsWith("/v1/v1", ignoreCase = true)) base = base.dropLast(3)
        val fullSuffix = "/v1/$suffix"
        if (base.endsWith(fullSuffix, ignoreCase = true)) return base
        if (base.endsWith("/$suffix", ignoreCase = true)) return base
        val path = runCatching { URI(base).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return when {
            base.endsWith("/v1", ignoreCase = true) -> "$base/$suffix"
            path.isEmpty() -> "$base$fullSuffix"
            else -> "$base/$suffix"
        }
    }
}
