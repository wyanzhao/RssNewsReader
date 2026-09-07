package com.dailynews.app.ui.common

import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.StageTimer
import com.dailynews.pipeline.observability.StageTiming
import kotlinx.serialization.decodeFromString

/** Logs are ordered by database ID; nested model operations return to their parent. */
fun activeGenerationLabel(logs: List<RunLogEntity>): String {
    val active = mutableListOf<String>()
    for (log in logs) {
        when (log.step) {
            StageTimer.START_STEP -> active.add(log.message)
            StageTimer.LOG_STEP -> {
                val stage = runCatching { ArtifactJson.codec.decodeFromString<StageTiming>(log.message).stage }.getOrNull()
                val index = active.indexOfLast { it == stage }
                if (index >= 0) active.subList(index, active.size).clear()
            }
        }
    }
    return when (active.lastOrNull()) {
        "validate" -> "正在校验文章数据…"
        "context", "artifact_audit" -> "正在准备并检查编辑材料…"
        "editorial" -> "正在编辑精选报告…"
        "llm.part1_shortlist" -> "模型正在筛选重要新闻…"
        "llm.part1_plan" -> "模型正在整理事件并生成摘要…"
        "assemble" -> "正在整理报告…"
        "review" -> "正在复核摘要与原文链接…"
        else -> "正在生成报告，请稍候…"
    }
}
