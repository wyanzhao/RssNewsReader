package com.dailynews.pipeline

import com.dailynews.llm.EditorialRole
import com.dailynews.llm.LlmProvider
import com.dailynews.llm.LlmRequest
import com.dailynews.llm.LlmResponse
import com.dailynews.llm.LlmTransportException
import com.dailynews.llm.RoleModel
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PeriodicDigest
import com.dailynews.model.PeriodicDigestSection
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import com.dailynews.model.Part1ShortlistPayload
import com.dailynews.model.Part2Mode
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.MissingPart2Payload
import com.dailynews.model.MissingPart2Summary
import com.dailynews.model.EditorialJsonSchemas
import com.dailynews.pipeline.context.CacheLookup
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.flow.LlmEditorialEngine
import com.dailynews.pipeline.flow.EditorialContractException
import com.dailynews.pipeline.flow.EditorialContractViolation
import com.dailynews.pipeline.flow.EditorialLlmException
import com.dailynews.pipeline.flow.PromptSource
import com.dailynews.pipeline.flow.ProviderBinding
import com.dailynews.pipeline.flow.ProviderResolver
import com.dailynews.pipeline.flow.Part2SummaryRequest
import com.dailynews.pipeline.ports.EditorialCacheRecord
import com.dailynews.pipeline.ports.ArtifactSink
import com.dailynews.pipeline.ports.LogLevel
import com.dailynews.pipeline.ports.RunLogSink
import com.dailynews.pipeline.validate.QcValidator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/** KEEP: fail-closed editorial contracts independent of the run/article storage shape. */
class LlmEditorialEngineTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `strict response schemas track serializable field names`() {
        fun names(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        fun properties(schema: kotlinx.serialization.json.JsonObject) = schema.getValue("properties").jsonObject.keys

        assertEquals(names(Part1ShortlistPayload.serializer().descriptor), properties(EditorialJsonSchemas.part1Shortlist))
        assertEquals(names(Part1Plan.serializer().descriptor), properties(EditorialJsonSchemas.part1Plan))
        assertEquals(names(MissingPart2Payload.serializer().descriptor), properties(EditorialJsonSchemas.missingPart2))
        assertEquals(
            names(Part1PlanItem.serializer().descriptor),
            EditorialJsonSchemas.part1Plan.getValue("properties").jsonObject
                .getValue("items").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.keys,
        )
        assertEquals(
            names(MissingPart2Summary.serializer().descriptor),
            EditorialJsonSchemas.missingPart2.getValue("properties").jsonObject
                .getValue("items").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.keys,
        )
        assertEquals(names(PeriodicDigest.serializer().descriptor), properties(EditorialJsonSchemas.periodicDigest))
        assertEquals(
            names(PeriodicDigestSection.serializer().descriptor),
            EditorialJsonSchemas.periodicDigest.getValue("properties").jsonObject
                .getValue("sections").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.keys,
        )
    }

