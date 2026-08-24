package com.dailynews.app.work

/**
 * Whether SQLite VACUUM should run after a prune.
 *
 * `auto_vacuum = NONE` means deleted pages stay on the free list until compact.
 * Call only when at least one counted delete is greater than zero — VACUUM rewrites
 * the whole file and must not run empty every day.
 */
internal fun shouldCompactAfterPrune(
    articlesDeleted: Int = 0,
    fetchLogsDeleted: Int = 0,
    runArtifactsDeleted: Int = 0,
    runLogsDeleted: Int = 0,
    runsDeleted: Int = 0,
    part2ItemsDeleted: Int = 0,
): Boolean = articlesDeleted > 0 ||
    fetchLogsDeleted > 0 ||
    runArtifactsDeleted > 0 ||
    runLogsDeleted > 0 ||
    runsDeleted > 0 ||
    part2ItemsDeleted > 0
