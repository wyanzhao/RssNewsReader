package com.dailynews.pipeline

import com.dailynews.model.*
import com.dailynews.pipeline.editorial.watchedDevelopments
import org.junit.jupiter.api.Test
import kotlin.test.*

class WatchedDevelopmentsTest {
    private val item = ReportItem(1, 1, "https://example.test/new", "New evidence", "Source", "", "", "摘要",
        eventKey = "chip-launch", development = EventDevelopment("2026-09-06", "已发布规格", "https://example.test/new", "published specifications"))
    private val report = AssembledReport("2026-09-07", "", "", listOf(item))
    private val watches = WatchPreferences(events = listOf(EventWatch("chip-launch", "Chip", "2026-09-05")))

    @Test fun `only explicitly watched developments after the watch baseline qualify`() {
        assertEquals(listOf(item), watchedDevelopments(report, watches))
        assertTrue(watchedDevelopments(report, WatchPreferences(topics = listOf("chip"))).isEmpty())
        assertTrue(watchedDevelopments(report, WatchPreferences()).isEmpty())
        assertTrue(watchedDevelopments(report, watches.copy(events = listOf(watches.events.single().copy(afterReportDate = report.reportDate)))).isEmpty())
    }
    @Test fun `repeated mention missing source and invalid or stale baseline are excluded`() {
        val development = requireNotNull(item.development)
        val invalid = listOf(item.copy(development = null), item.copy(part = 2)) + listOf(
            development.copy(baselineDate = "2026-09-04"),
            development.copy(baselineDate = "2026-09-07"),
            development.copy(baselineDate = "broken"),
            development.copy(evidenceLink = "https://unselected.test"),
            development.copy(evidenceQuote = ""),
        ).map { item.copy(development = it) }
        invalid.forEach { assertTrue(watchedDevelopments(report.copy(items = listOf(it)), watches).isEmpty()) }
    }
    @Test fun `merged evidence qualifies and repeated event is shown only once`() {
        val merged = item.copy(alsoLinks = listOf("https://example.test/merged"),
            development = item.development!!.copy(evidenceLink = "https://example.test/merged"))
        assertEquals(listOf(merged), watchedDevelopments(report.copy(items = listOf(merged, merged.copy(position = 2))), watches))
    }
}
