"""Output and dedup helpers for rss_news_monitor.py."""

from __future__ import annotations

import json
import os
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .text import dedup_link_key


def dedup_articles(articles: List[Dict]) -> List[Dict]:
    """Deduplicate articles by normalized link."""
    seen = set()
    deduped = []
    for article in articles:
        key = dedup_link_key(article["link"])
        if key and key not in seen:
            seen.add(key)
            deduped.append(article)
        elif not key:
            deduped.append(article)
    return deduped


def output_json(
    articles: List[Dict],
    hours: int = 24,
    feed_list: Optional[List[Dict]] = None,
    feed_status: Optional[Dict[str, Optional[str]]] = None,
    input_mode: str = "feeds.json",
    config_snapshot: Optional[Dict[str, Any]] = None,
) -> None:
    """Output articles as JSON for downstream processing."""
    articles.sort(key=lambda article: article["pub_date"], reverse=True)
    by_source = defaultdict(int)
    for article in articles:
        by_source[article["source"]] += 1

    unique_sources = sorted(by_source.keys())
    generated_at = datetime.now(timezone.utc)
    output = {
        "meta": {
            "generated_at_utc": generated_at.isoformat().replace("+00:00", "Z"),
            "run_id": f"rss-{generated_at.strftime('%Y%m%dT%H%M%SZ')}-{os.urandom(4).hex()}",
            "input_mode": input_mode,
            "feed_count_expected": len(feed_list) if feed_list is not None else None,
        },
        "hours": hours,
        "count": len(articles),
        "unique_source_count": len(unique_sources),
        "unique_sources": unique_sources,
        "articles": [
            {
                "source": article["source"],
                "title": article["title"],
                "link": article["link"],
                "pub_date": article["pub_date"].isoformat(),
                "summary_en": article["summary_en"],
                "article_text": article.get("article_text", ""),
            }
            for article in articles
        ],
    }

    if feed_list is not None:
        output["configured_feed_count"] = len(feed_list)
        output["configured_feeds"] = [feed["name"] for feed in feed_list]

        feed_results = []
        for feed in feed_list:
            name = feed["name"]
            error = (feed_status or {}).get(name)
            article_count = by_source.get(name, 0)
            if error:
                status = "error"
            elif article_count == 0:
                status = "empty"
            else:
                status = "ok"

            feed_results.append({
                "source": name,
                "url": feed["url"],
                "status": status,
                "error": error,
                "article_count": article_count,
            })

        output["feed_results"] = feed_results

    if config_snapshot is not None:
        output["runtime_config"] = config_snapshot

    print(json.dumps(output, ensure_ascii=False, indent=2))


def output_text_grouped(
    articles: List[Dict],
    hours: int = 24,
    feed_list: Optional[List[Dict]] = None,
) -> None:
    """Output articles grouped by source, with each group sorted by time."""
    if not articles:
        print(f"\nNo news found in the past {hours} hours from any feed.")
        return

    print("=" * 70)
    print(f"RSS News - Past {hours} Hours | {len(articles)} articles")
    print("=" * 70)

    by_source = defaultdict(list)
    for article in articles:
        by_source[article["source"]].append(article)

    for source in by_source:
        by_source[source].sort(key=lambda article: article["pub_date"], reverse=True)

    source_order = sorted(by_source.keys(), key=lambda source: -len(by_source[source]))

    if feed_list:
        all_source_names = [feed["name"] for feed in feed_list]
        for name in all_source_names:
            if name not in by_source:
                source_order.append(name)

    global_idx = 1
    for source in source_order:
        source_articles = by_source.get(source, [])
        print(f"\n--- [{source}] ({len(source_articles)}篇) ---\n")

        if not source_articles:
            print("  (No articles in this time range)")
            print()
            continue

        for article in source_articles:
            print(f"  {global_idx}. {article['title']}")
            print(f"     Time:  {article['pub_date_str']}")
            print(f"     Link:  {article['link']}")
            if article["summary_en"]:
                print(f"     Summary: {article['summary_en']}")
            print()
            global_idx += 1

    print("=" * 70)
    print(f"Total: {len(articles)} article(s) from {len(by_source)} source(s)")


def output_summary(
    feed_list: List[Dict],
    feed_status: Dict[str, Optional[str]],
    articles: List[Dict],
    hours: int = 24,
) -> None:
    """Output a summary / health check of feed status."""
    by_source = defaultdict(int)
    for article in articles:
        by_source[article["source"]] += 1

    print("=" * 60)
    print(f"Feed Health Summary (past {hours}h)")
    print("=" * 60)
    print()

    total_articles = 0
    ok_count = 0
    warn_count = 0
    fail_count = 0

    for feed in feed_list:
        name = feed["name"]
        error = feed_status.get(name)
        count = by_source.get(name, 0)
        total_articles += count

        if error:
            print(f"  ❌ {name}: FAILED ({error})")
            fail_count += 1
        elif count == 0:
            print(f"  ⚠️  {name}: 0 articles")
            warn_count += 1
        else:
            print(f"  ✅ {name}: {count} articles")
            ok_count += 1

    print()
    print(
        f"  Total: {total_articles} articles | "
        f"✅ {ok_count} OK | ⚠️ {warn_count} empty | ❌ {fail_count} failed"
    )
    print()
