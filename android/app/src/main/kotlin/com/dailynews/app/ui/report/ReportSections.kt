package com.dailynews.app.ui.report

/**
 * Epic U: Part 2 (grouped by source + per-item Chinese summaries) has retired
 * from the product surface.
 *
 * Kept untouched: ReportAssembler / Part2Merger / EditorialContracts /
 * ReportRepository.generatePart2Group / part=2 rows in report_items /
 * reports.groupsJson / the Part2Mode enum.
 *
 * Restoration steps (two steps, one line each):
 *   1. Change this to true here;
 *   2. Delete the line in PipelineConfig.normalized() that forces part2Mode = LAZY.
 * Note: the assembler is not part of the restoration steps — removing the Part 2
 * section from the markdown triggers ReportReviewer's per-item link/title checks
 * and gets the daily report judged FAILED.
 */
internal const val PART2_SECTION_ENABLED = false
