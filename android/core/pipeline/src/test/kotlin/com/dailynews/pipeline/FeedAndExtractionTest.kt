package com.dailynews.pipeline

import com.dailynews.pipeline.extract.MainTextExtractor
import com.dailynews.pipeline.parse.FeedParser
import com.dailynews.pipeline.parse.FeedParseException
import com.dailynews.pipeline.parse.OpmlParser
import com.dailynews.pipeline.fetch.ArticlePageEnricher
import com.dailynews.pipeline.fetch.FeedFetcher
import com.dailynews.pipeline.fetch.FeedFetchResult
import com.dailynews.pipeline.fetch.RawSnapshotBuilder
import com.dailynews.model.FeedDefinition
import com.dailynews.model.Article
import com.dailynews.model.PipelineConfig
import com.dailynews.pipeline.orchestrate.NetworkDiagnostics
import com.dailynews.pipeline.orchestrate.NetworkProbeTarget
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/** KEEP: feed parsing, extraction, network classification, and raw-building invariants. */
class FeedAndExtractionTest {
    @Test
    fun `rss fallback priority drops undated items and strips summary html`() {
        val xml = """
            <rss version="2.0"><channel>
              <item><title>A &amp; B</title><link>https://example.com/a</link>
                <pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate>
                <description><![CDATA[<p>Hello&nbsp;<b>world</b></p>]]></description>
              </item>
              <item><title>Undated</title><link>https://example.com/no-date</link></item>
            </channel></rss>
        """.trimIndent()

        val parsed = FeedParser.parse(xml, 300)

        assertEquals(1, parsed.size)
        assertEquals("A & B", parsed.single().title)
        assertEquals("Hello world", parsed.single().summaryEn)
    }

    @Test
    fun `atom prefers alternate link and updated fallback`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom"><entry>
              <title>Atom title</title>
              <link rel="self" href="https://example.com/feed-entry"/>
              <link rel="alternate" href="https://example.com/article"/>
              <updated>2026-04-10T21:43:33Z</updated><summary>Summary</summary>
            </entry></feed>
        """.trimIndent()

        assertEquals("https://example.com/article", FeedParser.parse(xml).single().link)
    }

    @Test
    fun `rdf rss accepts dc date and content encoded`() {
        val xml = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:content="http://purl.org/rss/1.0/modules/content/"
                xmlns="http://purl.org/rss/1.0/">
              <item rdf:about="https://example.com/paper">
                <title>Signal from RDF feed</title><link>https://example.com/paper</link>
                <dc:date>2099-01-01</dc:date>
                <content:encoded><![CDATA[<p>Research summary with <b>markup</b>.</p>]]></content:encoded>
              </item>
            </rdf:RDF>
        """.trimIndent()

