package com.dailynews.app.ui.diagnostics

import com.dailynews.data.db.RunEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.model.ArtifactJson
import com.dailynews.model.ContextBudget
import com.dailynews.model.ContextBudgetCounts
import com.dailynews.model.ContextBudgetLimits
import com.dailynews.model.ContextBudgetSizes
import com.dailynews.model.ContextBudgetViolation
import com.dailynews.model.FeedResult
import com.dailynews.model.ValidationCounts
import com.dailynews.model.ValidationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Truncation cap for raw JSON shown in the advanced section. */
const val MAX_RAW_CHARS = 4000

enum class ArtifactStatus { MISSING, PARSED, DEGRADED, UNPARSEABLE }

data class ArtifactPayload(
    val raw: String? = null,
    val status: ArtifactStatus = ArtifactStatus.MISSING,
    val truncated: Boolean = false,
)

data class ResolvedValidation(
    val blockingReasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val counts: ValidationCounts? = null,
    val feedResults: List<FeedResult> = emptyList(),
)

data class ContextBudgetView(
    val withinBudget: Boolean,
    val violations: List<ContextBudgetViolation>,
    val sizes: ContextBudgetSizes,
    val limits: ContextBudgetLimits,
    val counts: ContextBudgetCounts,
)

data class ResolvedArtifacts(
    val validation: ResolvedValidation = ResolvedValidation(),
    val validationArtifact: ArtifactPayload = ArtifactPayload(),
    val budget: ContextBudgetView? = null,
    val budgetArtifact: ArtifactPayload = ArtifactPayload(),
)

private fun rawPayload(text: String): ArtifactPayload =
    if (text.length <= MAX_RAW_CHARS) ArtifactPayload(raw = text)
    else ArtifactPayload(raw = text.take(MAX_RAW_CHARS), truncated = true)

