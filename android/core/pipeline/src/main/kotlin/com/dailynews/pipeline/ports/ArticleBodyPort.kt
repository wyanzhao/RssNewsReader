package com.dailynews.pipeline.ports

/** Explicit user request only; never called by RSS enrichment or report generation. */
fun interface ArticleBodyPort {
    suspend fun fetch(link: String): RetrievedArticleBody
}

data class RetrievedArticleBody(val text: String, val truncated: Boolean) {
    companion object { const val MAX_CHARS = 100_000 }
}
