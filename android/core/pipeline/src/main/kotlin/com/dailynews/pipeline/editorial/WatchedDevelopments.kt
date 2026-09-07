package com.dailynews.pipeline.editorial

import com.dailynews.model.AssembledReport
import com.dailynews.model.ReportItem
import com.dailynews.model.WatchPreferences
import java.time.LocalDate

/** Select from a successfully reviewed report; this is not an independent novelty detector. */
fun watchedDevelopments(report: AssembledReport, watches: WatchPreferences): List<ReportItem> {
    val date = runCatching { LocalDate.parse(report.reportDate) }.getOrNull() ?: return emptyList()
    val events = watches.normalized().events.associateBy { it.eventKey }
    return report.items.filter { item ->
        val watch = events[item.eventKey] ?: return@filter false
        val development = item.development ?: return@filter false
        val baseline = runCatching { LocalDate.parse(development.baselineDate) }.getOrNull() ?: return@filter false
        val since = LocalDate.parse(watch.afterReportDate)
        item.part == 1 && date > since && baseline >= since && baseline < date &&
            development.changeZh.isNotBlank() && development.evidenceQuote.isNotBlank() &&
            development.evidenceLink in (item.alsoLinks + item.link)
    }.sortedBy { it.position }.distinctBy { it.eventKey }
}
