package com.dailynews.app.ui.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.dailynews.data.repo.FeedRecord
import com.dailynews.data.repo.FeedEditorRepository
import com.dailynews.model.FeedDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedEditorState(
    val editingId: Long? = null,
    val name: String = "",
    val url: String = "",
    val warnOnly: Boolean = false,
) : java.io.Serializable

data class FeedsUiState(
    val feeds: List<FeedRecord> = emptyList(),
    val editor: FeedEditorState = FeedEditorState(),
    val transferMessage: String? = null,
    val busy: Boolean = false,
)

class FeedsViewModel(
    private val repository: FeedEditorRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val editor = MutableStateFlow(savedState[EDITOR_KEY] ?: FeedEditorState())
    private val transferMessage = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    val state: StateFlow<FeedsUiState> = combine(repository.observeAll(), editor, transferMessage, busy, ::FeedsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedsUiState())

    fun setName(value: String) { setEditor(editor.value.copy(name = value)) }
    fun setUrl(value: String) { setEditor(editor.value.copy(url = value)) }
    fun setWarnOnly(value: Boolean) { setEditor(editor.value.copy(warnOnly = value)) }

    fun edit(feed: FeedRecord) {
        setEditor(FeedEditorState(feed.id, feed.name, feed.url, feed.errorPolicy == "warn"))
    }

    fun cancelEdit() { setEditor(FeedEditorState()) }

    fun save() {
        val snapshot = editor.value
        if (snapshot.name.isBlank() || feedUrlError(snapshot.url) != null) return
        launchOperation {
            val feed = FeedDefinition(snapshot.name.trim(), snapshot.url.trim(), if (snapshot.warnOnly) "warn" else "block")
            snapshot.editingId?.let { repository.update(it, feed) } ?: repository.insert(feed)
            setEditor(FeedEditorState())
        }
    }

    fun delete(id: Long) = launchOperation { repository.delete(id) }
    fun restore(feed: FeedRecord) = launchOperation { repository.restore(feed) }

    fun move(id: Long, direction: Int) {
        val ordered = state.value.feeds.map(FeedRecord::id).toMutableList()
        val from = ordered.indexOf(id)
        val to = (from + direction.coerceIn(-1, 1)).coerceIn(0, ordered.lastIndex)
        if (from < 0 || from == to) return
        val moved = ordered.removeAt(from)
        ordered.add(to, moved)
        launchOperation { repository.reorder(ordered) }
    }

    fun importOpml(readText: suspend () -> String?) = launchOperation {
        val content = readText()
        transferMessage.value = if (content == null) "无法读取所选文件" else "已导入 ${repository.importOpml(content)} 个订阅源"
    }

    fun exportOpml(writeText: suspend (String) -> Unit) = launchOperation {
        writeText(repository.exportOpml())
        transferMessage.value = "已导出订阅源"
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            runCatching { block() }
                .onFailure { transferMessage.value = "操作失败：${it.message ?: it::class.simpleName}" }
            busy.value = false
        }
    }

    private fun setEditor(value: FeedEditorState) {
        editor.value = value
        savedState[EDITOR_KEY] = value
    }

    companion object { private const val EDITOR_KEY = "feed-editor" }
}

internal fun feedUrlError(value: String): String? {
    if (value.isBlank()) return "请输入 RSS URL"
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull()
    return if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) "请输入完整的 http(s) URL" else null
}
