package com.dailynews.pipeline

import com.dailynews.pipeline.observability.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class RecoveryAccountingTest {
    private fun call(cost: String? = "0.12") = LlmAttemptMeasurement("request:0", "plan", contractAttempt = 0, physicalAttempt = 0, outcome = "success", billedCostUsd = cost)
    private fun row(id: String, parent: String? = null, status: String = "SUCCESS") = AccountingRun(id, "2026-09-07", status, parent, 1, listOf(call()))

    @Test fun `failed source and recovered success sum only explicit ancestors`() = runBlocking {
        val rows = listOf(row("source", status = "FAILED"), row("recovered", "source"), row("unrelated")).associateBy { it.id }
        val result = recoveryAccounting("recovered") { rows[it] }
        assertEquals(listOf("recovered", "source"), result.runIds)
        assertEquals("0.24", result.reportedUsd)
        assertEquals(2, result.reportedCalls)
        assertTrue(result.complete)
    }
    @Test fun `unknown cancelled charge never becomes a complete total`() = runBlocking {
        val rows = mapOf("source" to row("source", status = "FAILED").copy(measurements = listOf(call(null)), mayHaveUnrecordedCalls = true), "recovered" to row("recovered", "source"))
        val result = recoveryAccounting("recovered") { rows[it] }
        assertEquals("0.12", result.reportedUsd)
        assertEquals(1, result.reportedCalls)
        assertEquals(2, result.observedCalls)
        assertFalse(result.complete)
        assertTrue(result.issues.any { it.startsWith("unknown_charge") })
    }
    @Test fun `missing cycle date mismatch and depth cannot silently shorten a chain`() = runBlocking {
        assertFalse(recoveryAccounting("a") { row(it, "missing").takeIf { it.id == "a" } }.complete)
        assertTrue(recoveryAccounting("a") { row(it, "a") }.issues.any { it.startsWith("cycle") })
        assertTrue(recoveryAccounting("a") { row(it, if (it == "a") "b" else null).copy(date = it) }.issues.any { it.startsWith("identity_mismatch") })
        assertTrue(recoveryAccounting("0") { row(it, (it.toInt() + 1).toString()) }.issues.contains("depth_limit"))
    }
    @Test fun `conflicting duplicate or invalid amounts do not enter known subtotal`() = runBlocking {
        val duplicate = recoveryAccounting("a") { row(it).copy(measurements = listOf(call("1"), call("2"))) }
        assertFalse(duplicate.complete)
        assertEquals("0", duplicate.reportedUsd)
        for (amount in listOf("-1", "NaN", "1e999", null)) {
            val result = recoveryAccounting("a") { row(it).copy(measurements = listOf(call(amount))) }
            assertFalse(result.complete)
            assertEquals(0, result.reportedCalls)
        }
    }
}
