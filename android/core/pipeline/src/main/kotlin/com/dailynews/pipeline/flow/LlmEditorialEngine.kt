package com.dailynews.pipeline.flow

import com.dailynews.llm.EditorialRole
import com.dailynews.llm.LlmProvider
import com.dailynews.llm.LlmRequest
import com.dailynews.llm.LlmResponse
import com.dailynews.llm.RoleModel
import com.dailynews.llm.StructuredLlm
import com.dailynews.llm.StructuredOutputSchema
import com.dailynews.model.ArtifactJson
import com.dailynews.model.ContextBudget
import com.dailynews.model.LlmContext
import com.dailynews.model.Part1Brief
import com.dailynews.model.PeriodicDigest
import com.dailynews.model.PeriodicDigestDraft
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanDraft
import com.dailynews.model.Part2Context
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2Mode
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.EditorialJsonSchemas
import com.dailynews.model.MissingPart2Draft
import com.dailynews.model.MissingPart2Payload
import com.dailynews.model.MissingPart2Summary
import com.dailynews.model.Part1ShortlistDraft
import com.dailynews.model.Part1ShortlistPayload
import com.dailynews.pipeline.editorial.ArticleRefIndex
import com.dailynews.pipeline.editorial.PeriodicDigestContracts
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.editorial.EditorialRefs
import com.dailynews.pipeline.editorial.Part2Merger
import com.dailynews.pipeline.context.Part1ShortlistContext
import com.dailynews.pipeline.ports.ArtifactSink
import com.dailynews.pipeline.ports.LogLevel
import com.dailynews.pipeline.ports.RunLogSink
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

data class ProviderBinding(
    val providerId: String,
    val provider: LlmProvider,
    val roleModel: RoleModel,
)

class EditorialLlmException(message: String, cause: Throwable) : RuntimeException(message, cause)

class EditorialContractException(
    val operation: String,
    val violations: List<String>,
) : RuntimeException("$operation contract validation failed after three attempts: ${violations.joinToString("; ")}")

@Serializable
data class EditorialContractViolation(
    val operation: String,
    val attempt: Int,
    val batch: Int? = null,
    @SerialName("item_count") val itemCount: Int? = null,
    val shortfall: Int? = null,
    val errors: List<String>,
)

fun interface ProviderResolver {
    fun resolve(role: EditorialRole, execution: LlmExecutionConfig): ProviderBinding
}

interface PromptSource {
    fun part1Shortlist(topN: Int): String
    fun part1Plan(topN: Int): String
    fun part2Drafter(): String
    fun periodicDigest(): String
}

interface LlmCallAuditSink {
    suspend fun record(
        runId: String,
        role: EditorialRole,
        providerId: String,
        model: String,
        response: LlmResponse?,
        retryIndex: Int,
        outcome: String,
    )
}

object NoOpLlmCallAuditSink : LlmCallAuditSink {
    override suspend fun record(runId: String, role: EditorialRole, providerId: String, model: String, response: LlmResponse?, retryIndex: Int, outcome: String) = Unit
}

private object NoOpArtifactSink : ArtifactSink {
    override suspend fun write(runId: String, relativePath: String, content: ByteArray) = Unit
}

private object NoOpRunLogSink : RunLogSink {
    override suspend fun log(runId: String, step: String, level: LogLevel, message: String) = Unit
}

data class EditorialOutput(
    val part1: Part1Plan,
    val part2: Part2Draft,
    val part2MissingSummariesJson: String? = null,
)

fun interface ShortlistContextFactory {
    suspend fun build(context: LlmContext, links: List<String>): Part1ShortlistContext
}

private object NoCacheShortlistContextFactory : ShortlistContextFactory {
    override suspend fun build(context: LlmContext, links: List<String>) = Part1ShortlistContext(
        meta = context.meta,
        articleCount = links.size,
        cacheHits = 0,
        recentTopN = emptyList(),
        articles = links.mapIndexed { index, link ->
            val article = context.allArticles.first { it.link == link }
            com.dailynews.pipeline.context.ShortlistContextArticle(
                EditorialRefs.articleId(index),
                article.source, article.title, article.link, article.pubDateUtc, article.pubDateIso,
                article.summaryEn, article.articleText,
            )
        },
    )
}

fun interface EditorialEngine {
    suspend fun edit(
        runId: String,
        context: LlmContext,
        brief: Part1Brief,
        part2Context: Part2Context,
        budget: ContextBudget,
        topN: Int,
        maxCallsPerRun: Int,
        part2Mode: Part2Mode,
        llmExecution: LlmExecutionConfig,
    ): EditorialOutput
}

