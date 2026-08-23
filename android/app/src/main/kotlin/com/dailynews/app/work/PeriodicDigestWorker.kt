package com.dailynews.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dailynews.app.DailyNewsApplication
import com.dailynews.data.repo.PeriodKind
import com.dailynews.data.repo.PeriodicReportRepository
import com.dailynews.pipeline.editorial.PeriodicDigestRenderer
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Weekly / monthly report generation.
 *
 * **Absolutely no fallback**: if the LLM fails, write a FAILED row + reason; never fake one out of
 * daily digests. This is a direct consequence of the pure-LLM route the user explicitly chose in the
 * plan.
 */
class PeriodicDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as DailyNewsApplication
        val container = app.container
        val kind = runCatching { PeriodKind.valueOf(inputData.getString(KEY_KIND).orEmpty()) }.getOrNull()
            ?: return Result.failure()
        val today = LocalDate.now()
        val (start, end) = when (kind) {
            PeriodKind.WEEKLY -> PeriodicReportRepository.previousWeek(today)
            PeriodKind.MONTHLY -> PeriodicReportRepository.previousMonth(today)
        }
        val periodKey = PeriodicReportRepository.periodKeyFor(kind, start)
        val repository = container.periodicReportRepository
        // Idempotency: if it was already published successfully, neither rerun nor overwrite.
        if (repository.find(periodKey)?.status == "SUCCESS") return Result.success()

        val runId = "${kind.name.lowercase()}-$periodKey"
        val config = container.configRepository.config.first()
        return try {
            val input = repository.collectInput(kind, start, end)
            PeriodicDigestRenderer.emptyReason(input.items)?.let { reason ->
                repository.publishFailure(kind, periodKey, start, end, reason)
                return Result.success()
            }
            // The budget gate sits before the call: if over budget, do not start — rather than start
            // and regret it later.
            val monthTokens = container.llmCallRepository.tokensThisMonth()
            if (config.monthlyTokenBudget > 0 && monthTokens >= config.monthlyTokenBudget) {
                repository.publishFailure(
                    kind, periodKey, start, end,
                    "本月 token 用量 $monthTokens 已达预算 ${config.monthlyTokenBudget}，未发起周期简报生成。",
                )
                return Result.success()
            }

            val digest = container.editorialEngine.digest(runId, input, config.maxLlmCallsPerRun, config.llmExecution)
            repository.publish(kind, input, digest, PeriodicDigestRenderer.render(input, digest))
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Failures leave a trace; content is never fabricated. The UI shows this reason and
            // offers a retry entry point.
            repository.publishFailure(kind, periodKey, start, end, error.message ?: error::class.simpleName.orEmpty())
            Result.success()
        }
    }

    companion object {
        internal const val KEY_KIND = "kind"

        fun enqueue(context: Context, kind: PeriodKind) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "periodic-digest-${kind.name.lowercase()}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PeriodicDigestWorker>()
                    .setInputData(workDataOf(KEY_KIND to kind.name))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * After a daily report succeeds, decide whether a periodic digest is due. Hung off the daily
         * report instead of a new PeriodicWorkRequest set: this guarantees every daily report of the
         * whole period is already in the database, reuses the existing AlarmManager anchor, and is
         * naturally idempotent (row exists, skip).
         */
        internal fun dueKinds(
            today: LocalDate,
            weeklyEnabled: Boolean,
            monthlyEnabled: Boolean,
            weeklyWeekday: Int,
        ): List<PeriodKind> = buildList {
            if (weeklyEnabled && today.dayOfWeek.value == weeklyWeekday.coerceIn(1, 7)) add(PeriodKind.WEEKLY)
            if (monthlyEnabled && today.dayOfMonth == 1) add(PeriodKind.MONTHLY)
        }
    }
}
