package com.dailynews.pipeline

import com.dailynews.pipeline.fetch.ArticleBodyFetcher
import com.dailynews.pipeline.fetch.FeedFetcher
import com.dailynews.pipeline.ports.RetrievedArticleBody
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.*

class ArticleBodyFetcherTest {
    @Test fun `extracts text without scripts and discloses unicode truncation`() {
        val result = ArticleBodyFetcher.extract("<article><p>正文</p><script>hidden</script><p>second</p></article>")
        assertEquals("正文\nsecond", result.text)
        assertFalse(result.truncated)
        val long = ArticleBodyFetcher.extract("<article><p>${"字".repeat(RetrievedArticleBody.MAX_CHARS - 1)}😀end</p></article>")
        assertTrue(long.truncated)
        assertEquals(RetrievedArticleBody.MAX_CHARS - 1, long.text.length)
        assertFailsWith<IllegalArgumentException> { ArticleBodyFetcher.extract("<script>nothing</script>") }
    }
    @Test fun `only explicit fetch sends request and HTTP denial is not retried`() = runBlocking {
        MockWebServer().use { server ->
            val fetcher = ArticleBodyFetcher(FeedFetcher(OkHttpClient()))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(403))
            assertFails { fetcher.fetch(server.url("/denied").toString()) }
            assertEquals(1, server.requestCount)
        }
    }
}
