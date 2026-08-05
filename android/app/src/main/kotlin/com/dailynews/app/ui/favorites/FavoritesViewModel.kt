package com.dailynews.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailynews.data.db.FavoriteArticle
import com.dailynews.data.repo.FavoriteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(private val repository: FavoriteRepository) : ViewModel() {
    val favorites = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<FavoriteArticle>())
    fun remove(link: String) { viewModelScope.launch { repository.remove(link) } }
    fun restore(link: String) { viewModelScope.launch { repository.restore(link) } }
    fun markRead(link: String) { viewModelScope.launch { repository.markRead(link) } }
}
