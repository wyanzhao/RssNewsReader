package com.dailynews.pipeline.validate

import com.dailynews.model.FeedConfigDocument
import com.dailynews.model.RawRun
import com.dailynews.model.ValidationCounts
import com.dailynews.model.ValidationMeta
import com.dailynews.model.ValidationPolicy
import com.dailynews.model.ValidationResult
import com.dailynews.model.ValidatorExitClass
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class ValidationOutcome(val result: ValidationResult, val exitClass: ValidatorExitClass)

class QcValidator {
    fun validate(raw: RawRun, feeds: FeedConfigDocument): ValidationOutcome {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val allowedPolicies = setOf("block", "warn")
        val feedPolicies = linkedMapOf<String, String>()

        feeds.feeds.forEachIndexed { index, feed ->
            if (feed.name.isBlank()) {
                blocking += "feeds.json.feeds[$index].name must be a non-empty string"
            } else if (feed.errorPolicy !in allowedPolicies) {
                blocking += "feeds.json.feeds[$index].error_policy must be one of ['block', 'warn']"
            } else {
                feedPolicies[feed.name] = feed.errorPolicy
            }
        }

        if (feeds.feeds.size != raw.configuredFeedCount) {
            blocking += "configured_feed_count mismatch: feeds.json has ${feeds.feeds.size}, raw.json reports ${raw.configuredFeedCount}"
        }
        if (raw.meta.feedCountExpected != raw.configuredFeedCount) {
            blocking += "meta.feed_count_expected mismatch: expected ${raw.configuredFeedCount}, got ${raw.meta.feedCountExpected}"
        }
        if (raw.feedResults.size != raw.configuredFeedCount) {
            blocking += "feed_results length mismatch: expected ${raw.configuredFeedCount}, got ${raw.feedResults.size}"
        }
        if (raw.articles.size != raw.count) {
            blocking += "articles length mismatch: count=${raw.count}, articles=${raw.articles.size}"
        }

        val articleCountsBySource = linkedMapOf<String, Int>()
        raw.articles.forEachIndexed { index, article ->
            if (article.source.isBlank()) {
                blocking += "articles[$index].source must be a non-empty string"
            } else {
                articleCountsBySource[article.source] = articleCountsBySource.getOrDefault(article.source, 0) + 1
                if (article.source !in feedPolicies) {
                    blocking += "articles[$index].source not found in feeds.json: ${article.source}"
                }
            }
        }

        val validStatuses = setOf("ok", "empty", "error")
        val seenSources = mutableSetOf<String>()
        val errorLabels = mutableListOf<String>()
        val warnErrorSources = mutableListOf<String>()
        raw.feedResults.forEachIndexed { index, result ->
            val source = result.source
            if (source.isBlank()) {
                blocking += "feed_results[$index].source must be a non-empty string"
                return@forEachIndexed
            }
            if (source !in feedPolicies) blocking += "feed_results[$index].source not found in feeds.json: $source"
            if (!seenSources.add(source)) blocking += "feed_results[$index].source is duplicated: $source"
            if (result.status !in validStatuses) {
                blocking += "feed_results[$index].status must be one of ['empty', 'error', 'ok']"
            }
            val actual = articleCountsBySource.getOrDefault(source, 0)
            if (result.articleCount < 0) {
                blocking += "feed_results[$index].article_count must be a non-negative integer"
            }
            if (result.articleCount != actual) {
                blocking += "feed_results[$index].article_count mismatch for source $source: expected $actual, got ${result.articleCount}"
            }
            val hasError = !result.error.isNullOrBlank()
            when (result.status) {
                "ok" -> {
                    if (result.articleCount == 0) blocking += "feed_results[$index].status ok requires article_count > 0: $source"
                    if (hasError) blocking += "feed_results[$index].status ok must not include error: $source"
                }
                "empty" -> {
                    if (result.articleCount != 0) blocking += "feed_results[$index].status empty requires article_count == 0: $source"
                    if (hasError) blocking += "feed_results[$index].status empty must not include error: $source"
                }
                "error" -> {
                    if (!hasError) blocking += "feed_results[$index].status error requires non-empty error: $source"
                    val label = if (hasError) "$source (${result.error})" else source
                    errorLabels += label
                    if (feedPolicies[source] == "warn") warnErrorSources += source
                }
            }
        }
        feedPolicies.keys.forEach { source ->
            if (source !in seenSources) blocking += "feed_results missing source from feeds.json: $source"
        }

        val okCount = raw.feedResults.count { it.status == "ok" }
        val emptyCount = raw.feedResults.count { it.status == "empty" }
        val errorCount = raw.feedResults.count { it.status == "error" }
        val counts = ValidationCounts(
            configured = feeds.feeds.size,
            results = raw.feedResults.size,
            ok = okCount,
            empty = emptyCount,
            error = errorCount,
            articles = raw.count,
            blockingError = 0,
            warnError = errorCount,
        )
        val policy = ValidationPolicy(warnErrorSources = feedPolicies.filterValues { it == "warn" }.keys.sorted())

        val uniqueSources = raw.uniqueSources
        if (raw.uniqueSourceCount != null && uniqueSources != null && raw.uniqueSourceCount != uniqueSources.size) {
            warnings += "unique_source_count differs from unique_sources length: ${raw.uniqueSourceCount} vs ${uniqueSources.size}"
        }
        val emptySources = raw.feedResults.filter { it.status == "empty" }.map { it.source }
        if (emptySources.isNotEmpty()) warnings += "${emptySources.size} empty feed(s): ${emptySources.joinToString(", ")}"
        if (warnErrorSources.isNotEmpty()) warnings += "${warnErrorSources.size} warn-only error feed(s): ${warnErrorSources.sorted().joinToString(", ")}"
        val nonWarnErrors = errorLabels.filter { label -> label.substringBefore(" (") !in warnErrorSources }
        if (nonWarnErrors.isNotEmpty()) warnings += "${nonWarnErrors.size} failed feed(s): ${nonWarnErrors.joinToString(", ")}"
        staleLabels(raw).takeIf { it.isNotEmpty() }?.let { labels ->
            warnings += "${labels.size} stale feed(s): ${labels.joinToString(", ")}"
        }

        val totalArticles = raw.feedResults.sumOf { it.articleCount }
        if (totalArticles != raw.count) blocking += "sum(article_count) mismatch: expected ${raw.count}, got $totalArticles"
        if (raw.count == 0) blocking += "count == 0"

        val contractPrefixes = listOf(
            "configured_feed_count mismatch",
            "meta.feed_count_expected mismatch",
            "feed_results length mismatch",
            "articles length mismatch",
            "feed_results[",
            "sum(article_count) mismatch",
        )
        val exitClass = when {
            blocking.isEmpty() -> ValidatorExitClass.OK
            blocking.any { reason -> contractPrefixes.any(reason::startsWith) } -> ValidatorExitClass.CONTRACT_MISMATCH
            blocking.any { it.startsWith("count == 0") } -> ValidatorExitClass.QUALITY_BLOCK
            else -> ValidatorExitClass.CONTRACT_MISMATCH
        }
        return ValidationOutcome(
            result = ValidationResult(
                passed = exitClass == ValidatorExitClass.OK,
                blockingReasons = blocking,
                warnings = warnings,
                counts = counts,
                policy = policy,
                feedResults = raw.feedResults,
                meta = ValidationMeta(raw.meta.generatedAtUtc, raw.meta.runId, raw.meta.inputMode),
            ),
            exitClass = exitClass,
        )
    }

    fun damagedInput(message: String): ValidationOutcome = ValidationOutcome(
        ValidationResult(passed = false, blockingReasons = listOf(message)),
        ValidatorExitClass.INPUT_DAMAGED,
    )

    private fun staleLabels(raw: RawRun): List<String> {
        val generated = parseInstant(raw.meta.generatedAtUtc) ?: return emptyList()
        val fetchConfig = raw.runtimeConfig?.get("fetch") as? JsonObject
        val threshold = (fetchConfig?.get("stale_feed_warn_days") as? JsonPrimitive)
            ?.intOrNull
            ?.takeIf { it > 0 }
            ?: 30
        return raw.feedResults.mapNotNull { result ->
            if (result.status == "error") return@mapNotNull null
            val newest = result.newestItemDate?.let(::parseInstant) ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(newest, generated)
            if (days >= threshold) "${result.source} (newest item ${days}d old)" else null
        }
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        }
}
