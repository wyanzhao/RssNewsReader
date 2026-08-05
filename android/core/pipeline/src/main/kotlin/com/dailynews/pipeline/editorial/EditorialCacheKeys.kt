package com.dailynews.pipeline.editorial

import com.dailynews.model.Article
import com.dailynews.pipeline.text.TextUtils
import java.security.MessageDigest

object EditorialCacheKeys {
    fun cacheKey(article: Article): String = sha256("v2\u0000${TextUtils.cleanText(article.link)}")

    fun legacyCacheKey(article: Article): String = sha256(
        listOf(article.link, article.summaryEn, article.articleText).joinToString("\u0000") { TextUtils.cleanText(it) },
    )

    fun eventKey(explicit: String?, title: String): String {
        val value = TextUtils.cleanText(explicit)
        if (value.isNotEmpty()) return value
        return TextUtils.cleanText(title).lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(120)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
