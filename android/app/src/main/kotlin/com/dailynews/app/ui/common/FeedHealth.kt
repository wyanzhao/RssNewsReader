package com.dailynews.app.ui.common

import com.dailynews.data.repo.FeedRecord
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Unified source-health verdict. Shared since Epic U by the feeds screen, reader chips,
 * and the brief screen, with the semantics "status of the most recent sweep"; the brief
 * screen's source-health card separately carries an "at this report's fetch time"
 * snapshot semantics (from reports.groupsJson), and the two must be worded distinctly.
 */
fun feedDisplayStatus(feed: FeedRecord, now: Instant = Instant.now()): String {
    if (feed.lastStatus == "error") return "ERROR"
    val newest = feed.newestItemDateIso?.let { value ->
        runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        }
    }
    if (newest != null && Duration.between(newest, now).toDays() > 30) return "STALE"
    return feed.lastStatus ?: "UNKNOWN"
}
