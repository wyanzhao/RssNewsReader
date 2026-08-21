package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.PipelineConfig
import com.dailynews.pipeline.extract.MainTextExtractor
import com.dailynews.pipeline.parse.FeedParser
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class FeedFetchResult(
    val feed: FeedDefinition,
    val articles: List<Article>,
    val error: String? = null,
    val newestItemDate: String? = null,
)

class FeedFetcher(
    private val client: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fetchAll(feeds: List<FeedDefinition>, config: PipelineConfig): List<FeedFetchResult> = coroutineScope {
        val gate = Semaphore(8)
        feeds.map { feed -> async { gate.withPermit { fetchOne(feed, config) } } }.awaitAll()
    }

    suspend fun fetchOne(feed: FeedDefinition, config: PipelineConfig): FeedFetchResult {
        val response = try {
            execute(feed.url, "application/rss+xml, application/atom+xml, application/xml, text/xml", 2)
        } catch (error: HttpStatusException) {
            return FeedFetchResult(feed, emptyList(), "HTTP ${error.code}")
        } catch (_: SocketTimeoutException) {
            return FeedFetchResult(feed, emptyList(), "Connection failed - timed out")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return FeedFetchResult(feed, emptyList(), "Connection failed - ${error.message ?: error::class.simpleName}")
        }
        val parsed = try {
            FeedParser.parse(response, config.fetch.maxSummary)
        } catch (error: Exception) {
            return FeedFetchResult(feed, emptyList(), error.message ?: "XML parse failed")
        }
        val newest = parsed.maxOfOrNull { it.publishedAt }?.toOffsetIso()
        val cutoff = clock.instant().minusSeconds(config.fetch.hours * 3_600L)
        val articles = parsed.filter { !it.publishedAt.isBefore(cutoff) }.map { item ->
            Article(
                feed.name,
                item.title,
                item.link,
                UTC_DISPLAY.format(item.publishedAt),
                item.publishedAt.toOffsetIso(),
                item.summaryEn,
                "",
            )
        }
        return FeedFetchResult(feed, articles, null, newest)
    }

    internal suspend fun execute(url: String, accept: String, retries: Int): String {
        var last: Exception? = null
        repeat(retries + 1) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (compatible; RSS Monitor/3.0)")
                    .header("Accept", accept)
                    .build()
                return executeOnce(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BodyTooLargeException) {
                // 重发不会让响应变小。与 StructuredLlm 对截断的处理同一条原则：
                // 参数不变的重试是确定性失败，只是把等待时间乘以三。
                throw error
            } catch (error: Exception) {
                last = error
                if (attempt < retries) delay(1_000)
            }
        }
        throw last ?: IOException("request failed without an error")
    }

    private suspend fun executeOnce(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw HttpStatusException(it.code)
                        val body = readBounded(it)
                        if (continuation.isActive) continuation.resume(body)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    /**
     * 限长读取响应体。
     *
     * 此前是裸 `body.string()`，没有任何上限。OkHttp 自己加 `Accept-Encoding: gzip`
     * 并透明解压，所以约 1 MB 的 gzip 炸弹会在单个 Buffer 里展开成 GB 级——而 feed 有
     * 8 路并发、文章页还有若干路，放大是并行的。任何一个订阅源都能就这样杀掉后台
     * 进程，留下一行永久 RUNNING 且毫无诊断线索。
     *
     * 先看 `Content-Length` 短路，再按上限读，两道都要：前者省掉整次传输，后者兜住
     * 分块编码（不报长度）与谎报长度的情况。
     */
    private fun readBounded(response: Response): String {
        val body = response.body ?: return ""
        val declared = body.contentLength()
        if (declared > MAX_BODY_BYTES) throw BodyTooLargeException(declared)
        val source = body.source()
        // 多读一个字节就能区分"正好到上限"和"被截断"。
        source.request(MAX_BODY_BYTES + 1)
        val buffered = source.buffer
        if (buffered.size > MAX_BODY_BYTES) throw BodyTooLargeException(buffered.size)
        return buffered.readString(body.contentType()?.charset() ?: Charsets.UTF_8)
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** 响应体超过上限。不可重试：同一个请求只会得到同样大的响应。 */
    internal class BodyTooLargeException(bytes: Long) :
        IOException("response body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MiB (got $bytes bytes)")

    private companion object {
        val UTC_DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

        /**
         * 单个响应体上限（解压后）。
         *
         * 8 MB 远高于任何真实 RSS feed 或文章页，又低到即使几路并发同时撞上限也不会
         * 逼近进程堆。这条路径同时服务 feed 与文章页富化，所以取两者里更宽的那个需求。
         */
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
    }
}

private fun Instant.toOffsetIso(): String = toString().removeSuffix("Z") + "+00:00"
