package com.dailynews.app.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.ArticleDetail
import com.dailynews.data.repo.ArticleRepository
import com.dailynews.data.repo.FavoriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.dailynews.model.ReadingPreferences
import com.dailynews.data.config.PipelineConfigRepository
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ArticleDetailUiState(
    /** Three states: not loaded / loaded but absent / loaded with content. Same idea as ReaderPhase. */
    val loaded: Boolean = false,
    val article: ArticleDetail? = null,
    val reading: ReadingPreferences = ReadingPreferences(),
    val offlineBody: com.dailynews.data.db.OfflineArticleBody? = null,
)

class ArticleDetailViewModel(
    private val articles: ArticleRepository,
    private val favorites: FavoriteRepository,
    private val link: String,
    private val config: PipelineConfigRepository,
    private val offline: com.dailynews.data.repo.OfflineArticleRepository,
) : ViewModel() {
    val fetching = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val state: StateFlow<ArticleDetailUiState> = combine(articles.observeDetail(link), config.config, offline.observe(link)) { article, settings, body ->
            ArticleDetailUiState(loaded = true, article = article, reading = settings.reading, offlineBody = body)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleDetailUiState())

    init {
        // Opening marks as read, matching the list-tap semantics.
        viewModelScope.launch(Dispatchers.IO) { articles.markRead(link) }
    }

    suspend fun saveAnnotations(note: String, tags: List<String>): Boolean = perform {
        articles.saveAnnotations(link, note, tags)
    }

    suspend fun saveReading(preferences: ReadingPreferences): Boolean = perform {
        config.update { it.copy(reading = preferences) }
    }

    suspend fun savePosition(index: Int, offset: Int, contentKey: String) = perform {
        articles.saveReadingPosition(link, index, offset, contentKey)
    }

    private suspend fun perform(block: suspend () -> Unit): Boolean = try {
        withContext(Dispatchers.IO) { block() }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { message.value = error.message ?: "保存失败，请重试"; false }

    fun fetchBody() {
        if (fetching.value) return
        fetching.value = true
        viewModelScope.launch {
            try { perform { offline.fetchAndSave(link) } }
            finally { fetching.value = false }
        }
    }

    fun removeBody() {
        if (fetching.value) return
        viewModelScope.launch { perform { offline.remove(link) } }
    }

    fun toggleFavorite() {
        val current = state.value.article ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (current.favoritedAtUtc != null) favorites.remove(current.link) else favorites.restore(current.link)
        }
    }
}
