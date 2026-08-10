package com.dailynews.app.ui.diagnostics

/**
 * Deterministic plain-text diagnostics summary shared by the copy and share
 * overflow actions. Pure function over the UI state so both entry points can be
 * golden-tested byte for byte. Empty fields contribute no lines at all.
 */
fun buildDiagnosticsSummary(state: DiagnosticsUiState): String = buildString {
    appendLine("DailyNews 运行诊断摘要")
    state.detail?.let { run ->
        appendLine("运行：${run.runId} · ${run.reportDate}")
        appendLine(
            listOf(
                "状态 ${run.status}",
                run.classification,
                "exit ${run.validatorExitCode}",
                "重试 ${run.attempt}",
                "触发 ${run.trigger}",
            ).joinToString(" · "),
        )
        formatRunDuration(run.startedAtUtc, run.finishedAtUtc)?.let { appendLine("耗时：$it") }
    } ?: state.selectedRunId?.let { appendLine("运行：$it（产物已按保留期清理）") }

    appendLine("结论：${state.advice.headline}")
    state.stage?.let { appendLine("${stageLabel(state.detail?.classification)}：$it") }

    if (state.blockingReasons.isNotEmpty()) {
        appendLine("阻断原因：")
        state.blockingReasons.forEach { appendLine("- $it") }
    }
    if (state.warnings.isNotEmpty()) {
        appendLine("警告：")
        state.warnings.forEach { appendLine("- $it") }
    }
    state.counts?.let { counts ->
        appendLine("统计：配置 ${counts.configured} · 正常 ${counts.ok} · 空 ${counts.empty} · 失败 ${counts.error} · 文章 ${counts.articles}")
    }
    val abnormal = state.feedResults.filter { it.status != "ok" }
    if (abnormal.isNotEmpty()) {
        appendLine("异常来源：${abnormal.joinToString { "${it.source} (${it.status})" }}")
    }
    val errorLogs = state.logs.filter { it.level == "ERROR" }.takeLast(3)
    if (errorLogs.isNotEmpty()) {
        appendLine("末尾 ERROR 日志：")
        errorLogs.forEach { appendLine("- ${it.step}: ${it.message}") }
    }
    state.llmCalls.takeIf { it.isNotEmpty() }?.let {
        val totals = state.llmTotals
        appendLine("LLM：${totals.calls} 次 · ${totals.inputTokens}+${totals.outputTokens} tokens · ${totals.failed} 次失败")
    }
    state.budget?.takeIf { !it.withinBudget || it.violations.isNotEmpty() }?.let { budget ->
        budget.violations.forEach { violation ->
            appendLine("预算超出：${violation.size} ${violation.actual} > ${violation.limit}")
        }
    }
    if (state.probes.isNotEmpty()) {
        val targets = state.probes.filter { it.target != "android" }
        val failed = targets.count { !it.passed }
        appendLine("网络探测：${targets.size} 项，${targets.size - failed} 通过 / $failed 失败")
    }
}.trimEnd('\n')
