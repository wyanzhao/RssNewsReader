package com.dailynews.app.ui.brief

import androidx.work.WorkInfo

data class GenerationProgress(val active: Boolean = false, val queued: Boolean = false, val label: String = "")

fun generationProgressFor(infos: List<WorkInfo>, displayedDate: String, today: String): GenerationProgress {
    val relevant = infos.filter { work ->
        "report-date:$displayedDate" in work.tags ||
            (displayedDate == today && ("report-current-day" in work.tags || work.tags.none { it.startsWith("report-date:") }))
    }
    if (relevant.any { it.state == WorkInfo.State.RUNNING }) {
        return GenerationProgress(active = true, label = "正在准备生成任务…")
    }
    val queued = relevant.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    return when {
        queued.any { it.runAttemptCount > 0 } -> GenerationProgress(true, true, "生成任务等待重试，网络或系统条件满足后继续…")
        queued.isNotEmpty() -> GenerationProgress(true, true, "生成任务已排队，等待网络或系统调度…")
        else -> GenerationProgress()
    }
}
