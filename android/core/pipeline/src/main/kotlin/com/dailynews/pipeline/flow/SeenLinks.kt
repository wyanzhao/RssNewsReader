package com.dailynews.pipeline.flow

import com.dailynews.model.Article
import com.dailynews.pipeline.text.TextUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object SeenLinks {
    const val DEFAULT_MAX_AGE_DAYS = 14L

    data class FilterResult(val articles: List<Article>, val dropped: Int)

    fun filterPreviouslyReported(
        articles: List<Article>,
        entries: Map<String, LocalDate>,
        reportDate: LocalDate,
    ): FilterResult {
        if (entries.isEmpty()) return FilterResult(articles, 0)
        var dropped = 0
        val kept = articles.filter { article ->
            val key = TextUtils.dedupLinkKey(article.link)
            val seenOn = entries[key]
            val keep = seenOn == null || !seenOn.isBefore(reportDate)
            if (!keep) dropped += 1
            keep
        }
        return FilterResult(kept, dropped)
    }

    fun recordReportedLinks(
        entries: MutableMap<String, LocalDate>,
        links: Iterable<String>,
        reportDate: LocalDate,
    ): MutableMap<String, LocalDate> {
        for (link in links) {
            val key = TextUtils.dedupLinkKey(link)
            if (key.isEmpty()) continue
            val current = entries[key]
            if (current == null || current.isBefore(reportDate)) entries[key] = reportDate
        }
        return entries
    }

    fun prune(
        entries: MutableMap<String, LocalDate>,
        reportDate: LocalDate,
        maxAgeDays: Long = DEFAULT_MAX_AGE_DAYS,
    ): MutableMap<String, LocalDate> {
        entries.entries.removeIf { (_, seenOn) -> ChronoUnit.DAYS.between(seenOn, reportDate) > maxAgeDays }
        return entries
    }
}
