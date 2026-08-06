package com.dailynews.app.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dailynews.app.DailyNewsApplication
import com.dailynews.app.notify.NotificationHelper
import com.dailynews.app.widget.DailyNewsWidget
import androidx.glance.appwidget.updateAll
import com.dailynews.pipeline.orchestrate.RunRequest
import com.dailynews.pipeline.ports.LogLevel
import java.time.LocalDate
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class DailyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NotificationHelper.PROGRESS_ID,
        NotificationHelper.progress(applicationContext, "抓取 RSS 并准备编辑上下文"),
        if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    override suspend fun doWork(): Result {
        val app = applicationContext as DailyNewsApplication
        val container = app.container
        val date = LocalDate.now()
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        val trigger = if (scheduled) "scheduled" else "manual"
        val config = container.configRepository.config.first()
        // A later offline alarm must never replace an already-published success with a
        // synthetic preflight failure notification for the same date.
        if (scheduled && container.reportRepository.hasSuccess(date.toString())) {
            ReportScheduler(applicationContext).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes)
            return Result.success()
        }
        val foregroundFailure = runCatching { setForeground(getForegroundInfo()) }.exceptionOrNull()
        suspend fun logForegroundDegradation(runId: String?) {
            val failure = foregroundFailure ?: return
            container.runLogRepository.log(
                runId ?: "preflight-$date",
                "foreground_service",
                LogLevel.WARN,
                "foreground promotion rejected; continued in degraded mode: ${failure::class.simpleName}: ${failure.message}",
            )
        }
        if (!networkAvailable()) {
            if (runAttemptCount < 2) return Result.retry()
            val failure = container.runRepository.recordPreflightFailure(
                date,
                trigger,
                "network_preflight",
                "network unavailable after ${runAttemptCount + 1} WorkManager attempts",
                runAttemptCount + 1,
            )
            logForegroundDegradation(failure.runId)
            publishUiResult(failure)
            ReportScheduler(applicationContext).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes)
            return Result.success()
        }
        // OEM or WorkManager policy may still preempt a degraded worker before
        // this application-level deadline when foreground promotion fails.
        val watchdogMillis = if (foregroundFailure == null) FOREGROUND_WATCHDOG_MILLIS else DEGRADED_WATCHDOG_MILLIS
        val result = try {
            withTimeout(watchdogMillis) {
                container.orchestrator.run(
                    RunRequest(
                        reportDate = date,
                        reportPath = File(applicationContext.filesDir, "reports/rss-report-$date.md").absolutePath,
                        config = config,
                        trigger = trigger,
                    ),
                )
            }
        } catch (_: TimeoutCancellationException) {
            container.runRepository.failRunning(date, "watchdog", "run exceeded the ${watchdogMillis / 60_000} minute watchdog")
        } catch (stop: CancellationException) {
            // WorkManager stopped us. Record it before the scope dies, or the run
            // row stays RUNNING until the next cold start notices.
            withContext(NonCancellable) {
                container.runRepository.failRunning(date, "stopped", "WorkManager stopped the run before it finished")
            }
            throw stop
        }
        container.runRepository.finished(result)
        logForegroundDegradation(result.runId)
        val monthTokens = container.llmCallRepository.tokensThisMonth()
        if (config.monthlyTokenBudget > 0 && monthTokens.toDouble() / config.monthlyTokenBudget >= 0.8) {
            result.runId?.let { runId ->
                container.runLogRepository.log(
                    runId,
                    "cost_guard",
                    LogLevel.WARN,
                    "monthly token usage $monthTokens reached at least 80% of budget ${config.monthlyTokenBudget}",
                )
            }
        }
        // 周期简报挂在日报之后：此时整个周期的日报都已入库。有行即跳，天然幂等。
        runCatching {
            PeriodicDigestWorker.dueKinds(
                date,
                config.weeklyDigestEnabled,
                config.monthlyDigestEnabled,
                config.weeklyDigestWeekday,
            ).forEach { kind -> PeriodicDigestWorker.enqueue(applicationContext, kind) }
        }
        runCatching { container.runMaintenanceRepository.prune(config.artifactRetentionDays) }
        runCatching { container.articleRepository.prune(config.articleRetentionDays) }
            .onFailure { failure ->
                result.runId?.let { runId ->
                    container.runLogRepository.log(
                        runId,
                        "retention",
                        LogLevel.WARN,
                        "database retention failed after run completion: ${failure::class.simpleName}: ${failure.message}",
                    )
                }
            }
        publishUiResult(result)
        ReportScheduler(applicationContext).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes)
        // Once the deterministic pipeline starts, run state owns failure semantics.
        return Result.success()
    }

    private fun networkAvailable(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun publishUiResult(result: com.dailynews.pipeline.orchestrate.RunExecutionResult) {
        NotificationHelper.notifyResult(applicationContext, result)
        runCatching { DailyNewsWidget().updateAll(applicationContext) }
    }

    companion object {
        internal const val KEY_SCHEDULED = "scheduled"
        internal const val UNIQUE_WORK = "daily-report"
        internal const val FOREGROUND_WATCHDOG_MILLIS = 1_200_000L
        internal const val DEGRADED_WATCHDOG_MILLIS = 1_200_000L

        fun enqueue(context: Context, scheduled: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(scheduled))
        }

        internal fun request(scheduled: Boolean) = OneTimeWorkRequestBuilder<DailyReportWorker>()
                .setInputData(workDataOf(KEY_SCHEDULED to scheduled))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        fun retryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            9011,
            Intent(context, AlarmReceiver::class.java).putExtra(KEY_SCHEDULED, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
