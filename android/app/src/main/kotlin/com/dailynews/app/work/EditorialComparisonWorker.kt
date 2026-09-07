package com.dailynews.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import com.dailynews.app.DailyNewsApplication
import com.dailynews.app.notify.NotificationHelper
import kotlinx.coroutines.*
import java.util.UUID

class EditorialComparisonWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = ForegroundInfo(NotificationHelper.PROGRESS_ID,
        NotificationHelper.progress(applicationContext, "正在运行两组偏好对比试验"),
        if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

    override suspend fun doWork(): Result {
        val source = inputData.getString("source") ?: return Result.failure()
        val id = inputData.getString("experiment") ?: return Result.failure()
        val preference = inputData.getString("preference") ?: return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            withTimeout(480_000) {
                (applicationContext as DailyNewsApplication).container.runEditorialComparison(source, id, preference)
            }
            NotificationHelper.notifyComparison(applicationContext, source, true)
            Result.success()
        } catch (_: TimeoutCancellationException) {
            NotificationHelper.notifyComparison(applicationContext, source, false)
            Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationHelper.notifyComparison(applicationContext, source, false)
            Result.failure()
        }
    }

    companion object {
        suspend fun enqueue(context: Context, source: String, date: String, preference: String): Boolean {
            require(preference.isNotBlank() && preference.length <= 1000)
            val request = OneTimeWorkRequestBuilder<EditorialComparisonWorker>()
                .setInputData(workDataOf("source" to source, "preference" to preference, "experiment" to "comparison-${UUID.randomUUID()}"))
                .addTag("report-date:$date").addTag("editorial-comparison")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            val manager = WorkManager.getInstance(context)
            return withContext(Dispatchers.IO) {
                manager.enqueueUniqueWork(DailyReportWorker.UNIQUE_WORK, ExistingWorkPolicy.KEEP, request).result.get()
                manager.getWorkInfoById(request.id).get() != null
            }
        }
    }
}
