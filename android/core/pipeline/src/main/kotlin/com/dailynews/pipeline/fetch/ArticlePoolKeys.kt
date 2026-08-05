package com.dailynews.pipeline.fetch

import com.dailynews.model.Article
import com.dailynews.pipeline.text.TextUtils
import java.security.MessageDigest

/** Stable storage identity that preserves distinct linkless feed items. */
object ArticlePoolKeys {
    fun key(article: Article): String = TextUtils.dedupLinkKey(article.link).ifBlank {
        val material = listOf(article.source, article.title, article.pubDateIso).joinToString("\u0000")
        "linkless:" + MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
