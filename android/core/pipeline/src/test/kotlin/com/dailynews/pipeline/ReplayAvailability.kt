package com.dailynews.pipeline

import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Availability gate for the 2026-08-03 real replay data.
 *
 * The data comes from `runs/2026-08-03/` and `rss-report-2026-08-03.md`, both
 * excluded by `.gitignore` (`AGENTS.md`: fetched content lives only in the
 * user's local clone, not the repo). So on a clean clone they simply are not
 * there.
 *
 * Previously the six tests that depend on it failed **in two opposite ways**
 * when data was missing: `QcAndContextParityTest` went through
 * `FixtureFactory.text`'s `requireNotNull` and turned red; `EditorialReplayTest`
 * went through `assumeTrue`, skipped silently, and asserted nothing. Same
 * missing input, half the build fails, half goes fake-green — so every
 * "Android JVM all green" sentence in `TASKS.md` was only a statement about
 * one laptop.
 *
 * Now there is one policy, and it can be **asserted**:
 * - Default: missing data → skip all (the build stays green on a clean clone,
 *   honestly running fewer tests).
 * - `-PrequireReplayFixtures`: missing data → fail hard. Delivery verification
 *   runs this flag so these migration guards actually executed, not silently
 *   skipped.
 */
object ReplayAvailability {
    private const val PROBE = "replay/2026-08-03/llm_context.json"

    val present: Boolean
        get() = ReplayAvailability::class.java.classLoader.getResource(PROBE) != null

    private val required: Boolean
        get() = System.getProperty("dailynews.requireReplayFixtures") == "true"

    /** Call at the start of every test that depends on replay data. */
    fun require() {
        if (present) return
        check(!required) {
            "回放夹具缺失，但构建以 -PrequireReplayFixtures 运行。" +
                "本地 runs/2026-08-03/ 与 rss-report-2026-08-03.md 必须存在，这几条迁移守卫才算跑过。"
        }
        assumeTrue(false, "本地回放数据不可用（已被 .gitignore 排除）；这条迁移守卫被跳过")
    }
}
