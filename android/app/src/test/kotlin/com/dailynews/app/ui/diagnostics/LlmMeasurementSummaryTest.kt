package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.LlmAttemptMeasurement
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.assertTrue

class LlmMeasurementSummaryTest {
    private fun row(id: String, physical: Int = 0, contract: Int = 0, cost: String? = "0.1", outcome: String = "success") =
        LlmAttemptMeasurement(id, "part1_plan", contractAttempt = contract, physicalAttempt = physical, outcome = outcome, billedCostUsd = cost)

    private fun state(vararg rows: LlmAttemptMeasurement, extraCalls: Int = 0, status: String = "SUCCESS") = DiagnosticsUiState(
        detail = RunDetail("run", "2026-09-07", status, status, 0, 1, "manual", "2026-09-07T00:00:00Z", "2026-09-07T00:01:00Z"),
        logs = rows.mapIndexed { index, row -> RunLogEntity(index.toLong(), "run", LlmAttemptMeasurement.LOG_STEP, "INFO", ArtifactJson.compact.encodeToString(row), "now") },
        llmCalls = List(rows.size + extraCalls) { LlmCallEntity(it.toLong(), "run", "EDITOR", "p", "m", retryIndex = 0, outcome = "success", createdAtUtc = "now") },
    )

    @Test fun exactDecimalCostIncludesReworkAndPhysicalRepair() {
        val summary = llmMeasurementSummary(state(row("a:0"), row("b:0", contract = 1), row("b:1", physical = 1, contract = 1)))
        assertTrue("首轮成功：否" in summary)
        assertTrue(summary.any { "请求重试/修复：1 次 · 编辑返工：1 轮" in it })
        assertTrue(summary.any { "0.3 USD" in it })
    }

    @Test fun missingChargesAndLogsNeverBecomeZeroOrFirstPassSuccess() {
        val summary = llmMeasurementSummary(state(row("a:0"), row("b:0", cost = null), extraCalls = 1))
        assertTrue(summary.any { "无法判定" in it })
        assertTrue(summary.any { "总费用未知" in it })
        assertTrue(llmMeasurementSummary(state(row("a:0", cost = null))).any { "费用：未知" in it })
        assertTrue(llmMeasurementSummary(state(row("a:0", cost = "-1"))).any { "费用：未知" in it })
    }

    @Test fun missingTokenUsageIsNotZero() {
        assertTrue("未知+未知 tokens" == llmUsageText(state(row("a:0"))))
        val partial = state(row("a:0"), row("b:0")).let { original ->
            original.copy(llmCalls = original.llmCalls.mapIndexed { index, call ->
                if (index == 0) call.copy(inputTokens = 12, outputTokens = 0) else call
            })
        }
        assertTrue("12（部分）+0（部分） tokens" == llmUsageText(partial))
    }

    @Test fun parseableJsonDoesNotProvePublishedReportSuccess() {
        assertTrue("首轮成功：尚未成功" in llmMeasurementSummary(state(row("a:0"), status = "FAILED")))
        assertTrue("首轮成功：是" in llmMeasurementSummary(state(row("a:0"))))
    }
}