        val article = FeedParser.parse(xml).single()
        assertEquals("https://example.com/paper", article.link)
        assertEquals("Research summary with markup.", article.summaryEn)
    }

    @Test
    fun `malformed feed raises a classified parse error`() {
        assertFailsWith<FeedParseException> { FeedParser.parse("<rss><channel>") }
    }

    @Test
    fun `main text extractor prefers article and removes page chrome`() {
        val html = """
            <html><body><nav>navigation poison</nav><article>
              <h1>Headline</h1><p>The first useful paragraph has enough source material.</p>
              <aside>advertisement poison</aside><p>The second useful paragraph continues the story.</p>
            </article><footer>footer poison</footer></body></html>
        """.trimIndent()

        val extracted = MainTextExtractor.extract(html)

        assertTrue("first useful paragraph" in extracted)
        assertTrue("second useful paragraph" in extracted)
        assertFalse("poison" in extracted)
    }

    @Test
    fun `main text extractor emits nested containers and blocks exactly once`() {
        val html = """
            <main><article>
              <h1>Headline</h1>
              <ul><li><p>Nested paragraph.</p></li></ul>
              <blockquote><p>Quoted paragraph.</p></blockquote>
            </article></main>
        """.trimIndent()

        val lines = MainTextExtractor.extract(html).lines()

        assertEquals(listOf("Headline", "Nested paragraph.", "Quoted paragraph."), lines)
    }

    @Test
    fun `nested blocks keep the enclosing block's own text and close inner first`() {
        // Expectations mirror scripts/_common/article_extract.py verbatim: a block
        // is emitted when its end tag closes, carrying only its own character data.
        assertEquals(
            "Nested item\nTop level text",
            MainTextExtractor.extract("<article><ul><li>Top level text<ul><li>Nested item</li></ul></li></ul></article>"),
        )
        assertEquals(
            "Body\nLead-in",
            MainTextExtractor.extract("<article><li>Lead-in <p>Body</p></li></article>"),
        )
        assertEquals(
            "Quoted",
            MainTextExtractor.extract("<article><blockquote><p>Quoted</p></blockquote></article>"),
        )
    }

    @Test
    fun `extraction unescapes entities and collapses unicode whitespace`() {
        assertEquals("A & B C", MainTextExtractor.extract("<article><p>A &amp; B　C</p></article>"))
        assertEquals("", MainTextExtractor.extract("<html></html>"))
    }

    @Test
    fun `truncate words matches the Python word cap`() {
        assertEquals("one two...", MainTextExtractor.truncateWords("one two three", 2))
        assertEquals("one two three", MainTextExtractor.truncateWords("one two three", 3))
        assertEquals("one two three", MainTextExtractor.truncateWords("one two three", 0))
        assertEquals("", MainTextExtractor.truncateWords("", 5))
    }

    @Test
    fun `role main excludes content after the preferred container`() {
        val html = """
            <div><p>Sidebar blurb.</p></div>
            <section role="main"><p>Main story body.</p></section>
            <div><p>Related links.</p></div>
        """.trimIndent()

        assertEquals("Main story body.", MainTextExtractor.extract(html))
    }

    @Test
    fun `empty preferred container falls back to document blocks`() {
        val html = """
            <article><div>No supported block here.</div></article>
            <section><p>Fallback story paragraph.</p></section>
        """.trimIndent()

        assertEquals("Fallback story paragraph.", MainTextExtractor.extract(html))
    }

    @Test
    fun `feed parser allows doctype while refusing external entity expansion`() {
        val xml = """<!DOCTYPE rss SYSTEM "https://invalid.example/external.dtd">
            <rss version="2.0"><channel><item>
              <title>Healthy feed</title><link>https://example.com/a</link>
              <pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate>
              <description>Summary</description>
            </item></channel></rss>
        """.trimIndent()

        assertEquals("Healthy feed", FeedParser.parse(xml).single().title)
    }

    @Test
    fun `feed parser rejects entity declarations before the XML provider can expand them`() {
        val xml = """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel><item>
              <title>&xxe;</title><link>https://example.com/a</link>
              <pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate>
            </item></channel></rss>
        """.trimIndent()

        val error = assertFailsWith<FeedParseException> { FeedParser.parse(xml) }

        assertEquals("XML entity declarations are not allowed", error.message)
    }

    @Test
    fun `prolog comments cannot hide a later entity declaration or cause a false positive`() {
        val malicious = """<!-- decoy <rss> --><!DOCTYPE rss [
            <!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
            ]><rss><channel><item><title>&b;</title><link>https://example.com/a</link><pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate></item></channel></rss>""".trimIndent()
        assertFailsWith<FeedParseException> { FeedParser.parse(malicious) }

        val healthy = """<!-- Security prose quotes <!ENTITY but declares nothing. -->
            <rss><channel><item><title>Healthy</title><link>https://example.com/a</link><pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate></item></channel></rss>""".trimIndent()
        assertEquals("Healthy", FeedParser.parse(healthy).single().title)
    }

    @Test
    fun `article prose that merely quotes an entity declaration still parses`() {
        // Security feeds routinely ship CDATA bodies containing a literal
        // "<!ENTITY". Only the prolog may be scanned, or the whole source dies.
        val xml = """<?xml version="1.0"?>
            <rss version="2.0"><channel><item>
              <title>Explaining XXE</title><link>https://example.com/xxe</link>
              <pubDate>Fri, 10 Apr 2026 21:43:33 GMT</pubDate>
              <description><![CDATA[Attackers embed <!ENTITY xxe SYSTEM "file:///etc/passwd"> in the DTD.]]></description>
            </item></channel></rss>
        """.trimIndent()

        val article = FeedParser.parse(xml).single()

        assertEquals("Explaining XXE", article.title)
        assertEquals("https://example.com/xxe", article.link)
        assertTrue(article.summaryEn.isNotBlank())
    }

    @Test
    fun `opml import surfaces malformed documents as a typed failure`() {
        assertFailsWith<FeedParseException> { OpmlParser.parse("<opml><body><outline") }
        assertFailsWith<FeedParseException> {
            OpmlParser.parse("""<!DOCTYPE opml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><opml><body><outline xmlUrl="&xxe;"/></body></opml>""")
        }
        assertFailsWith<FeedParseException> {
            OpmlParser.parse("""<!-- decoy <opml> --><!DOCTYPE opml [<!ENTITY x "boom">]><opml/>""")
        }
    }

    @Test
    fun `opml import keeps parsing feeds after the shared hardening`() {
        val opml = """<?xml version="1.0"?><opml version="2.0"><body>
            <outline type="rss" text="Example" xmlUrl="https://example.com/feed"/>
        </body></opml>""".trimIndent()

        val feeds = OpmlParser.parse(opml)

        assertEquals(1, feeds.size)
        assertEquals("https://example.com/feed", feeds.single().url)
    }

    @Test
    fun `atom id is the stable link fallback when link is absent`() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title>ID-only item</title><id>tag:example.com,2026:item-1</id>
            <updated>2026-04-10T21:43:33Z</updated><summary>Summary</summary>
        </entry></feed>""".trimIndent()

        assertEquals("tag:example.com,2026:item-1", FeedParser.parse(atom).single().link)
    }

    @Test
    fun `empty meta content yields to the next matching meta`() {
        val html = """<head><meta name="description" content=""><meta name="description" content="Useful summary"></head>"""
        assertEquals("Useful summary", FeedParser.extractHtmlSummary(html, 200))
    }

    @Test
    fun `page enrichment trims link for both fetch and result lookup`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<html><head><meta name="description" content="A longer fallback summary"></head><main><p>Useful article body.</p></main></html>"""))
            server.start()
            val link = server.url("/article").toString()
            val article = Article("Source", "Title", "  $link  ", "2026-08-04 00:00 UTC", "2026-08-04T00:00:00+00:00", "short", "")
            val config = PipelineConfig(summaryEnrichment = com.dailynews.model.SummaryEnrichmentConfig(shortSummaryThreshold = 20))

            val result = ArticlePageEnricher(FeedFetcher(OkHttpClient())).enrich(listOf(article), config).single()

            assertEquals("A longer fallback summary", result.summaryEn)
            assertEquals("Useful article body.", result.articleText)
            assertEquals("/article", server.takeRequest().path)
        }
    }

    @Test
    fun `feed HTTP cancellation cancels the call without entering retry delay`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("delayed body").setBodyDelay(5, TimeUnit.SECONDS),
            )
            server.start()
            val fetcher = FeedFetcher(OkHttpClient())
            val started = System.nanoTime()

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) { fetcher.execute(server.url("/slow").toString(), "text/plain", retries = 2) }
            }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsedMillis < 1_000, "cancellation took ${elapsedMillis}ms")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `raw runtime config contains only the Python compatible snapshot subset`() {
        val feed = FeedDefinition("Source", "https://example.test/feed")
        val article = Article("Source", "Title", "https://example.test/a", "2026-08-04 00:00 UTC", "2026-08-04T00:00:00+00:00", "summary", "body")
        val config = PipelineConfig(editorFeedback = listOf("private feedback"), monthlyTokenBudget = 123, maxLlmCallsPerRun = 77)
        val raw = RawSnapshotBuilder.build(
            listOf(feed),
            listOf(FeedFetchResult(feed, listOf(article))),
            listOf(article),
            Instant.parse("2026-08-04T00:00:00Z"),
            "run",
            config,
        )

        val snapshot = requireNotNull(raw.runtimeConfig)
        assertEquals(setOf("config_path", "fetch", "summary_enrichment", "article_text", "render", "context_budget"), snapshot.keys)
        assertFalse("editor_feedback" in snapshot.toString())
        assertFalse("monthly_token_budget" in snapshot.toString())
    }

    @Test
    fun `network diagnostic gate recognizes underlying network exception types`() {
        assertTrue(NetworkDiagnostics.evidenceWarrantsProbe(UnknownHostException("unusual platform wording")))
    }

    @Test
    fun `network diagnostic gate recognizes Android DNS message variants`() {
        listOf(
            "Unable to resolve host api.deepseek.com",
            "No address associated with hostname",
            "java.net.UnknownHostException: api.deepseek.com",
        ).forEach { evidence ->
            assertTrue(NetworkDiagnostics.evidenceWarrantsProbe(evidence), evidence)
            assertTrue(NetworkDiagnostics.evidenceWarrantsDelayedRetry(IllegalStateException(evidence)), evidence)
        }
    }

    @Test
    fun `provider probe treats an unauthenticated HTTP response as reachable`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))

            val probes = NetworkDiagnostics(OkHttpClient()).run(
                feeds = emptyList(),
                providerTargets = listOf(NetworkProbeTarget("LLM provider test", server.url("/v1").toString())),
            )

            assertEquals(listOf("dns", "tcp", "https"), probes.map { it.stage })
            assertTrue(probes.all { it.target == "LLM provider test" })
            assertTrue(probes.last().passed)
            assertEquals("HTTP 401", probes.last().detail)
        }
    }

    @Test
    fun `opml export import round trips names urls and order`() {
        val feeds = listOf(
            FeedDefinition("A & News", "https://example.com/a?x=1&y=2", position = 0),
            FeedDefinition("B", "https://example.com/b", position = 1),
        )

        val parsed = OpmlParser.parse(OpmlParser.render(feeds))

        assertEquals(feeds.map { it.name to it.url }, parsed.map { it.name to it.url })
    }
}
