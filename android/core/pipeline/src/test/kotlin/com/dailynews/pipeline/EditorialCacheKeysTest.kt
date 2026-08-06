package com.dailynews.pipeline

import com.dailynews.pipeline.editorial.EditorialCacheKeys
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** KEEP: event_key 是跨日线索 id，必须非空、稳定、且无法被注入。 */
class EditorialCacheKeysTest {
    @Test
    fun `ascii titles keep the historic slug shape`() {
        assertEquals(
            "openai-raises-40b-at-a-300b-valuation",
            EditorialCacheKeys.eventKey(null, "OpenAI raises \$40B at a \$300B valuation!", LINK),
        )
        assertEquals("nvidia-gtc-2026", EditorialCacheKeys.eventKey(null, "  NVIDIA — GTC 2026  ", LINK))
    }

    /**
     * 旧实现按 `[^a-z0-9]+` 折叠，任何纯中文标题都塌成空串，于是所有中文源
     * 共享同一个"空"线索。Room v8 的 report_items.eventKey 依赖非空做归并。
     */
    @Test
    fun `cjk titles produce a non empty slug instead of collapsing`() {
        val key = EditorialCacheKeys.eventKey(null, "苹果发布新款 M5 芯片", LINK)
        assertEquals("苹果发布新款-m5-芯片", key)
        assertNotEquals("", key)
    }

    @Test
    fun `titles without letters or digits fall back to a link hash`() {
        val key = EditorialCacheKeys.eventKey(null, "!!! ??? ---", LINK)
        assertTrue(key.startsWith("h-"), "expected link-hash fallback, got $key")
        assertEquals(18, key.length)
        // 同一链接必须稳定得到同一线索 id，否则每天都会新开一条线索。
        assertEquals(key, EditorialCacheKeys.eventKey(null, "***", LINK))
        assertNotEquals(key, EditorialCacheKeys.eventKey(null, "***", "https://other.example/x"))
    }

    @Test
    fun `explicit keys win but are sanitized first`() {
        assertEquals("apple-m5-launch", EditorialCacheKeys.eventKey("  apple-m5-launch  ", "Whatever", LINK))
        // 链接形态一律拒绝：这个值会被原样注回次日 prompt。
        assertEquals("", EditorialCacheKeys.sanitizeEventKey("see https://evil.example"))
        assertEquals("", EditorialCacheKeys.sanitizeEventKey("[click](https://evil.example)"))
        assertEquals("", EditorialCacheKeys.sanitizeEventKey(null))
        assertEquals("", EditorialCacheKeys.sanitizeEventKey("   "))
    }

    @Test
    fun `keys are capped on both paths`() {
        val long = "a".repeat(400)
        assertEquals(EditorialCacheKeys.EVENT_KEY_MAX_CHARS, EditorialCacheKeys.sanitizeEventKey(long).length)
        assertEquals(EditorialCacheKeys.EVENT_KEY_MAX_CHARS, EditorialCacheKeys.eventKey(null, long, LINK).length)
    }

    @Test
    fun `poisoned explicit keys fall through to the derived slug`() {
        assertEquals(
            "openai-ships-gpt-6",
            EditorialCacheKeys.eventKey("https://evil.example/inject", "OpenAI ships GPT-6", LINK),
        )
    }

    private companion object {
        const val LINK = "https://example.com/article"
    }
}
