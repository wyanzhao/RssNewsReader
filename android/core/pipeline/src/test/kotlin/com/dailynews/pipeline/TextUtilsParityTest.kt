package com.dailynews.pipeline

import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** KEEP: shape-independent text/date invariants owned by the Kotlin V2 oracle. */
class TextUtilsParityTest {
    @Test
    fun `empty input and the zero max char word cap match Python`() {
        assertEquals("", TextUtils.stripHtml(null))
        assertEquals("", TextUtils.stripHtml(""))
        val longBody = (0 until TextUtils.DEFAULT_SUMMARY_WORD_CAP + 5).joinToString(" ") { "w$it" }
        val capped = TextUtils.stripHtml(longBody, 0)
        assertEquals(TextUtils.DEFAULT_SUMMARY_WORD_CAP, capped.split(' ').size)
        assertEquals("short body", TextUtils.stripHtml("short body", 0))
    }

    @Test
    fun `strips tags entities and collapses whitespace`() {
        assertEquals("A & B next", TextUtils.stripHtml(" <p>A &amp; B</p>\n\u00a0next "))
        assertEquals("A B", TextUtils.cleanText("A\u2003\u202fB"))
    }

    @Test
    fun `truncates at prior word boundary like Python`() {
        assertEquals("one two...", TextUtils.stripHtml("one two three four", 10))
    }

    @Test
    fun `truncation without a space keeps the CJK prefix`() {
        assertEquals("人工智能改变...", TextUtils.stripHtml("人工智能改变世界", 6))
    }

    @Test
    fun `parses RFC ISO naive and fallback dates`() {
        val expected = Instant.parse("2026-04-10T21:43:33Z")
        assertEquals(expected, TextUtils.parseRssDate("Fri, 10 Apr 2026 21:43:33 GMT"))
        assertEquals(expected, TextUtils.parseRssDate("Fri, 10 Apr 2026 21:43:33 +0000"))
        assertEquals(expected, TextUtils.parseRssDate("2026-04-10T21:43:33Z"))
        assertEquals(expected, TextUtils.parseRssDate("2026-04-10 21:43:33"))
        assertEquals(Instant.parse("2026-04-10T21:57:17Z"), TextUtils.parseRssDate("2026-04-10T14:57:17-07:00"))
        assertEquals(Instant.parse("2026-04-10T12:00:00Z"), TextUtils.parseRssDate("2026-04-10 12:00:00 UTC"))
        assertEquals(Instant.parse("2026-04-10T00:00:00Z"), TextUtils.parseRssDate("2026-04-10"))
        assertNull(TextUtils.parseRssDate(null))
        assertNull(TextUtils.parseRssDate(""))
        assertNull(TextUtils.parseRssDate("not a date"))
    }

    @Test
    fun `RFC 822 parser handles stale weekdays named zones and two digit years`() {
        assertEquals(
            Instant.parse("2026-04-10T19:43:33Z"),
            TextUtils.parseRssDate("Mon, 10 Apr 2026 14:43:33 EST"),
        )
        assertEquals(
            Instant.parse("2026-04-10T21:43:33Z"),
            TextUtils.parseRssDate("10 Apr 26 21:43:33 UT"),
        )
        assertEquals(
            Instant.parse("1999-04-10T21:43:33Z"),
            TextUtils.parseRssDate("10 Apr 99 21:43:33 Z"),
        )
        assertEquals(
            Instant.parse("2068-04-10T21:43:33Z"),
            TextUtils.parseRssDate("10 Apr 68 21:43:33 Z"),
        )
        assertEquals(
            Instant.parse("1969-04-10T21:43:33Z"),
            TextUtils.parseRssDate("10 Apr 69 21:43:33 Z"),
        )
    }

    @Test
    fun `RFC 5322 trailing comments do not drop the article`() {
        assertEquals(
            Instant.parse("2026-07-03T23:00:00Z"),
            TextUtils.parseRssDate("Thu, 03 Jul 2026 23:00:00 +0000 (UTC)"),
        )
        assertEquals(
            Instant.parse("2026-07-04T06:00:00Z"),
            TextUtils.parseRssDate("Thu, 03 Jul 2026 23:00:00 -0700 (PDT)"),
        )
    }

    @Test
    fun `atlantic zones and full month names match Python`() {
        assertEquals(
            Instant.parse("2026-04-10T16:00:00Z"),
            TextUtils.parseRssDate("Mon, 10 Apr 2026 12:00:00 AST"),
        )
        assertEquals(
            Instant.parse("2026-04-10T15:00:00Z"),
            TextUtils.parseRssDate("Mon, 10 Apr 2026 12:00:00 ADT"),
        )
        assertEquals(
            Instant.parse("2026-04-10T12:00:00Z"),
            TextUtils.parseRssDate("10 April 2026 12:00:00 +0000"),
        )
        assertEquals(
            Instant.parse("2026-09-10T12:00:00Z"),
            TextUtils.parseRssDate("Mon, 10 September 2026 12:00:00 GMT"),
        )
    }

    @Test
    fun `ISO variants keep their offset instead of being read as UTC`() {
        assertEquals(
            Instant.parse("2026-04-10T16:43:33Z"),
            TextUtils.parseRssDate("2026-04-10 21:43:33+05:00"),
        )
        assertEquals(
            Instant.parse("2026-04-10T16:13:33Z"),
            TextUtils.parseRssDate("2026-04-10T21:43:33+0530"),
        )
    }

    @Test
    fun `unknown zone names fall back to UTC rather than dropping the article`() {
        // Python yields a naive datetime here, which its own window filter then rejects outright.
        assertEquals(
            Instant.parse("2026-04-10T12:00:00Z"),
            TextUtils.parseRssDate("Mon, 10 Apr 2026 12:00:00 XYZ"),
        )
        assertEquals(
            Instant.parse("2026-04-10T12:00:00Z"),
            TextUtils.parseRssDate("Mon, 10 Apr 2026 12:00:00 A"),
        )
    }

    @Test
    fun `dedup key strips all trailing slashes`() {
        assertEquals("https://example.test/a", TextUtils.dedupLinkKey("https://example.test/a///"))
        assertEquals("https://example.test/a", TextUtils.dedupLinkKey("https://example.test/a"))
        assertEquals("", TextUtils.dedupLinkKey(null))
        assertNotEquals(
            TextUtils.dedupLinkKey("https://example.test/a?x=1&y=2"),
            TextUtils.dedupLinkKey("https://example.test/a?y=2&x=1"),
        )
    }
}
