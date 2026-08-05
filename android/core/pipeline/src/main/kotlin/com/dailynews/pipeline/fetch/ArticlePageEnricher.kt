package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.model.PipelineConfig
import com.dailynews.pipeline.extract.MainTextExtractor
import com.dailynews.pipeline.parse.FeedParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ArticlePageEnricher(private val fetcher: FeedFetcher) {
    suspend fun enrich(articles: List<Article>, config: PipelineConfig): List<Article> = coroutineScope {
        val needs = articles.groupBy { it.link.trim() }.filterKeys(String::isNotEmpty).mapValues { (_, matches) ->
            matches.any { it.summaryEn.trim().length < config.summaryEnrichment.shortSummaryThreshold } to
                (config.articleText.enabled && matches.any { it.articleText.isBlank() })
        }.filterValues { it.first || it.second }
        val gate = Semaphore(config.articleText.maxWorkers.coerceIn(1, 16))
        val fallbackLimit = minOf(config.fetch.maxSummary, config.summaryEnrichment.pageFallbackCap)
        val results = needs.entries.map { (link, flags) ->
            async {
                gate.withPermit {
                    val html = try {
                        fetcher.execute(link, "text/html, application/xhtml+xml", 1)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ""
                    }
                    link to if (html.isBlank()) {
                        "" to ""
                    } else {
                        val summary = if (flags.first) FeedParser.extractHtmlSummary(html, fallbackLimit) else ""
                        val body = if (flags.second) MainTextExtractor.truncateWords(MainTextExtractor.extract(html), config.articleText.maxWords) else ""
                        summary to body
                    }
                }
            }
        }.awaitAll().toMap()
        articles.map { article ->
            val (summary, body) = results[article.link.trim()] ?: ("" to "")
            val current = article.summaryEn.trim()
            article.copy(
                summaryEn = if (current.length < config.summaryEnrichment.shortSummaryThreshold && summary.trim().length > current.length) summary.trim() else current,
                articleText = if (config.articleText.enabled && article.articleText.isBlank()) body.trim() else article.articleText,
            )
        }
    }
}
