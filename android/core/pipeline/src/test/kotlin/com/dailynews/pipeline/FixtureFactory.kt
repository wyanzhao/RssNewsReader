package com.dailynews.pipeline

import com.dailynews.model.Article
import com.dailynews.model.ArtifactJson
import com.dailynews.model.FeedConfigDocument
import com.dailynews.model.FeedResult
import com.dailynews.model.PipelineConfig
import com.dailynews.model.RawMeta
import com.dailynews.model.RawRun
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object FixtureFactory {
    private val codec = ArtifactJson.codec
    private val utcFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    fun text(path: String): String = requireNotNull(javaClass.classLoader.getResource(path)) { "missing resource $path" }.readText()
    fun json(path: String): JsonObject = codec.parseToJsonElement(text(path)).jsonObject

    fun goldenRaw(): Triple<RawRun, FeedConfigDocument, PipelineConfig> {
        val scenario = json("fixtures/golden_success.json")
        val samples = json("fixtures/article_samples.json")["articles"]!!.jsonArray
        val indices = scenario["article_indices"]!!.jsonArray.map { it.jsonPrimitive.int }
        val articles = indices.map { index ->
            val item = samples[index].jsonObject
            val instant = OffsetDateTime.parse(item.string("pub_date")).toInstant()
            Article(
                source = item.string("source"),
                title = item.string("title"),
                link = item.string("link"),
                pubDateUtc = utcFormatter.format(instant),
                pubDateIso = instant.toString().removeSuffix("Z") + "+00:00",
                summaryEn = item.string("summary_en"),
                articleText = "",
            )
        }
        val feeds = codec.decodeFromString<FeedConfigDocument>(text("fixtures/feeds_fixture.json"))
        val configElement = json("fixtures/pipeline_config_fixture.json")
        val config = codec.decodeFromJsonElement(PipelineConfig.serializer(), configElement)
        val defaultStatus = scenario["default_feed_status"]!!.jsonObject
        val overrides = scenario["feed_overrides"]!!.jsonObject
        val counts = articles.groupingBy { it.source }.eachCount()
        val feedResults = feeds.feeds.map { feed ->
            val override = overrides[feed.name]?.jsonObject
            val status = override?.get("status")?.jsonPrimitive?.contentOrNull
                ?: defaultStatus.string("status")
            val articleCount = override?.get("article_count")?.jsonPrimitive?.int
                ?: defaultStatus["article_count"]!!.jsonPrimitive.int
            FeedResult(feed.name, feed.url, status, null, articleCount.takeIf { it >= 0 } ?: counts.getOrDefault(feed.name, 0))
        }
        val uniqueSources = articles.map { it.source }.distinct().sorted()
        val raw = RawRun(
            meta = RawMeta("2026-04-10T22:00:00Z", "test-golden_success.json", "feeds.json", feeds.feeds.size),
            count = articles.size,
            articles = articles,
            feedResults = feedResults,
            configuredFeedCount = feeds.feeds.size,
            uniqueSourceCount = uniqueSources.size,
            uniqueSources = uniqueSources,
            runtimeConfig = configElement,
        )
        return Triple(raw, feeds, config)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
