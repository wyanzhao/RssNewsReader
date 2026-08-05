package com.dailynews.pipeline

import com.dailynews.model.AssembledReport
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2DraftArticle
import com.dailynews.model.Part2DraftGroup
import com.dailynews.model.RawRun
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.editorial.ReportAssembler
import com.dailynews.pipeline.flow.EditorialEngine
import com.dailynews.pipeline.flow.EditorialLlmException
import com.dailynews.pipeline.flow.EditorialOutput
import com.dailynews.pipeline.orchestrate.RunExecutionResult
import com.dailynews.pipeline.orchestrate.RunOrchestrator
import com.dailynews.pipeline.orchestrate.RunRequest
import com.dailynews.pipeline.orchestrate.UnexpectedFailureDiagnostics
import com.dailynews.pipeline.orchestrate.ArtifactAuditGate
import com.dailynews.pipeline.orchestrate.ReportReviewGate
import com.dailynews.pipeline.orchestrate.DamagedInputException
import com.dailynews.pipeline.editorial.ArtifactAudit
import com.dailynews.pipeline.editorial.ReportReview
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
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** KEEP: deterministic branch, retry, audit, and best-effort bookkeeping contracts. */
class RunOrchestratorTest {
    @Test
    fun `success writes report before top n and publishes ledgers best effort`() = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val harness = Harness(raw, failLedgers = true)

        val result = harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", config.copy(part1MaxItems = 10)))

