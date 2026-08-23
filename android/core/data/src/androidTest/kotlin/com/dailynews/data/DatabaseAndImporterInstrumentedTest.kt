package com.dailynews.data

import androidx.room.Room
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailynews.data.config.ApiKeyVault
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.ArticleEntity
import com.dailynews.data.db.ReportItemEntity
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.db.RunEntity
import com.dailynews.data.db.RunLogEntity
import com.dailynews.data.db.LlmCallEntity
import com.dailynews.data.repo.EditorialCacheRepository
import com.dailynews.data.repo.ArticleRepository
import com.dailynews.data.repo.FeedRepository
import com.dailynews.data.repo.ReportRepository
import com.dailynews.data.repo.RunMaintenanceRepository
import com.dailynews.data.repo.SeenLinksRepository
import com.dailynews.data.repo.StateImporter
import com.dailynews.data.repo.StateBackupRepository
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.model.AssembledReport
import com.dailynews.model.Article
import com.dailynews.model.FeedDefinition
import com.dailynews.model.FeedResult
import com.dailynews.model.RawMeta
import com.dailynews.model.RawRun
import com.dailynews.model.ReportGroup
import com.dailynews.model.ReportItem
import com.dailynews.pipeline.ports.SweepFeedOutcome
import com.dailynews.pipeline.ports.SweepWrite
import com.dailynews.pipeline.editorial.EditorialCacheKeys
import com.dailynews.pipeline.flow.Part2OnDemandGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.time.LocalDate
import java.time.Instant
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseAndImporterInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: DailyNewsDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seenLinksImportMergesDeviceHistoryAndKeepsLatestDate() = runBlocking {
        val seen = SeenLinksRepository(database)
        seen.replace(mapOf("https://device" to LocalDate.parse("2026-08-03"), "https://same" to LocalDate.parse("2026-08-02")))
        val payload = """{"entries":{"https://imported":"2026-08-01","https://same":"2026-08-04"}}"""

        val count = StateImporter(seen, EditorialCacheRepository(database)).importSeenLinks(
            ByteArrayInputStream(payload.toByteArray()),
        )

        assertEquals(2, count)
        assertEquals(
            mapOf(
                "https://device" to LocalDate.parse("2026-08-03"),
                // Conflicts resolve to the later date, exactly like recordReportedLinks.
                "https://same" to LocalDate.parse("2026-08-04"),
                "https://imported" to LocalDate.parse("2026-08-01"),
            ),
            seen.entries(),
        )
    }

    @Test
    fun reportPublishIsTransactionalAndReviewDowngradeClearsSuccessGate() = runBlocking {
        val repository = ReportRepository(database, context)
        val report = AssembledReport(
            reportDate = "2026-08-04",
            markdown = "# report",
            topNMarkdown = "",
            items = listOf(
                ReportItem(1, 1, "https://example", "English title", "Source", "2026-08-04 00:00 UTC", "2026-08-04T00:00:00+00:00", "中文摘要"),
            ),
            groups = listOf(ReportGroup("Source", "ok", 1)),
        )

        repository.publish(report)

        assertTrue(repository.hasSuccess(report.reportDate))
        assertEquals(1, database.reports().itemsNow(report.reportDate).size)
        assertEquals("SUCCESS", database.reports().get(report.reportDate)?.status)

        repository.markFailed(report.reportDate, "review mismatch")

        assertFalse(repository.hasSuccess(report.reportDate))
        assertEquals("FAILED", database.reports().get(report.reportDate)?.status)
    }

    @Test
    fun lazyPart2GenerationPersistsReportItemAndEditorialCacheTogether() = runBlocking {
        val reportDate = "2026-08-04"
        val link = "https://example/lazy"
        val article = ArticleEntity(
            linkKey = link,
            link = link,
            feedName = "Source",
            title = "Lazy source article",
            summaryEn = "short",
            articleText = "article body used as the on demand summary material",
            pubDateUtc = "2026-08-04 00:00 UTC",
            pubDateIso = "2026-08-04T00:00:00Z",
            fetchedAtUtc = "2026-08-04T00:01:00Z",
        )
        database.articles().insert(article)
        database.runs().upsert(
            RunEntity("run-lazy", reportDate, "SUCCESS", "SUCCESS", 0, 1, "test", startedAtUtc = "2026-08-04T00:00:00Z"),
        )
        val repository = ReportRepository(database, context)
        repository.publish(
            AssembledReport(
                reportDate,
                "# lazy",
                "",
                listOf(ReportItem(2, 1, link, article.title, article.feedName, article.pubDateUtc, article.pubDateIso, "")),
                listOf(ReportGroup(article.feedName, "ok", 1)),
            ),
        )
        // LAZY reports own an immutable identity/material snapshot. Retention may remove
        // the mutable article-pool row before the user expands this source.
        database.articles().clear()
        var calls = 0
        val generator = Part2OnDemandGenerator { runId, requests, maxCalls, _ ->
            calls += 1
            assertEquals("run-lazy", runId)
            assertTrue(maxCalls > 0)
            assertEquals(article.articleText, requests.single().summaryMaterial)
            delay(100)
            listOf(com.dailynews.model.MissingPart2Summary(link, "按需生成的中文摘要", "covered", "lazy-event"))
        }

        val results = coroutineScope {
            listOf(
                async { repository.generatePart2Group(reportDate, "Source", generator, 80, 20, com.dailynews.model.LlmExecutionConfig()) },
                async { repository.generatePart2Group(reportDate, "Source", generator, 80, 20, com.dailynews.model.LlmExecutionConfig()) },
            ).awaitAll()
        }
        assertEquals(listOf(0, 1), results.sorted())

        assertEquals(1, calls)
        assertEquals("按需生成的中文摘要", database.reports().itemsNow(reportDate).single().summaryZh)
        val cacheKey = EditorialCacheKeys.cacheKey(
            Article(article.feedName, article.title, article.link, article.pubDateUtc, article.pubDateIso, article.summaryEn, article.articleText),
        )
        val cached = database.editorialCache().find(cacheKey)
        assertEquals("按需生成的中文摘要", cached?.summaryZh)
        assertEquals("lazy-event", cached?.eventKey)
    }

    @Test
    fun interruptedRunsAreRecoveredAndEncryptedVaultRoundTrips() = runBlocking {
        database.runs().upsert(
            RunEntity(
                runId = "run-1",
                reportDate = "2026-08-04",
                status = "RUNNING",
                classification = "PENDING",
                validatorExitCode = 40,
                attempt = 1,
                trigger = "test",
                startedAtUtc = "2026-08-04T00:00:00Z",
            ),
        )
        database.runs().markRunningInterrupted("2026-08-04T00:01:00Z")
        val recovered = database.runs().get("run-1")
        assertEquals("FAILED", recovered?.status)
        assertEquals("INTERRUPTED", recovered?.classification)

        context.deleteSharedPreferences("provider_keys")
        val vault = ApiKeyVault(context)
        vault.write("test-alias", "sk-test-only")
        assertEquals("sk-test-only", vault.read("test-alias"))
        vault.delete("test-alias")
        assertNull(vault.read("test-alias"))
    }

    @Test
    fun restoredEncryptedPreferencesRecoverWhenTheDeviceMasterKeyIsMissing() {
        context.deleteSharedPreferences("provider_keys")
        val original = ApiKeyVault(context)
        original.write("restored-alias", "sk-old-device")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }

        val recovered = ApiKeyVault(context)

        assertNull(recovered.read("restored-alias"))
        recovered.write("new-alias", "sk-new-device")
        assertEquals("sk-new-device", recovered.read("new-alias"))
    }

    @Test
    fun feedEditKeepsIdentityAndPositionAndNeverReplacesConflictingRows() = runBlocking {
        val repository = FeedRepository(database, context)
        val firstId = repository.insert(FeedDefinition("First", "https://example.com/first"))
        val secondId = repository.insert(FeedDefinition("Second", "https://example.com/second"))

        repository.update(firstId, FeedDefinition("Renamed", "https://example.com/renamed", "warn"))

        val rows = database.feeds().enabled()
        assertEquals(listOf(firstId, secondId), rows.map { it.id })
        assertEquals(listOf(0, 1), rows.map { it.position })
        assertEquals("Renamed", rows.first().name)
        assertEquals("https://example.com/renamed", rows.first().url)

        runCatching {
            repository.update(firstId, FeedDefinition("Second", "https://example.com/second"))
        }
        assertEquals(2, database.feeds().count())
        assertEquals("Second", database.feeds().get(secondId)?.name)
    }

    @Test
    fun opmlImportAppendsPositionsAndIgnoresConflictsWithoutDeletingRows() = runBlocking {
        val repository = FeedRepository(database, context)
        repository.insert(FeedDefinition("Existing", "https://example.com/existing"))
        val opml = """<opml version="1.0"><body>
            <outline text="Existing" xmlUrl="https://example.com/conflicting-name" />
            <outline text="Imported" xmlUrl="https://example.com/imported" />
        </body></opml>""".trimIndent()

        assertEquals(1, repository.importOpml(opml))
        val rows = database.feeds().enabled()
        assertEquals(listOf("Existing", "Imported"), rows.map { it.name })
        assertEquals(listOf(0, 2), rows.map { it.position })
    }

    @Test
    fun reportListSearchUsesProjectionAndRoomSideArticleSearch() = runBlocking {
        val repository = ReportRepository(database, context)
        database.articles().insert(
            ArticleEntity(
                linkKey = "https://search",
                link = "https://search",
                feedName = "Source",
                title = "Distinctive English title at 100%",
                summaryEn = "pool_only_search_term",
                articleText = "",
                pubDateUtc = "2026-08-04 00:00 UTC",
                pubDateIso = "2026-08-04T00:00:00+00:00",
                fetchedAtUtc = "2026-08-04T01:00:00Z",
            ),
        )
        repository.publish(
            AssembledReport(
                reportDate = "2026-08-04",
                markdown = "# very large markdown body that should not be projected into the list",
                topNMarkdown = "top",
                items = listOf(ReportItem(1, 1, "https://search", "Distinctive English title at 100%", "Source", "2026-08-04 00:00 UTC", "2026-08-04T00:00:00+00:00", "可检索中文摘要")),
                groups = listOf(ReportGroup("Source", "ok", 1)),
            ),
        )
        repository.publish(
            AssembledReport(
                reportDate = "2026-08-03",
                markdown = "# another successful report",
                topNMarkdown = "top",
                items = listOf(ReportItem(1, 1, "https://wildcard-control", "Distinctive 1000 result", "Source", "2026-08-03 00:00 UTC", "2026-08-03T00:00:00+00:00", "control")),
                groups = listOf(ReportGroup("Source", "ok", 1)),
            ),
        )
        repository.publishFailure("2026-08-02", "# failed_body_42\nnetwork unavailable")

        val byTitle = repository.summaries("Distinctive").first()
        val bySummary = repository.summaries("中文摘要").first()
        val literalPercent = repository.summaries("100%").first()
        val failureBody = repository.summaries("failed_body_42").first()
        val previewByPoolArticle = repository.previews("pool_only_search_term").first()
        val previewByFailureBody = repository.previews("failed_body_42").first()

        assertEquals(listOf("2026-08-04", "2026-08-03"), byTitle.map { it.reportDate })
        assertEquals(listOf("2026-08-04"), bySummary.map { it.reportDate })
        assertEquals(listOf("2026-08-04"), literalPercent.map { it.reportDate })
        assertEquals(listOf("2026-08-02"), failureBody.map { it.reportDate })
        assertEquals(listOf("2026-08-04"), previewByPoolArticle.map { it.reportDate })
        assertEquals(listOf("2026-08-02"), previewByFailureBody.map { it.reportDate })
    }

    @Test
    fun fetchedArticlesBecomeSearchableStateWithoutLosingFavoriteOrReadMarkers() = runBlocking {
        val articles = ArticleRepository(database)
        val favorites = com.dailynews.data.repo.FavoriteRepository(database)
        val raw = RawRun(
            meta = RawMeta("2026-08-04T12:00:00Z", "run-v2", "feeds.json", 1),
            count = 1,
            articles = listOf(
                Article(
                    source = "Source",
                    title = "Literal 100% native article",
                    link = "https://example/native/",
                    pubDateUtc = "2026-08-04 11:00 UTC",
                    pubDateIso = "2026-08-04T11:00:00+00:00",
                    summaryEn = "Article centered storage",
                    articleText = "Full body",
                ),
            ),
            feedResults = listOf(FeedResult("Source", "https://feed", "ok", articleCount = 1)),
            configuredFeedCount = 1,
        )

        database.feeds().insert(com.dailynews.data.db.FeedEntity(name = "Source", url = "https://feed"))
        articles.recordFetch(raw)
        favorites.save("https://example/native/", "snapshot", "Source", "摘要")
        favorites.markRead("https://example/native/")
        articles.recordFetch(raw.copy(meta = raw.meta.copy(generatedAtUtc = "2026-08-04T13:00:00Z")))

        val stored = database.articles().get("https://example/native")
        assertEquals("Literal 100% native article", stored?.title)
        assertEquals("2026-08-04T13:00:00Z", stored?.fetchedAtUtc)
        assertTrue(stored?.favoritedAtUtc != null)
        assertTrue(stored?.readAtUtc != null)
        assertEquals(listOf("https://example/native"), articles.search("100%").first().map { it.linkKey })
        assertEquals(2, database.fetchLogs().allNow().size)
        assertEquals("ok", database.feeds().get(1)?.lastStatus)
    }

    @Test
    fun sweepPoolBackfillIsIdempotentAndLaterBlankFetchCannotEraseEnrichment() = runBlocking {
        val articles = ArticleRepository(database)
        database.feeds().insert(com.dailynews.data.db.FeedEntity(name = "Source", url = "https://feed"))
        val item = Article(
            source = "Source",
            title = "Incremental",
            link = "https://example/incremental/",
            pubDateUtc = "2026-08-04 11:00 UTC",
            pubDateIso = "2026-08-04T11:00:00+00:00",
            summaryEn = "feed summary",
        )
        fun write(at: String) = SweepWrite(
            Instant.parse(at),
            listOf(item),
            emptySet(),
            listOf(SweepFeedOutcome("Source", "ok", null, 1, item.pubDateIso)),
        )

        articles.recordSweep(write("2026-08-04T12:00:00Z"))
        assertTrue(articles.articlesSince(Instant.parse("2026-08-04T00:00:00Z")).single().needsEnrichment)
        articles.updateEnriched(listOf(item.copy(summaryEn = "page summary", articleText = "page body")), Instant.parse("2026-08-04T12:05:00Z"))
        articles.recordSweep(write("2026-08-04T13:00:00Z"))

        val stored = database.articles().get("https://example/incremental")
        assertEquals("page summary", stored?.summaryEn)
        assertEquals("page body", stored?.articleText)
        assertEquals("2026-08-04T12:05:00Z", stored?.enrichedAtUtc)
        assertFalse(articles.articlesSince(Instant.parse("2026-08-04T00:00:00Z")).single().needsEnrichment)
        assertEquals(listOf(1, 0), database.fetchLogs().allNow().map { it.newCount })
    }

    @Test
    fun fullDeviceStateZipRoundTripsArticleCenteredDataAndPipelineConfig() = runBlocking {
        val configs = PipelineConfigRepository(context)
        configs.save(com.dailynews.model.PipelineConfig(articleRetentionDays = 61))
        val backups = StateBackupRepository(database, configs)
        database.feeds().insert(com.dailynews.data.db.FeedEntity(name = "Backup feed", url = "https://backup/feed"))
        com.dailynews.data.repo.FavoriteRepository(database).save(
            "https://backup/article",
            "Backup article",
            "Backup feed",
            "备份摘要",
        )
        ArtifactStore(database) { Instant.parse("2026-08-04T12:00:00Z") }
            .write("backup-run", "validation.json", "{\"passed\":true}".toByteArray())
        // Epic V: periodic digests are a separate table and must be in the backup
        // envelope explicitly. Missing them will not fail the compile; the user
        // only discovers weekly digests vanished after restoring a device.
        database.periodicReports().upsert(
            com.dailynews.data.db.PeriodicReportEntity(
                periodKey = "2026-W32",
                kind = "WEEKLY",
                periodStartDate = "2026-08-03",
                periodEndDate = "2026-08-09",
                status = "SUCCESS",
                markdown = "# DailyNews 周报 · 2026-W32",
                sourceReportDatesJson = "[\"2026-08-04\"]",
                itemCount = 7,
                createdAtUtc = "2026-08-10T00:00:00Z",
                publishedAtUtc = "2026-08-10T00:00:00Z",
            ),
        )
        val output = ByteArrayOutputStream()

        val exported = backups.exportZip(output)
        database.articles().clear()
        database.feeds().clear()
        database.runArtifacts().clear()
        database.periodicReports().clear()
        configs.save(com.dailynews.model.PipelineConfig(articleRetentionDays = 7))
        val restored = backups.importZip(output.toByteArray())

        assertEquals(1, exported.articles)
        assertEquals(exported, restored)
        assertEquals("Backup article", database.articles().get("https://backup/article")?.title)
        assertEquals("Backup feed", database.feeds().allNow().single().name)
        assertEquals(61, configs.config.first().articleRetentionDays)
        assertEquals("{\"passed\":true}", ArtifactStore(database).readText("backup-run", "validation.json"))
        val digest = database.periodicReports().find("2026-W32")
        assertEquals("# DailyNews 周报 · 2026-W32", digest?.markdown)
        assertEquals(7, digest?.itemCount)
    }

    @Test
    fun fullDeviceStateStreamsArtifactLargerThanCursorWindow() = runBlocking {
        val configs = PipelineConfigRepository(context)
        val backups = StateBackupRepository(database, configs)
        val content = ByteArray(3 * 1_048_576).also(SecureRandom()::nextBytes)
        val store = ArtifactStore(database) { Instant.parse("2026-08-04T12:00:00Z") }
        store.write("large-run", "raw.bin", content)
        assertTrue(requireNotNull(database.runArtifacts().bodySize("large-run", "raw.bin")) > 2 * 1_048_576)

        val stateZip = ByteArrayOutputStream()
        backups.exportZip(stateZip)
        database.runArtifacts().clear()
        backups.importZip(stateZip.toByteArray())

        val diagnosticZip = ByteArrayOutputStream()
        store.exportZip("large-run", diagnosticZip)
        val restored = ZipInputStream(ByteArrayInputStream(diagnosticZip.toByteArray())).use { zip ->
            assertEquals("raw.bin", requireNotNull(zip.nextEntry).name)
            zip.readBytes()
        }
        assertTrue(content.contentEquals(restored))
    }

    @Test
    fun fullDeviceStateRejectsInvalidSeenLedgerBeforeMutatingDatabase() = runBlocking {
        val configs = PipelineConfigRepository(context)
        val backups = StateBackupRepository(database, configs)
        SeenLinksRepository(database).replace(mapOf("https://valid" to LocalDate.parse("2026-08-04")))
        val exported = ByteArrayOutputStream().also { backups.exportZip(it) }.toByteArray()
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(exported)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val body = zip.readBytes()
                entries[entry.name] = if (entry.name == "dailynews-state.json") {
                    body.toString(Charsets.UTF_8)
                        .replace("\"firstSeenDate\":\"2026-08-04\"", "\"firstSeenDate\":\"garbage\"")
                        .toByteArray()
                } else body
                zip.closeEntry()
            }
        }
        val damaged = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(body)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        val failure = runCatching { backups.importZip(damaged) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("invalid firstSeenDate"))
        assertEquals(mapOf("https://valid" to LocalDate.parse("2026-08-04")), SeenLinksRepository(database).entries())
    }

    @Test
    fun roomArtifactsRoundTripGzipAndMaterializeDeterministicDiagnosticZip() = runBlocking {
        val store = ArtifactStore(database) { Instant.parse("2026-08-04T12:00:00Z") }
        store.write("run-zip", "validation.json", "validation".toByteArray())
        store.write("run-zip", "raw.json", "raw".toByteArray())
        val compressed = requireNotNull(database.runArtifacts().get("run-zip", "raw.json")).gzipBody
        assertEquals(0x1f, compressed[0].toInt() and 0xff)
        assertEquals(0x8b, compressed[1].toInt() and 0xff)
        assertEquals("validation", store.readText("run-zip", "validation.json"))

        val output = ByteArrayOutputStream()
        store.exportZip("run-zip", output)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }

        assertEquals(mapOf("raw.json" to "raw", "validation.json" to "validation"), entries)
    }

    @Test
    fun readerTimelineCoversFourFilterCombinationsAndBlocksSyntheticRows() = runBlocking {
        val articles = ArticleRepository(database)
        fun entity(key: String, feed: String, iso: String, readAtUtc: String? = null) = ArticleEntity(
            linkKey = key,
            link = key,
            feedName = feed,
            title = "title $key",
            summaryEn = "summary $key",
            articleText = "",
            pubDateUtc = "2026-08-04 10:00 UTC",
            pubDateIso = iso,
            fetchedAtUtc = "2026-08-04T10:01:00Z",
            readAtUtc = readAtUtc,
        )
        database.articles().insert(entity("https://example/a1", "Alpha", "2026-08-04T10:00+00:00"))
        database.articles().insert(entity("https://example/a2", "Alpha", "2026-08-04T09:00+00:00", readAtUtc = "2026-08-04T11:00:00Z"))
        database.articles().insert(entity("https://example/b1", "Beta", "2026-08-04T10:00:30+00:00"))
        // Synthetic row from FavoriteRepository.save: no date, must be kept out of the reader.
        com.dailynews.data.repo.FavoriteRepository(database).save("https://example/favorite", "Favorite only", "Gamma", "中文摘要")

        val all = articles.observeTimeline(null, false, 100).first()
        assertEquals(listOf("https://example/b1", "https://example/a1", "https://example/a2"), all.map { it.linkKey })

        val allUnread = articles.observeTimeline(null, true, 100).first()
        assertEquals(listOf("https://example/b1", "https://example/a1"), allUnread.map { it.linkKey })

        val alpha = articles.observeTimeline("Alpha", false, 100).first()
        assertEquals(listOf("https://example/a1", "https://example/a2"), alpha.map { it.linkKey })

        val alphaUnread = articles.observeTimeline("Alpha", true, 100).first()
        assertEquals(listOf("https://example/a1"), alphaUnread.map { it.linkKey })

        assertEquals(3, articles.observePoolCount().first())
        // Gamma is the dateless favorite synthetic row above. observeUnreadCounts
        // has the same `pubDateIso <> ''` guard as observePoolCount, so it must
        // not appear in the source-chip unread count — otherwise the user sees
        // an unread number they can never open or clear.
        assertEquals(
            mapOf("Alpha" to 1, "Beta" to 1),
            articles.observeUnreadCounts().first().associate { it.feedName to it.unread },
        )
    }

    @Test
    fun markAllReadBatchRollsBackOnlyItsOwnStamp() = runBlocking {
        val articles = ArticleRepository(database)
        fun entity(key: String, feed: String) = ArticleEntity(
            linkKey = key,
            link = key,
            feedName = feed,
            title = "title $key",
            summaryEn = "summary",
            articleText = "",
            pubDateUtc = "2026-08-04 10:00 UTC",
            pubDateIso = "2026-08-04T10:00+00:00",
            fetchedAtUtc = "2026-08-04T10:01:00Z",
        )
        database.articles().insert(entity("https://example/batch1", "Alpha"))
        database.articles().insert(entity("https://example/batch2", "Alpha"))
        database.articles().insert(entity("https://example/other", "Beta"))

        assertEquals(2, articles.markAllRead("Alpha", "2026-08-04T12:00:00Z"))
        // Articles the user actually read after the batch use a different timestamp and must not be hurt by undo.
        articles.markRead("https://example/other", Instant.parse("2026-08-04T12:05:00Z"))

        assertTrue(articles.observeTimeline("Beta", true, 100).first().isEmpty())
        // The batch marked 2 rows (batch1 + batch2), so undo must restore 2 —
        // the next two assertions expect both back to unread; the original 1
        // contradicted them.
        assertEquals(2, articles.undoMarkAllRead("2026-08-04T12:00:00Z"))
        assertEquals(
            listOf("https://example/batch1", "https://example/batch2"),
            articles.observeTimeline("Alpha", true, 100).first().map { it.linkKey },
        )
        assertTrue(articles.observeTimeline("Beta", true, 100).first().isEmpty())
        // Marking a single item unread must work in reverse too.
        articles.markUnread("https://example/other")
        assertEquals(listOf("https://example/other"), articles.observeTimeline("Beta", true, 100).first().map { it.linkKey })
    }

    @Test
    fun favoritesAndReaderStillFallBackToEnglishSummaryWhenPart2RowsAreBlank() = runBlocking {
        // U1 regression lock: under LAZY, part=2 rows write empty-string summaryZh;
        // COALESCE skips NULL only, not ''. Same-day part=1/part=2 are unordered
        // under ORDER BY reportDate DESC LIMIT 1.
        val link = "https://example/fallback"
        database.articles().insert(
            ArticleEntity(
                linkKey = link,
                link = link,
                feedName = "Source",
                title = "Fallback title",
                summaryEn = "English fallback summary",
                articleText = "",
                pubDateUtc = "2026-08-04 10:00 UTC",
                pubDateIso = "2026-08-04T10:00+00:00",
                fetchedAtUtc = "2026-08-04T10:01:00Z",
            ),
        )
        database.reports().insertItems(
            listOf(
                ReportItemEntity("2026-08-04", 2, 1, link, "Fallback title", "Source", "", "", summaryZh = ""),
                ReportItemEntity("2026-08-04", 1, 1, link, "Fallback title", "Source", "", "", summaryZh = "精选中文摘要"),
            ),
        )
        com.dailynews.data.repo.FavoriteRepository(database).restore(link)

        assertEquals("精选中文摘要", database.articles().observeFavorites().first().single().summaryZh)
        assertEquals("精选中文摘要", ArticleRepository(database).observeTimeline(null, false, 100).first().single().summaryZh)

        // When both summary rows are empty strings, fall back to summaryEn.
        database.reports().deleteItems("2026-08-04")
        database.reports().insertItems(
            listOf(ReportItemEntity("2026-08-04", 2, 1, link, "Fallback title", "Source", "", "", summaryZh = "")),
        )
        assertEquals("English fallback summary", database.articles().observeFavorites().first().single().summaryZh)
    }

    @Test
    fun databaseRetentionKeepsCurrentMonthAndRollsUpOlderLlmCalls() = runBlocking {
        database.runs().upsert(RunEntity("old", "2026-06-01", "FAILED", "EXPECTED_BLOCK", 30, 1, "test", startedAtUtc = "2026-06-01T00:00:00Z", finishedAtUtc = "2026-06-01T00:01:00Z"))
        database.runs().upsert(RunEntity("new", "2026-08-03", "SUCCESS", "SUCCESS", 0, 1, "test", startedAtUtc = "2026-08-03T00:00:00Z", finishedAtUtc = "2026-08-03T00:01:00Z"))
        database.runLogs().insert(RunLogEntity(runId = "old", step = "test", level = "INFO", message = "old", createdAtUtc = "2026-06-01T00:00:00Z"))
        database.runLogs().insert(RunLogEntity(runId = "new", step = "test", level = "INFO", message = "new", createdAtUtc = "2026-08-03T00:00:00Z"))
        database.llmCalls().insert(LlmCallEntity(runId = "old", role = "EDITOR", provider = "p", model = "m", inputTokens = 10, outputTokens = 5, retryIndex = 0, outcome = "ok", createdAtUtc = "2026-07-01T00:00:00Z"))
        database.llmCalls().insert(LlmCallEntity(runId = "new", role = "EDITOR", provider = "p", model = "m", inputTokens = 20, outputTokens = 7, retryIndex = 0, outcome = "ok", createdAtUtc = "2026-08-03T00:00:00Z"))
        ArtifactStore(database) { Instant.parse("2026-06-01T00:00:00Z") }.write("old", "raw.json", "old".toByteArray())
        ArtifactStore(database) { Instant.parse("2026-08-03T00:00:00Z") }.write("new", "raw.json", "new".toByteArray())

        val result = RunMaintenanceRepository(database).prune(14, Instant.parse("2026-08-04T12:00:00Z"))

        assertEquals(1, result.runArtifactsDeleted)
        assertEquals(1, result.runLogsDeleted)
        assertEquals(1, result.runsDeleted)
        assertEquals(1, result.llmCallsRolledUp)
        assertNull(database.runs().get("old"))
        assertEquals(1, database.runLogs().count("new"))
        assertEquals(1, database.llmCalls().count("new"))
        assertEquals(15L, database.llmUsageMonths().get("2026-07")?.let { it.inputTokens + it.outputTokens })
        assertNull(database.runArtifacts().get("old", "raw.json"))
        assertEquals("new", ArtifactStore(database).readText("new", "raw.json"))
    }
}
