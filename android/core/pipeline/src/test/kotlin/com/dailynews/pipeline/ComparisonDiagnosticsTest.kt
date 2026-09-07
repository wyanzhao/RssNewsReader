package com.dailynews.pipeline

import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.ComparisonDiagnostics
import com.dailynews.pipeline.observability.LlmAttemptMeasurement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ComparisonDiagnosticsTest {
    private val manifest = """{"status":"failed","preference":"AI"}"""
    private fun measurement(outcome: String, tokens: Long? = 65_536): String = buildJsonObject {
        put("step", "llm_attempt_measurement")
        put("message", ArtifactJson.codec.encodeToString(LlmAttemptMeasurement("request:0", "part1_plan",
            contractAttempt = 0, physicalAttempt = 0, outcome = outcome, outputTokens = tokens)))
    }.toString()

    @Test fun `real truncation shape preserves arm operation and reported output`() {
        val result = ComparisonDiagnostics.parse(manifest, listOf("baseline" to measurement("truncated")))
        assertEquals("failed", result.status)
        assertEquals("AI", result.preference)
        assertEquals("baseline", result.truncations.single().arm)
        assertEquals("part1_plan", result.truncations.single().operation)
        assertEquals(65_536L, result.truncations.single().outputTokens)
        assertFalse(result.unreadableTelemetry)
    }
    @Test fun `missing legacy measurements do not invent failure or zero tokens`() {
        assertTrue(ComparisonDiagnostics.parse(manifest, emptyList()).truncations.isEmpty())
        val result = ComparisonDiagnostics.parse(manifest, listOf("candidate" to measurement("repair_truncated", null)))
        assertNull(result.truncations.single().outputTokens)
        assertEquals("candidate", result.truncations.single().arm)
    }
    @Test fun `malformed evidence is explicit and raw error strings are not classified`() {
        val result = ComparisonDiagnostics.parse(manifest, listOf("baseline" to "{", "baseline" to measurement("failed: truncated secret"), "other" to measurement("truncated")))
        assertTrue(result.truncations.isEmpty())
        assertTrue(result.unreadableTelemetry)
        assertTrue(ComparisonDiagnostics.parse(manifest, listOf("baseline" to measurement("truncated", -1))).unreadableTelemetry)
    }
}
