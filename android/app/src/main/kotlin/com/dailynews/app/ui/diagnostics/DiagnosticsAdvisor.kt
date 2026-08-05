package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.RunLogEntity
import com.dailynews.pipeline.orchestrate.NetworkDiagnostics

/**
 * Deterministic "what happened / why / what to do" rule table for the diagnostics
 * screen. Every headline is a plain Kotlin constant on purpose: the advisor must be
 * unit-testable without Robolectric or string resources.
 */
enum class DiagnosticsAction {
    NONE,
    RUN_NOW,
    RUN_PROBE,
    OPEN_FEEDS,
    OPEN_PIPELINE_SETTINGS,
    OPEN_PROVIDER_SETTINGS,
    EXPORT_ZIP,
}

data class DiagnosticsAdvice(val headline: String, val action: DiagnosticsAction)

data class DiagnosticsAdviceInput(
    val hasRuns: Boolean,
    val status: String,
    val classification: String,
    val stage: String?,
    val validatorExitCode: Int,
    val warningCount: Int,
    val errorFeedCount: Int,
    val evidence: String,
)

/** Stage names written by RunOrchestrator + DailyReportWorker into blocking reasons. */
val KNOWN_DIAGNOSTIC_STAGES = setOf(
    "fetch",
    "validate",
    "classification",
    "pipeline",
    "context_budget",
    "artifact_audit",
    "review",
    "editorial",
    "success_branch",
    "network_preflight",
    "watchdog",
    "stopped",
)

private val providerRegex = Regex(
    "unauthorized|invalid[_ ]?api[_ ]?key|insufficient_quota|model .*not found|401",
    RegexOption.IGNORE_CASE,
)

/**
 * Recovers the failing stage from a `"$stage: $message"` blocking reason. Only
 * accepted when the prefix is a known stage and an actual separator was present,
 * so free-form reasons are never mistaken for stages.
 */
fun stageFrom(blockingReasons: List<String>): String? {
    val first = blockingReasons.firstOrNull()?.trim().orEmpty()
    if (first.isEmpty()) return null
    val candidate = first.substringBefore(": ").trim()
    return candidate.takeIf { it in KNOWN_DIAGNOSTIC_STAGES && it != first }
}

/** Concatenated proof string used by the provider/network regexes. */
fun evidenceFor(blockingReasons: List<String>, warnings: List<String>, logs: List<RunLogEntity>): String =
    (
        blockingReasons +
            warnings +
            logs.filter { it.level == "WARN" || it.level == "ERROR" }.map { "${it.step}: ${it.message}" }
        ).joinToString(" ")

/** First matching rule wins; R7 must stay ahead of R9 (403/429 hit both). */
fun advise(input: DiagnosticsAdviceInput): DiagnosticsAdvice = when {
    // R0
    !input.hasRuns -> DiagnosticsAdvice("还没有运行记录", DiagnosticsAction.RUN_NOW)
    // R1
    input.status == "RUNNING" -> DiagnosticsAdvice("正在运行，稍候", DiagnosticsAction.NONE)
    // R2: 'INTERRUPTED' is written by RunDao.markRunningInterrupted and is not a RunClassification value.
    input.classification == "INTERRUPTED" -> DiagnosticsAdvice("上次运行被系统中断，重跑即可", DiagnosticsAction.RUN_NOW)
    // R3
    input.classification == "SUCCESS" -> DiagnosticsAdvice(
        if (input.warningCount > 0) "这次运行正常，有 ${input.warningCount} 条警告，不影响出报" else "这次运行正常",
        DiagnosticsAction.NONE,
    )
    // R4
    input.stage == "network_preflight" -> DiagnosticsAdvice("运行开始时设备没有可用网络", DiagnosticsAction.RUN_PROBE)
    // R5
    input.stage == "watchdog" || input.stage == "stopped" ->
        DiagnosticsAdvice("运行超时或被系统停止，重跑通常能过", DiagnosticsAction.RUN_NOW)
    // R6
    input.stage == "context_budget" -> DiagnosticsAdvice("上下文超出预算被硬拦", DiagnosticsAction.OPEN_PIPELINE_SETTINGS)
    // R7
    input.stage == "editorial" || providerRegex.containsMatchIn(input.evidence) ->
        DiagnosticsAdvice("LLM provider 拒绝请求，多半是密钥或模型名", DiagnosticsAction.OPEN_PROVIDER_SETTINGS)
    // R8
    input.stage == "artifact_audit" || input.stage == "review" ->
        DiagnosticsAdvice("产物自检/审校未过，需产物定位", DiagnosticsAction.EXPORT_ZIP)
    // R9
    input.classification == "UNEXPECTED_ERROR" && NetworkDiagnostics.evidenceWarrantsProbe(input.evidence) ->
        DiagnosticsAdvice("未预期错误，证据指向网络问题", DiagnosticsAction.RUN_PROBE)
    // R10
    input.classification == "UNEXPECTED_ERROR" -> DiagnosticsAdvice("未预期错误，重跑一次", DiagnosticsAction.RUN_NOW)
    // R11
    input.classification == "EXPECTED_BLOCK" && input.validatorExitCode == 30 ->
        DiagnosticsAdvice("今天没抓到任何文章，这不是故障", DiagnosticsAction.NONE)
    // R12
    input.classification == "EXPECTED_BLOCK" && input.validatorExitCode == 20 && input.errorFeedCount > 0 ->
        DiagnosticsAdvice("${input.errorFeedCount} 个订阅源抓取失败拦住了这次运行", DiagnosticsAction.OPEN_FEEDS)
    // R13
    input.classification == "EXPECTED_BLOCK" && input.validatorExitCode == 10 ->
        DiagnosticsAdvice("输入产物损坏，重跑会重新抓取", DiagnosticsAction.RUN_NOW)
    // R14
    input.classification == "EXPECTED_BLOCK" -> DiagnosticsAdvice("系统按规则主动阻断，不是故障", DiagnosticsAction.NONE)
    // Fallback: rerun.
    else -> DiagnosticsAdvice("未预期错误，重跑一次", DiagnosticsAction.RUN_NOW)
}

/** Drives the probe-section guidance banner; independent additive rule. */
fun probeSuggested(probes: List<*>, evidence: String): Boolean =
    probes.isEmpty() && NetworkDiagnostics.evidenceWarrantsProbe(evidence)
