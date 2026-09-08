package com.dailynews.pipeline.fetch

import com.dailynews.pipeline.extract.MainTextExtractor
import com.dailynews.pipeline.ports.ArticleBodyPort
import com.dailynews.pipeline.ports.RetrievedArticleBody

/** Uses the composition root's bounded, URL-safe page client; no extra retries. */
class ArticleBodyFetcher(private val fetcher: FeedFetcher) : ArticleBodyPort {
    override suspend fun fetch(link: String): RetrievedArticleBody = extract(
        fetcher.execute(link, "text/html, application/xhtml+xml", 0),
    )

    companion object {
        fun extract(html: String): RetrievedArticleBody {
            val text = MainTextExtractor.extract(html).trim()
            require(text.isNotBlank()) { "网页没有可提取正文；可能需要登录或不支持正文提取" }
            val limit = RetrievedArticleBody.MAX_CHARS
            // Avoid leaving half of a UTF-16 surrogate pair at the truncation boundary.
            val end = if (text.length > limit && text[limit - 1].isHighSurrogate()) limit - 1 else minOf(text.length, limit)
            return RetrievedArticleBody(text.substring(0, end), text.length > end)
        }
    }
}
