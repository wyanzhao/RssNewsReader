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
    /** 服务端 `Retry-After` 换算成的毫秒数，超出上限时截断。 */
    val retryAfterMillis: Long? = null,
)

/** 退避封顶。限流窗口再长也不该让一次运行在这里静坐几分钟。 */
internal const val MAX_RETRY_AFTER_MILLIS = 60_000L

/**
 * 完整的 HTTP 往返，**可取消**，包含响应体读取。
 *
 * 两点缺一不可：
 *
 * 1. 体读取必须留在这个边界内。OkHttp 可能先返回响应头，之后才在
 *    `ResponseBody.string()` 里撞上 socket 超时；两个阶段都在这里，结构化重试层才
 *    能一致地看到每一次瞬时传输故障。
 * 2. 必须走 `enqueue` + `invokeOnCancellation`，不能用阻塞的 `execute()`。此前用的是
 *    后者，于是 `withTimeout` 的看门狗、WorkManager 的 `onStopped()` 全都停不掉一次
 *    在途的 LLM 调用——协程要等 socket 自己返回（最长 `callTimeout`，默认 1200 秒）
 *    才会解开。看门狗因此不是 20 分钟，而是「20 分钟 + 最长再 20 分钟」，且记录
 *    `stopped` 的那段 `NonCancellable` 代码往往等不到执行机会，`runs` 表就留下一行
 *    永久 RUNNING。同仓库的 `FeedFetcher.executeOnce` 一直是这么写的，这里对齐它。
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
 * 只认整秒形式的 `Retry-After`。
 *
 * RFC 允许 HTTP-date，但 LLM 网关实际发的都是秒数；把日期形式当作「没给」比猜错
 * 一个时区安全——猜错会让退避要么退化成立即重试，要么变成一次几小时的静坐。
 */
private fun retryAfterMillis(response: Response): Long? =
    response.header("Retry-After")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.let { (it * 1_000).coerceAtMost(MAX_RETRY_AFTER_MILLIS) }
