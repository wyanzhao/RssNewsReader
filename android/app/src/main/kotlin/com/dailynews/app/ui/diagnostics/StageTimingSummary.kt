package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.StageTimer
import com.dailynews.pipeline.observability.StageTiming
import kotlinx.serialization.decodeFromString

internal fun stageTimingsFor(logs: List<RunLogEntity>): List<StageTiming> = logs
    .filter { it.step == StageTimer.LOG_STEP }
    .mapNotNull { runCatching { ArtifactJson.codec.decodeFromString<StageTiming>(it.message) }.getOrNull() }
    .filter { it.elapsedMs >= 0 }

internal fun stageTimingText(timing: StageTiming): String {
    val label = when (timing.stage) {
        "fetch" -> "准备文章池"
        "validate" -> "素材校验"
        "context" -> "准备编辑素材"
        "artifact_audit" -> "编辑前审计"
        "editorial" -> "编辑全流程"
        "assemble" -> "组装报告"
        "review" -> "报告复核"
        "llm.part1_shortlist" -> "模型选题"
        "llm.part1_plan" -> "模型编辑"
        "llm.periodic_digest" -> "模型周期总结"
        else -> timing.stage
    }
    val outcome = when (timing.outcome) {
        "success" -> "完成"
        "failed" -> "失败"
        "blocked" -> "未通过"
        "cancelled" -> "已中断"
        else -> timing.outcome
    }
    return "$label：${timing.elapsedMs / 1000}.${(timing.elapsedMs % 1000) / 100} 秒 · $outcome"
}
