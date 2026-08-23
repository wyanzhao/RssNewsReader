package com.dailynews.pipeline.context

import com.dailynews.model.Article
import com.dailynews.model.ArticleRef
import com.dailynews.model.ContextBudget
import com.dailynews.model.ContextBudgetCounts
import com.dailynews.model.ContextBudgetLimits
import com.dailynews.model.ContextBudgetSizes
import com.dailynews.model.ContextBudgetViolation
import com.dailynews.model.LlmContext
import com.dailynews.model.LlmMeta
import com.dailynews.model.LlmValidation
import com.dailynews.model.Part1Brief
import com.dailynews.model.Part1BriefArticle
import com.dailynews.model.Part2Context
import com.dailynews.model.Part2ContextArticle
import com.dailynews.model.Part2ContextGroup
import com.dailynews.model.PerSourceCount
import com.dailynews.model.PipelineConfig
import com.dailynews.model.PreviewPolicy
import com.dailynews.model.RawRun
import com.dailynews.model.SourceGroup
import com.dailynews.model.ValidationResult
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.editorial.EditorialRefs
import com.dailynews.pipeline.ports.EditorialCacheRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ContextArtifacts(
    val llmContext: LlmContext,
    val part1Brief: Part1Brief,
    val part2Context: Part2Context,
    val contextBudget: ContextBudget,
)

fun interface CacheLookup {
    suspend fun get(article: Article): EditorialCacheRecord?
}

class LlmContextBuilder {
    suspend fun build(
        raw: RawRun,
        validation: ValidationResult,
        reportDate: String,
        reportPath: String,
        config: PipelineConfig = PipelineConfig(),
        feedback: List<String> = emptyList(),
        cacheLookup: CacheLookup = CacheLookup { null },
    ): ContextArtifacts {
        require(validation.passed) { "validation.passed == true is required before building editorial contexts" }
        val meta = LlmMeta(reportDate, raw.meta.generatedAtUtc, raw.meta.runId, reportPath)
        val context = buildContext(raw, validation, meta)
        val brief = buildBrief(raw, meta, config, feedback.ifEmpty { config.editorFeedback })
        val part2 = buildPart2(raw, validation, meta, config, cacheLookup)
        val budget = buildBudget(context, brief, part2, config)
        return ContextArtifacts(context, brief, part2, budget)
    }

    private fun buildContext(raw: RawRun, validation: ValidationResult, meta: LlmMeta): LlmContext {
        val grouped = raw.articles.groupBy { it.source }
        val groups = validation.feedResults.map { result ->
            val articles = grouped[result.source].orEmpty()
            SourceGroup(
                source = result.source,
                url = result.url,
                status = result.status,
                articleCount = articles.size,
                articleRefs = articles.map { ArticleRef(it.title, it.link, it.pubDateIso) },
            )
        }
        return LlmContext(
            meta = meta,
            validation = LlmValidation(
                validation.passed,
                validation.blockingReasons,
                validation.warnings,
                validation.counts,
                validation.policy,
            ),
            allArticles = raw.articles,
            sourceGroups = groups,
        )
    }

    private fun buildBrief(raw: RawRun, meta: LlmMeta, config: PipelineConfig, feedback: List<String>): Part1Brief {
        val threshold = config.summaryEnrichment.shortSummaryThreshold
        return Part1Brief(
            meta = meta,
            articleCount = raw.articles.size,
            articleTextPreviewWords = 70,
            previewPolicy = PreviewPolicy(threshold, "summary_en_missing_or_shorter_than_threshold"),
            articles = raw.articles.mapIndexed { index, article ->
                Part1BriefArticle(
                    id = EditorialRefs.articleId(index),
                    source = article.source,
                    title = article.title,
                    link = article.link,
                    pubDateUtc = article.pubDateUtc,
                    pubDateIso = article.pubDateIso,
                    summaryEn = article.summaryEn,
                    articleTextPreview = article.articleText
                        .takeIf { article.summaryEn.trim().length < threshold && it.isNotBlank() }
                        ?.truncateWords(70),
                )
            },
            editorFeedback = feedback.takeLast(20),
        )
    }

