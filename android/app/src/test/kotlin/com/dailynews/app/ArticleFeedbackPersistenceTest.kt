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
class ArticleFeedbackPersistenceTest {
    @Test
    fun concurrentFeedbackAndSettingsPersistAndUndoWithoutLosingOtherPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = PipelineConfigRepository(context)
        repository.save(PipelineConfig(editorFeedback = listOf("compiler research")))
        coroutineScope {
            repeat(12) { id ->
                launch(Dispatchers.IO) {
                    repository.recordFeedback(ArticleFeedback("https://example.org/$id", "Article $id", "Example", kind = FeedbackKind.VALUABLE))
                }
            }
            launch(Dispatchers.IO) { repository.update { it.copy(scheduleTime = "09:15") } }
        }
        val reopened = PipelineConfigRepository(context)
        assertEquals(12, reopened.config.first().articleFeedback.size)
        assertEquals("09:15", reopened.config.first().scheduleTime)
        assertEquals(listOf("compiler research"), reopened.config.first().editorFeedback)
        reopened.removeFeedback("https://example.org/3")
        assertEquals(11, repository.config.first().articleFeedback.size)
        assertTrue(repository.config.first().articleFeedback.none { it.link.endsWith("/3") })
    }
}
