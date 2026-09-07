package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.model.ArticleFeedback
import com.dailynews.model.FeedbackKind
import com.dailynews.model.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchPreferencesPersistenceTest {
    @Test fun eventsTopicsAndArticleFeedbackStayIndependentAcrossConcurrentUpdates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = PipelineConfigRepository(context)
        repository.save(PipelineConfig())
        coroutineScope {
            launch(Dispatchers.IO) { repository.setEventWatch(com.dailynews.model.EventWatch("chip-release", "New chip", "2026-09-07"), true) }
            launch(Dispatchers.IO) { repository.update { it.copy(watches = it.watches.copy(topics = listOf("AI 编译器", "Memory systems"))) } }
            launch(Dispatchers.IO) { repository.recordFeedback(ArticleFeedback("https://example.org/1", "Article", "Lab", kind = FeedbackKind.VALUABLE)) }
        }
        val reopened = PipelineConfigRepository(context)
        val result = reopened.config.first()
        assertEquals(listOf("AI 编译器", "Memory systems"), result.watches.topics)
        assertEquals("2026-09-07", result.watches.events.single().afterReportDate)
        assertEquals(1, result.articleFeedback.size)
        reopened.removeEventWatch("chip-release")
        assertTrue(repository.config.first().watches.events.isEmpty())
        assertEquals(result.watches.topics, repository.config.first().watches.topics)
        assertEquals(result.articleFeedback, repository.config.first().articleFeedback)
    }
}