        val success = assertIs<RunExecutionResult.Success>(result)
        assertEquals(listOf("report", "topN"), harness.publishOrder)
        assertEquals(2, success.warnings.size)
        assertEquals(1, harness.editorCalls)
        assertTrue("raw.json" in harness.artifacts)
        assertTrue("top10.md" in harness.artifacts)
        assertEquals(
            setOf(
                "raw.json", "validation.json", "llm_context.json", "part1_brief.json",
                "part2_context.json", "context_budget.json", "part1_plan.json",
                "part2_draft.json", "top10.md",
            ),
            harness.artifacts.keys,
        )
    }

    @Test
    fun `expected block never calls editorial engine`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val empty = raw.copy(
            count = 0,
            articles = emptyList(),
            feedResults = feeds.feeds.map { com.dailynews.model.FeedResult(it.name, it.url, "empty", articleCount = 0) },
            uniqueSourceCount = 0,
            uniqueSources = emptyList(),
        )
        val harness = Harness(empty)

        val result = harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.failed.md", config))

        assertIs<RunExecutionResult.ExpectedBlock>(result)
        assertEquals(0, harness.editorCalls)
        assertEquals(1, harness.failureWrites)
        assertEquals(0, harness.reportWrites)
        assertEquals(setOf("raw.json", "validation.json"), harness.artifacts.keys)
    }

    @Test
    fun `unexpected fetch failure retries exactly once then runs gated diagnostics`() = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val harness = Harness(raw, alwaysFailFetch = true)

        val result = harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", config))

        val failed = assertIs<RunExecutionResult.Failed>(result)
        assertEquals("fetch", failed.stage)
        assertEquals(2, harness.fetchCalls)
        assertEquals(1, harness.diagnosticCalls)
        assertEquals(0, harness.editorCalls)
    }

    @Test
    fun `unexpected retry continues into success`() = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val harness = Harness(raw, failFirstFetch = true)

        assertIs<RunExecutionResult.Success>(harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", config.copy(part1MaxItems = 10))))
        assertEquals(2, harness.fetchCalls)
        assertEquals(1, harness.editorCalls)
    }

    @Test
    fun `unexpected retry continues into expected block`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val empty = raw.copy(
            count = 0,
            articles = emptyList(),
            feedResults = feeds.feeds.map { com.dailynews.model.FeedResult(it.name, it.url, "empty", articleCount = 0) },
            uniqueSourceCount = 0,
            uniqueSources = emptyList(),
        )
        val harness = Harness(empty, failFirstFetch = true)

        assertIs<RunExecutionResult.ExpectedBlock>(harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.failed.md", config)))
        assertEquals(2, harness.fetchCalls)
        assertEquals(0, harness.editorCalls)
    }

    @Test
    fun `LLM failure does not rerun deterministic pipeline`() = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val harness = Harness(raw, failEditorial = true)

        val failed = assertIs<RunExecutionResult.Failed>(harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", config)))

        assertEquals("editorial", failed.stage)
        assertTrue("role=EDITOR" in failed.message)
        assertEquals(1, harness.fetchCalls)
        assertEquals(1, harness.editorCalls)
    }

    @Test
    fun `artifact audit and opt in budget gate stop before LLM`(): Unit = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val auditHarness = Harness(raw, failAudit = true)
        val auditFailure = assertIs<RunExecutionResult.Failed>(auditHarness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", config)))
        assertEquals("artifact_audit", auditFailure.stage)
        assertEquals(0, auditHarness.editorCalls)

        val budgetHarness = Harness(raw)
        val hardBudget = config.copy(contextBudget = config.contextBudget.copy(totalContextMaxBytes = 1, hardBlock = true))
        val budgetFailure = assertIs<RunExecutionResult.Failed>(budgetHarness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", hardBudget)))
        assertEquals("context_budget", budgetFailure.stage)
        assertEquals(0, budgetHarness.editorCalls)

        val advisoryHarness = Harness(raw)
        val advisory = config.copy(part1MaxItems = 10, contextBudget = config.contextBudget.copy(totalContextMaxBytes = 1, hardBlock = false))
        assertIs<RunExecutionResult.Success>(advisoryHarness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", advisory)))
    }

    @Test
    fun `review failure downgrades report without recording publication ledgers`() = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val harness = Harness(raw, failReview = true)

        val failed = assertIs<RunExecutionResult.Failed>(harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.md", config.copy(part1MaxItems = 10))))

        assertEquals("review", failed.stage)
        assertEquals(listOf("report", "markFailed"), harness.publishOrder)
        assertEquals(1, harness.reportDowngrades)
        assertEquals(
            setOf(
                "raw.json", "validation.json", "llm_context.json", "part1_brief.json",
                "part2_context.json", "context_budget.json", "part1_plan.json", "part2_draft.json",
            ),
            harness.artifacts.keys,
        )
    }

    @Test
    fun `damaged input writes a concrete failed report with exit ten`() = runBlocking {
        val (raw, _, config) = FixtureFactory.goldenRaw()
        val harness = Harness(raw, damagedInput = true)

        val blocked = assertIs<RunExecutionResult.ExpectedBlock>(harness.orchestrator.run(RunRequest(LocalDate.parse("2026-04-10"), "/report.failed.md", config)))

        assertEquals(10, blocked.validatorExitCode)
        assertTrue("validator_exit_code: 10" in blocked.failureMarkdown)
        assertEquals(1, harness.failureWrites)
        assertEquals(1, harness.fetchCalls)
        assertEquals(setOf("validation.json"), harness.artifacts.keys)
    }

    private class Harness(
        private val raw: RawRun,
        private val failLedgers: Boolean = false,
        private val alwaysFailFetch: Boolean = false,
        private val failFirstFetch: Boolean = false,
        private val failEditorial: Boolean = false,
        private val failAudit: Boolean = false,
        private val failReview: Boolean = false,
        private val damagedInput: Boolean = false,
    ) {
        private val feeds = FixtureFactory.goldenRaw().second
        var fetchCalls = 0
        var editorCalls = 0
        var diagnosticCalls = 0
        var reportWrites = 0
        var failureWrites = 0
        var reportDowngrades = 0
        val publishOrder = mutableListOf<String>()
        val artifacts = linkedMapOf<String, String>()

        private val editorial = EditorialEngine { _, context, _, part2Context, _, topN, _, part2Mode, _ ->
            editorCalls += 1
            if (failEditorial) throw EditorialLlmException(
                "role=EDITOR provider=test model=test operation=part1_shortlist contract_attempt=1 transport_attempt=3: timeout",
                IllegalStateException("timeout"),
            )
            val part1 = Part1Plan(
                items = context.allArticles.take(topN).map { Part1PlanItem(it.link, "中文事件摘要", emptyList()) },
                shortfall = maxOf(0, topN - context.allArticles.size),
            )
            val groups = part2Context.groups.map { group ->
                Part2DraftGroup(
                    source = group.source,
                    status = group.status,
                    articleCount = group.articles.size,
                    errorText = group.errorText,
                    articles = group.articles.map { article ->
                        Part2DraftArticle(article.title, article.link, article.pubDateIso, "中文短摘要")
                    },
                )
            }
            EditorialOutput(
                part1,
                if (part2Mode == com.dailynews.model.Part2Mode.LAZY) Part2Draft(0, groups.map { it.copy(articleCount = 0, articles = emptyList()) })
                else Part2Draft(context.allArticles.size, groups),
            )
        }

        private val cache = object : EditorialCacheStore {
            override suspend fun find(cacheKey: String): EditorialCacheRecord? = null
            override suspend fun recentSince(since: Instant): List<EditorialCacheRecord> = emptyList()
            override suspend fun upsert(records: List<EditorialCacheRecord>) {
                if (failLedgers) error("cache unavailable")
                publishOrder += "cache"
            }
            override suspend fun prune(before: Instant) = Unit
        }

        private val seen = object : SeenLinksStore {
            override suspend fun entries(): Map<String, LocalDate> = emptyMap()
            override suspend fun replace(entries: Map<String, LocalDate>) {
                if (failLedgers) error("seen unavailable")
                publishOrder += "seen"
            }
            override suspend fun recordReportedLinks(links: List<String>, reportDate: LocalDate) {
                if (failLedgers) error("seen unavailable")
                publishOrder += "seen"
            }
        }

        val orchestrator = RunOrchestrator(
            fetch = FetchPort { _, _, _, _ ->
                fetchCalls += 1
                if (damagedInput) throw DamagedInputException("raw.json is truncated", "damaged-run")
                if (alwaysFailFetch) error("DNS timeout")
                if (failFirstFetch && fetchCalls == 1) error("transient timeout")
                raw
            },
            feeds = object : FeedSource {
                override suspend fun enabledFeeds() = feeds.feeds
            },
            validator = QcValidator(),
            contexts = LlmContextBuilder(),
            editorial = editorial,
            assembler = ReportAssembler(),
            reportSink = object : ReportSink {
                override suspend fun publish(report: AssembledReport) {
                    reportWrites += 1
                    publishOrder += "report"
                }

                override suspend fun markFailed(reportDate: String, reason: String) {
                    reportDowngrades += 1
                    publishOrder += "markFailed"
                }
            },
            failureSink = object : FailureReportSink {
                override suspend fun publishFailure(reportDate: String, markdown: String) {
                    failureWrites += 1
                }
            },
            topNSink = object : TopNReportSink {
                override suspend fun publishTopN(reportDate: String, markdown: String) {
                    publishOrder += "topN"
                }
            },
            artifactSink = object : ArtifactSink {
                override suspend fun write(runId: String, relativePath: String, content: ByteArray) {
                    artifacts[relativePath] = content.toString(Charsets.UTF_8)
                }
            },
            logSink = object : RunLogSink {
                override suspend fun log(runId: String, step: String, level: LogLevel, message: String) = Unit
            },
            seenLinks = seen,
            cache = cache,
            clock = ClockProvider { Instant.parse("2026-04-10T22:00:00Z") },
            unexpectedDiagnostics = UnexpectedFailureDiagnostics {
                diagnosticCalls += 1
                listOf("dns failed")
            },
            artifactAuditGate = ArtifactAuditGate { context, _ ->
                if (failAudit) ArtifactAudit(false, listOf("forced audit failure"), context.allArticles.size, context.sourceGroups.size)
                else com.dailynews.pipeline.editorial.EditorialContracts.audit(context, com.dailynews.pipeline.validate.QcValidator().validate(raw, feeds).result)
            },
            reportReviewGate = ReportReviewGate { _, _, _, _, _, _, _, _ ->
                if (failReview) ReportReview(false, listOf("forced review failure")) else ReportReview(true, emptyList())
            },
        )
    }
}
