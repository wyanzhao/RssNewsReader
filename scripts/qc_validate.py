#!/usr/bin/env python3
"""Validate RSS raw output against the feed configuration contract."""

import argparse
import json
import os
import sys
from typing import Any, Dict, List, Optional, Tuple


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, '..'))
DEFAULT_FEEDS_PATH = os.path.join(REPO_ROOT, 'feeds.json')

EXIT_OK = 0
EXIT_INPUT_DAMAGED = 10
EXIT_CONTRACT_MISMATCH = 20
EXIT_DATA_QUALITY_BLOCK = 30
EXIT_UNEXPECTED = 40

VALID_STATUSES = {'ok', 'empty', 'error'}
VALID_ERROR_POLICIES = {'block', 'warn'}


def _blank_result() -> Dict[str, Any]:
    return {
        'passed': False,
        'blocking_reasons': [],
        'warnings': [],
        'counts': {
            'configured': 0,
            'results': 0,
            'ok': 0,
            'empty': 0,
            'error': 0,
            'articles': 0,
        },
        'policy': {
            'block_on_error_count': False,
            'block_on_zero_articles': True,
            'block_on_feed_results_mismatch': True,
            'empty_is_warning_only': True,
            'unique_source_count_is_observational': True,
        },
    }


def _load_json_from_path(path: str) -> Dict[str, Any]:
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f'JSON root must be an object: {path}')
    return data


def _load_raw_input(path: Optional[str]) -> Dict[str, Any]:
    if path in (None, '-', ''):
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError('raw input JSON root must be an object')
        return data
    return _load_json_from_path(path)


def _require_dict(data: Any, label: str) -> Dict[str, Any]:
    if not isinstance(data, dict):
        raise KeyError(f'{label} must be an object')
    return data


def _require_list(data: Any, label: str) -> List[Any]:
    if not isinstance(data, list):
        raise KeyError(f'{label} must be an array')
    return data


def _require_key(obj: Dict[str, Any], key: str, label: str) -> Any:
    if key not in obj:
        raise KeyError(f'missing required field: {label}.{key}')
    return obj[key]


def _count_feed_results(feed_results: List[Dict[str, Any]]) -> Tuple[int, int, int]:
    ok = empty = error = 0
    for item in feed_results:
        status = item.get('status')
        if status == 'ok':
            ok += 1
        elif status == 'empty':
            empty += 1
        elif status == 'error':
            error += 1
    return ok, empty, error


