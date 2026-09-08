package com.dailynews.data.repo

import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.OfflineArticleBody
import com.dailynews.pipeline.ports.ArticleBodyPort
import com.dailynews.pipeline.ports.RetrievedArticleBody
import com.dailynews.pipeline.text.TextUtils
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OfflineArticleRepository(
    private val database: DailyNewsDatabase,
    private val fetcher: ArticleBodyPort,
    private val now: () -> Instant = Instant::now,
) {
    private val mutations = Mutex()
    fun observe(link: String) = database.offlineBodies().observe(TextUtils.dedupLinkKey(link))

    /** Failed requests never replace or delete the previously saved body. */
    suspend fun fetchAndSave(link: String) = mutations.withLock {
        val key = TextUtils.dedupLinkKey(link)
        val article = requireNotNull(database.articles().get(key)) { "文章已不在本地" }
        val body = fetcher.fetch(article.link)
        require(body.text.isNotBlank() && body.text.length <= RetrievedArticleBody.MAX_CHARS) { "正文为空或超出保存上限" }
        database.offlineBodies().save(OfflineArticleBody(key, body.text, now().toString(), body.truncated))
    }

    suspend fun remove(link: String) = mutations.withLock {
        database.offlineBodies().remove(TextUtils.dedupLinkKey(link))
    }
}
