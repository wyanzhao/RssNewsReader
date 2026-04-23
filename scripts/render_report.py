#!/usr/bin/env python3
"""
Render RSS daily report from raw.json + validation.json.

This script is intentionally read-only with respect to the source data:
- It does not fetch from the network.
- It does not validate completeness.
- It only renders markdown and chooses the final output path based on
  validation.passed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List


# Make ``scripts/`` importable when this file is launched directly or imported
# via ``importlib`` in tests.
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from _common.editorial import (  # noqa: E402
    Article,
    as_dict,
    format_time_only,
    format_utc,
    group_articles,
    normalize_articles,
    normalize_source_groups,
    parse_pub_date,
    report_date,
)
from _common.runtime_config import (  # noqa: E402
    DEFAULT_PART1_SUMMARY_MAX_CHARS,
    DEFAULT_PART2_SUMMARY_MAX_CHARS,
    DEFAULT_PIPELINE_CONFIG_FILE,
    load_pipeline_config,
)


TOP_N = 30
LINE = "=" * 70
DEFAULT_CONFIG_PATH = DEFAULT_PIPELINE_CONFIG_FILE


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render RSS daily report from raw.json and validation.json.",
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to raw.json produced by the RSS monitor.",
    )
    parser.add_argument(
        "--validation",
        required=True,
        help="Path to validation.json produced by the validator.",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Target markdown path for the official report.",
    )
    parser.add_argument(
        "--date",
        help="Optional YYYY-MM-DD date to display in the report title.",
    )
    parser.add_argument(
        "--config",
        default=None,
        help="Optional pipeline config path. Used when raw.json lacks a config snapshot.",
    )
    return parser.parse_args()


def load_json(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def _coerce_limit(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    return None


def _first_limit(*values: Any) -> int | None:
    for value in values:
        limit = _coerce_limit(value)
        if limit is not None:
            return limit
    return None


def _dict_or_empty(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _extract_render_overrides(config: Dict[str, Any]) -> Dict[str, int]:
    render = _dict_or_empty(config.get("render"))
    part1_limit = _first_limit(render.get("part1_summary_max_chars"))
    part2_limit = _first_limit(render.get("part2_summary_max_chars"))
    overrides: Dict[str, int] = {}
    if part1_limit is not None:
        overrides["part1_summary_max_chars"] = part1_limit
    if part2_limit is not None:
        overrides["part2_summary_max_chars"] = part2_limit
    return overrides


def _extract_raw_config_snapshot(raw: Dict[str, Any]) -> Dict[str, Any]:
    meta = _dict_or_empty(raw.get("meta"))
    candidates = (
        raw.get("runtime_config"),
        meta.get("config_snapshot"),
        meta.get("pipeline_config_snapshot"),
        raw.get("config_snapshot"),
        raw.get("pipeline_config_snapshot"),
    )
    for candidate in candidates:
        if isinstance(candidate, dict):
            return candidate
    return {}


def default_render_config() -> Dict[str, int]:
    return {
        "part1_summary_max_chars": DEFAULT_PART1_SUMMARY_MAX_CHARS,
        "part2_summary_max_chars": DEFAULT_PART2_SUMMARY_MAX_CHARS,
    }


def resolve_render_config(raw: Dict[str, Any], config_path: str | None = None) -> Dict[str, int]:
    raw_overrides = _extract_render_overrides(_extract_raw_config_snapshot(raw))
    config = default_render_config()

    if config_path:
        path = Path(config_path)
        if path.exists():
            config.update(_extract_render_overrides(load_pipeline_config(str(path))[0]))
        elif not raw_overrides:
            raise FileNotFoundError(f"config file not found: {config_path}")
    elif DEFAULT_CONFIG_PATH.exists():
        config.update(_extract_render_overrides(load_pipeline_config(DEFAULT_CONFIG_PATH)[0]))

    config.update(raw_overrides)
    return config


def clamp_text(text: str, limit: int) -> str:
    text = " ".join((text or "").split())
    if limit <= 0:
        return text
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 1)].rstrip() + "…"


def choose_top_articles(articles: List[Article], limit: int = TOP_N) -> List[Article]:
    """Return the first `limit` articles in publication-time-desc order.

    This is a deterministic fallback used only by the static renderer. The
    Claude Code runtime overwrites Part 1 on the success path via
    `report-assembler`, which consumes `part1_plan.json` produced by the LLM.
    """
    return list(articles)[: max(limit, 0)]


def render_report(
    raw: Dict[str, Any],
    validation: Dict[str, Any],
    date_str: str,
    render_config: Dict[str, int] | None = None,
) -> str:
    config = dict(default_render_config())
    if render_config:
        config.update(render_config)
    part1_summary_max_chars = config["part1_summary_max_chars"]
    part2_summary_max_chars = config["part2_summary_max_chars"]

    articles = normalize_articles(raw)
    groups = normalize_source_groups(raw, validation, articles)
    article_map = group_articles(articles)
    top_articles = choose_top_articles(articles, TOP_N)

    passed = validation.get("passed") is True
    total_articles = len(articles)
    configured_count = as_dict(validation.get("counts")).get("configured")
    if configured_count is None:
        configured_count = raw.get("configured_feed_count")
    if configured_count is None:
        configured_count = len(groups)
    try:
        configured_count = int(configured_count)
    except Exception:
        configured_count = len(groups)

    lines: List[str] = []
    lines.append(LINE)
    lines.append(f"[{date_str}] RSS 每日精选 TOP {TOP_N}")
    lines.append(LINE)
    lines.append("")

    if passed:
        lines.append(f"数据来源：{configured_count}个RSS源，共获取 {total_articles} 篇文章")
    else:
        reasons = validation.get("blocking_reasons") or []
        reason_text = "；".join(str(item) for item in reasons if str(item).strip())
        if reason_text:
            lines.append(f"校验状态：未通过，阻断原因：{reason_text}")
        else:
            lines.append("校验状态：未通过")
        lines.append(f"数据来源：{configured_count}个RSS源，共获取 {total_articles} 篇文章")
    lines.append(f"生成时间：{date_str} UTC")
    lines.append("")

    failed_groups = [group for group in groups if group.status == "error"]
    if failed_groups:
        failure_summary = "；".join(
            f"{group.name} ({group.error})" if group.error else group.name
            for group in failed_groups
        )
        lines.append(f"抓取异常：{failure_summary}")
        lines.append("")

    if top_articles:
        for idx, article in enumerate(top_articles, 1):
            lines.append(f"{idx}. {article.title}")
            lines.append(f"   来源: {article.source}")
            lines.append(f"   时间: {format_utc(article.pub_date)}")
            lines.append(f"   链接: {article.link}")
            if article.summary:
                lines.append(f"   摘要: {clamp_text(article.summary, part1_summary_max_chars)}")
            else:
                lines.append("   摘要: （无）")
            lines.append("")
    else:
        lines.append("本次抓取结果为空，过去 24 小时未获取到可展示的文章。")
        lines.append("")

    lines.append(LINE)
    lines.append("按来源分组")
    lines.append(LINE)
    lines.append("")

    for group in groups:
        source_articles = article_map.get(group.name, [])
        group_count = len(source_articles)
        if group.status == "error":
            lines.append(f"--- {group.name} ({group_count}篇 · 抓取失败) ---")
        else:
            lines.append(f"--- {group.name} ({group_count}篇) ---")
        lines.append("")
        if group.status == "error":
            lines.append(f"抓取状态: {group.error or '抓取失败'}")
            lines.append("")
        if not source_articles:
            lines.append("无文章")
            lines.append("")
            continue

        for idx, article in enumerate(source_articles, 1):
            lines.append(f"{idx}. {article.title}")
            lines.append(f"   时间: {format_time_only(article.pub_date)} | 链接: {article.link}")
            if article.summary:
                lines.append(f"   摘要: {clamp_text(article.summary, part2_summary_max_chars)}")
            else:
                lines.append("   摘要: （无）")
            lines.append("")

    lines.append(LINE)
    lines.append("统计检查")
    lines.append(LINE)
    lines.append("")

    part1_count = len(top_articles)
    part2_groups = len(groups)
    part2_article_total = sum(len(article_map.get(group.name, [])) for group in groups)
    group_count_match = "是" if part2_groups == configured_count else "否"
    article_total_match = "是" if part2_article_total == total_articles else "否"
    unique_sources = raw.get("unique_sources")
    if not isinstance(unique_sources, list):
        unique_sources = sorted({article.source for article in articles})
    unique_source_count = raw.get("unique_source_count")
    if not isinstance(unique_source_count, int):
        unique_source_count = len(unique_sources)

    lines.append(f"- feeds.json feed 数量: {configured_count}")
    lines.append(f"- JSON count: {total_articles}")
    lines.append(f"- JSON 去重 source 列表: {json.dumps(unique_sources, ensure_ascii=False)}")
    lines.append(f"- JSON 去重 source 数量: {unique_source_count}")
    lines.append(f"- Part 1 文章数: {part1_count}")
    lines.append(f"- Part 2 分组数: {part2_groups}")
    lines.append(f"- Part 2 文章总数: {part2_article_total}")
    lines.append(f"- Part 2 分组数与 feeds.json 一致: {group_count_match}")
    lines.append(f"- Part 2 文章总数与 JSON count 一致: {article_total_match}")
    lines.append(
        f"- JSON 去重 source 数量与 feeds.json 一致: {'是' if unique_source_count == configured_count else '否'}"
    )

    passed_value = validation.get("passed") is True
    lines.append(f"- 校验结论: {'通过' if passed_value else '未通过'}")

    warnings = validation.get("warnings") or []
    if warnings:
        lines.append(f"- 校验警告: {'；'.join(str(item) for item in warnings if str(item).strip())}")
    if failed_groups:
        lines.append(
            "- 抓取失败来源: "
            + "；".join(
                f"{group.name} ({group.error})" if group.error else group.name
                for group in failed_groups
            )
        )

    if not passed_value:
        reasons = validation.get("blocking_reasons") or []
        if reasons:
            lines.append(f"- 阻断原因: {'；'.join(str(item) for item in reasons if str(item).strip())}")

    return "\n".join(lines).rstrip() + "\n"


def failed_output_path(output_path: str) -> str:
    path = Path(output_path)
    if path.stem.endswith(".failed"):
        return str(path)
    if path.suffix.lower() == ".md":
        return str(path.with_name(f"{path.stem}.failed{path.suffix}"))
    return str(path.with_name(f"{path.name}.failed.md"))


def write_text(path: str, content: str) -> None:
    os.makedirs(str(Path(path).parent), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def main() -> int:
    args = parse_args()

    try:
        raw = load_json(args.input)
        validation = load_json(args.validation)
    except Exception as exc:
        print(f"Failed to load input: {exc}", file=sys.stderr)
        return 10

    try:
        date_str = report_date(args.date, args.output, raw, validation)
        render_config = resolve_render_config(raw, args.config)
        content = render_report(raw, validation, date_str, render_config=render_config)
        passed = validation.get("passed") is True
        target_path = args.output if passed else failed_output_path(args.output)
        write_text(target_path, content)
        print(target_path)
        return 0
    except Exception as exc:
        print(f"Failed to render report: {exc}", file=sys.stderr)
        return 40


if __name__ == "__main__":
    raise SystemExit(main())
