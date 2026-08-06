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
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part2Context
import com.dailynews.model.Part2Draft
import com.dailynews.model.Part2Mode
import com.dailynews.model.LlmExecutionConfig
import com.dailynews.model.EditorialJsonSchemas
import com.dailynews.model.MissingPart2Payload
import com.dailynews.model.MissingPart2Summary
import com.dailynews.model.Part1ShortlistPayload
import com.dailynews.pipeline.editorial.PeriodicDigestContracts
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.editorial.Part2Merger
import com.dailynews.pipeline.context.Part1ShortlistContext
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

data class ProviderBinding(
    val providerId: String,
    val provider: LlmProvider,
    val roleModel: RoleModel,
)

class EditorialLlmException(message: String, cause: Throwable) : RuntimeException(message, cause)

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

data class EditorialOutput(
    val part1: Part1Plan,
    val part2: Part2Draft,
    val part1ShortlistJson: String? = null,
    val part1ShortlistContextJson: String? = null,
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
        articles = links.map { link ->
            val article = context.allArticles.first { it.link == link }
            com.dailynews.pipeline.context.ShortlistContextArticle(
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
            part1ShortlistJson = codec.encodeToString(Part1ShortlistPayload(part1.links)),
            part1ShortlistContextJson = codec.encodeToString(part1.context),
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
        val known = context.allArticles.associateBy { com.dailynews.pipeline.text.TextUtils.cleanText(it.link) }
        val maxTarget = minOf(context.allArticles.size, topN + 15)
        val minTarget = when {
            context.allArticles.isEmpty() -> 0
            context.allArticles.size >= topN + 10 -> topN + 10
            else -> 1
        }
        var shortlistFeedback = ""
        var acceptedLinks: List<String>? = null
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
            val decodedShortlist = runCatching { codec.decodeFromJsonElement<Part1ShortlistPayload>(shortlistObject) }
            val shortlist = decodedShortlist.getOrNull()
            if (shortlist == null) {
                shortlistFeedback = "\n\nPrevious shortlist JSON violated the schema: ${decodedShortlist.exceptionOrNull()?.message}. Return {\"links\":[...]} only."
                continue
            }
            val errors = buildList {
                val cleanedLinks = shortlist.links.map(com.dailynews.pipeline.text.TextUtils::cleanText)
                if (cleanedLinks.size != cleanedLinks.distinct().size) add("shortlist contains duplicate links")
                if (!cleanedLinks.all(known::containsKey)) add("shortlist contains links absent from all_articles")
                if (shortlist.links.size !in minTarget..maxTarget) add("shortlist size ${shortlist.links.size} outside $minTarget..$maxTarget")
            }
            if (errors.isEmpty()) {
                acceptedLinks = shortlist.links.map { known.getValue(com.dailynews.pipeline.text.TextUtils.cleanText(it)).link }
                break
            }
            shortlistFeedback = "\n\nPrevious shortlist violated deterministic contracts: ${errors.joinToString("; ")}. Correct every issue."
        }
        val links = acceptedLinks ?: error("Part 1 shortlist validation failed after two retries")
        val shortlistContext = shortlistContexts.build(context, links)
        var feedback = ""
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
            val decoded = runCatching { codec.decodeFromJsonElement<Part1Plan>(output) }.getOrElse { error ->
                feedback = "\n\nPrevious JSON violated the schema: ${error.message}. Return a corrected object."
                return@repeat
            }
            // Validate the model's own shortfall instead of overwriting it.
            // Recomputing would make the contract vacuous and let a plan that
            // lost items — to a truncated or repaired response — publish as if
            // it were complete.
            val errors = EditorialContracts.validatePart1(context, decoded, topN)
            if (errors.isEmpty()) return Part1Result(decoded, links, shortlistContext)
            feedback = "\n\nPrevious output violated these deterministic contracts: ${errors.joinToString("; ")}. Correct every item."
        }
        error("Part 1 contract validation failed after two retries")
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
        val availableLinks = input.items.mapTo(mutableSetOf()) { it.link }
        var feedback = ""
        repeat(3) { retryIndex ->
            val output = callObject(
                runId,
                EditorialRole.EDITOR,
                binding,
                StructuredOutputSchema("periodic_digest", EditorialJsonSchemas.periodicDigest),
                prompts.periodicDigest(),
                codec.encodeToString(input) + feedback,
                retryIndex,
                counter,
                binding.roleModel.maxTokens,
                "periodic_digest",
            )
            val decoded = codec.decodeFromJsonElement(PeriodicDigest.serializer(), output)
            val errors = PeriodicDigestContracts.validate(decoded, input.period, availableLinks)
            if (errors.isEmpty()) return decoded
            feedback = "\n\nPrevious output violated these deterministic contracts: ${errors.joinToString("; ")}. Correct every item."
        }
        error("Periodic digest contract validation failed after three attempts")
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
            val batchPayload = Part2BatchInput(batch.map(Part2SummaryRequest::toBatchArticle))
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
        var feedback = ""
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
            val payload = runCatching { codec.decodeFromJsonElement<MissingPart2Payload>(objectResult) }.getOrElse { error ->
                feedback = "\n\nSchema error: ${error.message}. Return a corrected object."
                return@repeat
            }
            val allowed = input.articles.associateBy { com.dailynews.pipeline.text.TextUtils.cleanText(it.link) }
            val cleanedPayloadLinks = payload.items.map { com.dailynews.pipeline.text.TextUtils.cleanText(it.link) }
            val errors = buildList {
                if (cleanedPayloadLinks.size != cleanedPayloadLinks.toSet().size) {
                    add("batch response contains duplicate links")
                }
                payload.items.forEachIndexed { index, item ->
                    if (com.dailynews.pipeline.text.TextUtils.cleanText(item.link) !in allowed) add("item ${index + 1} link absent from batch")
                    addAll(EditorialContracts.summaryLintErrors(item.summaryZh, "item ${index + 1}", 200))
                }
                if (cleanedPayloadLinks.toSet() != allowed.keys) add("batch response does not cover every requested link")
            }
            if (errors.isEmpty()) {
                return payload.items.map { item ->
                    item.copy(link = allowed.getValue(com.dailynews.pipeline.text.TextUtils.cleanText(item.link)).link)
                }
            }
            feedback = "\n\nContract errors: ${errors.joinToString("; ")}. Correct all items."
        }
        error("Part 2 batch contract validation failed after two retries")
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
    val source: String,
    val title: String,
    val link: String,
    @SerialName("pub_date_iso") val pubDateIso: String,
    @SerialName("summary_material") val summaryMaterial: String,
)

private fun Part2SummaryRequest.toBatchArticle() = Part2BatchArticle(
    source,
    title,
    link,
    pubDateIso,
    summaryMaterial,
)

private data class Part1Result(val plan: Part1Plan, val links: List<String>, val context: Part1ShortlistContext)
private data class Part2Result(val draft: Part2Draft, val missing: List<MissingPart2Summary>)
