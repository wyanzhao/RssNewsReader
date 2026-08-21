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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ArticleDetailUiState(
    /** 三态：未加载 / 已加载但不存在 / 已加载有内容。与 ReaderPhase 同一思路。 */
    val loaded: Boolean = false,
    val article: ArticleDetail? = null,
)

class ArticleDetailViewModel(
    private val articles: ArticleRepository,
    private val favorites: FavoriteRepository,
    private val link: String,
) : ViewModel() {
    val state: StateFlow<ArticleDetailUiState> = articles.observeDetail(link)
        .map { ArticleDetailUiState(loaded = true, article = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleDetailUiState())

    init {
        // 打开即已读，与列表里点开的语义一致。
        viewModelScope.launch(Dispatchers.IO) { articles.markRead(link) }
    }

    fun toggleFavorite() {
        val current = state.value.article ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (current.favoritedAtUtc != null) favorites.remove(current.link) else favorites.restore(current.link)
        }
    }
}
