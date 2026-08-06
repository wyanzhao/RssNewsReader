package com.dailynews.app.ui.reader

import com.dailynews.data.db.ReaderArticle

/**
 * 阅读器固定数据：V3 矩阵在 Robolectric 上是空 Room，只能拍到空态；
 * 「有数据的样子」由本 fixture + ReaderScreenshotTest 单独重录，不受 64 张矩阵牵连。
 */
fun readerFixtureArticles(): List<ReaderArticle> = listOf(
        ReaderArticle(
            linkKey = "https://example/top-story",
            link = "https://example/top-story",
            title = "OpenAI ships new reasoning model with tool use",
            source = "TechCrunch",
            summaryZh = "OpenAI 发布新一代推理模型，原生支持工具调用与更长上下文，并向企业客户开放。",
            pubDateUtc = "2026-08-05 06:30 UTC",
            pubDateIso = "2026-08-05T06:30+00:00",
            readAtUtc = null,
            favoritedAtUtc = null,
        ),
        ReaderArticle(
            linkKey = "https://example/read-story",
            link = "https://example/read-story",
            title = "EU regulator opens formal probe into app store fees",
            source = "Ars Technica",
            summaryZh = "欧盟监管机构对应用商店抽成启动正式调查，或影响多家平台分成规则。",
            pubDateUtc = "2026-08-05 04:10 UTC",
            pubDateIso = "2026-08-05T04:10+00:00",
            readAtUtc = "2026-08-05T05:00:00Z",
            favoritedAtUtc = "2026-08-05T05:01:00Z",
        ),
        ReaderArticle(
            linkKey = "https://example/no-summary",
            link = "https://example/no-summary",
            title = "Kernel maintainers merge long-awaited scheduler patch",
            source = "LWN",
            summaryZh = "",
            pubDateUtc = "2026-08-04 22:45 UTC",
            pubDateIso = "2026-08-04T22:45+00:00",
            readAtUtc = null,
            favoritedAtUtc = null,
        ),
)

fun readerFixtureState(): ReaderUiState = ReaderUiState(
    phase = ReaderPhase.CONTENT,
    articles = readerFixtureArticles(),
    // 分节头的计数**故意**大于窗口内渲染的条数：08-05 那天真实有 24 篇，
    // 但分页窗口只吐出 2 篇。这条契约（全量计数 ≠ 窗口计数）被钉进截图基线，
    // 以后谁把 header 改成数渲染条数，基线就会红。
    sections = listOf(
        ReaderDaySection(
            day = "2026-08-05",
            label = readerDayLabel("2026-08-05"),
            totalCount = 24,
            articles = readerFixtureArticles().take(2),
        ),
        ReaderDaySection(
            day = "2026-08-04",
            label = readerDayLabel("2026-08-04"),
            totalCount = 31,
            articles = readerFixtureArticles().drop(2),
        ),
    ),
    filter = ReaderFilter(),
    chips = listOf(
        FeedChipModel("TechCrunch", 5, "ok", null),
        FeedChipModel("Ars Technica", 12, "ok", null),
        FeedChipModel("Broken Feed", 0, "ERROR", "connect timeout"),
    ),
    totalUnread = 17,
    poolCount = 412,
)

fun readerEmptyFixtureState(): ReaderUiState = ReaderUiState(
    phase = ReaderPhase.EMPTY,
    articles = emptyList(),
    emptyReason = readerEmptyReason(ReaderFilter(unreadOnly = true), poolCount = 412, feedCount = 31),
    chips = listOf(FeedChipModel("Ars Technica", 0, "ok", null)),
    poolCount = 412,
)
