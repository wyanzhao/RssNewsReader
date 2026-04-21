"""TypedDict shapes for the JSON artifacts the pipeline produces.

These are documentation-grade hints — the validator stays the source of truth
for what is actually rejected. Importing TypedDicts gives editors and future
contributors a structured handle on raw.json / validation.json /
llm_context.json without changing any runtime behaviour.

Stage 3 may switch from string ``blocking_reasons`` to a structured
``blocking_reasons_v2`` (see plans/rippling-puzzling-rose.md). To keep the
contract surface stable, the names below match what AGENTS.md lists.
"""

from __future__ import annotations

from typing import Any, Dict, List, Literal, Optional, TypedDict


# --- Status enums ------------------------------------------------------------

STATUS_OK: Literal["ok"] = "ok"
STATUS_EMPTY: Literal["empty"] = "empty"
STATUS_ERROR: Literal["error"] = "error"

ErrorPolicy = Literal["block", "warn"]
FeedStatus = Literal["ok", "empty", "error"]


# --- raw.json ----------------------------------------------------------------

class RawMeta(TypedDict, total=False):
    generated_at_utc: str
    run_id: str
    input_mode: str
    feed_count_expected: int


class RawArticle(TypedDict, total=False):
    source: str
    title: str
    link: str
    pub_date: str          # ISO 8601 with offset
    summary_en: str


class FeedResult(TypedDict, total=False):
    source: str
    url: str
    status: FeedStatus
    error: Optional[str]
    article_count: int


class RawDocument(TypedDict, total=False):
    meta: RawMeta
    hours: int
    count: int
    unique_source_count: int
    unique_sources: List[str]
    configured_feed_count: int
    configured_feeds: List[str]
    feed_results: List[FeedResult]
    articles: List[RawArticle]


# --- validation.json ---------------------------------------------------------

class ValidationCounts(TypedDict, total=False):
    configured: int
    results: int
    ok: int
    empty: int
    error: int
    articles: int
    blocking_error: int
    warn_error: int


class ValidationPolicy(TypedDict, total=False):
    block_on_error_count: bool
    block_on_zero_articles: bool
    block_on_feed_results_mismatch: bool
    empty_is_warning_only: bool
    unique_source_count_is_observational: bool
    warn_error_sources: List[str]


class ValidationDocument(TypedDict, total=False):
    passed: bool
    blocking_reasons: List[str]
    warnings: List[str]
    counts: ValidationCounts
    policy: ValidationPolicy
    feed_results: List[FeedResult]
    meta: Dict[str, Any]


# --- llm_context.json --------------------------------------------------------

ALLOWED_AUDIT_FLAGS = (
    "major_company",
    "business_signal",
    "security_signal",
    "breakthrough_signal",
    "launch_signal",
    "speculation",
    "noise",
    "hard_noise",
    "funding_or_deal_ge_100m",
)


class LlmContextMeta(TypedDict, total=False):
    date: str
    generated_at_utc: Optional[str]
    run_id: Optional[str]
    report_path: str


class LlmContextValidation(TypedDict, total=False):
    passed: bool
    blocking_reasons: List[str]
    warnings: List[str]
    counts: ValidationCounts
    policy: ValidationPolicy


class LlmArticle(TypedDict, total=False):
    source: str
    title: str
    link: str
    pub_date_utc: str
    pub_date_iso: str
    summary_en: str
    heuristic_score: float
    audit_flags: List[str]
    amount_millions: float


class LlmSourceGroup(TypedDict, total=False):
    source: str
    url: str
    status: Optional[FeedStatus]
    article_count: int
    articles: List[LlmArticle]


class LlmContextDocument(TypedDict, total=False):
    meta: LlmContextMeta
    validation: LlmContextValidation
    candidate_articles: List[LlmArticle]
    all_articles: List[LlmArticle]
    source_groups: List[LlmSourceGroup]


# --- rss_daily_report.py --json-output payload -------------------------------

class PipelineOutput(TypedDict):
    report_date: str
    run_dir: str
    raw_path: str
    validation_path: str
    llm_context_path: str
    report_path: str
    validation_passed: bool
    validator_exit_code: int
