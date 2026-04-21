#!/usr/bin/env python3
"""
Diagnostic helper for automation network failures.

Checks:
- selected environment variables
- DNS resolution
- raw TCP reachability
- HTTPS fetch to known endpoints
- per-feed DNS/HTTP status from feeds.json

Usage:
    python3 scripts/network_debug.py
    python3 scripts/network_debug.py --json
    python3 scripts/network_debug.py --limit 5
"""

import argparse
import json
import os
import platform
import socket
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


SCRIPT_DIR = Path(__file__).resolve().parent
FEEDS_FILE = SCRIPT_DIR.parent / "feeds.json"
ENV_KEYS = [
    "CODEX_CI",
    "CODEX_INTERNAL_ORIGINATOR_OVERRIDE",
    "CODEX_SHELL",
    "CODEX_THREAD_ID",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
    "http_proxy",
    "https_proxy",
    "all_proxy",
    "no_proxy",
]
DEFAULT_TEST_URLS = [
    "https://example.com",
    "https://pytorch.org/blog/feed/",
]


def load_feeds() -> List[Dict[str, str]]:
    if not FEEDS_FILE.exists():
        return []
    data = json.loads(FEEDS_FILE.read_text(encoding="utf-8"))
    return data.get("feeds", [])


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def resolve_host(hostname: str) -> Dict[str, object]:
    result: Dict[str, object] = {"hostname": hostname}
    try:
        infos = socket.getaddrinfo(hostname, 443, proto=socket.IPPROTO_TCP)
        addrs = sorted({item[4][0] for item in infos})
        result.update({
            "ok": True,
            "addresses": addrs,
        })
    except Exception as exc:
        result.update({
            "ok": False,
            "error": repr(exc),
        })
    return result


def tcp_check(host: str, port: int, timeout: float) -> Dict[str, object]:
    result: Dict[str, object] = {"target": f"{host}:{port}"}
    try:
        with socket.create_connection((host, port), timeout=timeout):
            result["ok"] = True
    except Exception as exc:
        result.update({
            "ok": False,
            "error": repr(exc),
        })
    return result


def http_check(url: str, timeout: float) -> Dict[str, object]:
    result: Dict[str, object] = {"url": url}
    req = Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (compatible; NetworkDebug/1.0)",
            "Accept": "*/*",
        },
    )
    try:
        with urlopen(req, timeout=timeout) as response:
            result.update({
                "ok": True,
                "status": getattr(response, "status", None),
                "content_type": response.headers.get("Content-Type"),
            })
    except HTTPError as exc:
        result.update({
            "ok": False,
            "status": exc.code,
            "error": f"HTTP {exc.code}",
        })
    except URLError as exc:
        result.update({
            "ok": False,
            "error": f"URLError: {exc.reason}",
        })
    except Exception as exc:
        result.update({
            "ok": False,
            "error": repr(exc),
        })
    return result


def feed_checks(feeds: List[Dict[str, str]], timeout: float) -> List[Dict[str, object]]:
    results: List[Dict[str, object]] = []
    for feed in feeds:
        name = feed["name"]
        url = feed["url"]
        host = urlparse(url).hostname or ""
        dns = resolve_host(host) if host else {"hostname": host, "ok": False, "error": "missing hostname"}
        http = http_check(url, timeout)
        status = "ok"
        if not dns.get("ok"):
            status = "dns_error"
        elif not http.get("ok"):
            status = "http_error"
        results.append({
            "source": name,
            "url": url,
            "hostname": host,
            "status": status,
            "dns": dns,
            "http": http,
        })
    return results


def build_report(limit: int, timeout: float) -> Dict[str, object]:
    feeds = load_feeds()
    if limit > 0:
        feeds = feeds[:limit]

    return {
        "timestamp": iso_now(),
        "cwd": os.getcwd(),
        "python": sys.version.split()[0],
        "platform": platform.platform(),
        "env": {key: os.environ.get(key) for key in ENV_KEYS if os.environ.get(key) is not None},
        "dns_tests": [
            resolve_host("example.com"),
            resolve_host("openai.com"),
        ],
        "tcp_tests": [
            tcp_check("1.1.1.1", 443, timeout),
            tcp_check("8.8.8.8", 443, timeout),
        ],
        "http_tests": [http_check(url, timeout) for url in DEFAULT_TEST_URLS],
        "feeds_checked": len(feeds),
        "feed_checks": feed_checks(feeds, timeout),
    }


def print_text(report: Dict[str, object]) -> None:
    print(f"Timestamp: {report['timestamp']}")
    print(f"CWD: {report['cwd']}")
    print(f"Python: {report['python']}")
    print(f"Platform: {report['platform']}")
    print()

    print("Environment:")
    env = report["env"]
    if env:
        for key, value in env.items():
            print(f"  {key}={value}")
    else:
        print("  (no relevant env vars set)")
    print()

    print("DNS tests:")
    for item in report["dns_tests"]:
        if item["ok"]:
            print(f"  OK  {item['hostname']} -> {', '.join(item['addresses'][:3])}")
        else:
            print(f"  ERR {item['hostname']} -> {item['error']}")
    print()

    print("TCP tests:")
    for item in report["tcp_tests"]:
        if item["ok"]:
            print(f"  OK  {item['target']}")
        else:
            print(f"  ERR {item['target']} -> {item['error']}")
    print()

    print("HTTP tests:")
    for item in report["http_tests"]:
        if item["ok"]:
            print(f"  OK  {item['url']} -> {item['status']}")
        else:
            status = item.get("status")
            suffix = f" ({status})" if status is not None else ""
            print(f"  ERR {item['url']} -> {item['error']}{suffix}")
    print()

    print(f"Feed checks: {report['feeds_checked']}")
    for item in report["feed_checks"]:
        dns_ok = item["dns"].get("ok")
        http_ok = item["http"].get("ok")
        line = f"  [{item['status']}] {item['source']}"
        if not dns_ok:
            line += f" | DNS: {item['dns'].get('error')}"
        elif not http_ok:
            line += f" | HTTP: {item['http'].get('error')}"
            if item["http"].get("status") is not None:
                line += f" ({item['http']['status']})"
        else:
            line += f" | HTTP: {item['http'].get('status')}"
        print(line)


def main() -> None:
    parser = argparse.ArgumentParser(description="Debug automation network access.")
    parser.add_argument("--json", action="store_true", help="Output JSON.")
    parser.add_argument("--limit", type=int, default=0, help="Only check the first N feeds from feeds.json.")
    parser.add_argument("--timeout", type=float, default=8.0, help="Timeout in seconds for network checks.")
    args = parser.parse_args()

    report = build_report(limit=args.limit, timeout=args.timeout)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_text(report)


if __name__ == "__main__":
    main()
