"""Network fetch helpers for rss_news_monitor.py."""

from __future__ import annotations

import errno
import http.client
import ipaddress
import socket
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from http.client import HTTPException
from typing import Callable, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request

from .feed_parse import extract_html_summary, parse_feed
from .runtime_config import (
    DEFAULT_ARTICLE_TEXT_MAX_WORDS,
    DEFAULT_ARTICLE_TEXT_MAX_WORKERS,
    DEFAULT_PAGE_FALLBACK_CAP,
    DEFAULT_SHORT_SUMMARY_THRESHOLD,
    resolve_article_text_settings,
    resolve_page_fallback_cap,
)

SHORT_SUMMARY_THRESHOLD = DEFAULT_SHORT_SUMMARY_THRESHOLD
FALLBACK_SUMMARY_CAP = DEFAULT_PAGE_FALLBACK_CAP

DEFAULT_USER_AGENT = "Mozilla/5.0 (compatible; RSS Monitor/3.0)"

# Hard cap on any single response body (feed XML or article HTML). A hostile
# or broken feed must not be able to exhaust memory via an unbounded read.
MAX_RESPONSE_BYTES = 20 * 1024 * 1024
_READ_CHUNK_BYTES = 64 * 1024


class BlockedUrlError(ValueError):
    """Raised when a fetch URL fails the scheme or internal-host checks."""


class ResponseTooLargeError(ValueError):
    """Raised when a response body exceeds ``MAX_RESPONSE_BYTES``."""


