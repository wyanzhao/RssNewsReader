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
    "editorial_contract",
    "success_branch",
    "report_contract",
    "network_preflight",
    "cost_guard",
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

/** First matching rule wins; network evidence must be classified before generic editorial failures. */
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
    // R4a: 预算闸门在花钱之前挡下了这次运行。这不是故障，改预算或等下个月。
    input.stage == "cost_guard" ->
        DiagnosticsAdvice("本月 token 预算已用尽，本次未发起生成", DiagnosticsAction.OPEN_PIPELINE_SETTINGS)
    // R4: the app saw no network, which is not the same as the device having none —
    // Doze, Data Saver and a restricted standby bucket all make getActiveNetwork()
    // return null for a background UID. A probe would run in the foreground and come
    // back all green, so send the user to the one action that actually works.
    input.classification == "DEFERRED" || input.stage == "network_preflight" ->
        DiagnosticsAdvice("没拿到可用网络，本次已顺延；如果只在灭屏时发生，去设置里允许后台运行", DiagnosticsAction.RUN_NOW)
    // R5
    input.stage == "watchdog" || input.stage == "stopped" ->
        DiagnosticsAdvice("运行超时或被系统停止，重跑通常能过", DiagnosticsAction.RUN_NOW)
    // R6
    input.stage == "context_budget" -> DiagnosticsAdvice("上下文超出预算被硬拦", DiagnosticsAction.OPEN_PIPELINE_SETTINGS)
    // R7: deterministic editorial contract failures need the persisted artifacts.
    input.stage == "editorial_contract" ->
        DiagnosticsAdvice("LLM 输出连续三轮未通过合约校验，需导出产物定位", DiagnosticsAction.EXPORT_ZIP)
    // R8: a provider call can fail in the editorial stage because DNS/TLS/429 failed;
    // routing that to credential settings hides the real remedy.
    input.classification == "UNEXPECTED_ERROR" && NetworkDiagnostics.evidenceWarrantsProbe(input.evidence) ->
        DiagnosticsAdvice("未预期错误，证据指向网络问题", DiagnosticsAction.RUN_PROBE)
    // R9
    input.stage == "editorial" || providerRegex.containsMatchIn(input.evidence) ->
        DiagnosticsAdvice("LLM provider 拒绝请求，多半是密钥或模型名", DiagnosticsAction.OPEN_PROVIDER_SETTINGS)
    // R10：装配/审校/Top N 的确定性契约失败。`success_branch` 此前落进最后的兜底，
    // 用户拿到的建议是"重跑一次"——而这类失败重跑必然精确复现。
    input.stage == "artifact_audit" || input.stage == "review" ||
        input.stage == "report_contract" || input.stage == "success_branch" ->
        DiagnosticsAdvice("产物自检/审校未过，需产物定位", DiagnosticsAction.EXPORT_ZIP)
    // R11
    input.classification == "UNEXPECTED_ERROR" -> DiagnosticsAdvice("未预期错误，重跑一次", DiagnosticsAction.RUN_NOW)
    // R12
    input.classification == "EXPECTED_BLOCK" && input.validatorExitCode == 30 ->
        DiagnosticsAdvice("今天没抓到任何文章，这不是故障", DiagnosticsAction.NONE)
    // R13
    input.classification == "EXPECTED_BLOCK" && input.validatorExitCode == 20 && input.errorFeedCount > 0 ->
        DiagnosticsAdvice("${input.errorFeedCount} 个订阅源抓取失败拦住了这次运行", DiagnosticsAction.OPEN_FEEDS)
    // R14
    input.classification == "EXPECTED_BLOCK" && input.validatorExitCode == 10 ->
        DiagnosticsAdvice("输入产物损坏，重跑会重新抓取", DiagnosticsAction.RUN_NOW)
    // R15
    input.classification == "EXPECTED_BLOCK" -> DiagnosticsAdvice("系统按规则主动阻断，不是故障", DiagnosticsAction.NONE)
    // R16：抓取阶段真的失败了。排在全部 EXPECTED_BLOCK 规则之后，所以"零文章"
    // 与"订阅源报错"仍然走它们各自的解释；能到这里的是别的原因。
    input.stage == "fetch" -> DiagnosticsAdvice("抓取阶段失败，先看订阅源状态", DiagnosticsAction.OPEN_FEEDS)
    // R17：确定性流水线阶段失败意味着产物之间自相矛盾。重跑不会让它们变得一致。
    input.stage == "validate" || input.stage == "classification" || input.stage == "pipeline" ->
        DiagnosticsAdvice("确定性流水线阶段失败，需导出产物定位", DiagnosticsAction.EXPORT_ZIP)
    // Fallback: rerun.
    else -> DiagnosticsAdvice("未预期错误，重跑一次", DiagnosticsAction.RUN_NOW)
}

/**
 * A deferred run never reached a stage that could fail — `network_preflight` is where it
 * stopped, not what broke. Shared by the detail card and the copyable summary so the two
 * can never disagree about whether this was a failure.
 */
fun stageLabel(classification: String?): String = if (classification == "DEFERRED") "阶段" else "失败阶段"

/** Drives the probe-section guidance banner; independent additive rule. */
fun probeSuggested(probes: List<*>, evidence: String): Boolean =
    probes.isEmpty() && NetworkDiagnostics.evidenceWarrantsProbe(evidence)
