package com.dailynews.llm

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class LlmHttpResponse(
    val code: Int,
    val isSuccessful: Boolean,
    val body: String,
    /** Milliseconds converted from the server's `Retry-After`, clamped when over the cap. */
    val retryAfterMillis: Long? = null,
)

/** Backoff cap. No matter how long the rate-limit window is, one run should not sit idle here for minutes. */
internal const val MAX_RETRY_AFTER_MILLIS = 60_000L

/**
 * The full HTTP round trip, **cancellable**, including the response-body read.
 *
 * Two points are non-negotiable:
 *
 * 1. The body read must stay inside this boundary. OkHttp may return the response
 *    headers first and only hit a socket timeout later inside `ResponseBody.string()`;
 *    with both phases here, the structured-retry layer sees every transient transport
 *    failure consistently.
 * 2. It must use `enqueue` + `invokeOnCancellation`, not the blocking `execute()`. The
 *    code previously used the latter, so neither the `withTimeout` watchdog nor
 *    WorkManager's `onStopped()` could stop an in-flight LLM call — the coroutine only
 *    unblocked once the socket returned on its own (up to `callTimeout`, default 1200
 *    seconds). The watchdog therefore became "20 minutes + up to another 20 minutes"
 *    instead of 20 minutes, and the `NonCancellable` block that records `stopped` often
 *    never got a chance to run, leaving a permanently RUNNING row in the `runs` table.
 *    `FeedFetcher.executeOnce` in this repo has always been written this way; this
 *    aligns with it.
 */
internal suspend fun executeLlmHttp(
    client: OkHttpClient,
    request: Request,
    providerDescription: String,
): LlmHttpResponse = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }

    fun failTransport(error: IOException) {
        if (!continuation.isActive) return
        val detail = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
        continuation.resumeWithException(
            LlmTransportException("$providerDescription transport failed: $detail", error, retryable = true),
        )
    }

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) = failTransport(error)

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    val payload = LlmHttpResponse(
                        code = it.code,
                        isSuccessful = it.isSuccessful,
                        body = it.body?.string().orEmpty(),
                        retryAfterMillis = retryAfterMillis(it),
                    )
                    if (continuation.isActive) continuation.resume(payload)
                }
            } catch (error: IOException) {
                failTransport(error)
            }
        }
    })
}

/**
 * Only whole-second `Retry-After` values are honored.
 *
 * The RFC allows HTTP-date, but LLM gateways in practice always send seconds; treating
 * the date form as "not given" is safer than guessing a timezone wrong — a wrong guess
 * makes the backoff either degenerate into an immediate retry or turn into a multi-hour
 * sit-and-wait.
 */
private fun retryAfterMillis(response: Response): Long? =
    response.header("Retry-After")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let { (it * 1_000).coerceAtMost(MAX_RETRY_AFTER_MILLIS) }
