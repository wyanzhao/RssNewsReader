package com.dailynews.pipeline

import com.dailynews.model.*
import com.dailynews.pipeline.flow.*
import com.dailynews.pipeline.editorial.PeriodicDigestRenderer
import org.junit.jupiter.api.Test
import kotlin.test.*

class PeriodicDigestMaterialTest {
    private fun item(day: Int, key: String, suffix: String = "$day") = PeriodicDigestItem(
        "2026-09-%02d".format(day), "Title $key", "Source", "https://example.test/$key/$suffix", "摘要 $day", key)

    @Test fun `trajectory retains oldest latest and preceding distinct source within total cap`() {
        val rows = (1..7).map { item(it, "event") }
        val result = boundPeriodicMaterial(rows, WatchPreferences())
        assertEquals(listOf(rows[0], rows[5], rows[6]), result)
        assertEquals(listOf(rows.last()), boundPeriodicMaterial(rows, WatchPreferences(), 1))
        assertEquals(2, boundPeriodicMaterial(rows, WatchPreferences(), 2).size)
    }
    @Test fun `explicit watches survive capacity limit and unrelated events retain breadth`() {
        val watch = WatchPreferences(events = listOf(EventWatch("older-event", "Old", "2026-09-01")))
        val rows = listOf(item(1, "older-event")) + (2..9).map { item(it, "event-$it") }
        val result = boundPeriodicMaterial(rows, watch, 3)
        assertEquals(setOf("older-event", "event-9", "event-8"), result.map { it.eventKey }.toSet())
        assertEquals(3, result.size)
        assertEquals(setOf("event-9", "event-8", "event-7"), boundPeriodicMaterial(rows, WatchPreferences(), 3).map { it.eventKey }.toSet())
    }
    @Test fun `repeated publication of same article cannot pretend to be multiple developments`() {
        val row = item(1, "event")
        val latest = row.copy(reportDate = "2026-09-07", summaryZh = "最新摘要")
        assertEquals(listOf(latest), boundPeriodicMaterial(listOf(row, latest), WatchPreferences()))
    }
    @Test fun `rendered coverage and personalization are explicit without inventing missing articles`() {
        val row = item(1, "event")
        val input = PeriodicDigestInput("2026-W36", "WEEKLY", "2026-08-31", "2026-09-06", listOf(row.reportDate), listOf(row),
            watches = WatchPreferences(topics = listOf("AI")), sourceArticleCount = 10)
        val output = PeriodicDigestRenderer.render(input, PeriodicDigest(input.period, listOf(PeriodicDigestSection("主题", "摘要", listOf(row.link)))))
        assertTrue(output.contains("已发布 10 篇不同原文，本次提供 1 篇材料"))
        assertTrue(output.contains("按生成时的关注偏好"))
        assertTrue(output.contains(row.link))
    }
}
