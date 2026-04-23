"""Runtime config loading for fetch and render settings."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Optional, Tuple


DEFAULT_PIPELINE_CONFIG_FILE = Path(__file__).resolve().parents[2] / "pipeline_config.json"
DEFAULT_SHORT_SUMMARY_THRESHOLD = 80
DEFAULT_PAGE_FALLBACK_CAP = 300
DEFAULT_PART1_SUMMARY_MAX_CHARS = 200
DEFAULT_PART2_SUMMARY_MAX_CHARS = 200
DEFAULT_ARTICLE_TEXT_ENABLED = True
DEFAULT_ARTICLE_TEXT_MAX_WORDS = 300
DEFAULT_ARTICLE_TEXT_MAX_WORKERS = 4


def _validated_non_negative_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(f"{label} must be a non-negative integer")
    return value


def _validated_positive_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def load_pipeline_config(config_path: str | Path | None = None) -> Tuple[Dict[str, Any], Path]:
    """Load the repo-level pipeline config and apply defaults."""
    path = Path(config_path).expanduser() if config_path else DEFAULT_PIPELINE_CONFIG_FILE
    resolved_path = path.resolve()

    try:
        payload = json.loads(resolved_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise FileNotFoundError(f"pipeline config not found: {resolved_path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"pipeline config is not valid JSON: {resolved_path}") from exc

    if not isinstance(payload, dict):
        raise ValueError("pipeline config root must be a JSON object")

    summary_payload = payload.get("summary_enrichment", {})
    if summary_payload is None:
        summary_payload = {}
    if not isinstance(summary_payload, dict):
        raise ValueError("summary_enrichment must be a JSON object")

    summary_config = {
        "short_summary_threshold": DEFAULT_SHORT_SUMMARY_THRESHOLD,
        "page_fallback_cap": DEFAULT_PAGE_FALLBACK_CAP,
    }
    if "short_summary_threshold" in summary_payload:
        summary_config["short_summary_threshold"] = _validated_non_negative_int(
            summary_payload["short_summary_threshold"],
            "summary_enrichment.short_summary_threshold",
        )
    if "page_fallback_cap" in summary_payload:
        summary_config["page_fallback_cap"] = _validated_positive_int(
            summary_payload["page_fallback_cap"],
            "summary_enrichment.page_fallback_cap",
        )

    article_text_payload = payload.get("article_text", {})
    if article_text_payload is None:
        article_text_payload = {}
    if not isinstance(article_text_payload, dict):
        raise ValueError("article_text must be a JSON object")

    article_text_config = {
        "enabled": DEFAULT_ARTICLE_TEXT_ENABLED,
        "max_words": DEFAULT_ARTICLE_TEXT_MAX_WORDS,
        "max_workers": DEFAULT_ARTICLE_TEXT_MAX_WORKERS,
    }
    if "enabled" in article_text_payload:
        enabled_value = article_text_payload["enabled"]
        if not isinstance(enabled_value, bool):
            raise ValueError("article_text.enabled must be a boolean")
        article_text_config["enabled"] = enabled_value
    if "max_words" in article_text_payload:
        article_text_config["max_words"] = _validated_positive_int(
            article_text_payload["max_words"],
            "article_text.max_words",
        )
    if "max_workers" in article_text_payload:
        article_text_config["max_workers"] = _validated_positive_int(
            article_text_payload["max_workers"],
            "article_text.max_workers",
        )

    render_payload = payload.get("render", {})
    if render_payload is None:
        render_payload = {}
    if not isinstance(render_payload, dict):
        raise ValueError("render must be a JSON object")

    render_config = {
        "part1_summary_max_chars": DEFAULT_PART1_SUMMARY_MAX_CHARS,
        "part2_summary_max_chars": DEFAULT_PART2_SUMMARY_MAX_CHARS,
    }
    if "part1_summary_max_chars" in render_payload:
        render_config["part1_summary_max_chars"] = _validated_non_negative_int(
            render_payload["part1_summary_max_chars"],
            "render.part1_summary_max_chars",
        )
    if "part2_summary_max_chars" in render_payload:
        render_config["part2_summary_max_chars"] = _validated_non_negative_int(
            render_payload["part2_summary_max_chars"],
            "render.part2_summary_max_chars",
        )

    return {
        "summary_enrichment": summary_config,
        "article_text": article_text_config,
        "render": render_config,
    }, resolved_path


def resolve_article_text_settings(
    pipeline_config: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Resolve effective article_text settings from pipeline config."""
    enabled = DEFAULT_ARTICLE_TEXT_ENABLED
    max_words = DEFAULT_ARTICLE_TEXT_MAX_WORDS
    max_workers = DEFAULT_ARTICLE_TEXT_MAX_WORKERS
    if pipeline_config:
        article_text_config = pipeline_config.get("article_text", {})
        if isinstance(article_text_config, dict):
            raw_enabled = article_text_config.get("enabled")
            if isinstance(raw_enabled, bool):
                enabled = raw_enabled
            raw_max_words = article_text_config.get("max_words")
            if isinstance(raw_max_words, int) and not isinstance(raw_max_words, bool) and raw_max_words > 0:
                max_words = raw_max_words
            raw_max_workers = article_text_config.get("max_workers")
            if isinstance(raw_max_workers, int) and not isinstance(raw_max_workers, bool) and raw_max_workers > 0:
                max_workers = raw_max_workers
    return {
        "enabled": enabled,
        "max_words": max_words,
        "max_workers": max_workers,
    }


