package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.RunEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.ValidationCounts
import com.dailynews.model.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsArtifactsTest {
    private val sample = ValidationResult(
        passed = false,
        blockingReasons = listOf("feed_results count mismatch"),
        warnings = listOf("1 failed feed(s): Example"),
        counts = ValidationCounts(configured = 5, ok = 4, error = 1, articles = 42),
    )

    @Test fun strictDecodeMarksParsedAndRoundTripsFields() {
        val text = ArtifactJson.codec.encodeToString(ValidationResult.serializer(), sample)
        val (validation, payload) = resolveValidationArtifact(text)
        assertEquals(ArtifactStatus.PARSED, payload.status)
        assertEquals(sample.blockingReasons, validation.blockingReasons)
        assertEquals(sample.warnings, validation.warnings)
        assertEquals(42, validation.counts?.articles)
    }

    @Test fun additiveUnknownFieldsStayParsed() {
        val text = ArtifactJson.codec.encodeToString(ValidationResult.serializer(), sample)
            .replaceFirst("{", "{\"future_flag\": true, ")
        val (_, payload) = resolveValidationArtifact(text)
        assertEquals(ArtifactStatus.PARSED, payload.status)
    }

    @Test fun driftedCountsDegradeButKeepReadableFields() {
        val text = """
            {"passed": false,
             "blocking_reasons": ["zero articles"],
             "warnings": [],
             "counts": {"articles": "many", "ok": 4},
             "feed_results": [{"source": "Example", "url": "https://e", "status": "ok", "article_count": 4}]}
        """.trimIndent()
        val (validation, payload) = resolveValidationArtifact(text)
        assertEquals(ArtifactStatus.DEGRADED, payload.status)
        assertEquals(listOf("zero articles"), validation.blockingReasons)
        assertNull(validation.counts)
        assertEquals(1, validation.feedResults.size)
    }

    @Test fun nonJsonIsUnparseableAndFallsBackToRunEntity() {
        val entity = RunEntity(
            runId = "run-1",
            reportDate = "2026-08-04",
            status = "FAILED",
            classification = "UNEXPECTED_ERROR",
            validatorExitCode = 40,
            attempt = 1,
            trigger = "manual",
            blockingReasonsJson = """["editorial: HTTP 401"]""",
            warningsJson = """["watchdog approaching"]""",
            startedAtUtc = "2026-08-04T00:00:00Z",
            finishedAtUtc = "2026-08-04T00:05:00Z",
        )
        val resolved = resolveDiagnosticsArtifacts("not json at all {{{", null, entity, emptyList())
        assertEquals(ArtifactStatus.UNPARSEABLE, resolved.validationArtifact.status)
        assertEquals(listOf("editorial: HTTP 401"), resolved.validation.blockingReasons)
        assertEquals(listOf("watchdog approaching"), resolved.validation.warnings)
    }

    @Test fun allEmptyFallsBackToFinalErrorLog() {
        val logs = listOf(
            RunLogEntity(1, "run", "fetch", "INFO", "started", "2026-08-04T00:00:00Z"),
            RunLogEntity(2, "run", "pipeline", "ERROR", "boom", "2026-08-04T00:00:01Z"),
        )
        val resolved = resolveDiagnosticsArtifacts(null, null, null, logs)
        assertEquals(ArtifactStatus.MISSING, resolved.validationArtifact.status)
        assertEquals(listOf("pipeline: boom"), resolved.validation.blockingReasons)
    }

    @Test fun longRawJsonIsTruncatedToTheCap() {
        val text = buildString {
            append("{\"passed\": false, \"blob\": \"")
            repeat(MAX_RAW_CHARS + 500) { append('x') }
            append("\"}")
        }
        val (_, payload) = resolveValidationArtifact(text)
        assertTrue(payload.truncated)
        assertEquals(MAX_RAW_CHARS, payload.raw?.length)
    }

    @Test fun budgetStrictDecodeAndGarbage() {
        val parsed = resolveBudgetArtifact(
            """{"meta": {"date": "2026-08-04", "generated_at_utc": "2026-08-04T00:00:00Z", "run_id": "r", "report_path": "/x"},
               "limits": {"llm_context_max_bytes": 100, "part1_brief_max_bytes": 100, "part2_context_max_bytes": 100, "total_context_max_bytes": 300},
               "sizes": {"llm_context_bytes": 150, "part1_brief_bytes": 20, "part2_context_bytes": 30, "total_context_bytes": 200},
               "counts": {"articles": 42, "sources": 5, "part2_cache_hits": 3, "part2_missing_summaries": 2},
               "per_source": [],
               "within_budget": false,
               "violations": [{"size": "llm_context", "actual": 150, "limit": 100}]}""",
        )
        assertEquals(ArtifactStatus.PARSED, parsed.second.status)
        assertFalse(parsed.first!!.withinBudget)
        assertEquals(1, parsed.first!!.violations.size)

        val garbage = resolveBudgetArtifact("nope")
        assertEquals(ArtifactStatus.UNPARSEABLE, garbage.second.status)
        assertNull(garbage.first)
    }
}
