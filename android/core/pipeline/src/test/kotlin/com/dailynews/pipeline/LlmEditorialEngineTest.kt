package com.dailynews.pipeline

import com.dailynews.llm.EditorialRole
import com.dailynews.llm.LlmProvider
import com.dailynews.llm.LlmRequest
import com.dailynews.llm.LlmResponse
import com.dailynews.llm.LlmTransportException
import com.dailynews.llm.RoleModel
import com.dailynews.model.ArtifactJson
import com.dailynews.model.PeriodicDigestDraft
import com.dailynews.model.PeriodicDigestDraftSection
import com.dailynews.model.Part1PlanDraft
import com.dailynews.model.Part1PlanDraftItem
import com.dailynews.model.Part1ShortlistDraft
import com.dailynews.model.Part2Mode
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.MissingPart2Draft
import com.dailynews.model.MissingPart2DraftItem
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

        // 校验的是**草稿**类型：schema 约束的是模型返回什么（`ref`），
        // 不是解析之后落盘的 link-keyed 契约。
        assertEquals(names(Part1ShortlistDraft.serializer().descriptor), properties(EditorialJsonSchemas.part1Shortlist))
        assertEquals(names(Part1PlanDraft.serializer().descriptor), properties(EditorialJsonSchemas.part1Plan))
        assertEquals(names(MissingPart2Draft.serializer().descriptor), properties(EditorialJsonSchemas.missingPart2))
        assertEquals(
            names(Part1PlanDraftItem.serializer().descriptor),
            EditorialJsonSchemas.part1Plan.getValue("properties").jsonObject
                .getValue("items").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.keys,
        )
        assertEquals(
            names(MissingPart2DraftItem.serializer().descriptor),
            EditorialJsonSchemas.missingPart2.getValue("properties").jsonObject
                .getValue("items").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.keys,
        )
        assertEquals(names(PeriodicDigestDraft.serializer().descriptor), properties(EditorialJsonSchemas.periodicDigest))
        assertEquals(
            names(PeriodicDigestDraftSection.serializer().descriptor),
            EditorialJsonSchemas.periodicDigest.getValue("properties").jsonObject
                .getValue("sections").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.keys,
        )
    }

    /**
     * 这条是这次重构的落点：模型交回的是短 id，而**改写过的链接不再有任何生路**。
     * 2026-08-19 那次三轮失败正是链接改写，当时的契约无法区分「抄错」和「造假」。
     */
    @Test
    fun `plan items reference articles by short id and rewritten links are rejected`() = runBlocking {
        val artifacts = lowVolumeArtifacts()
        val selected = artifacts.llmContext.allArticles.map { it.link }.take(5)
        val rewritten = ArtifactJson.compact.encodeToString(
            // 按标题重造 slug —— 8-19 事故的确切形状。
            Part1PlanDraft(listOf(Part1PlanDraftItem("https://example.test/story-1-extended", "中文事件摘要", emptyList())), 29),
        )
        val responses = ArrayDeque(
            listOf(
                LlmResponse(shortlistDraft(selected.size), stopReason = "stop"),
                LlmResponse(rewritten, stopReason = "stop"),
                // 大小写与前导零都属于同义写法，不该浪费一整轮重试。
                LlmResponse(planDraft(listOf("a1", "A2", "a03", "4", "a5"), shortfall = 25), stopReason = "stop"),
            ),
        )

        val output = runEngine(artifacts, responses)

        assertEquals(selected, output.part1.items.map { it.link })
        assertEquals(0, responses.size)
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
                LlmResponse(shortlistDraft(selected.size), stopReason = "stop"),
                LlmResponse(planDraft(ids(3), shortfall = 0), stopReason = "stop"),
                LlmResponse(planDraft(ids(5), shortfall = 25), stopReason = "stop"),
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
        val invalidPlan = planDraft(ids(3), shortfall = 0)
        val responses = ArrayDeque(
            listOf(
                LlmResponse(shortlistDraft(selected.size), stopReason = "stop"),
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
        // 按内容找而不是按位置找：编辑阶段还会写别的日志（比如缓存命中率），
        // "第一条就是契约告警"从来不是这个测试真正想钉的东西。
        assertTrue(
            capturedLogs.first { it.startsWith("contract_part1_plan/WARN:") }
                .contains("attempt=1 items=3 shortfall=0 errors=${first.errors.single()}"),
            capturedLogs.toString(),
        )
        assertEquals(0, responses.size)
    }

    @Test
    fun `low volume day may shortlist fewer than the article pool`() = runBlocking {
        val artifacts = lowVolumeArtifacts()
        val links = artifacts.llmContext.allArticles.map { it.link }
        val selected = links.take(5)
        val responses = ArrayDeque(
            listOf(
                LlmResponse(shortlistDraft(0), stopReason = "stop"),
                LlmResponse(shortlistDraft(selected.size), stopReason = "stop"),
                LlmResponse(planDraft(ids(5), shortfall = 25), stopReason = "stop"),
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
                LlmResponse(shortlistDraft(selected.size), stopReason = "stop"),
                LlmResponse(planDraft(ids(5), shortfall = 25), stopReason = "stop"),
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
                    MissingPart2Draft(
                        requests.mapIndexed { index, article -> MissingPart2DraftItem("a${index + 1}", "${article.title} 中文摘要") },
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
        // 批量摘要按操作收窄，而不是照搬角色上限：max_tokens 是预留，供应商按
        // 「输入 + 预留」判断能否受理，一批 25 条短摘要没有理由预留 8K。
        assertTrue(observedMaxTokens < 8_192, "expected a per-operation cap, got $observedMaxTokens")
        assertTrue(observedMaxTokens >= 2_048, "cap must stay clear of truncation, got $observedMaxTokens")
    }

    @Test
    fun `shortlist reserves far less than the plan, and never more than the role cap`() = runBlocking {
        val artifacts = lowVolumeArtifacts(cachedPart2 = false)
        val selected = artifacts.llmContext.allArticles.map { it.link }.take(5)
        val responses = ArrayDeque(
            listOf(
                LlmResponse(shortlistDraft(selected.size), stopReason = "stop"),
                LlmResponse(planDraft(ids(5), shortfall = 25), stopReason = "stop"),
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

        val (shortlistCap, planCap) = observed
        // 短名单只吐几十个 `a12` 形状的 id；照搬 12288 会在 32K 上下文的便宜模型上
        // 变成「26K 输入 + 12K 预留」直接 400。计划确实要写满，保持角色上限。
        assertEquals(12_288, planCap)
        assertTrue(shortlistCap < planCap, "shortlist should reserve less than the plan, got $shortlistCap")
        assertTrue(shortlistCap <= 12_288, "never exceed the user's role cap")
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

    /** 入围顺序即 id 顺序，所以测试可以直接按位置造引用。 */
    private fun ids(count: Int) = (1..count).map { "a$it" }

    /** 短名单现在也走短 id：模型写 brief 里的 id，不回显 URL。 */
    private fun shortlistDraft(count: Int) =
        ArtifactJson.compact.encodeToString(Part1ShortlistDraft(ids(count)))

    private fun planDraft(refs: List<String>, shortfall: Int) = ArtifactJson.compact.encodeToString(
        Part1PlanDraft(refs.map { Part1PlanDraftItem(it, "中文事件摘要", emptyList()) }, shortfall),
    )

    private object TestPrompts : PromptSource {
        override fun part1Shortlist(topN: Int) = "shortlist"
        override fun part1Plan(topN: Int) = "plan"
        override fun part2Drafter() = "part2"
        override fun periodicDigest() = "digest"
    }
}