# Explicit denylist instead of ``ipaddress.is_private``: that flag also covers
# the benchmarking (198.18.0.0/15) and CGNAT (100.64.0.0/10) ranges, which
# local fake-IP proxies (Clash, Surge) hand out for every DNS lookup. Blocking
# those ranges made the pipeline unable to fetch any feed at all on machines
# behind such a proxy. The ranges below are the ones that actually host
# services a poisoned feed must not be able to probe — loopback, RFC 1918
# LANs, and link-local, which includes the 169.254.169.254 cloud-metadata
# endpoint.
_BLOCKED_NETWORKS_V4 = (
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("169.254.0.0/16"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("255.255.255.255/32"),
)
_BLOCKED_NETWORKS_V6 = (
    ipaddress.ip_network("::/128"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("fe80::/10"),
    ipaddress.ip_network("fc00::/7"),
    ipaddress.ip_network("ff00::/8"),
)


def _is_blocked_address(address) -> bool:
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
        address = address.ipv4_mapped
    networks = (
        _BLOCKED_NETWORKS_V4
        if isinstance(address, ipaddress.IPv4Address)
        else _BLOCKED_NETWORKS_V6
    )
    return any(address in network for network in networks)


# ---------------------------------------------------------------------------
# Untrusted-URL policy enforcement
# ---------------------------------------------------------------------------
#
# Feed and article URLs are untrusted input, so the policy is enforced at
# three layers that all share the same denylist:
#
# 1. ``validate_fetch_url`` — pre-flight scheme/host check on the original URL
#    (and, via the redirect handler, on every redirect hop);
# 2. ``_GuardedRedirectHandler`` — re-applies that check to each redirect
#    destination *before* a follow-up request is built, so a public URL cannot
#    302 the fetch onto a forbidden network;
# 3. ``_resolve_and_pin`` — re-resolves the host at connect time, validates the
#    actual DNS answer, and the connection dials exactly the validated address.
#    Because nothing re-resolves after that, a DNS answer that changes between
#    any earlier check and the connection (rebinding) cannot smuggle the fetch
#    onto a forbidden network.
#
# Name resolution is an explicit injectable boundary (``resolver``): the
# production default wraps ``socket.getaddrinfo``, while the offline test
# suite supplies deterministic public / private / failure / multi-address
# answers, so the focused fetch tests behave identically with or without host
# DNS access.

#: Injectable resolver: host name -> list of IP-literal strings.
ResolveFn = Callable[[str], List[str]]


def default_resolve(host: str) -> List[str]:
    """Production resolver: all TCP addresses for ``host``, deduplicated.

    Raises ``OSError`` (e.g. ``socket.gaierror``) when the name does not
    resolve; callers let that surface as the fetch's own connection error
    rather than treating it as a policy block.
    """
    infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    addresses: List[str] = []
    for info in infos:
        addr = info[4][0].split("%")[0]
        if addr not in addresses:
            addresses.append(addr)
    return addresses


def _check_resolved_addresses(host: str, addresses: List[str], url: str) -> None:
    """Reject a DNS answer containing any denylisted address.

    A mixed public/internal answer is refused outright: round-robin between a
    legitimate record and an internal one is exactly how a poisoned answer
    slips past a check that only looked at the first record.
    """
    for addr in addresses:
        address = ipaddress.ip_address(addr.split("%")[0])
        if _is_blocked_address(address):
            raise BlockedUrlError(
                f"fetch URL host {host!r} resolves to an internal address "
                f"({addr}): {url!r}"
            )


def validate_fetch_url(url: str, resolver: Optional[ResolveFn] = None) -> None:
    """Reject non-http(s) schemes and hosts that are (or resolve to) internal.

    Article links come from feed content, which is untrusted input. ``urlopen``
    reads ``file://`` URLs natively and follows whatever host it is given, so
    without this check a poisoned feed could exfiltrate local files into
    ``raw.json`` or probe localhost / cloud metadata endpoints.

    "Internal" here is the explicit denylist in ``_BLOCKED_NETWORKS_*``
    (loopback, RFC 1918, link-local incl. cloud metadata, multicast,
    unspecified) — deliberately narrower than ``ipaddress.is_private`` so
    fake-IP proxy ranges keep working; see the denylist comment.

    DNS resolution failures are let through: the fetch itself will then fail
    with the real name-resolution error, keeping failure modes distinguishable
    from a deliberate block. This pre-flight check is best-effort on hostnames
    (the authoritative bind to a policy-checked address happens at connect
    time in ``_resolve_and_pin``); on redirect hops it runs inside
    ``_GuardedRedirectHandler`` before the follow-up request is built.

    ``resolver`` is the injectable DNS boundary; it defaults to
    :func:`default_resolve`.
    """
    try:
        parsed = urlparse(url)
    except ValueError as exc:
        raise BlockedUrlError(f"unparseable fetch URL: {exc}") from exc
    if parsed.scheme not in ("http", "https"):
        raise BlockedUrlError(
            f"fetch URL scheme must be http or https, got {parsed.scheme!r}: {url!r}"
        )
    host = parsed.hostname
    if not host:
        raise BlockedUrlError(f"fetch URL has no host: {url!r}")
    try:
        literal = ipaddress.ip_address(host)
    except ValueError:
        literal = None
    if literal is not None:
        if _is_blocked_address(literal):
            raise BlockedUrlError(f"fetch URL host is an internal address: {url!r}")
        return
    resolve = resolver or default_resolve
    try:
        addresses = resolve(host)
    except (OSError, UnicodeError):
        return
    _check_resolved_addresses(host, addresses, url)


def _resolve_and_pin(host: str, port: int,
                     resolver: ResolveFn) -> str:
    """Connect-time gate: resolve ``host``, apply the denylist, return the
    address the socket must dial.

    The caller connects to exactly the returned address and never re-resolves,
    so the destination actually reached is one this gate just validated.
    Resolution failures propagate unchanged (the fetch then reports the real
    name-resolution error instead of a policy block).
    """
    addresses = resolver(host)
    if not addresses:
        raise URLError(socket.gaierror(f"no addresses found for host {host!r}"))
    _check_resolved_addresses(host, addresses, f"{host}:{port}")
    return addresses[0].split("%")[0]


def _dial(address: Tuple[str, int], timeout, source_address):
    """socket.create_connection seam (patch target for offline tests)."""
    return socket.create_connection(address, timeout, source_address)


class _GuardedHTTPConnection(http.client.HTTPConnection):
    """HTTPConnection that only ever dials a denylist-validated address."""

    def __init__(self, host, port=None, *, resolve_fn: Optional[ResolveFn] = None,
                 **kwargs):
        super().__init__(host, port, **kwargs)
        self._resolve_fn = resolve_fn or default_resolve

    def connect(self) -> None:
        target = _resolve_and_pin(self.host, self.port, self._resolve_fn)
        self.sock = _dial((target, self.port), self.timeout, self.source_address)
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError as exc:
            if exc.errno != errno.ENOPROTOOPT:
                raise
        if self._tunnel_host:
            self._tunnel()


class _GuardedHTTPSConnection(http.client.HTTPSConnection):
    """HTTPSConnection that only ever dials a denylist-validated address.

    SNI and certificate verification keep using the original hostname even
    though the socket is dialed by IP.
    """

    def __init__(self, host, port=None, *, resolve_fn: Optional[ResolveFn] = None,
                 **kwargs):
        super().__init__(host, port, **kwargs)
        self._resolve_fn = resolve_fn or default_resolve

    def connect(self) -> None:
        target = _resolve_and_pin(self.host, self.port, self._resolve_fn)
        sock = _dial((target, self.port), self.timeout, self.source_address)
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError as exc:
            if exc.errno != errno.ENOPROTOOPT:
                raise
        server_hostname = self._tunnel_host or self.host
        self.sock = self._context.wrap_socket(sock, server_hostname=server_hostname)


class _GuardedHTTPHandler(urllib.request.HTTPHandler):
    def __init__(self, resolve_fn: Optional[ResolveFn] = None):
        super().__init__()
        self._resolve_fn = resolve_fn

    def http_open(self, req):
        return self.do_open(_GuardedHTTPConnection, req, resolve_fn=self._resolve_fn)


class _GuardedHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, resolve_fn: Optional[ResolveFn] = None):
        super().__init__()
        self._resolve_fn = resolve_fn

    def https_open(self, req):
        return self.do_open(_GuardedHTTPSConnection, req,
                            context=self._context, resolve_fn=self._resolve_fn)


class _GuardedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Re-applies the untrusted-URL policy to every redirect destination.

    Without this, ``validate_fetch_url`` only ever saw the original URL and
    urllib followed 3xx hops unchecked — a feed URL that resolves publicly
    could redirect to loopback / RFC 1918 / link-local / metadata hosts. The
    block raises before the follow-up request is built, so the forbidden
    destination is never connected to and its body is never read.
    """

    def __init__(self, resolve_fn: Optional[ResolveFn] = None):
        self._resolve_fn = resolve_fn

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_fetch_url(newurl, resolver=self._resolve_fn)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def build_guarded_opener(resolve_fn: Optional[ResolveFn] = None):
    """Opener whose every request and redirect hop goes through the URL policy.

    Proxies are disabled (``ProxyHandler({})``): an environment-configured HTTP
    proxy would otherwise become an unchecked intermediate destination (and a
    route around the guard). The documented local-proxy setups for this repo
    (Clash / Surge fake-IP mode) operate at the DNS layer and work unchanged.
    """
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        _GuardedHTTPHandler(resolve_fn),
        _GuardedHTTPSHandler(resolve_fn),
        _GuardedRedirectHandler(resolve_fn),
    )


def guarded_urlopen(request, timeout=None,
                    resolve_fn: Optional[ResolveFn] = None):
    """urlopen replacement that routes through :func:`build_guarded_opener`.

    This is the seam the offline retry tests patch; production code reaches it
    only via :func:`fetch_url`.
    """
    opener = build_guarded_opener(resolve_fn)
    if timeout is None:
        return opener.open(request)
    return opener.open(request, timeout=timeout)


def _read_capped(response, max_bytes: int) -> bytes:
    """Read a response body in chunks, refusing to exceed ``max_bytes``."""
    chunks: List[bytes] = []
    total = 0
    while True:
        chunk = response.read(_READ_CHUNK_BYTES)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise ResponseTooLargeError(
                f"response body exceeded {max_bytes // (1024 * 1024)} MiB cap"
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _is_retryable(exc: BaseException) -> bool:
    """Whether re-issuing the same request could plausibly succeed.

    A 4xx is the server telling us *this request* is wrong — repeating it byte
    for byte cannot change the answer, so a UA-blocked feed used to burn the
    full retry budget on every run for nothing. ``429`` is deliberately
    excluded: it is a rate-limit signal, and waiting before retrying is exactly
    the right response to it.
    """
    if isinstance(exc, HTTPError) and 400 <= exc.code < 500 and exc.code != 429:
        return False
    return True


def fetch_url(url: str, timeout: int = 30, retries: int = 2,
              headers: Optional[Dict[str, str]] = None,
              user_agent: str = "",
              resolve_fn: Optional[ResolveFn] = None) -> Tuple[bytes, Optional[str]]:
    """Fetch URL content with retry on transient errors.

    The untrusted-URL policy is enforced on every destination this fetch can
    reach: the original URL up front, every redirect hop before it is
    followed, and the address actually dialed at connect time (see the
    module-level policy note). ``resolve_fn`` is the injectable DNS boundary;
    it defaults to :func:`default_resolve`.
    """
    request_headers = {
        "User-Agent": user_agent or DEFAULT_USER_AGENT,
        "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml",
    }
    if headers:
        request_headers.update(headers)

    resolve = resolve_fn or default_resolve

    # Deliberately outside the retry loop: a blocked URL is a policy decision,
    # and retrying it byte for byte cannot change the answer.
    validate_fetch_url(url, resolver=resolve)

    last_error: Optional[BaseException] = None
    for attempt in range(retries + 1):
        try:
            req = Request(url, headers=request_headers)
            # Scheme/host policy is enforced above for the original URL; the
            # guarded opener re-checks every redirect hop and binds each
            # connection to a policy-validated address. BlockedUrlError is a
            # ValueError, so it deliberately bypasses the retry tuple below.
            with guarded_urlopen(req, timeout=timeout,
                                 resolve_fn=resolve) as response:  # nosec B310
                raw = _read_capped(response, MAX_RESPONSE_BYTES)
                charset = response.headers.get_content_charset()
                return raw, charset
        # HTTPException covers http.client.IncompleteRead — a mid-transfer
        # truncation, i.e. the most transient failure there is. It descends from
        # Exception, *not* OSError, so the original three-branch tuple silently
        # excluded the one error this retry loop most needed to catch.
        except (HTTPError, URLError, OSError, HTTPException) as exc:
            last_error = exc
            if attempt >= retries or not _is_retryable(exc):
                break
            time.sleep(1)

    if last_error is None:
        raise RuntimeError(f"fetch_url failed without a recorded error: {url}")
    raise last_error


def decode_content(raw: bytes, charset: Optional[str]) -> str:
    """Decode raw bytes using detected charset, falling back to utf-8."""
    encoding = charset or "utf-8"
    try:
        return raw.decode(encoding)
    except (UnicodeDecodeError, LookupError):
        return raw.decode("utf-8", errors="replace")


def fetch_article_summary(
    url: str,
    max_summary: int = 0,
    *,
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    extract_summary_fn: Callable[[str, int], str] = extract_html_summary,
) -> str:
    """Fetch a linked article page and extract a fallback summary from HTML."""
    raw, charset = fetch_url_fn(
        url,
        timeout=20,
        retries=1,
        headers={
            "Accept": "text/html, application/xhtml+xml",
        },
    )
    content = decode_content_fn(raw, charset)
    return extract_summary_fn(content, max_chars=max_summary)


def _normalize_summary(summary: object) -> str:
    return str(summary or "").strip()


def _short_summary_threshold(pipeline_config: Optional[Dict[str, object]] = None) -> int:
    if pipeline_config:
        summary_config = pipeline_config.get("summary_enrichment", {})
        if isinstance(summary_config, dict):
            value = summary_config.get("short_summary_threshold")
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                return value
    return DEFAULT_SHORT_SUMMARY_THRESHOLD


def _needs_summary_fallback(summary: object,
                            pipeline_config: Optional[Dict[str, object]] = None) -> bool:
    return len(_normalize_summary(summary)) < _short_summary_threshold(pipeline_config)


def _fallback_summary_limit(max_summary: int,
                            pipeline_config: Optional[Dict[str, object]] = None) -> int:
    if pipeline_config:
        return resolve_page_fallback_cap(max_summary, pipeline_config)
    if max_summary <= 0:
        return DEFAULT_PAGE_FALLBACK_CAP
    return min(max_summary, DEFAULT_PAGE_FALLBACK_CAP)


def enrich_missing_summaries(
    articles: List[Dict],
    max_summary: int = 0,
    max_workers: int = 2,
    pipeline_config: Optional[Dict[str, object]] = None,
    *,
    fetch_summary_fn: Callable[[str, int], str] = fetch_article_summary,
) -> None:
    """Backfill empty or too-short summaries from linked article pages."""
    missing_links = sorted({
        str(article.get("link", "")).strip()
        for article in articles
        if _needs_summary_fallback(article.get("summary_en"), pipeline_config)
        and str(article.get("link", "")).strip()
    })
    if not missing_links:
        return

    results: Dict[str, str] = {}
    worker_count = min(max_workers, len(missing_links))
    fallback_limit = _fallback_summary_limit(max_summary, pipeline_config)
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(fetch_summary_fn, link, fallback_limit): link
            for link in missing_links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ""
                print(f"[WARN] Summary fallback failed for {link}: {exc}", file=sys.stderr)

    for article in articles:
        current_summary = _normalize_summary(article.get("summary_en"))
        if not _needs_summary_fallback(current_summary, pipeline_config):
            continue
        link = str(article.get("link", "")).strip()
        fallback = _normalize_summary(results.get(link, ""))
        if not fallback:
            continue
        if current_summary and len(fallback) <= len(current_summary):
            continue
        if fallback:
            article["summary_en"] = fallback


def enrich_article_text(
    articles: List[Dict],
    pipeline_config: Optional[Dict[str, object]] = None,
    *,
    fetch_article_text_fn: Optional[Callable[[str, int], str]] = None,
) -> None:
    """Backfill ``article_text`` by fetching each article page and extracting
    its main body. Existing non-empty ``article_text`` values are preserved.

    This is a best-effort enrichment: a fetch failure or empty extraction
    leaves ``article_text`` as an empty string. Callers should still be able
    to fall back to ``summary_en``.
    """
    # Imported lazily to avoid a top-level circular import with article_extract,
    # which itself imports fetch_url / decode_content from this module.
    if fetch_article_text_fn is None:
        from .article_extract import fetch_article_text as _default_fetch_article_text
        fetch_article_text_fn = _default_fetch_article_text

    settings = resolve_article_text_settings(pipeline_config)
    if not settings["enabled"]:
        for article in articles:
            article.setdefault("article_text", "")
        return

    max_words = settings["max_words"]
    max_workers = settings["max_workers"]

    missing_links = sorted({
        str(article.get("link", "")).strip()
        for article in articles
        if not _normalize_summary(article.get("article_text"))
        and str(article.get("link", "")).strip()
    })
    if not missing_links:
        for article in articles:
            article.setdefault("article_text", "")
        return

    results: Dict[str, str] = {}
    worker_count = min(max(max_workers, 1), len(missing_links))
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(fetch_article_text_fn, link, max_words): link
            for link in missing_links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ""
                print(f"[WARN] Article text extraction failed for {link}: {exc}",
                      file=sys.stderr)

    for article in articles:
        if _normalize_summary(article.get("article_text")):
            continue
        link = str(article.get("link", "")).strip()
        extracted = _normalize_summary(results.get(link, ""))
        article["article_text"] = extracted


def enrich_article_pages(
    articles: List[Dict],
    max_summary: int = 0,
    pipeline_config: Optional[Dict[str, object]] = None,
    *,
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    extract_summary_fn: Callable[[str, int], str] = extract_html_summary,
    extract_main_text_fn: Optional[Callable[[str], str]] = None,
) -> None:
    """Fetch each article page at most once and extract both enrichments.

    This merges the work previously split across :func:`enrich_missing_summaries`
    (HTML meta-summary fallback) and :func:`enrich_article_text` (main-body
    extraction). Both passes independently re-fetched the same article URLs; on
    the JSON output path that meant every short-summary article page was fetched
    twice. Here each page that needs *either* enrichment is fetched once, and the
    single decoded response feeds both extractors.

    Acceptance rules are preserved verbatim from the two original passes:

    - ``summary_en`` is replaced only when the page summary is non-empty and
      strictly longer than the current (short) summary.
    - ``article_text`` is filled only when currently empty, and is left as an
      empty string when extraction is disabled, the link is missing, or the
      fetch / extraction fails.
    """
    # Imported lazily to avoid a top-level circular import with article_extract,
    # which itself imports fetch_url / decode_content from this module.
    if extract_main_text_fn is None:
        from .article_extract import extract_main_text as _default_extract_main_text
        extract_main_text_fn = _default_extract_main_text
    from .article_extract import truncate_words

    settings = resolve_article_text_settings(pipeline_config)
    body_enabled = bool(settings["enabled"])
    max_words = settings["max_words"]
    worker_count_cfg = settings["max_workers"]
    fallback_limit = _fallback_summary_limit(max_summary, pipeline_config)

    # Parity with enrich_article_text: the field always exists once this pass
    # has been asked to run, even when extraction is disabled or a link is
    # missing.
    for article in articles:
        article.setdefault("article_text", "")

    # Decide, per unique link, what each single fetch must yield.
    needs: Dict[str, Tuple[bool, bool]] = {}
    for article in articles:
        link = str(article.get("link", "")).strip()
        if not link:
            continue
        want_summary = _needs_summary_fallback(article.get("summary_en"), pipeline_config)
        want_body = body_enabled and not _normalize_summary(article.get("article_text"))
        if want_summary or want_body:
            prev = needs.get(link, (False, False))
            needs[link] = (prev[0] or want_summary, prev[1] or want_body)

    if not needs:
        return

    def _fetch_page(link: str, want_summary: bool, want_body: bool) -> Tuple[str, str]:
        raw, charset = fetch_url_fn(
            link,
            timeout=20,
            retries=1,
            headers={"Accept": "text/html, application/xhtml+xml"},
        )
        content = decode_content_fn(raw, charset)
        summary = extract_summary_fn(content, max_chars=fallback_limit) if want_summary else ""
        body = truncate_words(extract_main_text_fn(content), max_words) if want_body else ""
        return summary, body

    links = sorted(needs)
    worker_count = min(max(worker_count_cfg, 1), len(links))
    results: Dict[str, Tuple[str, str]] = {}
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(_fetch_page, link, needs[link][0], needs[link][1]): link
            for link in links
        }
        for future in as_completed(futures):
            link = futures[future]
            try:
                results[link] = future.result()
            except Exception as exc:
                results[link] = ("", "")
                print(f"[WARN] Article page enrichment failed for {link}: {exc}",
                      file=sys.stderr)

    for article in articles:
        link = str(article.get("link", "")).strip()
        if link not in results:
            continue
        fetched_summary, fetched_body = results[link]

        current_summary = _normalize_summary(article.get("summary_en"))
        if _needs_summary_fallback(current_summary, pipeline_config):
            fallback = _normalize_summary(fetched_summary)
            if fallback and (not current_summary or len(fallback) > len(current_summary)):
                article["summary_en"] = fallback

        if body_enabled and not _normalize_summary(article.get("article_text")):
            article["article_text"] = _normalize_summary(fetched_body)


def fetch_rss_feed(
    name: str,
    url: str,
    hours: int = 24,
    max_summary: int = 0,
    *,
    user_agent: str = "",
    fetch_url_fn: Callable[..., Tuple[bytes, Optional[str]]] = fetch_url,
    decode_content_fn: Callable[[bytes, Optional[str]], str] = decode_content,
    parse_feed_fn: Callable[[str, int], List[Dict]] = parse_feed,
) -> Tuple[List[Dict], Optional[str], Optional[str]]:
    """Fetch and parse a single RSS/Atom feed.

    Returns ``(articles_within_window, error, newest_item_date)``.
    ``newest_item_date`` is the ISO timestamp of the newest item in the whole
    feed *before* window filtering — a feed can be HTTP-healthy and XML-valid
    yet dormant, and only this pre-filter timestamp can tell (a stale feed
    otherwise looks identical to a quiet day).
    """
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=hours)

    try:
        raw, charset = fetch_url_fn(url, user_agent=user_agent)
        content = decode_content_fn(raw, charset)
        articles = parse_feed_fn(content, max_summary=max_summary)
        newest = max(
            (article["pub_date"] for article in articles if article.get("pub_date")),
            default=None,
        )
        newest_iso = newest.isoformat() if newest else None
        articles = [article for article in articles if article["pub_date"] >= cutoff]

        for article in articles:
            article["source"] = name

        return articles, None, newest_iso
    except HTTPError as exc:
        msg = f"HTTP {exc.code}"
        print(f"[WARN] {name}: {msg} - {url}", file=sys.stderr)
        return [], msg, None
    except URLError as exc:
        msg = f"Connection failed - {exc.reason}"
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg, None
    except Exception as exc:
        msg = str(exc)
        print(f"[WARN] {name}: {msg}", file=sys.stderr)
        return [], msg, None


def fetch_all_feeds(
    feed_list: List[Dict],
    hours: int = 24,
    max_workers: int = 8,
    max_summary: int = 0,
    *,
    fetch_feed_fn: Callable[..., Tuple] = fetch_rss_feed,
) -> Tuple[List[Dict], Dict[str, Optional[str]], Dict[str, Optional[str]]]:
    """Concurrently fetch all feeds.

    Returns ``(all_articles, feed_status, feed_newest)`` where ``feed_newest``
    maps feed name to the pre-window-filter newest item timestamp (or None).
    Injected ``fetch_feed_fn`` stubs may still return the legacy 2-tuple.
    """
    all_articles = []
    feed_status: Dict[str, Optional[str]] = {}
    feed_newest: Dict[str, Optional[str]] = {}

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {}
        for feed in feed_list:
            # Passed only when the feed actually opts in, so the legacy
            # four-positional call shape that injected stubs rely on stays
            # exactly as it was for the feeds that do not override the UA.
            overrides = {}
            user_agent = str(feed.get("user_agent") or "").strip()
            if user_agent:
                overrides["user_agent"] = user_agent
            future = executor.submit(
                fetch_feed_fn, feed["name"], feed["url"], hours, max_summary, **overrides,
            )
            futures[future] = feed["name"]
        for future in as_completed(futures):
            name = futures[future]
            try:
                result = future.result()
                if len(result) >= 3:
                    articles, error, newest = result[0], result[1], result[2]
                else:
                    articles, error = result
                    newest = None
                all_articles.extend(articles)
                feed_status[name] = error
                feed_newest[name] = newest
            except Exception as exc:
                feed_status[name] = str(exc)
                feed_newest[name] = None
                print(f"[WARN] {name}: {exc}", file=sys.stderr)

    return all_articles, feed_status, feed_newest
