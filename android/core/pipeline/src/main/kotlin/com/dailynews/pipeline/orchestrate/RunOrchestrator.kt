package com.dailynews.pipeline.orchestrate

import com.dailynews.model.ArtifactJson
import com.dailynews.model.AssembledReport
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawRun
import com.dailynews.model.RunClassification
import com.dailynews.model.ValidationResult
import com.dailynews.model.LlmContext
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part2Draft
import com.dailynews.model.classifyRun
import com.dailynews.pipeline.context.CacheLookup
import com.dailynews.pipeline.context.ContextArtifacts
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.editorial.EditorialCacheKeys
import com.dailynews.pipeline.editorial.FallbackReportRenderer
import com.dailynews.pipeline.editorial.ReportAssembler
import com.dailynews.pipeline.editorial.ReportReviewer
import com.dailynews.pipeline.editorial.TopNRenderer
import com.dailynews.pipeline.editorial.ArtifactAudit
import com.dailynews.pipeline.editorial.ReportReview
import com.dailynews.pipeline.flow.EditorialEngine
import com.dailynews.pipeline.flow.EditorialLlmException
import com.dailynews.pipeline.ports.ArtifactSink
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.EditorialCacheRecord
import com.dailynews.pipeline.ports.EditorialCacheStore
import com.dailynews.pipeline.ports.FailureReportSink
import com.dailynews.pipeline.ports.FeedSource
import com.dailynews.pipeline.ports.FetchPort
import com.dailynews.pipeline.ports.LogLevel
import com.dailynews.pipeline.ports.ReportSink
import com.dailynews.pipeline.ports.RunLogSink
import com.dailynews.pipeline.ports.SeenLinksStore
import com.dailynews.pipeline.ports.TopNReportSink
import com.dailynews.pipeline.validate.QcValidator
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException

data class RunRequest(
    val reportDate: LocalDate,
    val reportPath: String,
    val config: PipelineConfig,
    val trigger: String = "manual",
)