data class Part2SummaryRequest(
    val source: String,
    val title: String,
    val link: String,
    val pubDateIso: String,
    val summaryMaterial: String,
)

fun interface Part2OnDemandGenerator {
    suspend fun generate(
        runId: String,
        articles: List<Part2SummaryRequest>,
        maxCalls: Int,
        llmExecution: LlmExecutionConfig,
    ): List<MissingPart2Summary>
}

class LlmEditorialEngine(
    private val providers: ProviderResolver,
    private val prompts: PromptSource,
    private val audit: LlmCallAuditSink = NoOpLlmCallAuditSink,
    private val shortlistContexts: ShortlistContextFactory = NoCacheShortlistContextFactory,
    private val artifacts: ArtifactSink = NoOpArtifactSink,
    private val logs: RunLogSink = NoOpRunLogSink,
) : EditorialEngine, Part2OnDemandGenerator {
    private val codec = ArtifactJson.codec

    override suspend fun edit(
        runId: String,
        context: LlmContext,
        brief: Part1Brief,
        part2Context: Part2Context,
        budget: ContextBudget,
        topN: Int,
        maxCallsPerRun: Int,
        part2Mode: Part2Mode,
        llmExecution: LlmExecutionConfig,
    ): EditorialOutput = coroutineScope {
        val counter = CallCounter(maxCallsPerRun.coerceIn(4, 100))
        // Same clamp EditorialContracts.validatePart1 applies, so the targets the
        // prompt is given can never disagree with the contract it is checked against.
        val normalizedTopN = topN.coerceIn(EditorialContracts.MIN_TOP_N, EditorialContracts.MAX_TOP_N)
        val part1Deferred = async { editPart1(runId, context, brief, normalizedTopN, counter, llmExecution) }
        val part2Deferred = if (part2Mode == Part2Mode.FULL) async { draftPart2(runId, part2Context, counter, llmExecution) } else null
        val part1 = part1Deferred.await()
        val part2 = part2Deferred?.await()
            ?: Part2Result(Part2Merger.mergeCachedOnly(part2Context), emptyList())
        EditorialOutput(
            part1 = part1.plan,
            part2 = part2.draft,
            part2MissingSummariesJson = codec.encodeToString(MissingPart2Payload(part2.missing)),
        )
    }

    private suspend fun editPart1(
        runId: String,
        context: LlmContext,
        brief: Part1Brief,
        topN: Int,
        counter: CallCounter,
        llmExecution: LlmExecutionConfig,
    ): Part1Result {
        val binding = providers.resolve(EditorialRole.EDITOR, llmExecution)
        val maxTarget = minOf(context.allArticles.size, topN + 15)
        val minTarget = when {
            context.allArticles.isEmpty() -> 0
            context.allArticles.size >= topN + 10 -> topN + 10
            else -> 1
        }
        // Index built from the brief the model is handed, so the ids it reads and the
        // ids resolved here come from one assignment.
        val briefRefs = ArticleRefIndex(brief.articles.map { it.id to it.link })
        var shortlistFeedback = ""
        var acceptedLinks: List<String>? = null
        var lastShortlistErrors = emptyList<String>()
        for (retryIndex in 0..2) {
            val shortlistObject = callObject(
                runId,
                EditorialRole.EDITOR,
                binding,
                StructuredOutputSchema("part1_shortlist", EditorialJsonSchemas.part1Shortlist),
                prompts.part1Shortlist(topN),
                codec.encodeToString(brief) + shortlistFeedback,
                retryIndex,
                counter,
                binding.roleModel.maxTokens,
                operation = "part1_shortlist",
            )
            val decodedShortlist = runCatching { codec.decodeFromJsonElement<Part1ShortlistDraft>(shortlistObject) }
            val shortlist = decodedShortlist.getOrNull()
            if (shortlist == null) {
                val errors = listOf("schema: ${decodedShortlist.exceptionOrNull()?.message}")
                recordViolation(runId, "part1_shortlist", retryIndex, shortlistObject, errors)
                lastShortlistErrors = errors
                shortlistFeedback = "\n\nPrevious shortlist JSON violated the schema: ${decodedShortlist.exceptionOrNull()?.message}. Return {\"refs\":[...]} only."
                continue
            }
            val resolved = shortlist.refs.map { it to briefRefs.resolve(it) }
            val unknown = resolved.filter { it.second == null }.map { briefRefs.unknown("shortlist ref", it.first) }
            if (unknown.isNotEmpty()) {
                recordViolation(runId, "part1_shortlist", retryIndex, shortlistObject, unknown)
                lastShortlistErrors = unknown
                shortlistFeedback = "\n\nPrevious shortlist referenced unknown ids: ${unknown.joinToString("; ")}. " +
                    "Copy an id from the brief verbatim; never invent one."
                continue
            }
            val chosen = resolved.mapNotNull { it.second }
            val errors = buildList {
                if (chosen.size != chosen.distinct().size) add("shortlist references the same article twice")
                if (chosen.size !in minTarget..maxTarget) add("shortlist size ${chosen.size} outside $minTarget..$maxTarget")
            }
            if (errors.isEmpty()) {
                acceptedLinks = chosen
                break
            }
            recordViolation(runId, "part1_shortlist", retryIndex, shortlistObject, errors)
            lastShortlistErrors = errors
            shortlistFeedback = "\n\nPrevious shortlist violated deterministic contracts: ${errors.joinToString("; ")}. Correct every issue."
        }
        val links = acceptedLinks ?: throw EditorialContractException("part1_shortlist", lastShortlistErrors)
        persistArtifact(runId, "part1_shortlist.json", codec.encodeToString(Part1ShortlistPayload(links)))
        val shortlistContext = shortlistContexts.build(context, links)
        val shortlistJson = codec.encodeToString(shortlistContext)
        persistArtifact(runId, "part1_shortlist_context.json", shortlistJson)
        // 这是 Part 1 计划调用真正发出去的负载，也是整条链路上最大的一份，而
        // context_budget 只记 llm_context / part1_brief / part2_context 三份——它在那里
        // 计为零。条数由 shortlist 上限（topN + 15）结构性兜住，所以这里不硬拦，
        // 只把尺寸变成可观测的：真出问题时日志里有数，而不是只能猜。
        // 中文摘要缓存的命中率。
        //
        // 目前这个分支**结构性**命中为零：cacheKey 纯按 link 取，而 RunOrchestrator 把
        // 每一篇抓取到的文章（不只是被报道的）都记进 seen-links，于是任何链接都不会
        // 在跨天时第二次进入缓存查询——只有同日重跑才可能命中。这在 Android 上是自洽的
        // （阅读器本来就呈现整个文章池，所以"全部标记为已呈现"没错），但它让 prompt 里
        // 那两句"可复用 cached_summary_zh"描述了一个不会执行的分支。
        //
        // 不静默删掉这条路径：它的代价是存储与认知负担而不是 token，去留是产品决策。
        // 但要让它可见——有了这行日志，决策就有数据而不是猜测。
        runCatching {
            logs.log(
                runId,
                "editorial_cache",
                LogLevel.INFO,
                "part1 cache hits ${shortlistContext.cacheHits}/${shortlistContext.articles.size}",
            )
        }
        val shortlistBytes = shortlistJson.toByteArray(Charsets.UTF_8).size
        if (shortlistBytes > SHORTLIST_CONTEXT_WARN_BYTES) {
            runCatching {
                logs.log(
                    runId,
                    "context_budget",
                    LogLevel.WARN,
                    "part1_shortlist_context is $shortlistBytes bytes " +
                        "(over $SHORTLIST_CONTEXT_WARN_BYTES); it is resent on every contract retry",
                )
            }
        }
        // Build the index from the payload the model actually receives, so the ids it
        // reads and the ids resolved here can never come from two different orderings.
        val refs = ArticleRefIndex(shortlistContext.articles.map { it.id to it.link })
        var feedback = ""
        var lastPlanErrors = emptyList<String>()
        repeat(3) { retryIndex ->
            val output = callObject(
                runId,
                EditorialRole.EDITOR,
                binding,
                StructuredOutputSchema("part1_plan", EditorialJsonSchemas.part1Plan),
                prompts.part1Plan(topN),
                codec.encodeToString(shortlistContext) + feedback,
                retryIndex,
                counter,
                binding.roleModel.maxTokens,
                operation = "part1_plan",
            )
            val draft = runCatching { codec.decodeFromJsonElement<Part1PlanDraft>(output) }.getOrElse { error ->
                val errors = listOf("schema: ${error.message}")
                recordViolation(runId, "part1_plan", retryIndex, output, errors)
                lastPlanErrors = errors
                feedback = "\n\nPrevious JSON violated the schema: ${error.message}. Return a corrected object."
                return@repeat
            }
            val resolved = EditorialRefs.resolvePart1(draft, refs)
            val decoded = resolved.value ?: run {
                recordViolation(runId, "part1_plan", retryIndex, output, resolved.errors)
                lastPlanErrors = resolved.errors
                feedback = "\n\nPrevious output referenced unknown ids: ${resolved.errors.joinToString("; ")}. " +
                    "Copy an id from the input verbatim; never invent one."
                return@repeat
            }
            // Validate the model's own shortfall instead of overwriting it.
            // Recomputing would make the contract vacuous and let a plan that
            // lost items — to a truncated or repaired response — publish as if
            // it were complete.
            val errors = EditorialContracts.validatePart1(context, decoded, topN)
            if (errors.isEmpty()) return Part1Result(decoded)
            recordViolation(runId, "part1_plan", retryIndex, output, errors)
            lastPlanErrors = errors
            feedback = "\n\nPrevious output violated these deterministic contracts: " +
                "${refs.toIdLanguage(errors.joinToString("; "))}. Correct every item."
        }
        throw EditorialContractException("part1_plan", lastPlanErrors)
    }

    private suspend fun draftPart2(
        runId: String,
        context: Part2Context,
        counter: CallCounter,
        llmExecution: LlmExecutionConfig,
    ): Part2Result {
        val missingArticles = context.groups.flatMap { group -> group.articles.filter { it.needsSummary }.map { group.source to it } }
        val requests = missingArticles.map { (source, article) ->
            Part2SummaryRequest(source, article.title, article.link, article.pubDateIso, article.summaryMaterial.orEmpty())
        }
        val completed = generatePart2(runId, requests, counter, llmExecution)
        return Part2Result(Part2Merger.merge(context, completed), completed)
    }

    /**
     * 周期简报（周报 / 月报）。素材是**已发布**的每日 Top N 条目，所以这里没有抓取、
     * 没有 shortlist，只有一次二次编辑调用。
     *
     * 复用 EDITOR 角色：工作性质（中文编辑判断）与 Part 1 同类，而新增一个
     * EditorialRole 要改用户持久化的 RoleModelMapping——那条路径上任何解码意外
     * 都会把用户的全部 provider 配置静默退回默认，代价与收益完全不成比例。
     * DRAFTER 更不能用：它因 Part 2 停用而在主链路不可达，拿它跑周报等于
     * 悄悄复活一个用户以为已关闭的角色。
     */
    suspend fun digest(
        runId: String,
        input: PeriodicDigestInput,
        maxCalls: Int,
        llmExecution: LlmExecutionConfig,
    ): PeriodicDigest {
        require(input.items.isNotEmpty()) { "periodic digest requires at least one published item" }
        val counter = CallCounter(maxCalls.coerceIn(1, 100))
        val binding = providers.resolve(EditorialRole.EDITOR, llmExecution)
        // Stamping ids here rather than in collectInput keeps the payload and the index
        // to one assignment, so the caller cannot hand over a mis-numbered material list.
        val identified = input.copy(
            items = input.items.mapIndexed { index, item -> item.copy(id = EditorialRefs.articleId(index)) },
        )
        val refs = ArticleRefIndex(identified.items.map { it.id to it.link })
        val availableLinks = identified.items.mapTo(mutableSetOf()) { it.link }
        var feedback = ""
        var lastErrors = emptyList<String>()
        repeat(3) { retryIndex ->
            val output = callObject(
                runId,
                EditorialRole.EDITOR,
                binding,
                StructuredOutputSchema("periodic_digest", EditorialJsonSchemas.periodicDigest),
                prompts.periodicDigest(),
                codec.encodeToString(identified) + feedback,
                retryIndex,
                counter,
                binding.roleModel.maxTokens,
                "periodic_digest",
            )
            val draft = runCatching { codec.decodeFromJsonElement(PeriodicDigestDraft.serializer(), output) }.getOrElse { error ->
                val errors = listOf("schema: ${error.message}")
                recordViolation(runId, "periodic_digest", retryIndex, output, errors)
                lastErrors = errors
                feedback = "\n\nPrevious JSON violated the schema: ${error.message}. Return a corrected object."
                return@repeat
            }
            val resolved = EditorialRefs.resolveDigest(draft, refs)
            val decoded = resolved.value ?: run {
                recordViolation(runId, "periodic_digest", retryIndex, output, resolved.errors)
                lastErrors = resolved.errors
                feedback = "\n\nPrevious output referenced unknown ids: ${resolved.errors.joinToString("; ")}. " +
                    "Copy an id from the input verbatim; never invent one."
                return@repeat
            }
            val errors = PeriodicDigestContracts.validate(decoded, identified.period, availableLinks)
            if (errors.isEmpty()) return decoded
            recordViolation(runId, "periodic_digest", retryIndex, output, errors)
            lastErrors = errors
            feedback = "\n\nPrevious output violated these deterministic contracts: " +
                "${refs.toIdLanguage(errors.joinToString("; "))}. Correct every item."
        }
        throw EditorialContractException("periodic_digest", lastErrors)
    }

    override suspend fun generate(
        runId: String,
        articles: List<Part2SummaryRequest>,
        maxCalls: Int,
        llmExecution: LlmExecutionConfig,
    ): List<MissingPart2Summary> {
        if (articles.isEmpty()) return emptyList()
        require(maxCalls > 0) { "per-run LLM call limit exhausted" }
        return generatePart2(runId, articles, CallCounter(maxCalls), llmExecution)
    }

    private suspend fun generatePart2(
        runId: String,
        articles: List<Part2SummaryRequest>,
        counter: CallCounter,
        llmExecution: LlmExecutionConfig,
    ): List<MissingPart2Summary> {
        val binding = providers.resolve(EditorialRole.DRAFTER, llmExecution)
        val completed = mutableListOf<MissingPart2Summary>()
        articles.chunked(25).forEachIndexed { batchIndex, batch ->
            // Ids restart at a1 in every batch: the model only ever sees one batch, and a
            // short range is exactly what makes the reference cheap to copy correctly.
            val batchPayload = Part2BatchInput(
                batch.mapIndexed { index, request -> request.toBatchArticle(EditorialRefs.articleId(index)) },
            )
            completed += callPart2Batch(runId, binding, batchPayload, batchIndex, counter)
        }
        return completed
    }

    private suspend fun callPart2Batch(
        runId: String,
        binding: ProviderBinding,
        input: Part2BatchInput,
        batchIndex: Int,
        counter: CallCounter,
    ): List<MissingPart2Summary> {
        val refs = ArticleRefIndex(input.articles.map { it.id to it.link })
        var feedback = ""
        var lastErrors = emptyList<String>()
        repeat(3) { retryIndex ->
            val objectResult = callObject(
                runId,
                EditorialRole.DRAFTER,
                binding,
                StructuredOutputSchema("part2_summaries", EditorialJsonSchemas.missingPart2),
                prompts.part2Drafter(),
                codec.encodeToString(input) + feedback,
                retryIndex,
                counter,
                binding.roleModel.maxTokens,
                operation = "part2_batch",
                batch = (batchIndex + 1).toString(),
                auditIndexBase = batchIndex * 100,
            )
            val draft = runCatching { codec.decodeFromJsonElement<MissingPart2Draft>(objectResult) }.getOrElse { error ->
                val errors = listOf("schema: ${error.message}")
                recordViolation(runId, "part2_batch", retryIndex, objectResult, errors, batchIndex + 1)
                lastErrors = errors
                feedback = "\n\nSchema error: ${error.message}. Return a corrected object."
                return@repeat
            }
            val resolved = EditorialRefs.resolvePart2(draft, refs)
            val items = resolved.value ?: run {
                recordViolation(runId, "part2_batch", retryIndex, objectResult, resolved.errors, batchIndex + 1)
                lastErrors = resolved.errors
                feedback = "\n\nUnknown ids: ${resolved.errors.joinToString("; ")}. " +
                    "Copy an id from the input verbatim; never invent one."
                return@repeat
            }
            val requestedLinks = input.articles.map { com.dailynews.pipeline.text.TextUtils.cleanText(it.link) }.toSet()
            val answeredLinks = items.map { com.dailynews.pipeline.text.TextUtils.cleanText(it.link) }
            val errors = buildList {
                if (answeredLinks.size != answeredLinks.toSet().size) add("batch response answers the same article twice")
                items.forEachIndexed { index, item ->
                    addAll(EditorialContracts.summaryLintErrors(item.summaryZh, "item ${index + 1} summary_zh", 200))
                }
                if (answeredLinks.toSet() != requestedLinks) add("batch response does not cover every requested id")
            }
            if (errors.isEmpty()) return items
            recordViolation(runId, "part2_batch", retryIndex, objectResult, errors, batchIndex + 1)
            lastErrors = errors
            feedback = "\n\nContract errors: ${refs.toIdLanguage(errors.joinToString("; "))}. Correct all items."
        }
        throw EditorialContractException("part2_batch", lastErrors)
    }

    private suspend fun recordViolation(
        runId: String,
        operation: String,
        retryIndex: Int,
        output: JsonObject,
        errors: List<String>,
        batch: Int? = null,
    ) {
        val itemCount = sequenceOf("items", "links", "sections")
            .mapNotNull { key -> (output[key] as? JsonArray)?.size }
            .firstOrNull()
        val shortfall = (output["shortfall"] as? JsonPrimitive)?.intOrNull
        val violation = EditorialContractViolation(
            operation = operation,
            attempt = retryIndex + 1,
            batch = batch,
            itemCount = itemCount,
            shortfall = shortfall,
            errors = errors,
        )
        val batchPath = batch?.let { "-batch-$it" }.orEmpty()
        persistArtifact(
            runId,
            "contract_violations/$operation$batchPath-attempt-${retryIndex + 1}.json",
            codec.encodeToString(violation),
        )
        runCatching {
            logs.log(
                runId,
                "contract_$operation",
                LogLevel.WARN,
                "attempt=${retryIndex + 1}${batch?.let { " batch=$it" }.orEmpty()} " +
                    "items=${itemCount ?: "unknown"} shortfall=${shortfall ?: "unknown"} errors=${errors.joinToString("; ")}",
            )
        }
    }

    private suspend fun persistArtifact(runId: String, path: String, text: String) {
        try {
            artifacts.write(runId, path, text.toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            runCatching { logs.log(runId, "artifact", LogLevel.ERROR, "snapshot $path failed: ${error.message}") }
            throw IllegalStateException("required editorial artifact $path could not be persisted", error)
        }
    }

    private suspend fun callObject(
        runId: String,
        role: EditorialRole,
        binding: ProviderBinding,
        schema: StructuredOutputSchema,
        system: String,
        user: String,
        retryIndex: Int,
        counter: CallCounter,
        maxTokens: Int,
        operation: String,
        batch: String? = null,
        auditIndexBase: Int = 0,
    ): JsonObject {
        val request = LlmRequest(
            model = binding.roleModel.model,
            system = system,
            userContent = user + "\n\nReturn exactly one JSON object and no Markdown fencing.",
            maxTokens = maxTokens,
            responseSchema = schema,
        )
        var lastTransportAttempt = 0
        return try {
            StructuredLlm(binding.provider).completeObject(
                request = request,
                beforeAttempt = counter::take,
                onAttempt = { jsonAttempt, response, outcome ->
                    lastTransportAttempt = jsonAttempt
                    audit.record(
                        runId,
                        role,
                        binding.providerId,
                        binding.roleModel.model,
                        response,
                        auditIndexBase + retryIndex * 10 + jsonAttempt,
                        outcome,
                    )
                },
            ).first
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val batchDetail = batch?.let { " batch=$it" }.orEmpty()
            val detail = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
            throw EditorialLlmException(
                "role=${role.name} provider=${binding.providerId} model=${binding.roleModel.model} " +
                    "operation=$operation$batchDetail contract_attempt=${retryIndex + 1} " +
                    "transport_attempt=${lastTransportAttempt + 1}: $detail",
                error,
            )
        }
    }
}

/** 实测正常日约 114 KB。超出这个数就值得在日志里留一行，因为它每轮重试都重发。 */
private const val SHORTLIST_CONTEXT_WARN_BYTES = 200_000

private class CallCounter(private val maximum: Int) {
    private var count = 0
    @Synchronized fun take() {
        count += 1
        require(count <= maximum) { "per-run LLM call limit exceeded ($maximum)" }
    }
}

@Serializable
private data class Part2BatchInput(val articles: List<Part2BatchArticle>)

@Serializable
private data class Part2BatchArticle(
    /** 短引用 id（批次内编号）。摘要条目只写这个，不回显 link。 */
    val id: String,
    val source: String,
    val title: String,
    val link: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_material") val summaryMaterial: String,
)

private fun Part2SummaryRequest.toBatchArticle(id: String) = Part2BatchArticle(
    id,
    source,
    title,
    link,
    pubDateIso,
    summaryMaterial,
)

private data class Part1Result(val plan: Part1Plan)
private data class Part2Result(val draft: Part2Draft, val missing: List<MissingPart2Summary>)
