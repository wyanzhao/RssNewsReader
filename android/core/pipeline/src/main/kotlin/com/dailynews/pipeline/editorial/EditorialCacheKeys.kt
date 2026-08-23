package com.dailynews.pipeline.editorial

import com.dailynews.model.Article
import com.dailynews.pipeline.text.TextUtils
import java.security.MessageDigest

object EditorialCacheKeys {
    fun cacheKey(article: Article): String = sha256("v2\u0000${TextUtils.cleanText(article.link)}")

    fun legacyCacheKey(article: Article): String = sha256(
        listOf(article.link, article.summaryEn, article.articleText).joinToString("\u0000") { TextUtils.cleanText(it) },
    )

    /** Hard cap for event-lead ids. Over-long keys only bloat the next day's prompt without adding more discriminating power. */
    const val EVENT_KEY_MAX_CHARS = 120

    /**
     * event_key is a **total function**: every (title, link) yields a non-empty, stable lead id.
     * Returning an empty string used to be the norm — the old implementation collapsed titles on `[^a-z0-9]+`,
     * so every Chinese title collapsed to the empty string and all Chinese sources shared the same "empty" lead.
     * Callers (report_items.eventKey in Room v8) rely on non-emptiness to merge leads, so this function must
     * fall back all the way down.
     */
    fun eventKey(explicit: String?, title: String, link: String): String {
        val provided = sanitizeEventKey(explicit)
        if (provided.isNotEmpty()) return provided
        val slug = slugify(title)
        if (slug.isNotEmpty()) return slug
        return "h-" + sha256(TextUtils.cleanText(link)).take(16)
    }

    /**
     * event_key is produced by the LLM and then injected verbatim back into the next day's prompt
     * (cached_event_key / recent_top30). This is the only narrowing point on that loop: reject anything
     * link-shaped, pin the length.
     */
    fun sanitizeEventKey(raw: String?): String {
        val value = TextUtils.cleanText(raw)
        if (value.isEmpty()) return ""
        if (value.contains("http", ignoreCase = true) || value.contains("](")) return ""
        return value.take(EVENT_KEY_MAX_CHARS).trim()
    }

    /**
     * Character-by-character implementation instead of regex: Android ICU and the JVM do not always agree on
     * regex character-class semantics (this is exactly how R10's `(?U)` blew up). ASCII letters and digits are
     * preserved verbatim, so pure-English titles stay byte-for-byte equivalent to the old implementation;
     * non-ASCII letters and digits such as CJK are kept too, instead of collapsing to the empty string.
     */
    private fun slugify(title: String): String {
        val cleaned = TextUtils.cleanText(title).lowercase()
        val slug = buildString(cleaned.length) {
            var pendingSeparator = false
            for (character in cleaned) {
                if (character.isLetterOrDigit()) {
                    if (pendingSeparator && isNotEmpty()) append('-')
                    pendingSeparator = false
                    append(character)
                } else {
                    pendingSeparator = true
                }
            }
        }
        return slug.take(EVENT_KEY_MAX_CHARS).trim('-')
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
