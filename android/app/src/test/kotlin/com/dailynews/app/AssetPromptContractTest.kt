package com.dailynews.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailynews.model.Part1Plan
import com.dailynews.model.Part1PlanItem
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
 * prompt 是这套流水线里唯一没有编译期保护的契约面：markdown 里的字段名与数字
 * 全部是字面量，改 `@SerialName` 或改 Kotlin 常量而忘记改 markdown 不会报任何错，
 * 只会让模型收到一份描述错误结构的说明。这个测试是那道护栏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssetPromptContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = AssetPromptSource(context)

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
     * Part 1 plan 的输出契约由 `EditorialJsonSchemas.part1Plan` 强制，模型能不能*正确*填充
     * 则取决于 prompt 是否点名了每个字段。所以 plan 契约类的字段名一个都不许缺席。
     */
    @Test
    fun planPromptNamesEveryPlanContractField() {
        val prompt = source.read(AssetPromptSource.PLAN_TEMPLATE)
        val required = wireNames<Part1PlanItem>() + wireNames<Part1Plan>()
        required.forEach { field ->
            assertTrue(field in prompt, "part1_plan.md 没有点名输出字段 `$field` —— 改了 @SerialName 却没改 prompt")
        }
    }

    /**
     * 输入侧只要求 prompt 点名它真正指导模型去读的字段。豁免集是显式的：
     * 新增一个输入字段会默认让这个测试变红，逼作者决定「要不要告诉模型」，
     * 而不是让它悄悄躺在 JSON 里没人用。
     */
    @Test
    fun planPromptNamesEveryNonExemptInputField() {
        val prompt = source.read(AssetPromptSource.PLAN_TEMPLATE)
        val required = (wireNames<ShortlistContextArticle>() + wireNames<Part1ShortlistContext>()) - INPUT_EXEMPT
        required.forEach { field ->
            assertTrue(field in prompt, "part1_plan.md 没有点名输入字段 `$field`（如确实无需告知模型，请加进 INPUT_EXEMPT 并说明理由）")
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

        /** 结构导航字段与权威元数据：模型按 JSON 结构读取，无需 prompt 逐个点名。 */
        val INPUT_EXEMPT = setOf(
            "meta", "articles", "article_count", "cache_hits",
            "pub_date_utc", "pub_date_iso",
        )
    }
}
