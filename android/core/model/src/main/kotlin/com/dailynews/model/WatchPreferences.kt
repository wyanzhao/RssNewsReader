package com.dailynews.model

import kotlinx.serialization.Serializable

@Serializable
data class EventWatch(val eventKey: String, val title: String, val afterReportDate: String)

@Serializable
data class WatchPreferences(val topics: List<String> = emptyList(), val events: List<EventWatch> = emptyList()) {
    fun normalized() = copy(
        topics = topics.map { it.trim().take(80) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(20),
        events = events.filter { it.eventKey.matches(Regex("[a-z0-9-]{1,60}")) &&
            runCatching { java.time.LocalDate.parse(it.afterReportDate) }.isSuccess }
            .distinctBy { it.eventKey }.take(20).map { it.copy(title = it.title.take(300)) },
    )
}
