package com.dailynews.pipeline.observability

import java.math.BigDecimal
import kotlinx.serialization.Serializable

data class AccountingRun(
    val id: String, val date: String, val status: String, val parent: String?,
    val auditCalls: Int, val measurements: List<LlmAttemptMeasurement>,
    val issues: List<String> = emptyList(), val mayHaveUnrecordedCalls: Boolean = false,
)

@Serializable
data class RecoveryAccounting(
    val runIds: List<String>,
    val reportedUsd: String,
    val reportedCalls: Int,
    val observedCalls: Int,
    val complete: Boolean,
    val issues: List<String>,
)

/** Follow explicit provenance only; a missing ancestor never becomes a zero-cost run. */
suspend fun recoveryAccounting(start: String, load: suspend (String) -> AccountingRun?): RecoveryAccounting {
    val ids = mutableListOf<String>()
    val issues = mutableListOf<String>()
    var next: String? = start
    var date: String? = null
    var total = BigDecimal.ZERO
    var reported = 0
    var observed = 0
    while (next != null) {
        val id = next
        if (id in ids) { issues += "cycle:$id"; break }
        if (ids.size >= 32) { issues += "depth_limit"; break }
        val run = load(id)
        if (run == null) { issues += "missing_run:$id"; break }
        if (run.id != id || (date != null && date != run.date)) { issues += "identity_mismatch:$id"; break }
        date = run.date
        ids += id
        issues += run.issues.map { "$it:$id" }
        if (run.status == "RUNNING" || run.mayHaveUnrecordedCalls) issues += "unsettled_run:$id"
        if (run.auditCalls == 0 && run.measurements.isEmpty()) issues += "no_measurements:$id"
        val grouped = run.measurements.groupBy { it.attemptId }
        val unique = grouped.values.filter { it.size == 1 }.map { it.single() }
        if (unique.size != run.measurements.size) issues += "duplicate_measurement:$id"
        if (unique.size != run.auditCalls) issues += "measurement_gap:$id"
        observed += maxOf(run.auditCalls, grouped.size)
        for (row in unique) {
            if (row.attemptId.isBlank() || row.contractAttempt < 0 || row.physicalAttempt < 0) {
                issues += "invalid_measurement:$id"; continue
            }
            val amount = row.billedCostUsd?.toBigDecimalOrNull()?.takeIf {
                it.signum() >= 0 && it.precision() <= 30 && kotlin.math.abs(it.scale()) <= 18
            }
            if (amount == null) issues += "unknown_charge:$id" else { total += amount; reported++ }
        }
        next = run.parent
    }
    return RecoveryAccounting(ids, total.stripTrailingZeros().toPlainString(), reported, observed,
        issues.isEmpty(), issues.distinct())
}
