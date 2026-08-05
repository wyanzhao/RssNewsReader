package com.dailynews.app.ui.common

import com.dailynews.data.repo.FeedRecord
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 来源健康的统一判定。Epic U 起由订阅页、阅读页 chip 与简报页共用，
 * 语义为「最近一次 sweep 的状态」；简报页的来源健康卡另有
 * 「本次报告抓取时」的快照语义（来自 reports.groupsJson），两者文案必须显式区分。
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
