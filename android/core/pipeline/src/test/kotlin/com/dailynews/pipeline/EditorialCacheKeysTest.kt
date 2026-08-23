package com.dailynews.pipeline

import com.dailynews.pipeline.editorial.EditorialCacheKeys
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** KEEP: event_key is the cross-day story id; it must be non-empty, stable, and injection-proof. */
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
     * The old implementation collapsed on `[^a-z0-9]+`, so any all-Chinese title
     * became an empty string and every Chinese source shared the same "empty"
     * story. Room v8's report_items.eventKey depends on non-empty for merging.
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
        // The same link must stably produce the same story id, or every day would open a new story.
        assertEquals(key, EditorialCacheKeys.eventKey(null, "***", LINK))
        assertNotEquals(key, EditorialCacheKeys.eventKey(null, "***", "https://other.example/x"))
    }

    @Test
    fun `explicit keys win but are sanitized first`() {
        assertEquals("apple-m5-launch", EditorialCacheKeys.eventKey("  apple-m5-launch  ", "Whatever", LINK))
        // Link-shaped values are rejected outright: this value is injected back into the next day's prompt as-is.
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
