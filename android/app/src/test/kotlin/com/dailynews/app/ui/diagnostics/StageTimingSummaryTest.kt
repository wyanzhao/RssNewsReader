package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.StageTimer
import com.dailynews.pipeline.observability.StageTiming
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StageTimingSummaryTest {
    @Test
    fun oldAndMalformedLogsDoNotInventMeasurementsAndRetriesRemainSeparate() {
        fun row(id: Long, message: String, step: String = StageTimer.LOG_STEP) =
            RunLogEntity(id, "run", step, "INFO", message, "2026-09-07T10:00:00Z")
        val failed = StageTiming("llm.part1_plan", 1234, "failed")
        val success = StageTiming("llm.part1_plan", 10000, "success")
        val logs = listOf(
            row(1, "old log", "editorial"), row(2, "{truncated"),
            row(3, ArtifactJson.compact.encodeToString(failed)),
            row(4, ArtifactJson.compact.encodeToString(success)),
        )
        assertEquals(listOf(failed, success), stageTimingsFor(logs))
        assertEquals("模型编辑：1.2 秒 · 失败", stageTimingText(failed))
        assertTrue(buildDiagnosticsSummary(DiagnosticsUiState(logs = logs)).contains("模型编辑：10.0 秒 · 完成"))
    }
}
