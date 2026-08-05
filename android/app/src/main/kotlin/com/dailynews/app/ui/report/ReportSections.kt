package com.dailynews.app.ui.report

/**
 * Epic U：Part 2（按来源分组 + 逐条中文摘要）已退出产品面。
 *
 * 保留不动：ReportAssembler / Part2Merger / EditorialContracts /
 * ReportRepository.generatePart2Group / report_items 的 part=2 行 /
 * reports.groupsJson / Part2Mode 枚举。
 *
 * 恢复步骤（两步，各一行）：
 *   1. 这里改成 true；
 *   2. PipelineConfig.normalized() 里删掉强制 part2Mode = LAZY 的那一行。
 * 注意：assembler 不在恢复步骤里——从 markdown 删 Part 2 段会触发
 * ReportReviewer 的逐条 link/title 检查，导致每日报告被判 FAILED。
 */
internal const val PART2_SECTION_ENABLED = false
