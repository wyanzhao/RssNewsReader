package com.dailynews.pipeline

import com.dailynews.model.Article
import com.dailynews.model.ArticleTextConfig
import com.dailynews.model.ArtifactJson
import com.dailynews.model.ContextBudgetConfig
import com.dailynews.model.PipelineConfig
import com.dailynews.model.SummaryEnrichmentConfig
import com.dailynews.model.RawMeta
import com.dailynews.model.RawRun
import com.dailynews.model.ValidationResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The real 2026-08-03 run, reassembled so Kotlin can be diffed against the
 * artifacts Python wrote that day.
 *
 * Articles come from `llm_context.json` rather than `raw.json`: Python's raw
 * articles carry a single `pub_date`, while this port stores `pub_date_utc` and
 * `pub_date_iso` separately. That divergence is intentional (the two raw files
 * are never exchanged), so the fixture starts from the shape both sides agree on.
 */
object ReplayFixture {
    private const val DIR = "replay/2026-08-03"

    class Loaded(
        val raw: RawRun,
        val validation: ValidationResult,
        val config: PipelineConfig,
        val reportPath: String,
    ) {
        fun expected(name: String): JsonElement = normalize(FixtureFactory.text("$DIR/$name"))
    }

    fun load(): Loaded {
        val context = FixtureFactory.json("$DIR/llm_context.json")
        val validation = ArtifactJson.codec.decodeFromString<ValidationResult>(FixtureFactory.text("$DIR/validation.json"))
        val rawJson = FixtureFactory.json("$DIR/raw.json")
        val articles = context.getValue("all_articles").jsonArray.map {
            ArtifactJson.codec.decodeFromString<Article>(it.toString())
        }
        val meta = context.getValue("meta").jsonObject
        val runtimeConfig = rawJson.getValue("runtime_config").jsonObject
        val budgetLimits = runtimeConfig.getValue("context_budget").jsonObject
        val enrichment = runtimeConfig.getValue("summary_enrichment").jsonObject
        val articleText = runtimeConfig.getValue("article_text").jsonObject

        val raw = RawRun(
            meta = RawMeta(
                generatedAtUtc = meta.getValue("generated_at_utc").jsonPrimitive.content,
                runId = meta.getValue("run_id").jsonPrimitive.content,
                inputMode = "json",
                feedCountExpected = validation.feedResults.size,
            ),
            count = articles.size,
            uniqueSourceCount = articles.map(Article::source).distinct().size,
            uniqueSources = articles.map(Article::source).distinct(),
            articles = articles,
            configuredFeedCount = validation.feedResults.size,
            feedResults = validation.feedResults,
        )

        return Loaded(
            raw = raw,
            validation = validation,
            // Effective values that day, not this port's defaults: the run used a
            // repo pipeline_config.json that raises several of them.
            config = PipelineConfig(
                summaryEnrichment = SummaryEnrichmentConfig(
                    shortSummaryThreshold = enrichment.getValue("short_summary_threshold").jsonPrimitive.content.toInt(),
                    pageFallbackCap = enrichment.getValue("page_fallback_cap").jsonPrimitive.content.toInt(),
                ),
                articleText = ArticleTextConfig(
                    enabled = articleText.getValue("enabled").jsonPrimitive.content.toBoolean(),
                    maxWords = articleText.getValue("max_words").jsonPrimitive.content.toInt(),
                ),
                contextBudget = ContextBudgetConfig(
                    llmContextMaxBytes = budgetLimits.getValue("llm_context_max_bytes").jsonPrimitive.content.toInt(),
                    part1BriefMaxBytes = budgetLimits.getValue("part1_brief_max_bytes").jsonPrimitive.content.toInt(),
                    part2ContextMaxBytes = budgetLimits.getValue("part2_context_max_bytes").jsonPrimitive.content.toInt(),
                    totalContextMaxBytes = budgetLimits.getValue("total_context_max_bytes").jsonPrimitive.content.toInt(),
                ),
            ),
            reportPath = meta.getValue("report_path").jsonPrimitive.content,
        )
    }

    /**
     * Drops the fields that legitimately differ between a stored artifact and a
     * fresh rebuild: run identity, and the cache file path Android has no
     * equivalent for.
     */
    fun normalize(text: String): JsonElement = strip(ArtifactJson.codec.parseToJsonElement(text))

    private fun strip(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                when (key) {
                    "meta" -> Unit
                    // Registered divergence 2: Android stamps a short-ref id on
                    // every article so the model cites by id instead of echoing
                    // URLs; Python is still a link-keyed contract and has no
                    // such field. Every remaining field is still compared
                    // byte-for-byte, so this strip only lets that one deliberate
                    // difference through and will not hide drift in derived logic.
                    "id" -> Unit
                    "cache" -> put(key, strip(JsonObject(value.jsonObject - "path")))
                    else -> put(key, strip(value))
                }
            }
        }
        // Arrays were previously not recursed, so any registered divergence that
        // landed in a list like articles[] silently stopped working. `id` is
        // exactly that case.
        is JsonArray -> JsonArray(element.map(::strip))
        else -> element
    }
}