class DamagedInputException(message: String, val damagedRunId: String? = null, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

fun interface ArtifactAuditGate {
    fun audit(context: LlmContext, validation: ValidationResult): ArtifactAudit
}

fun interface ReportReviewGate {
    fun review(
        markdown: String,
        reportPath: String,
        context: LlmContext,
        validation: ValidationResult,
        part1: Part1Plan,
        part2: Part2Draft,
        topN: Int,
        part2Mode: com.dailynews.model.Part2Mode,
    ): ReportReview
}

sealed interface RunExecutionResult {
    val reportDate: LocalDate
    val runId: String?

    data class Success(
        override val reportDate: LocalDate,
        override val runId: String,
        val report: AssembledReport,
        val warnings: List<String>,
    ) : RunExecutionResult

    data class ExpectedBlock(
        override val reportDate: LocalDate,
        override val runId: String,
        val validation: ValidationResult,
        val failureMarkdown: String,
        val validatorExitCode: Int = 20,
    ) : RunExecutionResult

    data class Failed(
        override val reportDate: LocalDate,
        override val runId: String?,
        val stage: String,
        val message: String,
    ) : RunExecutionResult
}

class RunOrchestrator(
    private val fetch: FetchPort,
    private val feeds: FeedSource,
    private val validator: QcValidator,
    private val contexts: LlmContextBuilder,
    private val editorial: EditorialEngine,
    private val assembler: ReportAssembler,
    private val reportSink: ReportSink,
    private val failureSink: FailureReportSink,
    private val topNSink: TopNReportSink,
    private val artifactSink: ArtifactSink,
    private val logSink: RunLogSink,
    private val seenLinks: SeenLinksStore,
    private val cache: EditorialCacheStore,
    private val clock: ClockProvider,
    private val unexpectedDiagnostics: UnexpectedFailureDiagnostics = NoOpUnexpectedFailureDiagnostics,
    private val artifactAuditGate: ArtifactAuditGate = ArtifactAuditGate { context, validation ->
        com.dailynews.pipeline.editorial.EditorialContracts.audit(context, validation)
    },
    private val reportReviewGate: ReportReviewGate = ReportReviewGate { markdown, reportPath, context, validation, part1, part2, topN, part2Mode ->
        ReportReviewer.review(markdown, reportPath, context, validation, part1, part2, topN, part2Mode)
    },
) {
    suspend fun run(request: RunRequest): RunExecutionResult {
        val config = request.config.normalized()
        var lastFailure: Throwable? = null
        for (attempt in 1..2) {
            val raw = try {
                fetch.fetch(request.reportDate, attempt, request.trigger, config)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is DamagedInputException) {
                    val runId = error.damagedRunId ?: "damaged-${request.reportDate}-${clock.now().epochSecond}"
                    val validation = validator.damagedInput(error.message ?: "input artifact is damaged")
                    val markdown = FallbackReportRenderer.renderDamaged(request.reportDate.toString(), validation.result.blockingReasons.joinToString("; "))
                    snapshot(runId, "validation.json", ArtifactJson.codec.encodeToString(validation.result))
                    failureSink.publishFailure(request.reportDate.toString(), markdown)
                    return RunExecutionResult.ExpectedBlock(request.reportDate, runId, validation.result, markdown, validation.exitClass.code)
                }
                lastFailure = error
                if (attempt == 1) continue
                return unexpectedFailure(request, null, "fetch", error.message ?: "unexpected fetch failure", error)
            }
            val runId = raw.meta.runId
            // Preserve the fetched authority artifact even if validation itself crashes.
            snapshot(runId, "raw.json", ArtifactJson.codec.encodeToString(raw))
            val validation = try {
                validator.validate(raw, com.dailynews.model.FeedConfigDocument(feeds.enabledFeeds()))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (attempt == 1) continue
                return unexpectedFailure(request, runId, "validate", error.message ?: error::class.simpleName.orEmpty(), error)
            }
            snapshot(runId, "validation.json", ArtifactJson.codec.encodeToString(validation.result))
            when (classifyRun(validation.exitClass.code, validation.result.passed)) {
                RunClassification.UNEXPECTED_ERROR -> {
                    logSink.log(runId, "orchestrator", LogLevel.WARN, "unexpected error classification on attempt $attempt")
                    if (attempt == 1) continue
                    return unexpectedFailure(request, runId, "classification", "unexpected pipeline result after bounded retry")
                }
                RunClassification.EXPECTED_BLOCK -> {
                    val markdown = FallbackReportRenderer.render(raw, validation.result, request.reportDate.toString(), config)
                    failureSink.publishFailure(request.reportDate.toString(), markdown)
                    return RunExecutionResult.ExpectedBlock(request.reportDate, runId, validation.result, markdown, validation.exitClass.code)
                }
                RunClassification.SUCCESS -> return successBranch(request, config, raw, validation.result)
            }
        }
        return unexpectedFailure(request, null, "pipeline", lastFailure?.message ?: "pipeline ended without a result")
    }

    private suspend fun successBranch(
        request: RunRequest,
        config: PipelineConfig,
        raw: RawRun,
        validation: ValidationResult,
    ): RunExecutionResult {
        val runId = raw.meta.runId
        return try {
            val artifacts = contexts.build(
                raw,
                validation,
                request.reportDate.toString(),
                request.reportPath,
                config,
                cacheLookup = CacheLookup { article ->
                    cache.find(EditorialCacheKeys.cacheKey(article))
                        ?: cache.find(EditorialCacheKeys.legacyCacheKey(article))
                },
            )
            snapshotContexts(runId, artifacts)
            if (!artifacts.contextBudget.withinBudget && request.config.contextBudget.hardBlock) {
                return RunExecutionResult.Failed(request.reportDate, runId, "context_budget", artifacts.contextBudget.violations.joinToString("; "))
            }
            val audit = artifactAuditGate.audit(artifacts.llmContext, validation)
            if (!audit.passed) return RunExecutionResult.Failed(request.reportDate, runId, "artifact_audit", audit.errors.joinToString("; "))

            val output = editorial.edit(
                runId,
                artifacts.llmContext,
                artifacts.part1Brief,
                artifacts.part2Context,
                artifacts.contextBudget,
                config.part1MaxItems,
                config.maxLlmCallsPerRun,
                config.part2Mode,
                config.llmExecution,
            )
            output.part1ShortlistJson?.let { snapshot(runId, "part1_shortlist.json", it) }
            output.part1ShortlistContextJson?.let { snapshot(runId, "part1_shortlist_context.json", it) }
            snapshot(runId, "part1_plan.json", ArtifactJson.codec.encodeToString(output.part1))
            output.part2MissingSummariesJson?.let { snapshot(runId, "part2_missing_summaries.json", it) }
            snapshot(runId, "part2_draft.json", ArtifactJson.codec.encodeToString(output.part2))
            val report = assembler.assemble(
                artifacts.llmContext,
                validation,
                output.part1,
                output.part2,
                config.part1MaxItems,
                request.reportPath,
                renderTopN = false,
                part2Mode = config.part2Mode,
            )

            // ReportAssembler owns the only success write. Review deliberately runs after it.
            reportSink.publish(report)
            val review = reportReviewGate.review(report.markdown, request.reportPath, artifacts.llmContext, validation, output.part1, output.part2, config.part1MaxItems, config.part2Mode)
            if (!review.passed) {
                val reason = review.errors.joinToString("; ")
                reportSink.markFailed(request.reportDate.toString(), reason)
                return RunExecutionResult.Failed(request.reportDate, runId, "review", reason)
            }

            // A failed review must not consume cache/seen coverage for a report that
            // was immediately downgraded and never became publishable.
            val warnings = mutableListOf<String>()
            runCatching { updateCache(artifacts, output, config) }
                .onSuccess { stats ->
                    // 代码补齐比例长期居高，说明 prompt 的 event_key 复用规则没生效——
                    // 那是收紧 validatePart1 的触发条件，不是靠猜。
                    logSink.log(
                        runId,
                        "story_thread",
                        LogLevel.INFO,
                        "event_key reused=${stats.reusedExisting} model=${stats.modelSupplied} code=${stats.codeDerived}",
                    )
                }
                .onFailure { warnings += "editorial cache update failed: ${it.message}" }
            runCatching { updateSeenLinks(request.reportDate, artifacts) }.onFailure { warnings += "seen-links update failed: ${it.message}" }
            val topN = TopNRenderer.render(artifacts.llmContext, output.part1, config.part1MaxItems, request.reportPath)
            topNSink.publishTopN(request.reportDate.toString(), topN)
            snapshot(runId, "top${config.part1MaxItems}.md", topN)
            val finalReport = report.copy(topNMarkdown = topN)
            warnings.forEach { logSink.log(runId, "assemble", LogLevel.WARN, it) }
            RunExecutionResult.Success(request.reportDate, runId, finalReport, warnings)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            fail(request, runId, if (error is EditorialLlmException) "editorial" else "success_branch", error)
        }
    }

    private suspend fun updateSeenLinks(date: LocalDate, artifacts: ContextArtifacts) {
        seenLinks.recordReportedLinks(artifacts.llmContext.allArticles.map { it.link }, date)
    }

    /** 一轮编辑里 event_key 的来源分布。用来判断 prompt 的复用规则有没有真的生效。 */
    internal data class StoryThreadStats(val modelSupplied: Int, val codeDerived: Int, val reusedExisting: Int)

    private suspend fun updateCache(
        artifacts: ContextArtifacts,
        output: com.dailynews.pipeline.flow.EditorialOutput,
        config: PipelineConfig,
    ): StoryThreadStats {
        val now = clock.now()
        val articles = artifacts.llmContext.allArticles.associateBy { it.link }
        val byKey = linkedMapOf<String, EditorialCacheRecord>()
        var modelSupplied = 0
        var codeDerived = 0
        var reusedExisting = 0

        // event_key 首写为准。它是线索 id，不是标签：事后改写只会把已发布的线索劈成两条，
        // 或者把两条真线索错误地并成一条。Part 1 与 Part 2 必须同策略——此前 Part 1
        // 无条件覆写而 Part 2 保留旧值，同一篇文章走哪条路径决定了它的线索是否稳定。
        fun resolveEventKey(previous: String?, explicit: String, article: com.dailynews.model.Article): String {
            val existing = EditorialCacheKeys.sanitizeEventKey(previous)
            if (existing.isNotEmpty()) {
                reusedExisting += 1
                return existing
            }
            val resolved = EditorialCacheKeys.eventKey(explicit, article.title, article.link)
            if (EditorialCacheKeys.sanitizeEventKey(explicit).isNotEmpty()) modelSupplied += 1 else codeDerived += 1
            return resolved
        }

        output.part2.groups.flatMap { it.articles }.forEach { item ->
            val article = articles.getValue(item.link)
            val key = EditorialCacheKeys.cacheKey(article)
            val previous = cache.find(key)
            byKey[key] = (previous ?: EditorialCacheRecord(key, item.link, article.source, article.title)).copy(
                summaryZh = item.summaryZh,
                noiseBucket = item.noiseBucket,
                eventKey = resolveEventKey(previous?.eventKey, item.eventKey, article),
                updatedAtUtc = now,
            )
        }
        output.part1.items.forEach { item ->
            val article = articles.getValue(item.link)
            val key = EditorialCacheKeys.cacheKey(article)
            val previous = byKey[key] ?: cache.find(key) ?: EditorialCacheRecord(key, item.link, article.source, article.title)
            byKey[key] = previous.copy(
                part1SummaryZh = item.summaryZh,
                part1NoiseBucket = item.noiseBucket,
                eventKey = resolveEventKey(previous.eventKey, item.eventKey, article),
                updatedAtUtc = now,
            )
        }
        cache.upsert(byKey.values.toList())
        cache.prune(now.minus(90, ChronoUnit.DAYS))
        return StoryThreadStats(modelSupplied, codeDerived, reusedExisting)
    }

    private suspend fun snapshotContexts(runId: String, artifacts: ContextArtifacts) {
        snapshot(runId, "llm_context.json", ArtifactJson.codec.encodeToString(artifacts.llmContext))
        snapshot(runId, "part1_brief.json", ArtifactJson.codec.encodeToString(artifacts.part1Brief))
        snapshot(runId, "part2_context.json", ArtifactJson.codec.encodeToString(artifacts.part2Context))
        snapshot(runId, "context_budget.json", ArtifactJson.codec.encodeToString(artifacts.contextBudget))
    }

    private suspend fun snapshot(runId: String, path: String, text: String) {
        runCatching { artifactSink.write(runId, path, text.toByteArray(Charsets.UTF_8)) }
            .onFailure { logSink.log(runId, "artifact", LogLevel.WARN, "snapshot $path failed: ${it.message}") }
    }

    private suspend fun fail(request: RunRequest, runId: String?, stage: String, error: Throwable): RunExecutionResult.Failed {
        if (runId != null) logSink.log(runId, stage, LogLevel.ERROR, error.message ?: error::class.simpleName.orEmpty())
        return RunExecutionResult.Failed(request.reportDate, runId, stage, error.message ?: error::class.simpleName.orEmpty())
    }

    private suspend fun unexpectedFailure(
        request: RunRequest,
        runId: String?,
        stage: String,
        message: String,
        cause: Throwable? = null,
    ): RunExecutionResult.Failed {
        if (runId != null) logSink.log(runId, stage, LogLevel.ERROR, message)
        val diagnostics = if (cause?.let(NetworkDiagnostics::evidenceWarrantsProbe) == true || NetworkDiagnostics.evidenceWarrantsProbe(message)) {
            runCatching { unexpectedDiagnostics.run(message) }.getOrElse { listOf("diagnostic failed: ${it.message}") }
        } else {
            emptyList()
        }
        if (runId != null) diagnostics.forEach { logSink.log(runId, "network_diagnostics", LogLevel.INFO, it) }
        val suffix = diagnostics.takeIf { it.isNotEmpty() }?.joinToString(prefix = " | diagnostics: ", separator = "; ").orEmpty()
        return RunExecutionResult.Failed(request.reportDate, runId, stage, message + suffix)
    }
}
