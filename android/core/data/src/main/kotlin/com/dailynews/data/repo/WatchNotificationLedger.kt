package com.dailynews.data.repo

import android.content.Context
import com.dailynews.model.ArtifactJson
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class WatchNotificationEntry(val key: String, val reportDate: String)

/**
 * Device-local ledger of watched-event developments already surfaced in a notification, keyed by
 * [com.dailynews.pipeline.editorial.developmentNotificationKey]. Suppresses re-alerting the same
 * progress across runs (same-day recovery, next-day re-report). Not user backup state: losing it
 * only costs one duplicate alert. A corrupt payload therefore reads as empty instead of failing.
 */
class WatchNotificationLedger(context: Context) {
    private val preferences = context.getSharedPreferences("watch_notifications", Context.MODE_PRIVATE)

    fun notifiedKeys(): Set<String> = entries().mapTo(LinkedHashSet()) { it.key }

    fun record(keys: Collection<String>, reportDate: String) {
        val date = LocalDate.parse(reportDate)
        val fresh = keys.filter { it.isNotBlank() }.distinct()
        if (fresh.isEmpty()) return
        val retained = entries().filter { entry ->
            val entryDate = runCatching { LocalDate.parse(entry.reportDate) }.getOrNull() ?: return@filter false
            entry.key !in fresh && !entryDate.isBefore(date.minusDays(RETENTION_DAYS))
        }
        val merged = (retained + fresh.map { WatchNotificationEntry(it, reportDate) }).takeLast(MAX_ENTRIES)
        preferences.edit().putString(KEY, ArtifactJson.codec.encodeToString(merged)).apply()
    }

    private fun entries(): List<WatchNotificationEntry> {
        val value = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching { ArtifactJson.codec.decodeFromString<List<WatchNotificationEntry>>(value) }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "entries"
        const val MAX_ENTRIES = 300
        const val RETENTION_DAYS = 60L
    }
}
