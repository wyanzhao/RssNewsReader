package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ContextBudgetCounts
import com.dailynews.model.ContextBudgetLimits
import com.dailynews.model.ContextBudgetSizes
import com.dailynews.model.ContextBudgetViolation
import com.dailynews.model.FeedResult
import com.dailynews.model.ValidationCounts
import com.dailynews.pipeline.orchestrate.NetworkProbe
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsSummaryTest {
    @Test
    fun fullStateRendersTheGoldenSummary() {
        val state = DiagnosticsUiState(
            runs = emptyList(),
            selectedRunId = "run-42",
            detail = RunDetail(
                "run-42", "2026-08-04", "FAILED", "UNEXPECTED_ERROR", 40, 2, "manual",
                "2026-08-04T09:00:00Z", "2026-08-04T09:03:12Z",
            ),
            advice = DiagnosticsAdvice("未预期错误，证据指向网络问题", DiagnosticsAction.RUN_PROBE),
            stage = "fetch",
            blockingReasons = listOf("fetch: connection timed out", "pipeline: validation input damaged"),
            warnings = listOf("1 failed feed(s): Example"),
            counts = ValidationCounts(configured = 5, results = 5, ok = 3, empty = 1, error = 1, articles = 42),
            feedResults = listOf(
                FeedResult("Example", "https://example.com/rss", "error", "connect timed out", 0),
                FeedResult("SilentFeed", "https://silent.example/rss", "empty", null, 0),
                FeedResult("GoodFeed", "https://good.example/rss", "ok", null, 42),
            ),
            logs = listOf(
                RunLogEntity(1, "run-42", "fetch", "INFO", "fetched 42 articles", "2026-08-04T09:00:20Z"),
                RunLogEntity(2, "run-42", "fetch", "ERROR", "connection timed out after 30s", "2026-08-04T09:00:50Z"),
            ),
            llmCalls = listOf(
                LlmCallEntity(1, "run-42", "part1-editor", "openai", "gpt-4o", 4_800, 1_200, 0, "success", "2026-08-04T09:02:00Z"),
                LlmCallEntity(2, "run-42", "part2-drafter", "openai", "gpt-4o", 6_100, 900, 0, "success", "2026-08-04T09:02:40Z"),
            ),
            llmTotals = LlmTotals(calls = 2, inputTokens = 10_900, outputTokens = 2_100, failed = 0),
            budget = ContextBudgetView(
                withinBudget = false,
                violations = listOf(ContextBudgetViolation("llm_context", 150_000, 120_000)),
                sizes = ContextBudgetSizes(150_000, 20_000, 30_000, 200_000),
                limits = ContextBudgetLimits(120_000, 40_000, 60_000, 240_000),
                counts = ContextBudgetCounts(42, 5, 3, 2),
            ),
            probes = listOf(
                NetworkProbe("android", "transport", true, "wifi"),
                NetworkProbe("Example", "dns", true, "93.184.216.34"),
                NetworkProbe("Example", "https", false, "HTTP 503"),
            ),
            loading = false,
        )

        val expected = """
            DailyNews 运行诊断摘要
            运行：run-42 · 2026-08-04
            状态 FAILED · UNEXPECTED_ERROR · exit 40 · 重试 2 · 触发 manual
            耗时：3 分 12 秒
            结论：未预期错误，证据指向网络问题
            失败阶段：fetch
            阻断原因：
            - fetch: connection timed out
            - pipeline: validation input damaged
            警告：
            - 1 failed feed(s): Example
            统计：配置 5 · 正常 3 · 空 1 · 失败 1 · 文章 42
            异常来源：Example (error), SilentFeed (empty)
            末尾 ERROR 日志：
            - fetch: connection timed out after 30s
            LLM：2 次 · 10900+2100 tokens · 0 次失败
            预算超出：llm_context 150000 > 120000
            网络探测：2 项，1 通过 / 1 失败
        """.trimIndent()
        assertEquals(expected, buildDiagnosticsSummary(state))
    }

    @Test
    fun emptyFieldsProduceNoBlankLines() {
        val summary = buildDiagnosticsSummary(DiagnosticsUiState(loading = false))
        assertEquals("DailyNews 运行诊断摘要\n结论：正在读取运行记录…", summary)
    }

    @Test
    fun cleanedRunStillNamesItself() {
        val summary = buildDiagnosticsSummary(
            DiagnosticsUiState(selectedRunId = "cleaned-run", loading = false),
        )
        assertEquals("DailyNews 运行诊断摘要\n运行：cleaned-run（产物已按保留期清理）\n结论：正在读取运行记录…", summary)
    }
}
