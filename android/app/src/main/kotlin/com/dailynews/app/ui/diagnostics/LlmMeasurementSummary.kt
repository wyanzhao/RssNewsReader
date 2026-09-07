package com.dailynews.app.ui.diagnostics

import com.dailynews.model.ArtifactJson
import com.dailynews.pipeline.observability.LlmAttemptMeasurement
import java.math.BigDecimal

/** Coverage is explicit: missing attempt logs or provider accounting never become zero cost. */
internal fun llmMeasurementSummary(state: DiagnosticsUiState): List<String> {
    val rows = state.logs.filter { it.step == LlmAttemptMeasurement.LOG_STEP }.mapNotNull {
        runCatching { ArtifactJson.codec.decodeFromString<LlmAttemptMeasurement>(it.message) }.getOrNull()
            ?.takeIf { row -> row.attemptId.isNotBlank() && row.contractAttempt >= 0 && row.physicalAttempt >= 0 }
    }.distinctBy { it.attemptId }
    if (rows.isEmpty()) return emptyList()
    val covered = rows.size == state.llmCalls.size
    val retries = rows.count { it.physicalAttempt > 0 }
    val reworkRounds = rows.filter { it.contractAttempt > 0 }.map { it.attemptId.substringBeforeLast(':') }.distinct().size
    val firstPass = when {
        !covered || state.detail == null -> "无法判定（记录不完整）"
        state.detail.status != "SUCCESS" -> "尚未成功"
        state.detail.attempt > 1 || retries > 0 || reworkRounds > 0 || rows.any { it.outcome != "success" } -> "否"
        else -> "是"
    }
    val costs = rows.mapNotNull { row ->
        row.billedCostUsd?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.precision() <= 30 && kotlin.math.abs(it.scale()) <= 18 }
    }
    val knownCost = costs.fold(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros().toPlainString()
    val costText = when {
        costs.isEmpty() -> "费用：未知（服务未回报扣费）"
        covered && costs.size == rows.size -> "本次运行费用：$knownCost USD（服务回报，含返工与重试）"
        else -> "已知费用：$knownCost USD（${costs.size}/${state.llmCalls.size.coerceAtLeast(rows.size)} 次已回报，总费用未知）"
    }
    val usageRows = rows.count { (it.inputTokens ?: -1) >= 0 && (it.outputTokens ?: -1) >= 0 }
    return listOf(
        "首轮成功：$firstPass",
        "请求重试/修复：$retries 次 · 编辑返工：$reworkRounds 轮",
        "调用记录：${rows.size}/${state.llmCalls.size} · token 用量完整回报：$usageRows/${rows.size}",
        costText,
    )
}

internal fun llmUsageText(state: DiagnosticsUiState): String {
    fun amount(values: List<Long?>): String {
        val known = values.filterNotNull().filter { it >= 0 }
        if (known.isEmpty()) return "未知"
        val sum = known.fold(BigDecimal.ZERO) { total, value -> total + value.toBigDecimal() }.toPlainString()
        return if (known.size == values.size) sum else "$sum（部分）"
    }
    val input = amount(state.llmCalls.map { it.inputTokens })
    val output = amount(state.llmCalls.map { it.outputTokens })
    return "$input+$output tokens"
}

internal fun recoveryCostSummary(state: DiagnosticsUiState): List<String> {
    val chain = state.recoveryCosts ?: return emptyList()
    if (chain.runIds.size < 2 && state.detail?.trigger != "recovery") return emptyList()
    val amount = if (chain.reportedCalls == 0 && !chain.complete) "未知" else "${chain.reportedUsd} USD"
    return listOf(
        "恢复链路：${chain.runIds.size} 次运行（含本次）",
        if (chain.complete) "全链路费用：$amount（服务回报）" else "全链路已知费用：$amount；总费用未知",
        "费用回报：${chain.reportedCalls}/${chain.observedCalls} 次已观察调用",
    ) + chain.issues.map { issue ->
        when (issue.substringBefore(':')) {
            "cycle" -> "恢复关系存在循环"
            "depth_limit" -> "恢复链路超出检查上限"
            "missing_run" -> "父运行记录缺失"
            "identity_mismatch" -> "运行身份或报告日期不一致"
            "missing_provenance", "invalid_provenance" -> "恢复来源记录缺失或损坏"
            "unsettled_run" -> "存在进行中或中断运行，可能有未记录费用"
            "measurement_gap", "duplicate_measurement", "invalid_measurement" -> "调用测量记录不完整或冲突"
            "unknown_charge" -> "部分请求未回报费用"
            "no_measurements" -> "没有可核验的调用记录，不能按零费用计算"
            else -> "链路读取失败"
        }
    }.distinct()
}
