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
 * 周报 / 月报生成。
 *
 * **绝不确定性兜底**：LLM 失败就写 FAILED 行 + 原因，不用日摘要拼一份假的。
 * 这是用户在方案里明确选定的纯 LLM 路线的直接后果。
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
        // 幂等：已成功发布过就不再重跑，也不覆盖。
        if (repository.find(periodKey)?.status == "SUCCESS") return Result.success()

        val runId = "${kind.name.lowercase()}-$periodKey"
        val config = container.configRepository.config.first()
        return try {
            val input = repository.collectInput(kind, start, end)
            PeriodicDigestRenderer.emptyReason(input.items)?.let { reason ->
                repository.publishFailure(kind, periodKey, start, end, reason)
                return Result.success()
            }
            // 预算闸门在调用之前：超了就不发起，而不是发起后再后悔。
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
            // 失败留痕，绝不伪造内容。UI 展示这条原因并给重试入口。
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
         * 日报成功后判断是否该补一份周期简报。挂在日报之后而不是新建一套
         * PeriodicWorkRequest：这样能保证整个周期的日报都已入库，复用现有
         * AlarmManager 锚点，且天然幂等（有行即跳）。
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
