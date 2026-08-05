package com.dailynews.pipeline

import com.dailynews.pipeline.flow.SeenLinks
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** KEEP: cross-run deduplication semantics inherited unchanged by V2. */
class SeenLinksParityTest {
    @Test
    fun `earlier date filters while same date stays idempotent`() {
        val (raw) = FixtureFactory.goldenRaw()
        val first = raw.articles.first()
        val reportDate = LocalDate.parse("2026-04-10")
        val old = mapOf(first.link.trimEnd('/') to reportDate.minusDays(1))
        val same = mapOf(first.link.trimEnd('/') to reportDate)
        assertEquals(1, SeenLinks.filterPreviouslyReported(raw.articles, old, reportDate).dropped)
        assertEquals(0, SeenLinks.filterPreviouslyReported(raw.articles, same, reportDate).dropped)
    }

    @Test
    fun `record keeps larger date and prune keeps fourteen day boundary`() {
        val entries = mutableMapOf("new" to LocalDate.parse("2026-04-09"), "boundary" to LocalDate.parse("2026-03-27"), "old" to LocalDate.parse("2026-03-26"))
        SeenLinks.recordReportedLinks(entries, listOf("new/", "fresh"), LocalDate.parse("2026-04-10"))
        SeenLinks.prune(entries, LocalDate.parse("2026-04-10"))
        assertEquals(LocalDate.parse("2026-04-10"), entries["new"])
        assertEquals(setOf("new", "fresh", "boundary"), entries.keys)
    }
}
