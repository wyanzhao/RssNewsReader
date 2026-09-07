package com.dailynews.data.repo

import android.content.Context
import com.dailynews.model.ArtifactJson
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ReportWorkBinding(val workId: String, val runId: String, val reportDate: String)

/** One unique daily worker owns this bounded, device-local restart cursor. Not user backup state. */
class ReportWorkRepository(context: Context) {
    private val preferences = context.getSharedPreferences("report_work_resume", Context.MODE_PRIVATE)

    fun read(workId: String): ReportWorkBinding? {
        val value = preferences.getString("binding", null) ?: return null
        val binding = ArtifactJson.codec.decodeFromString<ReportWorkBinding>(value)
        require(binding.workId.isNotBlank() && binding.runId.isNotBlank())
        LocalDate.parse(binding.reportDate)
        return binding.takeIf { it.workId == workId }
    }

    fun bind(workId: String, runId: String, reportDate: String) {
        require(workId.isNotBlank() && runId.isNotBlank())
        LocalDate.parse(reportDate)
        // commit, rather than apply: no model request may start before the cursor is durable.
        check(preferences.edit().putString("binding", ArtifactJson.codec.encodeToString(
            ReportWorkBinding(workId, runId, reportDate),
        )).commit()) { "could not persist report worker recovery cursor" }
    }
}
