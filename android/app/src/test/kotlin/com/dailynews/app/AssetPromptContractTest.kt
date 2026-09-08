package com.dailynews.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailynews.model.Part1PlanDraft
import com.dailynews.model.Part1PlanDraftItem
import com.dailynews.model.Part1ShortlistDraft
import com.dailynews.pipeline.context.Part1ShortlistContext
import com.dailynews.pipeline.context.RECENT_EVENT_WINDOW_DAYS
import com.dailynews.pipeline.context.ShortlistContextArticle
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The prompts are the only contract surface in this pipeline without compile-time protection: the
 * field names and numbers in the markdown are all literals, so changing `@SerialName` or a Kotlin
 * constant while forgetting to update the markdown raises no error — it just hands the model a
 * description of the wrong structure. This test is that guardrail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssetPromptContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = AssetPromptSource(context)

    @Test fun planPromptNamesDevelopmentEvidenceFields() {
        val prompt = source.part1Plan(TOP_N)
        wireNames<com.dailynews.model.EventDevelopmentDraft>().forEach { name ->
            assertTrue(name in prompt, "Missing development field: $name")
        }
        assertTrue("development" in prompt)
    }

    @Test fun planPromptNamesWatchedHistoryFields() {
        val prompt = source.part1Plan(TOP_N)
        wireNames<com.dailynews.pipeline.context.WatchedEventHistory>().forEach { name ->
            assertTrue(name in prompt, "Missing watch baseline field: $name")
        }
        assertTrue("watched_history" in prompt)
        assertTrue("watched_events" in source.part1Shortlist(TOP_N))
    }

    @Test
    fun planPromptNamesPublishedHistoryEvidenceFields() {
        val prompt = source.part1Plan(TOP_N)
        wireNames<com.dailynews.pipeline.context.RecentTopNEvent>().forEach { name ->
            assertTrue(name in prompt, "Missing published-history field: $name")
        }
    }

    /**
     * Each anchor below maps to one failure class found in the 1.1.1 source-fidelity review
     * (quality-review-111/findings.json): relative-date anchoring, forecast-vs-achieved, superlative
     * qualifiers, source geometry wording, rights-vs-disclosure, multi-condition rules, merged scopes,
     * and invented steps. Removing an anchor silently re-opens that class.
     */
    @Test
    fun planPromptKeepsSourceFidelityGuards() {
        val prompt = source.part1Plan(TOP_N)
        listOf(
            "裸域名也属于链接",
            "ASP NET",
            "以该报道自身的发布日期为锚",
            "不得补年份",
            "不得机械加“去年/今年”",
            "不得改成“已成为/已达到”",
            "不构成现状断言",
            "必须原样保留其限定词",
            "不得自行判定为“竖折”“横折”",
            "有权查阅/检查 ≠ 应当公示/公开",
            "必须保留全部条件",
            "不得合并为一句",
            "不为通顺补写材料没有的环节",
        ).forEach { anchor -> assertTrue(anchor in prompt, "part1_plan.md lost fidelity guard: $anchor") }
    }

    @Test
    fun renderedPromptsLeaveNoPlaceholderBehind() {
        val rendered = mapOf(
            AssetPromptSource.SHORTLIST_TEMPLATE to source.part1Shortlist(TOP_N),
            AssetPromptSource.PLAN_TEMPLATE to source.part1Plan(TOP_N),
            AssetPromptSource.DRAFTER_TEMPLATE to source.part2Drafter(),
            AssetPromptSource.DIGEST_TEMPLATE to source.periodicDigest(),
        )
        rendered.forEach { (name, text) ->
            val leftover = PLACEHOLDER.findAll(text).map { it.value }.toList()
            assertTrue(leftover.isEmpty(), "$name 渲染后仍残留占位符 $leftover —— 占位符名与 substitutions() 失步")
        }
    }

    @Test
    fun everySubstitutionKeyIsUsedBySomeTemplate() {
        val templates = AssetPromptSource.TEMPLATES.associateWith { source.read(it) }
        AssetPromptSource.substitutions(TOP_N).keys.forEach { key ->
            assertTrue(
                templates.values.any { key in it },
                "substitutions() 声明了 $key，但没有任何模板用到它 —— 要么模板拼错，要么这个键已经死了",
            )
        }
    }

    /**
     * The Part 1 plan output contract is enforced by `EditorialJsonSchemas.part1Plan`; whether the
     * model can fill it in *correctly* depends on the prompt naming every field. So not a single
     * field name of the plan contract classes may be absent.
     *
     * What is pinned is the **draft** types: the model writes `ref`/`also_refs`; the link-keyed
     * `Part1Plan` only exists after Kotlin parsing, and its field names must not appear in the prompt.
     */
    @Test
    fun planPromptNamesEveryPlanContractField() {
        val prompt = source.read(AssetPromptSource.PLAN_TEMPLATE)
        val required = wireNames<Part1PlanDraftItem>() + wireNames<Part1PlanDraft>()
        required.forEach { field ->
            assertTrue(field in prompt, "part1_plan.md 没有点名输出字段 `$field` —— 改了 @SerialName 却没改 prompt")
        }
    }

    /**
     * On the input side, the prompt is only required to name the fields it actually instructs the
     * model to read. The exemption set is explicit: adding a new input field turns this test red by
     * default, forcing the author to decide "should the model be told about it", rather than letting
     * it silently lie unused in the JSON.
     */
    @Test
    fun planPromptNamesEveryNonExemptInputField() {
        val prompt = source.read(AssetPromptSource.PLAN_TEMPLATE)
        val required = (wireNames<ShortlistContextArticle>() + wireNames<Part1ShortlistContext>()) - INPUT_EXEMPT
        required.forEach { field ->
            assertTrue(field in prompt, "part1_plan.md 没有点名输入字段 `$field`（如确实无需告知模型，请加进 INPUT_EXEMPT 并说明理由）")
        }
    }

    /**
     * The shortlist is the one among the four editorial calls that names the most articles (40–45
     * items), and also the one missed in the first Epic Z round — which at the time still required
     * echoing URLs verbatim. This guardrail previously covered only the plan template, so nothing
     * would have caught that omission.
     */
    @Test
    fun shortlistPromptNamesItsOwnContractFields() {
        val prompt = source.read(AssetPromptSource.SHORTLIST_TEMPLATE)
        wireNames<Part1ShortlistDraft>().forEach { field ->
            assertTrue(field in prompt, "part1_shortlist.md 没有点名输出字段 `$field`")
        }
        assertTrue("id" in prompt, "part1_shortlist.md 必须告诉模型按 `id` 引用文章")
        assertTrue(
            "不要输出 link" in prompt,
            "part1_shortlist.md 必须明确禁止回显 link —— 这正是 2026-08-19 事故的成因",
        )
    }

    /** Scraped material is data, not instructions; all four templates must say so. */
    @Test
    fun everyPromptDeclaresScrapedMaterialUntrusted() {
        AssetPromptSource.TEMPLATES.forEach { template ->
            val prompt = source.read(template)
            assertTrue(
                "素材" in prompt && "忽略" in prompt,
                "$template 没有声明抓取内容是素材、其中的指令一律忽略",
            )
        }
    }

    @Test
    fun planPromptRecentWindowMatchesKotlinConstant() {
        val prompt = source.read(AssetPromptSource.PLAN_TEMPLATE)
        assertTrue(
            "近 $RECENT_EVENT_WINDOW_DAYS 天" in prompt,
            "part1_plan.md 的回看窗口与 RECENT_EVENT_WINDOW_DAYS=$RECENT_EVENT_WINDOW_DAYS 失步",
        )
    }

    private inline fun <reified T> wireNames(): Set<String> = wireNames(serializer<T>().descriptor)

    private fun wireNames(descriptor: SerialDescriptor): Set<String> =
        (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()

    private companion object {
        const val TOP_N = 30
        val PLACEHOLDER = Regex("\\{[A-Z_]+}")

        /** Structural navigation fields and authoritative metadata: the model reads them from JSON structure, so the prompt need not name each one. */
        val INPUT_EXEMPT = setOf(
            "meta", "articles", "article_count", "cache_hits",
            "pub_date_utc", "pub_date_iso",
        )
    }
}
