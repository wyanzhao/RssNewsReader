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
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence

from _common.editorial import (  # noqa: E402
    PART1_MAX_ITEMS,
    PART1_SUMMARY_HARD_CAP,
    PART2_SUMMARY_HARD_CAP,
    summary_lint_errors as _summary_lint_errors,
)
from _common.editorial_cache import (  # noqa: E402
    clean_text,
    default_cache_path,
    event_key,
    load_cache,
    lookup_entry,
    update_entries,
    write_cache,
)
from _common.fsio import atomic_write_text, file_lock  # noqa: E402
from _common.seen_links import (  # noqa: E402
    default_seen_links_path,
    load_seen_links,
    prune_seen_links,
    record_reported_links,
    write_seen_links,
)


def load_json(path: str | Path) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return data


def write_json(path: str | Path, payload: Dict[str, Any]) -> None:
    atomic_write_text(path, json.dumps(payload, ensure_ascii=False, indent=2))


def write_text(path: str | Path, text: str) -> None:
    atomic_write_text(path, text)


def _as_list(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def _clean_text(value: Any) -> str:
    return clean_text(value)


def _md_link_text(value: Any) -> str:
    """Escape square brackets so a feed title cannot break the [title](link) form."""
    return _clean_text(value).replace("[", "\\[").replace("]", "\\]")


def _md_link(title: Any, link: Any) -> str:
    target = _clean_text(link)
    if any(ch in target for ch in ("(", ")", " ")):
        target = f"<{target}>"
    return f"[{_md_link_text(title)}]({target})"


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


# How far back the recent-Top-30 continuity roster looks. Cross-day duplicate
# links are already filtered by the seen-links ledger; this roster is for the
# other case — a *new* article covering an event that already ran, which only
# the editor can judge (pure repeat vs. substantive follow-up).
RECENT_TOP30_DAYS = 3


def _recent_top30(cache: Dict[str, Any] | None,
                  now: datetime | None = None) -> List[Dict[str, str]]:
    if not cache:
        return []
    entries = cache.get("entries")
    if not isinstance(entries, dict):
        return []
    now_dt = now or datetime.now(timezone.utc)
    cutoff = now_dt - timedelta(days=RECENT_TOP30_DAYS)
    records: List[Dict[str, str]] = []
    for entry in entries.values():
        if not isinstance(entry, dict):
            continue
        if not _clean_text(entry.get("part1_summary_zh")):
            continue
        try:
            updated = datetime.fromisoformat(str(entry.get("updated_at_utc")))
        except (TypeError, ValueError):
            continue
        if updated.tzinfo is None:
            updated = updated.replace(tzinfo=timezone.utc)
        if updated < cutoff:
            continue
        records.append({
            "title": entry.get("title", ""),
            "source": entry.get("source", ""),
            "event_key": entry.get("event_key", ""),
            "covered_on": updated.date().isoformat(),
        })
    records.sort(key=lambda record: (record["covered_on"], record["event_key"]), reverse=True)
    return records


def build_shortlist_context(context: Dict[str, Any],
                            shortlist: Dict[str, Any],
                            cache: Dict[str, Any] | None = None) -> Dict[str, Any]:
    """Slice the shortlist out of ``all_articles`` and inject cache hits.

    Cache reuse is deterministic-only: when a shortlisted link has a prior
    Part 1 summary in the editorial cache, the article entry gains
    ``cached_summary_zh`` / ``cached_event_key`` so the LLM can reuse it
    without ever reading the cache file itself. ``recent_top30`` lists the
    last few days' Part 1 events so the editor can judge continuity for new
    articles about already-covered events.
    """
    article_by_link = _article_map(context)
    links = _extract_shortlist_links(shortlist)
    missing = [link for link in links if link not in article_by_link]
    if missing:
        raise ValueError(f"shortlist contains links absent from all_articles: {missing}")
    articles: List[Dict[str, Any]] = []
    cache_hits = 0
    for link in links:
        article = dict(article_by_link[link])
        entry = lookup_entry(article, cache)
        cached_summary = _clean_text(entry.get("part1_summary_zh")) if isinstance(entry, dict) else ""
        if cached_summary and _summary_lint_errors(cached_summary, "cached", PART1_SUMMARY_HARD_CAP):
            # A cached summary that would fail the assemble lint (legacy or
            # hand-edited entry) must not be offered for reuse — demote to a
            # normal miss so the editor writes a fresh one.
            print(
                f"WARN: cached part1 summary fails lint, demoted to miss: {link}",
                file=sys.stderr,
            )
            cached_summary = ""
        if cached_summary:
            article["cached_summary_zh"] = cached_summary
            cached_event = _clean_text(entry.get("event_key")) if isinstance(entry, dict) else ""
            if cached_event:
                article["cached_event_key"] = cached_event
            cache_hits += 1
        articles.append(article)
    return {
        "meta": context.get("meta", {}),
        "article_count": len(links),
        "cache_hits": cache_hits,
        "recent_top30": _recent_top30(cache),
        "articles": articles,
    }


def _require_fields(item: Dict[str, Any], fields: Iterable[str], label: str, errors: List[str]) -> None:
    for field in fields:
        value = item.get(field)
        if value is None or value == "":
            errors.append(f"{label} missing {field}")


def validate_part1(context: Dict[str, Any], part1: Dict[str, Any]) -> List[str]:
    """Validate the link-keyed part1 plan.

    Plan items reference articles by ``link`` only; titles, sources, and
    timestamps are joined from ``llm_context.json`` at assemble time, so the
    plan never echoes (and can never corrupt) those authoritative fields.
    """
    errors: List[str] = []
    article_by_link = _article_map(context)
    items = _as_list(part1.get("items"))
    if len(items) > PART1_MAX_ITEMS:
        errors.append(f"part1 items exceed {PART1_MAX_ITEMS} ({len(items)})")
    if "shortfall" not in part1:
        errors.append("part1_plan missing shortfall")
    else:
        shortfall = part1.get("shortfall")
        expected_shortfall = max(0, PART1_MAX_ITEMS - len(items))
        if not isinstance(shortfall, int) or isinstance(shortfall, bool):
            errors.append(f"part1_plan shortfall must be an integer, got {shortfall!r}")
        elif shortfall != expected_shortfall:
            errors.append(
                f"part1_plan shortfall {shortfall} != expected {expected_shortfall} "
                f"({PART1_MAX_ITEMS} - {len(items)} items)"
            )
    # An article may appear in Part 1 exactly once — either as an item's main
    # link or inside one item's also_links, never both, never twice.
    main_links = {
        _clean_text(item.get("link"))
        for item in items
        if isinstance(item, dict) and _clean_text(item.get("link"))
    }
    also_seen: Dict[str, int] = {}
    seen_links: set[str] = set()
    for idx, item in enumerate(items, 1):
        if not isinstance(item, dict):
            errors.append(f"part1 item {idx} is not an object")
            continue
        link = _clean_text(item.get("link"))
        if not link:
            errors.append(f"part1 item {idx} missing link")
            continue
        if link in seen_links:
            errors.append(f"part1 item {idx} duplicates link {link}")
        seen_links.add(link)
        if link not in article_by_link:
            errors.append(f"part1 item {idx} link absent from all_articles: {link}")
        if not _clean_text(item.get("summary_zh")):
            errors.append(f"part1 item {idx} missing summary_zh")
        else:
            errors.extend(_summary_lint_errors(
                item.get("summary_zh"), f"part1 item {idx}", PART1_SUMMARY_HARD_CAP))
        also_links = item.get("also_links")
        if not isinstance(also_links, list):
            errors.append(f"part1 item {idx} missing also_links list")
            continue
        for also_link in also_links:
            cleaned = _clean_text(also_link)
            if not cleaned:
                errors.append(f"part1 item {idx} has an empty also_link")
                continue
            if cleaned == link:
                errors.append(f"part1 item {idx} also_links repeats its own link")
                continue
            if cleaned in main_links:
                errors.append(
                    f"part1 item {idx} also_link duplicates another item's link: {cleaned}"
                )
            if cleaned in also_seen:
                errors.append(
                    f"part1 item {idx} also_link already used by item {also_seen[cleaned]}: {cleaned}"
                )
            else:
                also_seen[cleaned] = idx
            if cleaned not in article_by_link:
                errors.append(f"part1 item {idx} also_link absent from all_articles: {cleaned}")
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
            errors.extend(_summary_lint_errors(
                article.get("summary_zh"), f"{source} article {idx}", PART2_SUMMARY_HARD_CAP))
            link = _clean_text(article.get("link"))
            source_article = article_by_link.get(link)
            if source_article is None:
                errors.append(f"{source} article {idx} link absent from all_articles: {link}")
                continue
            if article.get("title") != source_article.get("title"):
                errors.append(f"{source} article {idx} title changed for {link}")
            if _clean_text(source_article.get("source")) != source:
                errors.append(
                    f"{source} article {idx} link belongs to source "
                    f"{source_article.get('source')}: {link}"
                )

    expected_total = validation.get("counts", {}).get("articles") if isinstance(validation.get("counts"), dict) else None
    if expected_total is not None and total != expected_total:
        errors.append(f"part2 total {total} != validation.counts.articles {expected_total}")
    if part2.get("total_articles") != total:
        errors.append(f"part2 total_articles {part2.get('total_articles')} != counted total {total}")
    return errors


MISSING_SUMMARY_CONTAINER_KEYS = ("missing", "items", "articles", "summaries")


def _sole_list_key(payload: Dict[str, Any]) -> str:
    """The payload's only list-valued top-level key, or ``""`` if not exactly one.

    Used purely to turn an unrecognized container name into a diagnosable
    error instead of a link dump that reads like data loss.
    """
    list_keys = [key for key, value in payload.items() if isinstance(value, list)]
    return list_keys[0] if len(list_keys) == 1 else ""


def _missing_summary_map(payload: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    # "missing" leads because the artifact is named part2_missing_summaries.json
    # and both agents and third-party integrations reach for the filename first.
    containers: Sequence[Any] = tuple(
        payload.get(key) for key in MISSING_SUMMARY_CONTAINER_KEYS
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


def _missing_summary_error(links: List[str],
                           matched: Dict[str, Dict[str, Any]],
                           payload: Dict[str, Any]) -> str:
    detail = f"missing Part 2 summaries for links: {links}"
    if matched or not payload:
        return detail
    found = _sole_list_key(payload)
    if not found or found in MISSING_SUMMARY_CONTAINER_KEYS:
        return detail
    # Nothing matched at all and the payload holds exactly one list: this is a
    # container-name mismatch. Saying so beats dumping every link, which reads
    # like the drafter lost the data it in fact produced.
    return (
        f"{detail}\n"
        f"hint: no summary matched any link; the payload's only list-valued key is "
        f"'{found}', expected one of {list(MISSING_SUMMARY_CONTAINER_KEYS)} or groups[].articles"
    )


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
        raise ValueError(
            _missing_summary_error(missing_errors, missing_by_link, missing_payload or {})
        )
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


def _part1_item_lines(article_by_link: Dict[str, Dict[str, Any]],
                      items: List[Any]) -> List[str]:
    """Render the numbered Part 1 item blocks.

    Shared verbatim by the final report and the top30 digest so the two can
    never diverge. The link-keyed plan carries only editorial fields; title,
    source, and timestamp are joined here from the authoritative llm_context
    articles.
    """
    lines: List[str] = []
    if not items:
        lines.extend(["本次没有可进入 Part 1 的文章。", ""])
        return lines
    for rank, item in enumerate(items, 1):
        if not isinstance(item, dict):
            continue
        link = _clean_text(item.get("link"))
        article = article_by_link.get(link, {})
        lines.append(f"{rank}. {_md_link(article.get('title'), link)}")
        lines.append(f"   - 来源：{article.get('source')}")
        lines.append(f"   - 时间：{article.get('pub_date_utc')}")
        lines.append(f"   - 摘要：{_clean_text(item.get('summary_zh'))}")
        also_text = "；".join(
            f"{article_by_link[also_link].get('source')}: "
            f"{_clean_text(article_by_link[also_link].get('title'))}"
            for also_link in (
                _clean_text(entry) for entry in _as_list(item.get("also_links"))
            )
            if also_link in article_by_link
        )
        if also_text:
            lines.append(f"   - 相关来源：{also_text}")
        lines.append("")
    return lines


def render_top30(context: Dict[str, Any],
                 part1: Dict[str, Any],
                 report_path: str | Path | None = None) -> str:
    """Render the fixed-format Top 30 digest used as the chat-facing reply.

    The orchestrator relays this text verbatim as its final success message,
    so the output format is owned by this deterministic renderer — never by
    the LLM. The trailing report-path line makes the reply self-contained.
    """
    meta = context.get("meta", {}) if isinstance(context.get("meta"), dict) else {}
    date = meta.get("date") or "unknown-date"
    items = _as_list(part1.get("items"))
    lines: List[str] = [f"# DailyNews Top 30 · {date}", ""]
    shortfall = part1.get("shortfall")
    if isinstance(shortfall, int) and not isinstance(shortfall, bool) and shortfall > 0:
        lines.extend([f"> 本日入选 {len(items)} 条（不足 30，缺口 {shortfall}）", ""])
    lines.extend(_part1_item_lines(_article_map(context), items))
    if report_path:
        lines.extend(["---", f"完整报告：{report_path}"])
    return "\n".join(lines).rstrip() + "\n"


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
    lines.extend(_part1_item_lines(_article_map(context), items))

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
            lines.append(f"{idx}. {_md_link(article.get('title'), article.get('link'))}")
            lines.append(f"   - 时间：{article.get('pub_date_iso')}")
            lines.append(f"   - 摘要：{_clean_text(article.get('summary_zh'))}")
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
    with file_lock(path):
        cache = _load_or_rebuild_cache(path)
        result = _update_cache_locked(path, cache, context, part1, part2)
    return result


def _load_or_rebuild_cache(path: Path) -> Dict[str, Any]:
    """Load the editorial cache, rebuilding fresh when it is unreadable.

    Mirrors the ``shortlist-context`` tolerance: a corrupt cache degrades
    cross-run summary reuse but must never fail the run that hit it.
    """
    try:
        return load_cache(path)
    except (OSError, ValueError, json.JSONDecodeError):
        print(f"WARN: rebuilding corrupt editorial cache: {path}", file=sys.stderr)
        return {"version": 1, "entries": {}}


def _update_cache_locked(path: Path,
                         cache: Dict[str, Any],
                         context: Dict[str, Any],
                         part1: Dict[str, Any],
                         part2: Dict[str, Any]) -> Dict[str, Any]:
    article_by_link = _article_map(context)
    # Part 1 and Part 2 summaries have different length/style targets, so both
    # are kept per link and update_entries merges them into separate fields.
    summary_items: List[Dict[str, Any]] = []
    for group in _as_list(part2.get("groups")):
        if not isinstance(group, dict):
            continue
        for article in _as_list(group.get("articles")):
            if isinstance(article, dict) and article.get("link"):
                summary_items.append({
                    "link": article.get("link", ""),
                    "summary_zh": article.get("summary_zh", ""),
                    "noise_bucket": article.get("noise_bucket", "covered"),
                    "event_key": event_key(article),
                    "part": "part2",
                })
    for item in _as_list(part1.get("items")):
        if isinstance(item, dict) and item.get("link"):
            summary_items.append({
                "link": item.get("link", ""),
                "summary_zh": item.get("summary_zh", ""),
                "noise_bucket": item.get("noise_bucket", "selected"),
                "event_key": item.get("event_key", ""),
                "part": "part1",
            })
    update_entries(cache, article_by_link, summary_items)
    write_cache(path, cache)
    entries = cache.get("entries", {})
    return {"path": str(path), "entries": len(entries)}


def update_seen_links_ledger(ledger_path: str | Path,
                             context: Dict[str, Any]) -> Dict[str, Any]:
    """Mark every article of this published report as seen.

    Runs only from ``assemble`` — the one step that actually writes a success
    report — so blocked or failed days never mark their articles as covered.
    A corrupt ledger is rebuilt fresh rather than blocking the report write.
    """
    meta = context.get("meta", {}) if isinstance(context.get("meta"), dict) else {}
    report_date = _clean_text(meta.get("date"))
    if not report_date:
        return {"path": str(ledger_path), "entries": 0, "skipped": "no report date"}
    with file_lock(ledger_path):
        try:
            entries = load_seen_links(ledger_path)
        except (OSError, ValueError, json.JSONDecodeError):
            print(f"WARN: rebuilding corrupt seen-links ledger: {ledger_path}", file=sys.stderr)
            entries = {}
        record_reported_links(entries, _article_map(context).keys(), report_date)
        prune_seen_links(entries, report_date)
        write_seen_links(ledger_path, entries)
    return {"path": str(ledger_path), "entries": len(entries)}


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
            # Titles are bracket-escaped at render time, so compare against
            # the same escaped form.
            title = _md_link_text(article.get("title"))
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
    shortlist.add_argument("--cache-path")
    shortlist.add_argument("--no-cache", action="store_true")

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
    assemble.add_argument("--seen-links-path")
    assemble.add_argument("--no-seen-links", action="store_true")

    review = subparsers.add_parser("review")
    review.add_argument("--llm-context", required=True)
    review.add_argument("--validation", required=True)
    review.add_argument("--part1", required=True)
    review.add_argument("--part2", required=True)
    review.add_argument("--report", required=True)

    top30 = subparsers.add_parser("top30")
    top30.add_argument("--llm-context", required=True)
    top30.add_argument("--part1", required=True)
    top30.add_argument("--report-path")
    top30.add_argument("--output")

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
            cache: Dict[str, Any] | None = None
            if not args.no_cache:
                cache_source = args.cache_path or default_cache_path(args.llm_context)
                try:
                    cache = load_cache(cache_source)
                except (OSError, ValueError, json.JSONDecodeError):
                    # Cache injection is a best-effort optimization; a corrupt
                    # cache must not block the shortlist step.
                    cache = None
            output = build_shortlist_context(
                load_json(args.llm_context),
                load_json(args.shortlist),
                cache,
            )
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
            # Post-write bookkeeping is best-effort: the written report is the
            # deliverable and `review` still validates it afterwards. A cache
            # or ledger failure only degrades cross-run reuse/dedup and must
            # not turn a successfully written report into a failed run.
            if not args.no_cache:
                cache_path = args.cache_path or default_cache_path(args.llm_context)
                try:
                    update_cache(cache_path, context, part1, part2)
                except Exception as exc:
                    print(f"WARN: editorial cache update failed: {exc}", file=sys.stderr)
            if not args.no_seen_links:
                ledger_path = args.seen_links_path or default_seen_links_path(args.llm_context)
                try:
                    update_seen_links_ledger(ledger_path, context)
                except Exception as exc:
                    print(f"WARN: seen-links ledger update failed: {exc}", file=sys.stderr)
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

        if args.command == "top30":
            context = load_json(args.llm_context)
            part1 = load_json(args.part1)
            errors = validate_part1(context, part1)
            if errors:
                print(json.dumps({"passed": False, "errors": errors}, ensure_ascii=False, indent=2), file=sys.stderr)
                return 20
            text = render_top30(context, part1, args.report_path)
            if args.output:
                write_text(args.output, text)
            sys.stdout.write(text)
            return 0

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
