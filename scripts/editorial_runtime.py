#!/usr/bin/env python3
"""Deterministic helpers for the DailyNews LLM handoff runtime.

The LLM still owns editorial judgment and Chinese writing. This module owns
machine-checkable work: artifact audits, shortlist slicing, final assembly,
final review, and cache updates.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence

from _common.editorial_cache import (  # noqa: E402
    cache_key,
    clean_text,
    default_cache_path,
    event_key,
    load_cache,
    update_entries,
    write_cache,
)


def load_json(path: str | Path) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def write_json(path: str | Path, payload: Dict[str, Any]) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_text(path: str | Path, text: str) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")


def _as_list(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def _clean_text(value: Any) -> str:
    return clean_text(value)


def _article_map(context: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    articles = _as_list(context.get("all_articles"))
    result: Dict[str, Dict[str, Any]] = {}
    for article in articles:
        if not isinstance(article, dict):
            continue
        link = _clean_text(article.get("link"))
        if link:
            result[link] = article
    return result


def _group_sources(context: Dict[str, Any]) -> List[str]:
    return [
        str(group.get("source"))
        for group in _as_list(context.get("source_groups"))
        if isinstance(group, dict)
    ]


def _validation_feed_map(validation: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    return {
        str(entry.get("source")): entry
        for entry in _as_list(validation.get("feed_results"))
        if isinstance(entry, dict) and entry.get("source")
    }


def audit_artifacts(context: Dict[str, Any], validation: Dict[str, Any]) -> Dict[str, Any]:
    errors: List[str] = []
    article_by_link = _article_map(context)
    articles = _as_list(context.get("all_articles"))
    groups = _as_list(context.get("source_groups"))
    counts = validation.get("counts") if isinstance(validation.get("counts"), dict) else {}
    expected_articles = counts.get("articles")

    if expected_articles is not None and len(articles) != expected_articles:
        errors.append(
            f"all_articles length {len(articles)} != validation.counts.articles {expected_articles}"
        )

    grouped_total = 0
    for group in groups:
        if not isinstance(group, dict):
            errors.append("source_groups contains a non-object entry")
            continue
        source = _clean_text(group.get("source"))
        refs = _as_list(group.get("article_refs"))
        count = group.get("article_count")
        if count != len(refs):
            errors.append(f"{source or '<unknown>'} article_count {count} != article_refs {len(refs)}")
        grouped_total += len(refs)
        for ref in refs:
            if not isinstance(ref, dict):
                errors.append(f"{source or '<unknown>'} contains a non-object article ref")
                continue
            link = _clean_text(ref.get("link"))
            if link not in article_by_link:
                errors.append(f"{source or '<unknown>'} article ref not found in all_articles: {link}")

    if expected_articles is not None and grouped_total != expected_articles:
        errors.append(
            f"source_groups article_refs total {grouped_total} != validation.counts.articles {expected_articles}"
        )

    feed_map = _validation_feed_map(validation)
    group_sources = _group_sources(context)
    feed_sources = list(feed_map)
    if feed_sources and group_sources != feed_sources:
        errors.append("source_groups source order does not match validation.feed_results")

    for group in groups:
        if not isinstance(group, dict):
            continue
        source = _clean_text(group.get("source"))
        if group.get("status") == "error":
            error_text = _clean_text(feed_map.get(source, {}).get("error"))
            if not error_text:
                errors.append(f"{source} has status=error but no validation.feed_results error text")

    return {
        "passed": not errors,
        "errors": errors,
        "article_count": len(articles),
        "source_count": len(groups),
    }


def _extract_shortlist_links(shortlist: Dict[str, Any]) -> List[str]:
    containers: Sequence[Any] = (
        shortlist.get("links"),
        shortlist.get("shortlist"),
        shortlist.get("items"),
        shortlist.get("articles"),
    )
    links: List[str] = []
    seen: set[str] = set()
    for container in containers:
        for item in _as_list(container):
            link = item if isinstance(item, str) else item.get("link") if isinstance(item, dict) else ""
            link = _clean_text(link)
            if link and link not in seen:
                links.append(link)
                seen.add(link)
        if links:
            break
    return links


def build_shortlist_context(context: Dict[str, Any], shortlist: Dict[str, Any]) -> Dict[str, Any]:
    article_by_link = _article_map(context)
    links = _extract_shortlist_links(shortlist)
    missing = [link for link in links if link not in article_by_link]
    if missing:
        raise ValueError(f"shortlist contains links absent from all_articles: {missing}")
    return {
        "meta": context.get("meta", {}),
        "article_count": len(links),
        "articles": [article_by_link[link] for link in links],
    }


def _require_fields(item: Dict[str, Any], fields: Iterable[str], label: str, errors: List[str]) -> None:
    for field in fields:
        value = item.get(field)
        if value is None or value == "":
            errors.append(f"{label} missing {field}")


def validate_part1(context: Dict[str, Any], part1: Dict[str, Any]) -> List[str]:
    errors: List[str] = []
    article_by_link = _article_map(context)
    items = _as_list(part1.get("items"))
    if "shortfall" not in part1:
        errors.append("part1_plan missing shortfall")
    for idx, item in enumerate(items, 1):
        if not isinstance(item, dict):
            errors.append(f"part1 item {idx} is not an object")
            continue
        _require_fields(
            item,
            ["rank", "title", "link", "source", "pub_date_utc", "summary_zh", "also_sources"],
            f"part1 item {idx}",
            errors,
        )
        link = _clean_text(item.get("link"))
        article = article_by_link.get(link)
        if article is None:
            errors.append(f"part1 item {idx} link absent from all_articles: {link}")
            continue
        if item.get("title") != article.get("title"):
            errors.append(f"part1 item {idx} title changed for {link}")
        if item.get("source") != article.get("source"):
            errors.append(f"part1 item {idx} source changed for {link}")
    return errors


def validate_part2(context: Dict[str, Any], validation: Dict[str, Any], part2: Dict[str, Any]) -> List[str]:
    errors: List[str] = []
    article_by_link = _article_map(context)
    expected_sources = _group_sources(context)
    groups = _as_list(part2.get("groups"))
    actual_sources = [
        str(group.get("source"))
        for group in groups
        if isinstance(group, dict)
    ]
    if actual_sources != expected_sources:
        errors.append("part2 groups source order does not match llm_context.source_groups")

    total = 0
    for group in groups:
        if not isinstance(group, dict):
            errors.append("part2 groups contains a non-object entry")
            continue
        source = _clean_text(group.get("source"))
        articles = _as_list(group.get("articles"))
        total += len(articles)
        if group.get("article_count") != len(articles):
            errors.append(f"{source} article_count {group.get('article_count')} != articles {len(articles)}")
        for idx, article in enumerate(articles, 1):
            if not isinstance(article, dict):
                errors.append(f"{source} article {idx} is not an object")
                continue
            _require_fields(article, ["title", "link", "pub_date_iso", "summary_zh"], f"{source} article {idx}", errors)
            link = _clean_text(article.get("link"))
            source_article = article_by_link.get(link)
            if source_article is None:
                errors.append(f"{source} article {idx} link absent from all_articles: {link}")
                continue
            if article.get("title") != source_article.get("title"):
                errors.append(f"{source} article {idx} title changed for {link}")

    expected_total = validation.get("counts", {}).get("articles") if isinstance(validation.get("counts"), dict) else None
    if expected_total is not None and total != expected_total:
        errors.append(f"part2 total {total} != validation.counts.articles {expected_total}")
    if part2.get("total_articles") != total:
        errors.append(f"part2 total_articles {part2.get('total_articles')} != counted total {total}")
    return errors


def _missing_summary_map(payload: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    containers: Sequence[Any] = (
        payload.get("items"),
        payload.get("articles"),
        payload.get("summaries"),
    )
    result: Dict[str, Dict[str, Any]] = {}
    for container in containers:
        for item in _as_list(container):
            if not isinstance(item, dict):
                continue
            link = _clean_text(item.get("link"))
            if link:
                result[link] = item
        if result:
            break
    for group in _as_list(payload.get("groups")):
        if not isinstance(group, dict):
            continue
        for item in _as_list(group.get("articles")):
            if not isinstance(item, dict):
                continue
            link = _clean_text(item.get("link"))
            if link:
                result[link] = item
    return result


def merge_part2_context(part2_context: Dict[str, Any],
                        missing_payload: Dict[str, Any] | None = None) -> Dict[str, Any]:
    missing_by_link = _missing_summary_map(missing_payload or {})
    groups: List[Dict[str, Any]] = []
    missing_errors: List[str] = []
    total = 0
    for group in _as_list(part2_context.get("groups")):
        if not isinstance(group, dict):
            continue
        article_entries: List[Dict[str, Any]] = []
        for article in _as_list(group.get("articles")):
            if not isinstance(article, dict):
                continue
            link = _clean_text(article.get("link"))
            if article.get("needs_summary") is False:
                summary_zh = _clean_text(article.get("summary_zh"))
                noise_bucket = article.get("noise_bucket", "cached")
                event_key_value = article.get("event_key", "")
            else:
                missing = missing_by_link.get(link)
                summary_zh = _clean_text(missing.get("summary_zh")) if missing else ""
                noise_bucket = missing.get("noise_bucket", "covered") if missing else "covered"
                event_key_value = missing.get("event_key", "") if missing else ""
            if not summary_zh:
                missing_errors.append(link or "<missing link>")
                continue
            article_entries.append({
                "title": article.get("title", ""),
                "link": link,
                "pub_date_iso": article.get("pub_date_iso", ""),
                "summary_zh": summary_zh,
                "noise_bucket": noise_bucket,
                "event_key": event_key_value,
            })
        total += len(article_entries)
        groups.append({
            "source": group.get("source", ""),
            "status": group.get("status"),
            "article_count": len(article_entries),
            "error_text": group.get("error_text"),
            "articles": article_entries,
        })
    if missing_errors:
        raise ValueError(f"missing Part 2 summaries for links: {missing_errors}")
    return {
        "groups": groups,
        "total_articles": total,
    }


def validate_handoffs(context: Dict[str, Any],
                      validation: Dict[str, Any],
                      part1: Dict[str, Any],
                      part2: Dict[str, Any],
                      report_path: str | Path | None = None) -> List[str]:
    errors = audit_artifacts(context, validation)["errors"]
    errors.extend(validate_part1(context, part1))
    errors.extend(validate_part2(context, validation, part2))
    if report_path is not None and str(report_path).endswith(".failed.md"):
        errors.append("report_path must not be a failed report")
    return errors


def assemble_markdown(context: Dict[str, Any], validation: Dict[str, Any], part1: Dict[str, Any], part2: Dict[str, Any]) -> str:
    meta = context.get("meta", {}) if isinstance(context.get("meta"), dict) else {}
    date = meta.get("date") or "unknown-date"
    counts = validation.get("counts", {}) if isinstance(validation.get("counts"), dict) else {}
    configured = counts.get("configured", len(_group_sources(context)))
    total = counts.get("articles", len(_article_map(context)))
    lines: List[str] = [
        f"# DailyNews · {date}",
        "",
        f"> 数据来源：{configured}个RSS源，共获取 {total} 篇文章",
    ]

    error_groups = [
        group for group in _as_list(part2.get("groups"))
        if isinstance(group, dict) and group.get("status") == "error"
    ]
    if error_groups:
        failure_text = "；".join(
            f"{group.get('source')} ({group.get('error_text') or '抓取失败'})"
            for group in error_groups
        )
        lines.append(f"> 抓取异常：{failure_text}")

    lines.extend(["", "## Part 1：当日 TOP 30", ""])
    items = _as_list(part1.get("items"))
    if not items:
        lines.extend(["本次没有可进入 Part 1 的文章。", ""])
    for item in items:
        lines.append(f"{item.get('rank')}. [{item.get('title')}]({item.get('link')})")
        lines.append(f"   - 来源：{item.get('source')}")
        lines.append(f"   - 时间：{item.get('pub_date_utc')}")
        lines.append(f"   - 摘要：{item.get('summary_zh')}")
        also_sources = _as_list(item.get("also_sources"))
        if also_sources:
            also_text = "；".join(
                f"{entry.get('source')}: {entry.get('title')}"
                for entry in also_sources
                if isinstance(entry, dict)
            )
            if also_text:
                lines.append(f"   - 相关来源：{also_text}")
        lines.append("")

    lines.extend(["## Part 2：按来源分组", ""])
    for group in _as_list(part2.get("groups")):
        if not isinstance(group, dict):
            continue
        lines.append(f"### {group.get('source')} ({group.get('article_count', 0)} 篇)")
        if group.get("status") == "error":
            lines.append(f"抓取状态：{group.get('error_text') or '抓取失败'}")
            lines.append("")
        articles = _as_list(group.get("articles"))
        if not articles:
            lines.append("无文章")
            lines.append("")
            continue
        for idx, article in enumerate(articles, 1):
            lines.append(f"{idx}. [{article.get('title')}]({article.get('link')})")
            lines.append(f"   - 时间：{article.get('pub_date_iso')}")
            lines.append(f"   - 摘要：{article.get('summary_zh')}")
            lines.append("")

    lines.extend([
        "## 统计检查",
        "",
        f"- Part 1 文章数：{len(items)}",
        f"- Part 2 分组数：{len(_as_list(part2.get('groups')))}",
        f"- Part 2 文章总数：{part2.get('total_articles')}",
    ])
    return "\n".join(lines).rstrip() + "\n"


def update_cache(cache_path: str | Path,
                 context: Dict[str, Any],
                 part1: Dict[str, Any],
                 part2: Dict[str, Any]) -> Dict[str, Any]:
    path = Path(cache_path)
    cache = load_cache(path)
    article_by_link = _article_map(context)
    summary_items: Dict[str, Dict[str, Any]] = {}
    for group in _as_list(part2.get("groups")):
        if not isinstance(group, dict):
            continue
        for article in _as_list(group.get("articles")):
            if isinstance(article, dict) and article.get("link"):
                summary_items[str(article["link"])] = {
                    "link": article.get("link", ""),
                    "summary_zh": article.get("summary_zh", ""),
                    "noise_bucket": article.get("noise_bucket", "covered"),
                    "event_key": event_key(article),
                }
    for item in _as_list(part1.get("items")):
        if isinstance(item, dict) and item.get("link"):
            summary_items[str(item["link"])] = {
                "link": item.get("link", ""),
                "summary_zh": item.get("summary_zh", ""),
                "noise_bucket": item.get("noise_bucket", "selected"),
                "event_key": event_key(item),
            }
    update_entries(cache, article_by_link, summary_items.values())
    write_cache(path, cache)
    entries = cache.get("entries", {})
    return {"path": str(path), "entries": len(entries)}


def review_report(report_path: str | Path,
                  context: Dict[str, Any],
                  validation: Dict[str, Any],
                  part1: Dict[str, Any],
                  part2: Dict[str, Any]) -> Dict[str, Any]:
    errors = validate_handoffs(context, validation, part1, part2, report_path)
    path = Path(report_path)
    if not path.exists():
        errors.append(f"report does not exist: {path}")
        return {"passed": False, "errors": errors}
    text = path.read_text(encoding="utf-8")
    for group in _as_list(part2.get("groups")):
        if not isinstance(group, dict):
            continue
        for article in _as_list(group.get("articles")):
            if not isinstance(article, dict):
                continue
            link = _clean_text(article.get("link"))
            title = _clean_text(article.get("title"))
            if link and link not in text:
                errors.append(f"report missing link: {link}")
            if title and title not in text:
                errors.append(f"report missing title: {title}")
    for item in _as_list(part1.get("items")):
        if isinstance(item, dict):
            link = _clean_text(item.get("link"))
            if link and link not in text:
                errors.append(f"report missing Part 1 link: {link}")
    return {"passed": not errors, "errors": errors}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="DailyNews editorial runtime helpers.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    audit = subparsers.add_parser("audit")
    audit.add_argument("--llm-context", required=True)
    audit.add_argument("--validation", required=True)

    shortlist = subparsers.add_parser("shortlist-context")
    shortlist.add_argument("--llm-context", required=True)
    shortlist.add_argument("--shortlist", required=True)
    shortlist.add_argument("--output", required=True)

    merge_part2 = subparsers.add_parser("merge-part2")
    merge_part2.add_argument("--part2-context", required=True)
    merge_part2.add_argument("--missing")
    merge_part2.add_argument("--output", required=True)

    assemble = subparsers.add_parser("assemble")
    assemble.add_argument("--llm-context", required=True)
    assemble.add_argument("--validation", required=True)
    assemble.add_argument("--part1", required=True)
    assemble.add_argument("--part2", required=True)
    assemble.add_argument("--output", required=True)
    assemble.add_argument("--cache-path")
    assemble.add_argument("--no-cache", action="store_true")

    review = subparsers.add_parser("review")
    review.add_argument("--llm-context", required=True)
    review.add_argument("--validation", required=True)
    review.add_argument("--part1", required=True)
    review.add_argument("--part2", required=True)
    review.add_argument("--report", required=True)

    cache = subparsers.add_parser("update-cache")
    cache.add_argument("--llm-context", required=True)
    cache.add_argument("--part1", required=True)
    cache.add_argument("--part2", required=True)
    cache.add_argument("--cache-path")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "audit":
            result = audit_artifacts(load_json(args.llm_context), load_json(args.validation))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["passed"] else 20

        if args.command == "shortlist-context":
            output = build_shortlist_context(load_json(args.llm_context), load_json(args.shortlist))
            write_json(args.output, output)
            print(args.output)
            return 0

        if args.command == "merge-part2":
            missing_payload = load_json(args.missing) if args.missing else {}
            output = merge_part2_context(load_json(args.part2_context), missing_payload)
            write_json(args.output, output)
            print(args.output)
            return 0

        if args.command == "assemble":
            context = load_json(args.llm_context)
            validation = load_json(args.validation)
            part1 = load_json(args.part1)
            part2 = load_json(args.part2)
            errors = validate_handoffs(context, validation, part1, part2, args.output)
            if errors:
                print(json.dumps({"passed": False, "errors": errors}, ensure_ascii=False, indent=2), file=sys.stderr)
                return 20
            write_text(args.output, assemble_markdown(context, validation, part1, part2))
            if not args.no_cache:
                cache_path = args.cache_path or default_cache_path(args.llm_context)
                update_cache(cache_path, context, part1, part2)
            print(args.output)
            return 0

        if args.command == "review":
            result = review_report(
                args.report,
                load_json(args.llm_context),
                load_json(args.validation),
                load_json(args.part1),
                load_json(args.part2),
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["passed"] else 20

        if args.command == "update-cache":
            result = update_cache(
                args.cache_path or default_cache_path(args.llm_context),
                load_json(args.llm_context),
                load_json(args.part1),
                load_json(args.part2),
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 40
    return 40


if __name__ == "__main__":
    raise SystemExit(main())
