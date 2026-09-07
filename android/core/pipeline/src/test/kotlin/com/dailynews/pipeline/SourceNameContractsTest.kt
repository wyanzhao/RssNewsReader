package com.dailynews.pipeline

import com.dailynews.pipeline.editorial.SourceNameContracts
import com.dailynews.pipeline.editorial.EditorialContracts
import com.dailynews.pipeline.context.LlmContextBuilder
import com.dailynews.pipeline.validate.QcValidator
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class SourceNameContractsTest {
    @Test fun `observed Athron typo is rejected without rewriting output`() {
        val summary = "俄克拉荷马州一座Athron Blockchain旗下数据中心发生泄漏。"
        val errors = SourceNameContracts.errors(summary, listOf("The Athlon Blockchain LLC-owned data center was condemned."), "summary")
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("Athlon Blockchain"))
        assertTrue(summary.contains("Athron"))
    }
    @Test fun `case whitespace exact names and inflections do not become spelling failures`() {
        assertTrue(SourceNameContracts.errors("Athlon  Blockchain", listOf("Athlon Blockchain LLC"), "summary").isEmpty())
        assertTrue(SourceNameContracts.errors("INTEL Foundry", listOf("Intel Foundry"), "summary").isEmpty())
        assertTrue(SourceNameContracts.errors("Intel Foundries", listOf("Intel Foundry"), "summary").isEmpty())
        assertTrue(SourceNameContracts.errors("其他名称", listOf("Intel Foundry"), "summary").isEmpty())
    }
    @Test fun `actual alternate names and ambiguous matches are not auto corrected`() {
        assertTrue(SourceNameContracts.errors("Inter Foundry", listOf("Intel Foundry", "Inter Foundry"), "summary").isEmpty())
        assertTrue(SourceNameContracts.errors("Athron Blockchain", listOf("Athlon Blockchain", "Athran Blockchain"), "summary").isEmpty())
        assertTrue(SourceNameContracts.errors("Intel Foundry", listOf("Intel", "Foundry"), "summary").isEmpty())
    }
    @Test fun `validation uses only this event sources and accepts correctly merged evidence`() = runBlocking {
        val (raw, feeds, config) = FixtureFactory.goldenRaw()
        val base = LlmContextBuilder().build(raw, QcValidator().validate(raw, feeds).result, "2026-04-10", "/report.md", config).llmContext
        val first = base.allArticles.first().copy(title = "Athlon Blockchain report", summaryEn = "", articleText = "")
        val other = first.copy(link = "https://source.example/other", title = "Athron Blockchain statement")
        val context = base.copy(allArticles = listOf(first, other))
        val item = Part1PlanItem(first.link, "Athron Blockchain 发表声明。", emptyList())
        assertTrue(EditorialContracts.validatePart1(context, Part1Plan(listOf(item), 29), 30).any { "source-name mismatch" in it })
        assertTrue(EditorialContracts.validatePart1(context, Part1Plan(listOf(item.copy(alsoLinks = listOf(other.link))), 29), 30).isEmpty())
    }
}
