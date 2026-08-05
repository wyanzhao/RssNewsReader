package com.dailynews.llm

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class LlmHttpResponse(
    val code: Int,
    val isSuccessful: Boolean,
    val body: String,
)

/**
 * Owns the complete blocking HTTP exchange, including response-body reads.
 * OkHttp can return response headers before a later socket timeout occurs in
 * ResponseBody.string(); keeping both phases inside this boundary ensures the
 * structured retry layer sees every transient transport failure consistently.
 */
internal fun executeLlmHttp(
    client: OkHttpClient,
    request: Request,
    providerDescription: String,
): LlmHttpResponse = try {
    client.newCall(request).execute().use { response ->
        LlmHttpResponse(
            code = response.code,
            isSuccessful = response.isSuccessful,
            body = response.body?.string().orEmpty(),
        )
    }
} catch (error: IOException) {
    val detail = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
    throw LlmTransportException(
        "$providerDescription transport failed: $detail",
        error,
        retryable = true,
    )
}
