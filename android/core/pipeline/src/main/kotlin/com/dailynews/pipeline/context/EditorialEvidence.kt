package com.dailynews.pipeline.context

/** Raw source artifacts remain intact; only the model's excerpt projection changes. */
fun editorialEvidenceContext(context: Part1ShortlistContext): Part1ShortlistContext {
    val articles = context.articles.map { article ->
        val text = completeExcerptPrefix(article.articleText)
        if (text == article.articleText) article else article.copy(
            articleText = text,
            articleTextTailOmitted = true,
            // A cached summary may have completed the now-withheld fragment.
            cachedSummaryZh = null,
        )
    }
    return context.copy(articles = articles, cacheHits = articles.count { it.cachedSummaryZh != null })
}

/**
 * Explicit trailing ellipsis indicates incomplete extraction. Withhold its last
 * sentence instead of inviting completion. This is not a general sentence parser
 * or semantic fact checker; unmarked truncation cannot be detected here.
 */
internal fun completeExcerptPrefix(text: String): String {
    val trimmed = text.trimEnd()
    if (!trimmed.endsWith("...") && !trimmed.endsWith("…")) return text
    val body = trimmed.trimEnd('.', '…')
    // Require whitespace after Western punctuation, avoiding decimal/version dots.
    val boundary = Regex("[.!?][\"'”’]?\\s+|[。！？]").findAll(body).lastOrNull()
    return boundary?.let { body.substring(0, it.range.last + 1).trimEnd() }.orEmpty()
}
