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
                        val body = it.body?.string().orEmpty()
                        if (continuation.isActive) continuation.resume(body)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private companion object {
        val UTC_DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}

private fun Instant.toOffsetIso(): String = toString().removeSuffix("Z") + "+00:00"
