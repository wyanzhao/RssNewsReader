package com.dailynews.app.ui

import com.dailynews.app.ui.common.activeGenerationLabel
import com.dailynews.data.db.RunLogEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerationPhaseTest {
    private fun start(stage: String) = RunLogEntity(runId = "run", step = "stage_started", level = "INFO", message = stage, createdAtUtc = "now")
    private fun end(stage: String) = start("""{"stage":"$stage","elapsedMs":1,"outcome":"success"}""").copy(step = "stage_timing")
    @Test fun nestedModelCompletionReturnsToEditorialAndThenReview() {
        val logs = mutableListOf(start("editorial"), start("llm.part1_shortlist"))
        assertTrue("筛选" in activeGenerationLabel(logs))
        logs += end("llm.part1_shortlist")
        assertTrue("编辑精选" in activeGenerationLabel(logs))
        logs += start("llm.part1_plan")
        assertTrue("生成摘要" in activeGenerationLabel(logs))
        logs += end("editorial")
        logs += start("review")
        assertTrue("复核" in activeGenerationLabel(logs))
    }
    @Test fun missingOrMalformedTelemetryDoesNotInventAModelPhase() {
        val fallback = activeGenerationLabel(emptyList())
        assertEquals(fallback, activeGenerationLabel(listOf(end("llm.part1_plan"))))
        assertEquals(fallback, activeGenerationLabel(listOf(start("unknown"))))
        assertTrue("复核" in activeGenerationLabel(listOf(start("review"), end("bad").copy(message = "invalid"))))
    }
}
