package com.dailynews.pipeline.editorial

import com.dailynews.model.Article
import com.dailynews.pipeline.text.TextUtils
import java.security.MessageDigest

object EditorialCacheKeys {
    fun cacheKey(article: Article): String = sha256("v2\u0000${TextUtils.cleanText(article.link)}")

    fun legacyCacheKey(article: Article): String = sha256(
        listOf(article.link, article.summaryEn, article.articleText).joinToString("\u0000") { TextUtils.cleanText(it) },
    )

    /** 事件线索 id 的硬上限。超长 key 只会撑大次日 prompt，不带来更多区分度。 */
    const val EVENT_KEY_MAX_CHARS = 120

    /**
     * event_key 是**全函数**：任何 (title, link) 都得到一个非空、稳定的线索 id。
     * 返回空串曾经是常态——旧实现把标题按 `[^a-z0-9]+` 折叠，任何中文标题都会塌成空串，
     * 于是所有中文源共享同一个"空"线索。调用方（Room v8 的 report_items.eventKey）
     * 依赖非空来做线索归并，所以这里必须兜底到底。
     */
    fun eventKey(explicit: String?, title: String, link: String): String {
        val provided = sanitizeEventKey(explicit)
        if (provided.isNotEmpty()) return provided
        val slug = slugify(title)
        if (slug.isNotEmpty()) return slug
        return "h-" + sha256(TextUtils.cleanText(link)).take(16)
    }

    /**
     * event_key 由 LLM 产出，又被原样注回次日 prompt（cached_event_key / recent_top30）。
     * 这里是那条回路上的唯一收窄点：拒绝任何链接形态，钉死长度。
     */
    fun sanitizeEventKey(raw: String?): String {
        val value = TextUtils.cleanText(raw)
        if (value.isEmpty()) return ""
        if (value.contains("http", ignoreCase = true) || value.contains("](")) return ""
        return value.take(EVENT_KEY_MAX_CHARS).trim()
    }

    /**
     * 逐字符实现而非 regex：Android ICU 与 JVM 的正则字符类语义并不总是一致
     * （R10 的 `(?U)` 就是这么炸的）。ASCII 字母数字逐字保留，因此纯英文标题
     * 与旧实现逐字节等价；CJK 等非 ASCII 字母数字同样保留，不再塌成空串。
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
