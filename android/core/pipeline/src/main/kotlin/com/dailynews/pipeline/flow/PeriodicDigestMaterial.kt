package com.dailynews.pipeline.flow

import com.dailynews.model.WatchPreferences

/** Keep source identities intact and bound the total number of snapshots, not just event count. */
fun boundPeriodicMaterial(items: List<PeriodicDigestItem>, watches: WatchPreferences, limit: Int = 120): List<PeriodicDigestItem> {
    require(limit > 0)
    val watched = watches.normalized().events.mapTo(mutableSetOf()) { it.eventKey }
    val groups = items.sortedByDescending { it.reportDate }.distinctBy { it.link }
        .groupBy { it.eventKey.ifBlank { it.link } }.values
        .sortedByDescending { it.first().eventKey in watched }
    val selected = linkedMapOf<String, PeriodicDigestItem>()
    // Preserve breadth first; explicit watches survive a crowded period's recency cap.
    val retained = groups.take(limit)
    retained.forEach { selected[it.first().link] = it.first() }
    // The old one-row-per-event cut made the requested trajectory impossible. Spend
    // remaining capacity on an early baseline, then the preceding distinct article.
    for (pass in 0..1) {
        retained.forEach { group ->
            if (selected.size >= limit) return@forEach
            val candidate = if (pass == 0) group.last() else group.getOrNull(1)
            if (candidate != null) selected.putIfAbsent(candidate.link, candidate)
        }
    }
    return selected.values.sortedBy { it.reportDate }
}
