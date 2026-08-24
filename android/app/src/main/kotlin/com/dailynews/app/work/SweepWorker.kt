package com.dailynews.app.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dailynews.app.DailyNewsApplication
import com.dailynews.pipeline.ports.LogLevel
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Periodic incremental RSS fetch. Editorial work remains in [DailyReportWorker]. */
class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DailyNewsApplication).container
        if (!networkAvailable()) return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        val config = container.configRepository.config.first()
        val failure = try {
            withTimeout(WATCHDOG_MILLIS) {
                container.sweepStep.run(LocalDate.now(), config)
            }
            null
        } catch (error: TimeoutCancellationException) {
            error
        } catch (error: Exception) {
            error
        }
        if (failure == null) {
            val articlePrune = runCatching { container.articleRepository.prune(config.articleRetentionDays) }.getOrNull()
            if (shouldCompactAfterPrune(
                    articlesDeleted = articlePrune?.first ?: 0,
                    fetchLogsDeleted = articlePrune?.second ?: 0,
                )
            ) {
                runCatching { container.runMaintenanceRepository.compact() }
            }
            return Result.success()
        }
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        container.runLogRepository.log(
            "sweep-${LocalDate.now()}",
            "sweep",
            LogLevel.WARN,
            "periodic sweep failed after ${runAttemptCount + 1} WorkManager attempts: ${failure::class.simpleName}: ${failure.message}",
        )
        return Result.success()
    }

    private fun networkAvailable(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        internal const val UNIQUE_WORK = "article-sweep"
        internal const val UNIQUE_REFRESH = "article-sweep-refresh"
        internal const val WATCHDOG_MILLIS = 1_200_000L
        private const val MAX_RETRIES = 2

        fun ensureScheduled(context: Context, intervalMinutes: Int) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request(intervalMinutes),
            )
        }

        fun enqueueRefresh(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_REFRESH,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SweepWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag("manual-pool-refresh")
                    .build(),
            )
        }

        internal fun request(intervalMinutes: Int) =
            PeriodicWorkRequestBuilder<SweepWorker>(intervalMinutes.coerceIn(15, 360).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}
