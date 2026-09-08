package com.dailynews.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dailynews.data.config.ApiKeyVault
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.config.ProviderSettingsRepository
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.repo.EditorialCacheRepository
import com.dailynews.data.repo.ArticleRepository
import com.dailynews.data.repo.FeedRepository
import com.dailynews.data.repo.FavoriteRepository
import com.dailynews.data.repo.FetchLifecycleRepository
import com.dailynews.data.repo.LlmCallRepository
import com.dailynews.data.repo.PeriodicReportRepository
import com.dailynews.data.repo.ReportRepository
import com.dailynews.data.repo.RecordingFetchPort
import com.dailynews.data.repo.RunLogRepository
import com.dailynews.data.repo.RunRepository
import com.dailynews.data.repo.RunMaintenanceRepository
import com.dailynews.data.repo.SeenLinksRepository
import com.dailynews.data.repo.StateImporter
import com.dailynews.data.repo.StateBackupRepository
import com.dailynews.llm.AnthropicProvider
import com.dailynews.llm.EditorialRole
import com.dailynews.llm.OpenAiCompatProvider
import com.dailynews.llm.LlmProvider
import com.dailynews.llm.LlmRequest
import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderType
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.context.ShortlistContextBuilder
import com.dailynews.pipeline.editorial.ReportAssembler
import com.dailynews.pipeline.fetch.ArticlePageEnricher
import com.dailynews.pipeline.fetch.FeedFetcher
import com.dailynews.pipeline.fetch.LinkSafety
import com.dailynews.pipeline.fetch.FetchStep
import com.dailynews.pipeline.fetch.SweepStep
import com.dailynews.pipeline.fetch.WindowSliceStep
import com.dailynews.pipeline.flow.LlmCallAuditSink
import com.dailynews.pipeline.flow.LlmEditorialEngine
import com.dailynews.pipeline.flow.ProviderBinding
import com.dailynews.pipeline.flow.ProviderResolver
import com.dailynews.pipeline.orchestrate.RunOrchestrator
import com.dailynews.pipeline.orchestrate.NetworkDiagnostics
import com.dailynews.pipeline.orchestrate.NetworkProbeTarget
import com.dailynews.pipeline.orchestrate.UnexpectedFailureDiagnostics
import com.dailynews.pipeline.ports.ClockProvider
import com.dailynews.pipeline.ports.NetworkStatePort
import com.dailynews.pipeline.ports.FetchPort
import com.dailynews.pipeline.validate.QcValidator
import com.dailynews.model.LlmExecutionConfig
import java.time.Clock
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = DailyNewsDatabase.create(appContext)
    val feedRepository = FeedRepository(database, appContext)
    val articleRepository = ArticleRepository(database)
    val reportRepository = ReportRepository(database, appContext)
    val periodicReportRepository = PeriodicReportRepository(database)
    val favoriteRepository = FavoriteRepository(database)
    val seenLinksRepository = SeenLinksRepository(database)
    val cacheRepository = EditorialCacheRepository(database)
    val stateImporter = StateImporter(seenLinksRepository, cacheRepository)
    val runLogRepository = RunLogRepository(database)
    val runRepository = RunRepository(database)
    val reportWorkRepository = com.dailynews.data.repo.ReportWorkRepository(appContext)
    val watchNotificationLedger = com.dailynews.data.repo.WatchNotificationLedger(appContext)
    val llmCallRepository = LlmCallRepository(database)
    val runMaintenanceRepository = RunMaintenanceRepository(database)
    val configRepository = PipelineConfigRepository(appContext)
    val stateBackupRepository = StateBackupRepository(database, configRepository)
    val apiKeyVault by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ApiKeyVault(appContext) }
    val providerSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ProviderSettingsRepository(appContext) }
    val artifactStore = ArtifactStore(database)
    private val clock = Clock.systemUTC()
    // Feed URLs are added by users themselves, but their content is not; they get the
    // same short timeout and intranet interceptor.
    private val feedClient = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addNetworkInterceptor(LinkSafety.privateHostInterceptor())
        .build()
    // Article-page URLs are entirely controlled by third-party feeds, so this client gets
    // the shortest timeout plus an intranet interceptor. The timeout does not share the
    // run-level constant: a hostile source must not be able to hold a concurrency
    // semaphore and drain the entire run's budget (3 attempts × 20 minutes = 60 minutes).
    private val pageClient = feedClient.newBuilder()
        .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addNetworkInterceptor(LinkSafety.privateHostInterceptor())
        .build()
    private val connectionTestClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_RUNTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_RUNTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(DEFAULT_RUNTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    val networkDiagnostics = NetworkDiagnostics(feedClient)
    private val feedFetcher = FeedFetcher(feedClient, clock)
    val offlineArticleRepository = com.dailynews.data.repo.OfflineArticleRepository(database, com.dailynews.pipeline.fetch.ArticleBodyFetcher(FeedFetcher(pageClient, clock)))
    private val pageEnricher = ArticlePageEnricher(FeedFetcher(pageClient, clock))

    private val networkState = NetworkStatePort {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    private val fetchLifecycle = FetchLifecycleRepository(runRepository, reportRepository, runLogRepository)
    private val legacyFetchPort = FetchStep(
        feeds = feedRepository,
        fetchAll = feedFetcher::fetchAll,
        enrich = pageEnricher::enrich,
        seenLinks = seenLinksRepository,
        networkState = networkState,
        lifecycle = fetchLifecycle,
        clock = ClockProvider { clock.instant() },
    )
    private val recordedLegacyFetchPort = RecordingFetchPort(legacyFetchPort, articleRepository)
    val sweepStep = SweepStep(
        feeds = feedRepository,
        fetchAll = feedFetcher::fetchAll,
        enrich = pageEnricher::enrich,
        seenLinks = seenLinksRepository,
        networkState = networkState,
        pool = articleRepository,
        clock = ClockProvider { clock.instant() },
    )
    private val windowSliceFetchPort = WindowSliceStep(
        feeds = feedRepository,
        sweep = sweepStep,
        pool = articleRepository,
        enrich = pageEnricher::enrich,
        seenLinks = seenLinksRepository,
        networkState = networkState,
        lifecycle = fetchLifecycle,
        clock = ClockProvider { clock.instant() },
    )
    private val fetchPort = FetchPort { reportDate, attempt, trigger, config ->
        if (BuildConfig.DEBUG && config.useLegacySingleShotFetch) {
            recordedLegacyFetchPort.fetch(reportDate, attempt, trigger, config)
        } else {
            windowSliceFetchPort.fetch(reportDate, attempt, trigger, config)
        }
    }

    private val providerResolver = ProviderResolver { role, execution ->
        val settings = providerSettings.load()
        val roleModel = if (role == EditorialRole.EDITOR) settings.mapping.editor else settings.mapping.drafter
        val config = settings.providers.firstOrNull { it.id == roleModel.providerId }
            ?: error("No provider configured for $role. Open Settings → Providers.")
        val provider = buildProvider(config, reportLlmClient(execution))
        ProviderBinding(config.id, provider, roleModel, com.dailynews.model.ArtifactJson.compact.encodeToString(config) + "|app=${BuildConfig.VERSION_CODE}:${BuildConfig.BUILD_TYPE}")
    }

    private fun buildProvider(config: ProviderConfig, client: OkHttpClient): LlmProvider = when (config.type) {
        ProviderType.OPENROUTER, ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(config, apiKeyVault, client)
        ProviderType.ANTHROPIC -> AnthropicProvider(config, apiKeyVault, client)
    }

    private fun reportLlmClient(execution: LlmExecutionConfig): OkHttpClient {
        val normalized = execution.normalized()
        return OkHttpClient.Builder()
            .connectTimeout(normalized.connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(normalized.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(normalized.callTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    suspend fun testProviderConnection(providerId: String, model: String) {
        require(model.isNotBlank()) { "model is required" }
        val config = providerSettings.load().providers.firstOrNull { it.id == providerId.trim() }
            ?: error("save provider $providerId before testing")
        buildProvider(config, connectionTestClient).complete(
            LlmRequest(
                model = model.trim(),
                system = "Reply with the single word OK.",
                userContent = "Connection test",
                // Reasoning models may consume output tokens before emitting the final OK.
                maxTokens = 1024,
                temperature = null,
                jsonMode = false,
                assistantPrefill = null,
            ),
        )
    }

    fun currentNetworkContext(): Map<String, String> {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        val power = appContext.getSystemService(android.os.PowerManager::class.java)
        return mapOf(
            "transport" to when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> "other/offline"
            },
            "metered" to connectivity.isActiveNetworkMetered.toString(),
            "device_idle" to power.isDeviceIdleMode.toString(),
        )
    }

    fun currentProviderNetworkTargets(): List<NetworkProbeTarget> {
        val settings = providerSettings.load()
        val configuredIds = listOf(settings.mapping.editor.providerId, settings.mapping.drafter.providerId).distinct()
        return configuredIds.mapNotNull { providerId ->
            settings.providers.firstOrNull { it.id == providerId }?.let { provider ->
                NetworkProbeTarget("LLM provider ${provider.id}", provider.baseUrl)
            }
        }
    }

    private val auditSink = object : LlmCallAuditSink {
        override suspend fun record(
            runId: String,
            role: EditorialRole,
            providerId: String,
            model: String,
            response: com.dailynews.llm.LlmResponse?,
            retryIndex: Int,
            outcome: String,
        ) = llmCallRepository.record(runId, role.name, providerId, model, response?.inputTokens, response?.outputTokens, retryIndex, outcome)
    }

    val editorialEngine = LlmEditorialEngine(
        providerResolver,
        AssetPromptSource(appContext),
        auditSink,
        ShortlistContextBuilder(cacheRepository, reportRepository),
        artifactStore,
        runLogRepository,
        checkpoints = artifactStore,
    )

    suspend fun runEditorialComparison(source: String, id: String, preference: String) {
        val repository = com.dailynews.data.repo.ComparisonRepository(database, artifactStore)
        val previous = repository.manifest(source, id)
        if (previous?.get("status")?.toString() == "\"complete\"") return
        if (previous != null) {
            repository.manifest(source, id, kotlinx.serialization.json.JsonObject(previous + ("status" to kotlinx.serialization.json.JsonPrimitive("incomplete"))))
            error("interrupted comparison requires a new explicit request")
        }
        val snapshot = repository.snapshot(source)
        val config = snapshot.config.normalized()
        val budget = configRepository.config.first().monthlyTokenBudget
        require(budget <= 0 || llmCallRepository.tokensThisMonth() < budget) { "monthly token budget reached" }
        val binding = providerResolver.resolve(EditorialRole.EDITOR, config.llmExecution)
        val prompts = AssetPromptSource(appContext)
        val output = repository.artifacts(source, id)
        val provenance = kotlinx.serialization.json.buildJsonObject {
            put("source_run_id", kotlinx.serialization.json.JsonPrimitive(source))
            put("app_version", kotlinx.serialization.json.JsonPrimitive(BuildConfig.VERSION_NAME))
            put("provider_fingerprint", kotlinx.serialization.json.JsonPrimitive(com.dailynews.pipeline.flow.recoveryHash(binding.configurationSignature)))
            put("model", com.dailynews.model.ArtifactJson.codec.encodeToJsonElement(com.dailynews.llm.RoleModel.serializer(), binding.roleModel))
            put("shortlist_prompt_hash", kotlinx.serialization.json.JsonPrimitive(com.dailynews.pipeline.flow.recoveryHash(prompts.part1Shortlist(config.part1MaxItems))))
            put("plan_prompt_hash", kotlinx.serialization.json.JsonPrimitive(com.dailynews.pipeline.flow.recoveryHash(prompts.part1Plan(config.part1MaxItems))))
            put("cache", kotlinx.serialization.json.JsonPrimitive("disabled_both_arms"))
            put("preference", kotlinx.serialization.json.JsonPrimitive(preference))
        }
        suspend fun status(value: String) = repository.manifest(source, id,
            kotlinx.serialization.json.JsonObject(provenance + ("status" to kotlinx.serialization.json.JsonPrimitive(value))))
        status("running")
        try {
            val serial = java.util.concurrent.atomic.AtomicLong()
            val logs = object : com.dailynews.pipeline.ports.RunLogSink {
                override suspend fun log(runId: String, step: String, level: com.dailynews.pipeline.ports.LogLevel, message: String) {
                    val data = kotlinx.serialization.json.buildJsonObject {
                        put("step", kotlinx.serialization.json.JsonPrimitive(step))
                        put("level", kotlinx.serialization.json.JsonPrimitive(level.name))
                        put("message", kotlinx.serialization.json.JsonPrimitive(message))
                    }
                    output.write(runId, "telemetry/${serial.incrementAndGet()}.json", data.toString().toByteArray())
                }
            }
            val runner = com.dailynews.pipeline.flow.EditorialComparison({ contexts ->
                LlmEditorialEngine(ProviderResolver { _, _ -> binding }, prompts, auditSink, contexts, output, logs)
            }, output)
            runner.run(id, snapshot.raw, snapshot.feeds, snapshot.date, config, preference, snapshot.history.recentTopN)
            status("complete")
        } catch (error: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withTimeoutOrNull(2000) { status(when (error) { is kotlinx.coroutines.TimeoutCancellationException -> "timeout"; is kotlinx.coroutines.CancellationException -> "cancelled"; else -> "failed" }) }
            }
            throw error
        }
    }

    suspend fun generatePart2Group(reportDate: String, source: String): Int {
        val config = configRepository.config.first()
        return reportRepository.generatePart2Group(
            reportDate,
            source,
            editorialEngine,
            config.summaryEnrichment.shortSummaryThreshold,
            config.maxLlmCallsPerRun,
            config.llmExecution,
        )
    }

    val orchestrator = RunOrchestrator(
        fetch = fetchPort,
        recovery = com.dailynews.data.repo.RunRecoveryRepository(database, artifactStore, runRepository, feedRepository),
        feeds = feedRepository,
        validator = QcValidator(),
        contexts = LlmContextBuilder(),
        editorial = editorialEngine,
        assembler = ReportAssembler(),
        reportSink = reportRepository,
        failureSink = reportRepository,
        topNSink = reportRepository,
        artifactSink = artifactStore,
        logSink = runLogRepository,
        seenLinks = seenLinksRepository,
        cache = cacheRepository,
        clock = ClockProvider { clock.instant() },
        // RunOrchestrator already decides whether the evidence warrants a probe,
        // and it inspects the throwable chain. Re-checking the message text here
        // would discard that and silently disable diagnostics on device.
        unexpectedDiagnostics = UnexpectedFailureDiagnostics {
            networkDiagnostics.run(
                feedRepository.enabledFeeds(),
                providerTargets = currentProviderNetworkTargets(),
            ).map { probe ->
                "${probe.target}/${probe.stage}: ${if (probe.passed) "ok" else "failed"} ${probe.detail}"
            }
        },
    )

    private companion object {
        const val DEFAULT_RUNTIME_TIMEOUT_SECONDS = 1_200L

        /**
         * Timeout for the fetch paths (feeds and article pages).
         *
         * Deliberately far shorter than the run-level 1200 seconds: fetching has
         * concurrency semaphores of 8 (feeds) and 4 (pages), and a single slow source
         * holding a permit for 20 minutes could drag the whole run past the watchdog,
         * while its contribution to the report is just one source. 60 seconds is more
         * than enough for any healthy RSS feed or article page.
         */
        const val FETCH_TIMEOUT_SECONDS = 60L
    }
}