    @Test
    fun `a plan whose shortfall contradicts its item count is rejected`() = runBlocking {
        val artifacts = lowVolumeArtifacts()
        val links = artifacts.llmContext.allArticles.map { it.link }
        val selected = links.take(5)
        // First plan claims a full Top 30 while carrying 3 items — the shape a
        // truncated or repaired response takes. It must not be published.
        val responses = ArrayDeque(
            listOf(
                LlmResponse("{\"links\":[${selected.joinToString(",") { "\"$it\"" }}]}", stopReason = "stop"),
                LlmResponse(
                    ArtifactJson.compact.encodeToString(
                        Part1Plan(items = selected.take(3).map { Part1PlanItem(it, "中文事件摘要", emptyList()) }, shortfall = 0),
                    ),
                    stopReason = "stop",
                ),
                LlmResponse(
                    ArtifactJson.compact.encodeToString(
                        Part1Plan(items = selected.map { Part1PlanItem(it, "中文事件摘要", emptyList()) }, shortfall = 25),
                    ),
                    stopReason = "stop",
                ),
            ),
        )

        val output = runEngine(artifacts, responses)

        assertEquals(5, output.part1.items.size)
        assertEquals(25, output.part1.shortfall)
        assertEquals(0, responses.size)
    }

    @Test
    fun `accepted shortlist is checkpointed and every rejected plan attempt is preserved`() = runBlocking {
        val artifacts = lowVolumeArtifacts(cachedPart2 = false)
        val selected = artifacts.llmContext.allArticles.map { it.link }.take(5)
        val invalidPlan = ArtifactJson.compact.encodeToString(
            Part1Plan(items = selected.take(3).map { Part1PlanItem(it, "中文事件摘要", emptyList()) }, shortfall = 0),
        )
        val responses = ArrayDeque(
            listOf(
                LlmResponse("{\"links\":[${selected.joinToString(",") { "\"$it\"" }}]}", stopReason = "stop"),
                LlmResponse(invalidPlan, stopReason = "stop"),
                LlmResponse(invalidPlan, stopReason = "stop"),
                LlmResponse(invalidPlan, stopReason = "stop"),
            ),
        )
        val capturedArtifacts = linkedMapOf<String, String>()
        val capturedLogs = mutableListOf<String>()
        val engine = LlmEditorialEngine(
            providers = ProviderResolver { _, _ ->
                ProviderBinding(
                    "test",
                    object : LlmProvider {
                        override suspend fun complete(request: LlmRequest) = responses.removeFirst()
                    },
                    RoleModel("test", "model", 8_192),
                )
            },
            prompts = TestPrompts,
            artifacts = object : ArtifactSink {
                override suspend fun write(runId: String, relativePath: String, content: ByteArray) {
                    capturedArtifacts[relativePath] = content.decodeToString()
                }
            },
            logs = object : RunLogSink {
                override suspend fun log(runId: String, step: String, level: LogLevel, message: String) {
                    capturedLogs += "$step/$level: $message"
                }
            },
        )

        val error = assertFailsWith<EditorialContractException> {
            engine.edit(
                "checkpoint-run",
                artifacts.llmContext,
                artifacts.part1Brief,
                artifacts.part2Context,
                artifacts.contextBudget,
                30,
                20,
                Part2Mode.LAZY,
                LlmExecutionConfig(),
            )
        }

        assertEquals("part1_plan", error.operation)
        assertTrue("part1_shortlist.json" in capturedArtifacts)
        assertTrue("part1_shortlist_context.json" in capturedArtifacts)
        val violationPaths = capturedArtifacts.keys.filter { it.startsWith("contract_violations/part1_plan-") }
        assertEquals(3, violationPaths.size)
        val first = ArtifactJson.codec.decodeFromString<EditorialContractViolation>(capturedArtifacts.getValue(violationPaths.first()))
        assertEquals(1, first.attempt)
        assertEquals(3, first.itemCount)
        assertEquals(0, first.shortfall)
        assertEquals(
            listOf("part1_plan shortfall 0 != expected 27 (30 - 3 items)"),
            first.errors,
        )
        assertEquals(3, capturedLogs.count { it.startsWith("contract_part1_plan/WARN:") })
        assertTrue(capturedLogs.first().contains("attempt=1 items=3 shortfall=0 errors=${first.errors.single()}"))
        assertEquals(0, responses.size)
    }

    @Test
    fun `low volume day may shortlist fewer than the article pool`() = runBlocking {
        val artifacts = lowVolumeArtifacts()
        val links = artifacts.llmContext.allArticles.map { it.link }
        val selected = links.take(5)
        val responses = ArrayDeque(
            listOf(
                LlmResponse("{\"links\":[]}", stopReason = "stop"),
                LlmResponse("{\"links\":[${selected.joinToString(",") { "\"$it\"" }}]}", stopReason = "stop"),
                LlmResponse(
                    ArtifactJson.compact.encodeToString(
                        Part1Plan(items = selected.map { Part1PlanItem(it, "中文事件摘要", emptyList()) }, shortfall = 25),
                    ),
                    stopReason = "stop",
                ),
            ),
        )

        val output = runEngine(artifacts, responses)

        assertEquals(5, output.part1.items.size)
        assertEquals(25, output.part1.shortfall)
        assertEquals(0, responses.size)
    }

    @Test
    fun `lazy mode completes Part 1 without resolving or calling the drafter`() = runBlocking {
        val artifacts = lowVolumeArtifacts(cachedPart2 = false)
        val selected = artifacts.llmContext.allArticles.map { it.link }.take(5)
        val responses = ArrayDeque(
            listOf(
                LlmResponse("{\"links\":[${selected.joinToString(",") { "\"$it\"" }}]}", stopReason = "stop"),
                LlmResponse(
                    ArtifactJson.compact.encodeToString(
                        Part1Plan(selected.map { Part1PlanItem(it, "中文事件摘要", emptyList()) }, shortfall = 25),
                    ),
                    stopReason = "stop",
                ),
            ),
        )
        var drafterResolutions = 0
        val engine = LlmEditorialEngine(
            providers = ProviderResolver { role, _ ->
                if (role == EditorialRole.DRAFTER) drafterResolutions += 1
                ProviderBinding(
                    "test",
                    object : LlmProvider {
                        override suspend fun complete(request: LlmRequest) = responses.removeFirst()
                    },
                    RoleModel("test", "model", 8_192),
                )
            },
            prompts = TestPrompts,
        )

        val output = engine.edit(
            "lazy-run",
            artifacts.llmContext,
            artifacts.part1Brief,
            artifacts.part2Context,
            artifacts.contextBudget,
            30,
            20,
            Part2Mode.LAZY,
            LlmExecutionConfig(),
        )

        assertEquals(0, drafterResolutions)
        assertEquals(0, output.part2.totalArticles)
        assertEquals(0, responses.size)
    }

    @Test
    fun `on demand Part 2 generator covers exactly one requested group`() = runBlocking {
        val requests = listOf(
            Part2SummaryRequest("Source", "One", "https://one", "2026-08-04T00:00:00Z", "material one"),
            Part2SummaryRequest("Source", "Two", "https://two", "2026-08-04T01:00:00Z", "material two"),
        )
        var observedMaxTokens = 0
        val provider = object : LlmProvider {
            override suspend fun complete(request: LlmRequest) = LlmResponse(
                ArtifactJson.compact.encodeToString(
                    MissingPart2Payload(
                        requests.map { MissingPart2Summary(it.link, "${it.title} 中文摘要") },
                    ),
                ),
                stopReason = "stop",
            ).also { observedMaxTokens = request.maxTokens }
        }
        val engine = LlmEditorialEngine(
            ProviderResolver { role, _ ->
                assertEquals(EditorialRole.DRAFTER, role)
                ProviderBinding("test", provider, RoleModel("test", "model", 8_192))
            },
            TestPrompts,
        )

        val generated = engine.generate(
            "lazy-run",
            requests,
            maxCalls = 1,
            LlmExecutionConfig(),
        )

        assertEquals(requests.map { it.link }, generated.map { it.link })
        assertEquals(8_192, observedMaxTokens)
    }

    @Test
    fun `Part 1 shortlist and plan both use EDITOR token cap`() = runBlocking {
        val artifacts = lowVolumeArtifacts(cachedPart2 = false)
        val selected = artifacts.llmContext.allArticles.map { it.link }.take(5)
        val responses = ArrayDeque(
            listOf(
                LlmResponse("{\"links\":[${selected.joinToString(",") { "\"$it\"" }}]}", stopReason = "stop"),
                LlmResponse(
                    ArtifactJson.compact.encodeToString(
                        Part1Plan(selected.map { Part1PlanItem(it, "中文事件摘要", emptyList()) }, shortfall = 25),
                    ),
                    stopReason = "stop",
                ),
            ),
        )
        val observed = mutableListOf<Int>()
        val engine = LlmEditorialEngine(
            ProviderResolver { _, _ ->
                ProviderBinding(
                    "test",
                    object : LlmProvider {
                        override suspend fun complete(request: LlmRequest): LlmResponse {
                            observed += request.maxTokens
                            return responses.removeFirst()
                        }
                    },
                    RoleModel("test", "model", 12_288),
                )
            },
            TestPrompts,
        )

        engine.edit(
            "token-caps",
            artifacts.llmContext,
            artifacts.part1Brief,
            artifacts.part2Context,
            artifacts.contextBudget,
            30,
            20,
            Part2Mode.LAZY,
            LlmExecutionConfig(),
        )

        assertEquals(listOf(12_288, 12_288), observed)
    }

    @Test
    fun `Part 2 transport failure reports role provider batch and attempts`() = runBlocking {
        val engine = LlmEditorialEngine(
            ProviderResolver { _, _ ->
                ProviderBinding(
                    "deepseek",
                    object : LlmProvider {
                        override suspend fun complete(request: LlmRequest): LlmResponse =
                            throw LlmTransportException("transport failed: timeout", retryable = true)
                    },
                    RoleModel("deepseek", "deepseek-v4-flash", 65_536),
                )
            },
            TestPrompts,
        )
        val error = assertFailsWith<EditorialLlmException> {
            engine.generate(
                "slow-run",
                listOf(Part2SummaryRequest("Source", "Title", "https://one", "2026-08-04T00:00:00Z", "material")),
                maxCalls = 3,
                LlmExecutionConfig(),
            )
        }

        assertTrue("role=DRAFTER" in error.message.orEmpty())
        assertTrue("provider=deepseek" in error.message.orEmpty())
        assertTrue("operation=part2_batch batch=1" in error.message.orEmpty())
        assertTrue("contract_attempt=1 transport_attempt=3" in error.message.orEmpty())
    }

    private suspend fun runEngine(
        artifacts: com.dailynews.pipeline.context.ContextArtifacts,
        responses: ArrayDeque<LlmResponse>,
    ) = LlmEditorialEngine(
        providers = ProviderResolver { _: EditorialRole, _ ->
            ProviderBinding(
                "test",
                object : LlmProvider {
                    override suspend fun complete(request: LlmRequest) = responses.removeFirst()
                },
                RoleModel("test", "model", 8_192),
            )
        },
        prompts = TestPrompts,
    ).edit(
        "run-low-volume",
        artifacts.llmContext,
        artifacts.part1Brief,
        artifacts.part2Context,
        artifacts.contextBudget,
        30,
        20,
        Part2Mode.FULL,
        LlmExecutionConfig(),
    )

    private suspend fun lowVolumeArtifacts(cachedPart2: Boolean = true): com.dailynews.pipeline.context.ContextArtifacts {
        val (baseRaw, feeds, config) = FixtureFactory.goldenRaw()
        val seed = baseRaw.articles.first()
        val articles = (1..28).map { index ->
            seed.copy(title = "Story $index", link = "https://example.test/story-$index")
        }
        val raw = baseRaw.copy(
            count = articles.size,
            articles = articles,
            feedResults = feeds.feeds.map { feed ->
                if (feed.name == seed.source) {
                    com.dailynews.model.FeedResult(feed.name, feed.url, "ok", articleCount = articles.size)
                } else {
                    com.dailynews.model.FeedResult(feed.name, feed.url, "empty", articleCount = 0)
                }
            },
            uniqueSourceCount = 1,
            uniqueSources = listOf(seed.source),
        )
        val validation = QcValidator().validate(raw, feeds).result
        return LlmContextBuilder().build(
            raw,
            validation,
            "2026-04-10",
            "/report.md",
            config,
            cacheLookup = CacheLookup { article ->
                if (!cachedPart2) null else EditorialCacheRecord(
                    cacheKey = article.link,
                    link = article.link,
                    source = article.source,
                    title = article.title,
                    summaryZh = "缓存中文摘要",
                )
            },
        )
    }

    private object TestPrompts : PromptSource {
        override fun part1Shortlist(topN: Int) = "shortlist"
        override fun part1Plan(topN: Int) = "plan"
        override fun part2Drafter() = "part2"
        override fun periodicDigest() = "digest"
    }
}
