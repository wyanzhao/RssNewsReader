package com.dailynews.app.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.RunEntity
import com.dailynews.data.files.ArtifactStore
import com.dailynews.data.repo.FeedRepository
import com.dailynews.data.repo.LlmCallRepository
import com.dailynews.data.repo.RunLogRepository
import com.dailynews.data.repo.RunRepository
import com.dailynews.pipeline.orchestrate.NetworkDiagnostics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real ViewModel against an in-memory Room database; no instrumentation
 * needed. NetworkDiagnostics runs with zero feeds, so probes stay offline
 * (android-context rows only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private lateinit var database: DailyNewsDatabase

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DailyNewsDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun runEntity(runId: String, startedAtUtc: String) = RunEntity(
        runId = runId,
        reportDate = startedAtUtc.take(10),
        status = "FAILED",
        classification = "UNEXPECTED_ERROR",
        validatorExitCode = 40,
        attempt = 1,
        trigger = "manual",
        blockingReasonsJson = """["fetch: timeout"]""",
        startedAtUtc = startedAtUtc,
        finishedAtUtc = startedAtUtc,
    )

    private fun newViewModel(initialRunId: String?) = DiagnosticsViewModel(
        initialRunId,
        SavedStateHandle(),
        RunRepository(database),
        RunLogRepository(database),
        LlmCallRepository(database),
        ArtifactStore(database),
        FeedRepository(database, ApplicationProvider.getApplicationContext()),
        NetworkDiagnostics(OkHttpClient()),
        networkContext = { mapOf("transport" to "wifi") },
    )

    private fun awaitState(viewModel: DiagnosticsViewModel, predicate: (DiagnosticsUiState) -> Boolean): DiagnosticsUiState =
        runBlocking { withTimeout(10_000) { viewModel.state.first(predicate) } }

    private fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition was not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun nullInitialRunIdAutoSelectsTheMostRecentRun() {
        runBlocking {
            database.runs().upsert(runEntity("run-old", "2026-08-03T10:00:00Z"))
            database.runs().upsert(runEntity("run-new", "2026-08-04T10:00:00Z"))
        }
        val viewModel = newViewModel(initialRunId = null)

        val state = awaitState(viewModel) { it.detail?.runId == "run-new" && !it.artifactsLoading }
        assertEquals("run-new", state.selectedRunId)
        assertEquals("run-new", state.detail?.runId)
        // Stage recovered from the entity's blocking reason drives the advisor.
        assertEquals("fetch", state.stage)
    }

    @Test
    fun explicitDeepLinkRunIdIsNeverRewrittenToLatest() {
        runBlocking { database.runs().upsert(runEntity("run-new", "2026-08-04T10:00:00Z")) }
        val viewModel = newViewModel(initialRunId = "cleaned-run")

        val state = awaitState(viewModel) { !it.loading && it.runs.isNotEmpty() }
        assertEquals("cleaned-run", state.selectedRunId)
        assertNull(state.detail)
        // hasRuns=true but no entity/classification: falls through to the rerun fallback.
        assertEquals(DiagnosticsAction.RUN_NOW, state.advice.action)
    }

    @Test
    fun exportSuccessAndFailureEachProduceExactlyOneConsumableEvent() {
        runBlocking { database.runs().upsert(runEntity("run-1", "2026-08-04T10:00:00Z")) }
        val viewModel = newViewModel(initialRunId = null)
        awaitState(viewModel) { it.selectedRunId == "run-1" }

        viewModel.export { true }
        val success = awaitState(viewModel) { it.events.isNotEmpty() }
        assertEquals("产物已导出", success.events.single().message)
        assertFalse(success.events.single().error)

        viewModel.consumeEvent(success.events.single().id)
        awaitState(viewModel) { it.events.isEmpty() }

        viewModel.export { false }
        val failure = awaitState(viewModel) { it.events.isNotEmpty() }
        assertTrue(failure.events.single().error)
        assertEquals("export", failure.events.single().retryTag)
    }

    @Test
    fun probingFlagSpansTheProbeRunAndAndroidContextIsReported() {
        val viewModel = newViewModel(initialRunId = null)
        val observed = CopyOnWriteArrayList<DiagnosticsUiState>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { viewModel.state.collect { observed += it } }
        Thread.sleep(100)

        viewModel.runNetworkDiagnostics()
        awaitCondition { observed.any { !it.probing && it.probes.isNotEmpty() } }
        job.cancel()
        scope.cancel()

        assertTrue(observed.any { it.probing })
        val finalProbes = observed.last { it.probes.isNotEmpty() }.probes
        assertTrue(finalProbes.any { it.target == "android" && it.detail == "wifi" })
    }
}