private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String>? =
    runCatching { element?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()

/**
 * Three-step ladder for `validation.json`: strict typed decode, tolerant field-by-field
 * extraction, then UNPARSEABLE. Each step is independently `runCatching`-guarded so one
 * drifted field never sinks the fields that are still readable.
 */
fun resolveValidationArtifact(text: String?): Pair<ResolvedValidation, ArtifactPayload> {
    if (text.isNullOrBlank()) return ResolvedValidation() to ArtifactPayload()
    val payload = rawPayload(text)

    val strict = runCatching { ArtifactJson.codec.decodeFromString<ValidationResult>(text) }.getOrNull()
    if (strict != null) {
        return ResolvedValidation(strict.blockingReasons, strict.warnings, strict.counts, strict.feedResults) to
            payload.copy(status = ArtifactStatus.PARSED)
    }

    val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    if (obj != null) {
        val reasons = stringList(obj["blocking_reasons"]).orEmpty()
        val warnings = stringList(obj["warnings"]).orEmpty()
        val counts = obj["counts"]?.let { runCatching { ArtifactJson.codec.decodeFromJsonElement<ValidationCounts>(it) }.getOrNull() }
        val feeds = obj["feed_results"]
            ?.let { runCatching { ArtifactJson.codec.decodeFromJsonElement<List<FeedResult>>(it) }.getOrNull() }
            .orEmpty()
        return ResolvedValidation(reasons, warnings, counts, feeds) to payload.copy(status = ArtifactStatus.DEGRADED)
    }
    return ResolvedValidation() to payload.copy(status = ArtifactStatus.UNPARSEABLE)
}

private val EMPTY_SIZES = ContextBudgetSizes(0, 0, 0, 0)
private val EMPTY_LIMITS = ContextBudgetLimits(0, 0, 0, 0)
private val EMPTY_BUDGET_COUNTS = ContextBudgetCounts(0, 0, 0, 0)

/** Two-step ladder for `context_budget.json`: strict typed decode, then tolerant extraction. */
fun resolveBudgetArtifact(text: String?): Pair<ContextBudgetView?, ArtifactPayload> {
    if (text.isNullOrBlank()) return null to ArtifactPayload()
    val payload = rawPayload(text)

    val strict = runCatching { ArtifactJson.codec.decodeFromString<ContextBudget>(text) }.getOrNull()
    if (strict != null) {
        return ContextBudgetView(strict.withinBudget, strict.violations, strict.sizes, strict.limits, strict.counts) to
            payload.copy(status = ArtifactStatus.PARSED)
    }

    val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return null to payload.copy(status = ArtifactStatus.UNPARSEABLE)
    val withinBudget = runCatching { obj["within_budget"]?.jsonPrimitive?.boolean }.getOrNull()
        ?: return null to payload.copy(status = ArtifactStatus.UNPARSEABLE)
    fun <T> element(name: String, decode: (kotlinx.serialization.json.JsonElement) -> T): T? =
        obj[name]?.let { runCatching { decode(it) }.getOrNull() }
    val view = ContextBudgetView(
        withinBudget = withinBudget,
        violations = element("violations") { ArtifactJson.codec.decodeFromJsonElement<List<ContextBudgetViolation>>(it) }.orEmpty(),
        sizes = element("sizes") { ArtifactJson.codec.decodeFromJsonElement<ContextBudgetSizes>(it) } ?: EMPTY_SIZES,
        limits = element("limits") { ArtifactJson.codec.decodeFromJsonElement<ContextBudgetLimits>(it) } ?: EMPTY_LIMITS,
        counts = element("counts") { ArtifactJson.codec.decodeFromJsonElement<ContextBudgetCounts>(it) } ?: EMPTY_BUDGET_COUNTS,
    )
    return view to payload.copy(status = ArtifactStatus.DEGRADED)
}

/** Same-shape JSON lists written by RunRepository for finished/failed runs. */
private fun entityStrings(json: String): List<String> =
    runCatching { ArtifactJson.codec.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())

/**
 * Full resolution chain: artifact text first, Room entity second, final ERROR log last.
 * Pure function — runs off the ViewModel on Dispatchers.IO.
 */
fun resolveDiagnosticsArtifacts(
    validationText: String?,
    budgetText: String?,
    entity: RunEntity?,
    logs: List<RunLogEntity>,
): ResolvedArtifacts {
    val (validation, validationArtifact) = resolveValidationArtifact(validationText)
    val (budget, budgetArtifact) = resolveBudgetArtifact(budgetText)

    var resolved = validation
    if (entity != null) {
        val entityReasons = entityStrings(entity.blockingReasonsJson)
        val entityWarnings = entityStrings(entity.warningsJson)
        val entityCounts = entity.finishedAtUtc
            ?.let { runCatching { ArtifactJson.codec.decodeFromString<ValidationCounts>(entity.countsJson) }.getOrNull() }
        // A success-branch failure deliberately keeps validation.json as passed=true.
        // That artifact still owns feed counts/warnings, but it must never erase the
        // later terminal reason stored on the run row. Put exit-40 reasons first so
        // stageFrom() sees the actual failing stage instead of an earlier validator note.
        val terminalReasonFirst = entity.validatorExitCode == 40 && entityReasons.isNotEmpty()
        resolved = ResolvedValidation(
            blockingReasons = (
                if (terminalReasonFirst) entityReasons + resolved.blockingReasons
                else resolved.blockingReasons + entityReasons
                ).distinct(),
            warnings = (resolved.warnings + entityWarnings).distinct(),
            counts = resolved.counts ?: entityCounts,
            feedResults = resolved.feedResults,
        )
    }
    // Ladder bottom: a parsed, successful validation artifact may coexist with a
    // later unexpected failure. Warnings must not suppress the final ERROR fallback.
    val failedOrMissingRun = entity == null || entity.status == "FAILED" || entity.classification == "INTERRUPTED"
    if (failedOrMissingRun && resolved.blockingReasons.isEmpty()) {
        logs.lastOrNull { it.level == "ERROR" }?.let { error ->
            resolved = resolved.copy(blockingReasons = listOf("${error.step}: ${error.message}"))
        }
    }
    return ResolvedArtifacts(resolved, validationArtifact, budget, budgetArtifact)
}
