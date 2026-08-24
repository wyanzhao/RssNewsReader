package com.dailynews.app.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dailynews.app.DailyNewsApplication
import com.dailynews.app.notify.NotificationHelper
import com.dailynews.app.widget.DailyNewsWidget
import androidx.glance.appwidget.updateAll
import com.dailynews.pipeline.orchestrate.RunRequest
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import com.dailynews.pipeline.orchestrate.RetryKind
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
        // Rare now that the request carries a CONNECTED constraint: the network has to
        // drop between the constraint being satisfied and this line, or the app has to be
        // firewalled off a network that still exists (Doze, Data Saver, a restricted
        // standby bucket). Either way the pipeline never starts, so the run is deferred,
        // not failed.
        if (!networkAvailable()) {
            if (runAttemptCount < 2) return Result.retry()
            val message = "network unavailable after ${runAttemptCount + 1} WorkManager attempts"
            val runId = container.runRepository.recordPreflightDeferral(
                date,
                trigger,
                "network_preflight",
                message,
                runAttemptCount + 1,
            )
            logForegroundDegradation(runId)
            NotificationHelper.notifyDeferred(applicationContext, date, message, runId)
            ReportScheduler(applicationContext).ensureScheduled(config.scheduleTime, config.sweepIntervalMinutes)
            return Result.success()
        }
        // The budget gate must come before the money is spent. Previously only an 80% WARN line was
        // logged after the run finished — meaning the only real hard gate sat in front of the periodic
        // digest (the cheapest calls), while the most expensive daily runs were only observed after the
        // fact. The daily-run happy path measures ~65k tokens/day in practice, against a default budget
        // of 1M/month, so without a gate the default configuration itself would overspend by ~2x.
        val monthTokensBefore = container.llmCallRepository.tokensThisMonth()
        if (config.monthlyTokenBudget > 0 && monthTokensBefore >= config.monthlyTokenBudget) {
            val message = "本月 token 用量 $monthTokensBefore 已达预算 ${config.monthlyTokenBudget}，未发起生成"
            val runId = container.runRepository.recordPreflightDeferral(
                date,
                trigger,
                "cost_guard",
                message,
                runAttemptCount + 1,
            )
            logForegroundDegradation(runId)
            NotificationHelper.notifyDeferred(applicationContext, date, message, runId)
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
        if (shouldRetryScheduledProviderNetwork(scheduled, runAttemptCount, result)) {
            result.runId?.let { runId ->
                container.runLogRepository.log(
                    runId,
                    "scheduled_retry",
                    LogLevel.WARN,
                    "provider network failure; WorkManager will retry after exponential backoff " +
                        "(attempt ${runAttemptCount + 2}/$MAX_PROVIDER_NETWORK_WORK_ATTEMPTS)",
                )
            }
            return Result.retry()
        }
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
        // Periodic digests hang off the daily report: by this point every daily report of the whole
        // period is already in the database. If a row exists, skip — naturally idempotent.
        runCatching {
            PeriodicDigestWorker.dueKinds(
                date,
                config.weeklyDigestEnabled,
                config.monthlyDigestEnabled,
                config.weeklyDigestWeekday,
            ).forEach { kind -> PeriodicDigestWorker.enqueue(applicationContext, kind) }
        }
        suspend fun warnRetention(failure: Throwable) {
            result.runId?.let { runId ->
                container.runLogRepository.log(
                    runId,
                    "retention",
                    LogLevel.WARN,
                    "database retention failed after run completion: ${failure::class.simpleName}: ${failure.message}",
                )
            }
        }
        // This line used to be a bare runCatching without onFailure: artifact/log/run-record retention
        // could fail every day without any signal while the database grew without bound. The line below
        // already had logging; the two lines should not differ.
        val pruned = runCatching {
            container.runMaintenanceRepository.prune(config.artifactRetentionDays, reportRetentionDays = config.reportRetentionDays)
        }.onFailure { warnRetention(it) }.getOrNull()
        val articlePrune = runCatching { container.articleRepository.prune(config.articleRetentionDays) }
            .onFailure { warnRetention(it) }
            .getOrNull()
        // SQLite defaults to auto_vacuum = NONE: deleted pages only go onto the free list and the file
        // never shrinks. Compact after any counted delete, not only Part 2 — Epic U writes almost no
        // part=2 rows, so gating on that counter alone never ran VACUUM on the success path.
        if (shouldCompactAfterPrune(
                articlesDeleted = articlePrune?.first ?: 0,
                fetchLogsDeleted = articlePrune?.second ?: 0,
                runArtifactsDeleted = pruned?.runArtifactsDeleted ?: 0,
                runLogsDeleted = pruned?.runLogsDeleted ?: 0,
                runsDeleted = pruned?.runsDeleted ?: 0,
                part2ItemsDeleted = pruned?.part2ItemsDeleted ?: 0,
            )
        ) {
            runCatching { container.runMaintenanceRepository.compact() }.onFailure { warnRetention(it) }
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

        /**
         * Watchdog for the degraded case (foreground promotion failed).
         *
         * Must be **clearly shorter** than the foreground value: without a foreground service the
         * platform gives an ordinary worker an execution window of about ten minutes, so a 20-minute
         * application-level watchdog would always come after the system force-kill, making it
         * nonexistent — and a system force-kill gives us no chance to record anything, leaving a row
         * in the `runs` table stuck at RUNNING. Eight minutes leaves headroom for failRunning to
         * complete. The two constants were once written with the same value, which made that ternary
         * branch dead.
         */
        internal const val DEGRADED_WATCHDOG_MILLIS = 480_000L
        internal const val MAX_PROVIDER_NETWORK_WORK_ATTEMPTS = 3

        fun enqueue(context: Context, scheduled: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(scheduled))
        }

        /**
         * The background hardening is deliberately scheduled-only.
         *
         * A scheduled trigger fires while the screen is off, so it needs both: without a
         * network constraint WorkManager's in-process greedy scheduler starts the worker
         * the instant the alarm fires — inside the Doze window, where the per-UID firewall
         * makes getActiveNetwork() return null and the run dies at preflight while the
         * device itself is online. The constraint turns "fail now" into "wait for a usable
         * network", and an expedited job is granted network access and a wakelock on API
         * 31+ (API 26-30: the getForegroundInfo() foreground service; out of quota:
         * ordinary work, never dropped) so it no longer depends on a background
         * setForeground() that Android 12 often refuses.
         *
         * A manual trigger must not get either. The app is in the foreground, so its UID is
         * never firewalled — and a constrained request would sit ENQUEUED and silent when
         * the device is genuinely offline, because only SweepWorker's WorkInfo is observed
         * by the UI. Failing fast within the retry bound is the feedback the user tapped for.
         */
        internal fun request(scheduled: Boolean) = OneTimeWorkRequestBuilder<DailyReportWorker>()
                .setInputData(workDataOf(KEY_SCHEDULED to scheduled))
                .apply {
                    if (scheduled) {
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
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

internal fun shouldRetryScheduledProviderNetwork(
    scheduled: Boolean,
    runAttemptCount: Int,
    result: RunExecutionResult,
): Boolean = scheduled &&
    runAttemptCount < DailyReportWorker.MAX_PROVIDER_NETWORK_WORK_ATTEMPTS - 1 &&
    result is RunExecutionResult.Failed &&
    result.retryKind == RetryKind.PROVIDER_NETWORK
