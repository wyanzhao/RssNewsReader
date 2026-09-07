package com.dailynews.pipeline

import com.dailynews.model.ArticleFeedback
import com.dailynews.model.ArtifactJson
import com.dailynews.model.FeedbackKind
import com.dailynews.model.PipelineConfig
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.validate.QcValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArticleFeedbackContextTest {
    @Test
    fun `saved feedback reaches shortlist and leaves article authority unchanged`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val article = raw.articles.first()
        val signal = ArticleFeedback(article.link, article.title, article.source, kind = FeedbackKind.LESS_TOPIC, topic = "general funding")
        val manual = (1..20).map { "prefer compiler research $it" }
        val saved = config.copy(editorFeedback = manual, articleFeedback = listOf(signal))
        val restored = ArtifactJson.codec.decodeFromString<PipelineConfig>(ArtifactJson.codec.encodeToString(saved)).normalized()
        val validation = QcValidator().validate(raw, feeds).result
        val before = LlmContextBuilder().build(raw, validation, "2026-04-10", "/report.md", config)
        val after = LlmContextBuilder().build(raw, validation, "2026-04-10", "/report.md", restored)
        assertEquals(before.llmContext, after.llmContext)
        assertEquals(before.part1Brief.articles, after.part1Brief.articles)
        assertEquals(manual, after.part1Brief.editorFeedback.take(20))
        assertTrue(after.part1Brief.editorFeedback.last().contains("LESS_TOPIC"))
        assertTrue(after.part1Brief.editorFeedback.last().contains("general funding"))
        assertTrue(after.contextBudget.sizes.part1BriefBytes > before.contextBudget.sizes.part1BriefBytes)
    }

    @Test
    fun `feedback is bounded deduplicated and compatible with old backups`() {
        assertTrue(ArtifactJson.codec.decodeFromString<PipelineConfig>("{}").articleFeedback.isEmpty())
        val original = ArticleFeedback("https://example.org/1", "a".repeat(1000), "source", kind = FeedbackKind.VALUABLE)
        val updated = original.copy(kind = FeedbackKind.REPETITIVE)
        val config = PipelineConfig(articleFeedback = listOf(original, updated)).normalized()
        assertEquals(listOf(updated.copy(title = "a".repeat(300))), config.articleFeedback)
        val many = (1..120).map { original.copy(link = "https://example.org/$it") }
        assertEquals(100, PipelineConfig(articleFeedback = many).normalized().articleFeedback.size)
    }
}