def resolve_page_fallback_cap(max_summary: int, pipeline_config: Optional[Dict[str, Any]] = None) -> int:
    """Resolve the runtime fallback cap after applying CLI max-summary."""
    page_fallback_cap = DEFAULT_PAGE_FALLBACK_CAP
    if pipeline_config:
        summary_config = pipeline_config.get("summary_enrichment", {})
        if isinstance(summary_config, dict):
            value = summary_config.get("page_fallback_cap")
            if isinstance(value, int) and not isinstance(value, bool) and value > 0:
                page_fallback_cap = value
    if max_summary <= 0:
        return page_fallback_cap
    return min(max_summary, page_fallback_cap)


def build_runtime_config_snapshot(
    pipeline_config: Dict[str, Any],
    config_path: str | Path,
    *,
    max_summary: int,
) -> Dict[str, Any]:
    """Build the config snapshot persisted into raw.json."""
    summary_config = pipeline_config.get("summary_enrichment", {})
    if not isinstance(summary_config, dict):
        summary_config = {}
    render_config = pipeline_config.get("render", {})
    if not isinstance(render_config, dict):
        render_config = {}
    article_text_settings = resolve_article_text_settings(pipeline_config)
    short_summary_threshold = summary_config.get(
        "short_summary_threshold",
        DEFAULT_SHORT_SUMMARY_THRESHOLD,
    )
    page_fallback_cap = summary_config.get(
        "page_fallback_cap",
        DEFAULT_PAGE_FALLBACK_CAP,
    )
    part1_summary_max_chars = render_config.get(
        "part1_summary_max_chars",
        DEFAULT_PART1_SUMMARY_MAX_CHARS,
    )
    part2_summary_max_chars = render_config.get(
        "part2_summary_max_chars",
        DEFAULT_PART2_SUMMARY_MAX_CHARS,
    )
    return {
        "config_path": str(Path(config_path).expanduser().resolve()),
        "summary_enrichment": {
            "short_summary_threshold": int(short_summary_threshold),
            "page_fallback_cap": int(page_fallback_cap),
            "effective_page_fallback_cap": resolve_page_fallback_cap(
                max_summary,
                pipeline_config,
            ),
        },
        "article_text": {
            "enabled": bool(article_text_settings["enabled"]),
            "max_words": int(article_text_settings["max_words"]),
            "max_workers": int(article_text_settings["max_workers"]),
        },
        "render": {
            "part1_summary_max_chars": int(part1_summary_max_chars),
            "part2_summary_max_chars": int(part2_summary_max_chars),
        },
    }
