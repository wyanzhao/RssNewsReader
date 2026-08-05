package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.RunSummary
import com.dailynews.model.ContextBudgetCounts
import com.dailynews.model.ContextBudgetLimits
import com.dailynews.model.ContextBudgetSizes
import com.dailynews.model.ContextBudgetViolation
import com.dailynews.model.FeedResult
import com.dailynews.model.ValidationCounts

/**
 * Hand-built fixture for semantics and screenshot tests: UNEXPECTED_ERROR with two
 * blocking reasons, three warnings, five feed results (1 error / 1 empty / 1 stale),
 * six logs (1 ERROR), two LLM calls and one budget violation. Lets tests drive
 * diagnosticsContent directly without seeding Robolectric's empty Room.
 */
internal fun diagnosticsFixtureState(): DiagnosticsUiState {
    val detail = RunDetail(
        runId = "run-42",
        reportDate = "2026-08-04",
        status = "FAILED",
        classification = "UNEXPECTED_ERROR",
        validatorExitCode = 40,
        attempt = 2,
        trigger = "manual",
        startedAtUtc = "2026-08-04T09:00:00Z",
        finishedAtUtc = "2026-08-04T09:03:12Z",
    )
    val validationRaw = buildString {
        append("{\n  \"passed\": false,\n  \"blocking_reasons\": [")
        append("\"fetch: connection timed out\", \"pipeline: validation input damaged\"")
        append("],\n  \"blob\": \"")
        repeat(MAX_RAW_CHARS) { append('x') }
        append("\"\n}")
    }
    return DiagnosticsUiState(
        runs = listOf(
            RunSummary("run-42", "2026-08-04", "FAILED", "UNEXPECTED_ERROR", 40, 2, "2026-08-04T09:00:00Z", "2026-08-04T09:03:12Z"),
            RunSummary("run-41", "2026-08-03", "SUCCESS", "SUCCESS", 0, 1, "2026-08-03T09:00:00Z", "2026-08-03T09:02:40Z"),
            RunSummary("run-40", "2026-08-02", "FAILED", "EXPECTED_BLOCK", 30, 1, "2026-08-02T09:00:00Z", "2026-08-02T09:00:30Z"),
        ),
        selectedRunId = "run-42",
        detail = detail,
        advice = DiagnosticsAdvice("未预期错误，证据指向网络问题", DiagnosticsAction.RUN_PROBE),
        stage = "fetch",
        blockingReasons = listOf("fetch: connection timed out", "pipeline: validation input damaged"),
        warnings = listOf(
            "1 failed feed(s): Example (https://example.com/rss)",
            "2 stale feed(s): OldFeed, QuietFeed",
            "empty feed: SilentFeed",
        ),
        counts = ValidationCounts(configured = 5, results = 5, ok = 3, empty = 1, error = 1, articles = 42),
        feedResults = listOf(
            FeedResult("Example", "https://example.com/rss", "error", "connect timed out", 0),
            FeedResult("SilentFeed", "https://silent.example/rss", "empty", null, 0),
            FeedResult("OldFeed", "https://old.example/rss", "ok", null, 3, "2026-06-01T00:00:00Z"),
            FeedResult("GoodFeed", "https://good.example/rss", "ok", null, 21),
            FeedResult("QuietFeed", "https://quiet.example/rss", "ok", null, 18, "2026-06-15T00:00:00Z"),
        ),
        budget = ContextBudgetView(
            withinBudget = false,
            violations = listOf(ContextBudgetViolation("llm_context", 150_000, 120_000)),
            sizes = ContextBudgetSizes(150_000, 20_000, 30_000, 200_000),
            limits = ContextBudgetLimits(120_000, 40_000, 60_000, 240_000),
            counts = ContextBudgetCounts(42, 5, 3, 2),
        ),
        logs = listOf(
            RunLogEntity(1, "run-42", "pipeline", "INFO", "run started", "2026-08-04T09:00:00Z"),
            RunLogEntity(2, "run-42", "fetch", "WARN", "slow feed: example.com took 9s", "2026-08-04T09:00:09Z"),
            RunLogEntity(3, "run-42", "fetch", "INFO", "fetched 42 articles", "2026-08-04T09:00:20Z"),
            RunLogEntity(4, "run-42", "fetch", "ERROR", "connection timed out after 30s", "2026-08-04T09:00:50Z"),
            RunLogEntity(5, "run-42", "validate", "INFO", "qc finished", "2026-08-04T09:01:10Z"),
            RunLogEntity(6, "run-42", "pipeline", "INFO", "run finished", "2026-08-04T09:03:12Z"),
        ),
        llmCalls = listOf(
            LlmCallEntity(1, "run-42", "part1-editor", "openai", "gpt-4o", 4_800, 1_200, 0, "success", "2026-08-04T09:02:00Z"),
            LlmCallEntity(2, "run-42", "part2-drafter", "openai", "gpt-4o", 6_100, 900, 0, "success", "2026-08-04T09:02:40Z"),
        ),
        llmTotals = LlmTotals(calls = 2, inputTokens = 10_900, outputTokens = 2_100, failed = 0),
        probes = emptyList(),
        probing = false,
        probeSuggested = true,
        validationArtifact = ArtifactPayload(raw = validationRaw, status = ArtifactStatus.PARSED, truncated = true),
        budgetArtifact = ArtifactPayload(raw = "{\"within_budget\": false}", status = ArtifactStatus.PARSED),
        loading = false,
        artifactsLoading = false,
    )
}

/** Empty-database state for the empty-state regression lock. */
internal fun diagnosticsEmptyState(): DiagnosticsUiState = DiagnosticsUiState(loading = false)
