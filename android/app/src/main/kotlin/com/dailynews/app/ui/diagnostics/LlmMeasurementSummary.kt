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
