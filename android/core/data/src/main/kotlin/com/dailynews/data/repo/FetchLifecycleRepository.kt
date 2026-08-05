package com.dailynews.data.repo

import com.dailynews.model.RawRun
import com.dailynews.pipeline.ports.FetchLifecyclePort
import com.dailynews.pipeline.ports.LogLevel
import java.time.LocalDate

class FetchLifecycleRepository(
    private val runs: RunRepository,
    private val reports: ReportRepository,
    private val logs: RunLogRepository,
) : FetchLifecyclePort {
    override suspend fun beforeRetry(reportDate: LocalDate) {
        runs.failRunning(reportDate, "bounded_retry", "attempt 1 ended unexpectedly; starting the single allowed retry")
    }

    override suspend fun recordStarted(raw: RawRun, reportDate: LocalDate, attempt: Int, trigger: String) {
        runs.started(raw, reportDate.toString(), attempt, trigger)
        if (reports.hasSuccess(reportDate.toString())) {
            logs.log(raw.meta.runId, "orchestrator", LogLevel.WARN, "same-date success already exists; continuing explicit rerun")
        }
    }

    override suspend fun recordWarning(raw: RawRun, step: String, message: String) {
        logs.log(raw.meta.runId, step, LogLevel.WARN, message)
    }
}
