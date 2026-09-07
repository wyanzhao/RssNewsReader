package com.dailynews.pipeline.flow

import com.dailynews.model.*
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.context.RecentTopNEvent
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.editorial.ReportAssembler
import com.dailynews.pipeline.editorial.ReportReviewer
import com.dailynews.pipeline.ports.ArtifactSink
import com.dailynews.pipeline.validate.QcValidator
import kotlinx.serialization.encodeToString

/** Separate from publication orchestration: deliberately has no report/cache/seen write ports. */
class EditorialComparison(
    private val buildEngine: (ShortlistContextFactory) -> EditorialEngine,
    private val artifacts: ArtifactSink,
) {
    data class Arm(val runId: String, val report: AssembledReport)

    suspend fun run(
        comparisonId: String,
        raw: RawRun,
        feeds: FeedConfigDocument,
        date: String,
        config: PipelineConfig,
        preference: String,
        recentEvents: List<RecentTopNEvent>,
    ): List<Arm> {
        require(comparisonId.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        java.time.LocalDate.parse(date)
        require(preference.isNotBlank() && preference.length <= 1000)
        val validation = QcValidator().validate(raw, feeds).result
        require(validation.passed) { "comparison source validation failed: ${validation.blockingReasons}" }
        val frozenHistory = recentEvents.toList()
        // This stateless factory never reads or writes the application's editorial cache.
        val engine = buildEngine(ShortlistContextFactory { context, links ->
            NoCacheShortlistContextFactory.build(context, links).copy(recentTopN = frozenHistory)
        })
        val results = mutableListOf<Arm>()
        for ((name, feedback) in listOf("baseline" to emptyList(), "candidate" to listOf(preference))) {
            val runId = "$comparisonId-$name"
            val input = raw.copy(meta = raw.meta.copy(runId = runId))
            val armValidation = QcValidator().validate(input, feeds).result
            val armConfig = config.copy(editorFeedback = feedback, articleFeedback = emptyList(), watches = WatchPreferences(), part2Mode = Part2Mode.LAZY)
            val reportPath = "comparison-$runId.md"
            val context = LlmContextBuilder().build(input, armValidation, date, reportPath, armConfig)
            require(context.contextBudget.withinBudget) { "comparison context exceeds budget" }
            val audit = EditorialContracts.audit(context.llmContext, armValidation)
            require(audit.passed) { "comparison source audit failed: ${audit.errors}" }
            suspend fun save(name: String, text: String) = artifacts.write(runId, name, text.toByteArray())
            save("raw.json", ArtifactJson.codec.encodeToString(input))
            save("run_config.json", ArtifactJson.codec.encodeToString(armConfig))
            save("run_feeds.json", ArtifactJson.codec.encodeToString(feeds.feeds))
            save("validation.json", ArtifactJson.codec.encodeToString(armValidation))
            save("llm_context.json", ArtifactJson.codec.encodeToString(context.llmContext))
            save("part1_brief.json", ArtifactJson.codec.encodeToString(context.part1Brief))
            save("context_budget.json", ArtifactJson.codec.encodeToString(context.contextBudget))
            val output = engine.edit(runId, context.llmContext, context.part1Brief, context.part2Context,
                context.contextBudget, armConfig.part1MaxItems, armConfig.maxLlmCallsPerRun,
                Part2Mode.LAZY, armConfig.llmExecution)
            require(output.part1.items.isNotEmpty()) { "comparison produced an empty digest" }
            val report = ReportAssembler().assemble(context.llmContext, armValidation, output.part1, output.part2,
                armConfig.part1MaxItems, reportPath, part2Mode = Part2Mode.LAZY)
            val review = ReportReviewer.review(report.markdown, reportPath, context.llmContext, armValidation,
                output.part1, output.part2, armConfig.part1MaxItems, Part2Mode.LAZY)
            require(review.passed) { "comparison report review failed: ${review.errors}" }
            save("part1_plan.json", ArtifactJson.codec.encodeToString(output.part1))
            save("reviewed-report.md", report.markdown)
            results += Arm(runId, report)
        }
        return results
    }
}