    private suspend fun buildPart2(
        raw: RawRun,
        validation: ValidationResult,
        meta: LlmMeta,
        config: PipelineConfig,
        cacheLookup: CacheLookup,
    ): Part2Context {
        val grouped = raw.articles.groupBy { it.source }
        var hits = 0
        var misses = 0
        val groups = validation.feedResults.map { result ->
            val articles = grouped[result.source].orEmpty().map { article ->
                val cached = cacheLookup.get(article)
                    ?.takeIf { !it.summaryZh.isNullOrBlank() }
                    ?.takeIf { EditorialContracts.summaryLintErrors(it.summaryZh, "cached summary_zh", 200).isEmpty() }
                if (cached != null) hits += 1 else misses += 1
                val useSummary = article.summaryEn.trim().length >= config.summaryEnrichment.shortSummaryThreshold
                Part2ContextArticle(
                    title = article.title,
                    link = article.link,
                    pubDateUtc = article.pubDateUtc,
                    pubDateIso = article.pubDateIso,
                    summaryMaterial = if (cached != null) null else when {
                        useSummary -> article.summaryEn
                        article.articleText.isNotBlank() -> article.articleText.truncateWords(60)
                        else -> article.summaryEn
                    },
                    summarySource = when {
                        cached != null -> "cache"
                        useSummary -> "summary_en"
                        article.articleText.isNotBlank() -> "article_text_fallback"
                        article.summaryEn.isNotBlank() -> "short_summary_en"
                        else -> "empty"
                    },
                    cacheStatus = if (cached != null) "hit" else "miss",
                    needsSummary = cached == null,
                    summaryZh = cached?.summaryZh,
                    eventKey = if (cached != null) cached.eventKey.orEmpty() else null,
                    noiseBucket = cached?.noiseBucket ?: if (cached != null) "covered" else null,
                )
            }
            Part2ContextGroup(
                source = result.source,
                url = result.url,
                status = result.status,
                articleCount = articles.size,
                errorText = result.error,
                articles = articles,
            )
        }
        return Part2Context(
            meta = meta,
            totalArticles = raw.articles.size,
            groups = groups,
            summaryPolicy = buildJsonObject {
                put("prefer", "summary_en")
                put("fallback", "article_text")
                put("short_summary_threshold", config.summaryEnrichment.shortSummaryThreshold)
                put("article_text_fallback_words", 60)
            },
            cache = buildJsonObject {
                put("path", "")
                put("hits", hits)
                put("misses", misses)
            },
        )
    }

    private fun buildBudget(
        context: LlmContext,
        brief: Part1Brief,
        part2: Part2Context,
        config: PipelineConfig,
    ): ContextBudget {
        val codec = com.dailynews.model.ArtifactJson.codec
        fun bytes(value: String) = value.toByteArray(Charsets.UTF_8).size
        val contextBytes = bytes(codec.encodeToString(context))
        val briefBytes = bytes(codec.encodeToString(brief))
        val part2Bytes = bytes(codec.encodeToString(part2))
        val totalBytes = contextBytes + briefBytes + part2Bytes
        val limits = config.contextBudget
        // All four sizes are reported as before (the artifact shape is a contract, and the Python side reconciles
        // byte-for-byte), but **only payloads that actually enter an LlmRequest produce a violation**.
        //
        // Previously all four took part in gating, yet three of them are never sent at all: `llm_context` is never
        // serialized into any request (it is only used for the known mapping and validation), `part2_context` is dead
        // under forced LAZY, and `total` is the sum of the three. That means roughly 81% of this gate's denominator
        // was free bytes — it could block a run over free bytes while letting through the one that actually costs
        // money (`part1_shortlist_context`, about 114 KB, is counted as zero here; see shortlistContextViolation).
        val violations = buildList {
            if (briefBytes > limits.part1BriefMaxBytes) add(ContextBudgetViolation("part1_brief_bytes", briefBytes, limits.part1BriefMaxBytes))
        }
        return ContextBudget(
            meta = context.meta,
            limits = ContextBudgetLimits(
                limits.llmContextMaxBytes,
                limits.part1BriefMaxBytes,
                limits.part2ContextMaxBytes,
                limits.totalContextMaxBytes,
            ),
            sizes = ContextBudgetSizes(contextBytes, briefBytes, part2Bytes, totalBytes),
            counts = ContextBudgetCounts(
                context.allArticles.size,
                context.sourceGroups.size,
                part2.groups.sumOf { group -> group.articles.count { !it.needsSummary } },
                part2.groups.sumOf { group -> group.articles.count { it.needsSummary } },
            ),
            perSource = context.sourceGroups.map { PerSourceCount(it.source, it.articleCount) },
            withinBudget = violations.isEmpty(),
            violations = violations,
        )
    }
}

private fun String.truncateWords(maxWords: Int): String {
    val words = trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return if (words.size > maxWords) words.take(maxWords).joinToString(" ") + "..." else words.joinToString(" ")
}
