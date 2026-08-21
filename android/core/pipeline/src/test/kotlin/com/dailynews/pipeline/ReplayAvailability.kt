package com.dailynews.pipeline

import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 2026-08-03 真实回放数据的可用性闸。
 *
 * 这份数据来自 `runs/2026-08-03/` 与 `rss-report-2026-08-03.md`，两者都被 `.gitignore`
 * 排除（`AGENTS.md`：抓取到的内容只存在于用户本地克隆，不进仓库）。所以在干净克隆
 * 上它们根本不存在。
 *
 * 此前六个依赖它的测试在缺数据时会以**两种相反的方式**失效：`QcAndContextParityTest`
 * 走 `FixtureFactory.text` 的 `requireNotNull` 直接红，`EditorialReplayTest` 走
 * `assumeTrue` 静默跳过、零断言。同一份缺失输入，一半让构建失败、一半让它假绿——
 * 后果是 `TASKS.md` 里每一句「Android JVM 全绿」都只是关于一台笔记本的陈述。
 *
 * 现在统一成一条策略，并且可以被**断言**：
 * - 默认：缺数据 → 全部 skip（干净克隆上构建仍然是绿的，且诚实地少跑几条）。
 * - `-PrequireReplayFixtures`：缺数据 → 直接失败。交付前的验证跑这个开关，
 *   就能确认这几条迁移守卫真的执行过，而不是被静默跳过。
 */
object ReplayAvailability {
    private const val PROBE = "replay/2026-08-03/llm_context.json"

    val present: Boolean
        get() = ReplayAvailability::class.java.classLoader.getResource(PROBE) != null

    private val required: Boolean
        get() = System.getProperty("dailynews.requireReplayFixtures") == "true"

    /** 在每个依赖回放数据的测试开头调用。 */
    fun require() {
        if (present) return
        check(!required) {
            "回放夹具缺失，但构建以 -PrequireReplayFixtures 运行。" +
                "本地 runs/2026-08-03/ 与 rss-report-2026-08-03.md 必须存在，这几条迁移守卫才算跑过。"
        }
        assumeTrue(false, "本地回放数据不可用（已被 .gitignore 排除）；这条迁移守卫被跳过")
    }
}