def validate(raw: Dict[str, Any], feeds: Dict[str, Any]) -> Tuple[Dict[str, Any], int]:
    result = _blank_result()

    try:
        feeds_obj = _require_dict(feeds, 'feeds.json')
        feed_list = _require_list(_require_key(feeds_obj, 'feeds', 'feeds.json'), 'feeds.json.feeds')
        feed_error_policies: Dict[str, str] = {}
        warn_error_sources: List[str] = []
        error_sources: List[str] = []

        raw_obj = _require_dict(raw, 'raw.json')
        meta = _require_dict(_require_key(raw_obj, 'meta', 'raw.json'), 'raw.json.meta')
        generated_at_utc = _require_key(meta, 'generated_at_utc', 'raw.json.meta')
        run_id = _require_key(meta, 'run_id', 'raw.json.meta')
        input_mode = _require_key(meta, 'input_mode', 'raw.json.meta')
        feed_count_expected = _require_key(meta, 'feed_count_expected', 'raw.json.meta')

        count = _require_key(raw_obj, 'count', 'raw.json')
        articles = _require_list(_require_key(raw_obj, 'articles', 'raw.json'), 'raw.json.articles')
        feed_results = _require_list(_require_key(raw_obj, 'feed_results', 'raw.json'), 'raw.json.feed_results')
        configured_feed_count = _require_key(raw_obj, 'configured_feed_count', 'raw.json')

        if not isinstance(generated_at_utc, str):
            raise KeyError('raw.json.meta.generated_at_utc must be a string')
        if not isinstance(run_id, str):
            raise KeyError('raw.json.meta.run_id must be a string')
        if not isinstance(input_mode, str):
            raise KeyError('raw.json.meta.input_mode must be a string')
        if not isinstance(count, int):
            raise KeyError('raw.json.count must be an integer')
        if not isinstance(configured_feed_count, int):
            raise KeyError('raw.json.configured_feed_count must be an integer')
        if not isinstance(feed_count_expected, int):
            raise KeyError('raw.json.meta.feed_count_expected must be an integer')

        for idx, feed in enumerate(feed_list):
            if not isinstance(feed, dict):
                result['blocking_reasons'].append(f'feeds.json.feeds[{idx}] must be an object')
                continue
            name = feed.get('name')
            if not isinstance(name, str) or not name.strip():
                result['blocking_reasons'].append(f'feeds.json.feeds[{idx}].name must be a non-empty string')
                continue
            error_policy = feed.get('error_policy', 'block')
            if error_policy not in VALID_ERROR_POLICIES:
                result['blocking_reasons'].append(
                    f"feeds.json.feeds[{idx}].error_policy must be one of {sorted(VALID_ERROR_POLICIES)}"
                )
                continue
            feed_error_policies[name] = error_policy

        if len(feed_list) != configured_feed_count:
            result['blocking_reasons'].append(
                f'configured_feed_count mismatch: feeds.json has {len(feed_list)}, raw.json reports {configured_feed_count}'
            )
        if feed_count_expected != configured_feed_count:
            result['blocking_reasons'].append(
                f'meta.feed_count_expected mismatch: expected {configured_feed_count}, got {feed_count_expected}'
            )
        if len(feed_results) != configured_feed_count:
            result['blocking_reasons'].append(
                f'feed_results length mismatch: expected {configured_feed_count}, got {len(feed_results)}'
            )
        if len(articles) != count:
            result['blocking_reasons'].append(
                f'articles length mismatch: count={count}, articles={len(articles)}'
            )

        for idx, item in enumerate(feed_results):
            if not isinstance(item, dict):
                result['blocking_reasons'].append(f'feed_results[{idx}] must be an object')
                continue
            source = item.get('source')
            if not isinstance(source, str) or not source.strip():
                result['blocking_reasons'].append(f'feed_results[{idx}].source must be a non-empty string')
                continue
            if source not in feed_error_policies:
                result['blocking_reasons'].append(f'feed_results[{idx}].source not found in feeds.json: {source}')
            status = item.get('status')
            if status not in VALID_STATUSES:
                result['blocking_reasons'].append(f'feed_results[{idx}].status must be one of {sorted(VALID_STATUSES)}')
            article_count = item.get('article_count')
            if not isinstance(article_count, int) or article_count < 0:
                result['blocking_reasons'].append(f'feed_results[{idx}].article_count must be a non-negative integer')
            if status == 'error' and isinstance(source, str):
                error_detail = item.get('error')
                source_label = source if not error_detail else f'{source} ({error_detail})'
                error_sources.append(source_label)
                if feed_error_policies.get(source) == 'warn':
                    warn_error_sources.append(source)

        ok_count, empty_count, error_count = _count_feed_results(feed_results)
        total_articles = 0
        for idx, item in enumerate(feed_results):
            if isinstance(item, dict) and isinstance(item.get('article_count'), int):
                total_articles += item['article_count']

        block_on_error_count = result['policy'].get('block_on_error_count') is True
        blocking_error_count = error_count - len(warn_error_sources) if block_on_error_count else 0
        warn_error_count = len(warn_error_sources) if block_on_error_count else error_count

        result['counts'] = {
            'configured': len(feed_list),
            'results': len(feed_results),
            'ok': ok_count,
            'empty': empty_count,
            'error': error_count,
            'articles': count,
            'blocking_error': blocking_error_count,
            'warn_error': warn_error_count,
        }
        result['feed_results'] = feed_results
        result['meta'] = {
            'generated_at_utc': generated_at_utc,
            'run_id': run_id,
            'input_mode': input_mode,
        }
        result['policy']['warn_error_sources'] = sorted(
            [name for name, policy in feed_error_policies.items() if policy == 'warn']
        )

        unique_source_count = raw_obj.get('unique_source_count')
        unique_sources = raw_obj.get('unique_sources')
        if isinstance(unique_source_count, int) and isinstance(unique_sources, list):
            if unique_source_count != len(unique_sources):
                result['warnings'].append(
                    f'unique_source_count differs from unique_sources length: {unique_source_count} vs {len(unique_sources)}'
                )

        empty_sources = [item.get('source') for item in feed_results if isinstance(item, dict) and item.get('status') == 'empty']
        if empty_sources:
            result['warnings'].append(
                f'{len(empty_sources)} empty feed(s): ' + ', '.join(str(source) for source in empty_sources if source)
            )
        if warn_error_sources:
            result['warnings'].append(
                f'{len(warn_error_sources)} warn-only error feed(s): ' + ', '.join(sorted(warn_error_sources))
            )
        non_warn_error_sources = [label for label in error_sources if label.split(' (', 1)[0] not in warn_error_sources]
        if non_warn_error_sources:
            result['warnings'].append(
                f'{len(non_warn_error_sources)} failed feed(s): ' + ', '.join(non_warn_error_sources)
            )

        if total_articles != count:
            result['blocking_reasons'].append(
                f'sum(article_count) mismatch: expected {count}, got {total_articles}'
            )

        if count == 0:
            result['blocking_reasons'].append('count == 0')

    except KeyError as exc:
        result['blocking_reasons'] = [str(exc)]
        return result, EXIT_INPUT_DAMAGED
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        result['blocking_reasons'] = [str(exc)]
        return result, EXIT_INPUT_DAMAGED

    if result['blocking_reasons']:
        contract_related = any(
            reason.startswith((
                'configured_feed_count mismatch',
                'meta.feed_count_expected mismatch',
                'feed_results length mismatch',
                'articles length mismatch',
                'feed_results[',
                'sum(article_count) mismatch',
            ))
            for reason in result['blocking_reasons']
        )
        if contract_related:
            result['passed'] = False
            return result, EXIT_CONTRACT_MISMATCH

        quality_related = any(reason.startswith('count == 0') for reason in result['blocking_reasons'])
        if quality_related:
            result['passed'] = False
            return result, EXIT_DATA_QUALITY_BLOCK

        result['passed'] = False
        return result, EXIT_CONTRACT_MISMATCH

    result['passed'] = True
    return result, EXIT_OK


def main() -> int:
    parser = argparse.ArgumentParser(description='Validate RSS raw output against feeds.json.')
    parser.add_argument('--input', help='Path to raw JSON input. Use - or omit to read from stdin.')
    parser.add_argument('--feeds', default=DEFAULT_FEEDS_PATH, help='Path to feeds.json.')
    args = parser.parse_args()

    try:
        raw = _load_raw_input(args.input)
        feeds = _load_json_from_path(args.feeds)
        validation, exit_code = validate(raw, feeds)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        validation = _blank_result()
        validation['blocking_reasons'] = [str(exc)]
        exit_code = EXIT_INPUT_DAMAGED

    json.dump(validation, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write('\n')
    return exit_code


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as exc:
        fallback = _blank_result()
        fallback['blocking_reasons'] = [f'unexpected error: {exc}']
        json.dump(fallback, sys.stdout, ensure_ascii=False, indent=2)
        sys.stdout.write('\n')
        print(f'unexpected error: {exc}', file=sys.stderr)
        raise SystemExit(EXIT_UNEXPECTED)
