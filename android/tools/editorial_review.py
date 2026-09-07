#!/usr/bin/env python3
"""Offline, source-bounded editorial review packets from Android diagnostic exports."""
import argparse
import hashlib
import json
import random
import zipfile
from pathlib import Path


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def load_run(path, prefix=""):
    with zipfile.ZipFile(path) as archive:
        def read(name):
            info = archive.getinfo(prefix + name)
            if info.file_size > 20_000_000:
                raise ValueError(f'{name}: oversized artifact')
            return json.loads(archive.read(info))
        raw, plan, shortlist = (read(n) for n in ('raw.json', 'part1_plan.json', 'part1_shortlist.json'))
        brief, context = read('part1_brief.json'), read('part1_shortlist_context.json')
        config = read('run_config.json')
    articles = raw['articles']
    by_link = {a['link']: a for a in articles}
    if len(by_link) != len(articles):
        raise ValueError('duplicate source links')
    selected = shortlist['links']
    excluded = shortlist.get('excluded', [])
    refs = selected + [x['link'] for x in excluded]
    if len(refs) != len(set(refs)) or not set(refs) <= by_link.keys():
        raise ValueError('invalid shortlist source references')
    if 'excluded' in shortlist and set(refs) != by_link.keys():
        raise ValueError('shortlist does not account for the source pool exactly once')
    used = [link for item in plan['items'] for link in [item['link'], *item.get('also_links', [])]]
    if len(used) != len(set(used)) or not set(used) <= set(selected):
        raise ValueError('invalid final report source references')
    if any(not x.get('reason', '').strip() for x in excluded):
        raise ValueError('missing exclusion reason')
    if 'excluded' in plan:
        final_excluded = plan['excluded']
        accounted = used + [x['link'] for x in final_excluded]
        if len(accounted) != len(set(accounted)) or set(accounted) != set(selected):
            raise ValueError('final selection does not account for the shortlist exactly once')
        if any(not x.get('reason', '').strip() for x in final_excluded):
            raise ValueError('missing final exclusion reason')
    pool = sorted(articles, key=lambda a: a['link'])
    return {'pool': pool, 'pool_hash': digest(pool), 'plan': plan, 'shortlist': shortlist,
            'brief': brief, 'context': context, 'config': config}


def without_feedback(value):
    if isinstance(value, dict):
        return {k: without_feedback(v) for k, v in value.items()
                if k not in ('editorFeedback', 'editor_feedback', 'articleFeedback', 'article_feedback',
                             'run_id', 'generated_at_utc', 'report_path')}
    if isinstance(value, list):
        return [without_feedback(v) for v in value]
    return value


def comparison_controls(left, right):
    if left['pool_hash'] != right['pool_hash']:
        raise ValueError('article pools differ; not a same-pool comparison')
    for field in ('brief', 'config'):
        if without_feedback(left[field]) != without_feedback(right[field]):
            raise ValueError(f'{field} differs beyond feedback; comparison is confounded')
    for field in ('recent_top30', 'watched_history'):
        if left['context'].get(field, []) != right['context'].get(field, []):
            raise ValueError(f'prior-event history differs: {field}')
    # Check shared shortlisted material. A missing arm cannot prove equivalent cache
    # exposure for items it never shortlisted, so this remains an explicit limitation.
    def cache(run):
        fields = ('source', 'title', 'pub_date_utc', 'pub_date_iso', 'summary_en',
                  'article_text', 'article_text_tail_omitted', 'cached_summary_zh', 'cached_event_key')
        return {a['link']: tuple(a.get(field) for field in fields)
                for a in run['context']['articles']}
    a, b = cache(left), cache(right)
    if any(a[k] != b[k] for k in a.keys() & b.keys()):
        raise ValueError('shared article material or cache exposure differs')


def render_arm(run, label):
    by_link = {a['link']: a for a in run['pool']}
    lines = [f'## {label}', '']
    for rank, item in enumerate(run['plan']['items'], 1):
        source = by_link[item['link']]
        lines += [f"{rank}. {source['title']}", f"   {source['source']} — {item['link']}",
                  f"   {item['summary_zh']}", '']
        if item.get('development'):
            progress = item['development']
            lines += [f"   Model-claimed development since {progress['baseline_date']}: {progress['change_zh']}",
                      f"   Evidence source: {progress['evidence_link']}",
                      f"   Source excerpt (not independent verification): {progress['evidence_quote']}", '']
        for link in item.get('also_links', []):
            lines += [f"   Merged source: {by_link[link]['title']} — {link}"]
    if run['plan'].get('excluded'):
        lines += ['', 'Final-stage exclusions:', '']
        for entry in run['plan']['excluded']:
            lines += [f"- {by_link[entry['link']]['title']} — {entry['link']}", f"  {entry['reason']}"]
    return '\n'.join(lines)


def prepare(paths, output, prefixes=None):
    prefixes = prefixes or ["" for _ in paths]
    runs = [load_run(p, prefix) for p, prefix in zip(paths, prefixes)]
    if len(runs) == 2:
        comparison_controls(*runs)
    output.mkdir(parents=True, exist_ok=False)
    order = list(range(len(runs)))
    random.SystemRandom().shuffle(order)
    arms = {chr(65 + i): runs[index] for i, index in enumerate(order)}
    instructions = '''# Editorial review

Assess only the supplied source material. Do not browse to silently fill source gaps.
For each judgment, cite the article link and a short supporting passage.

- Relevance: which arm better matches the supplied user preference, and why?
- Coverage: name important omitted events; do not reward item count alone.
- Duplication: identify repeated events without substantive new developments.
- Faithfulness: flag claims unsupported by the supplied source and any lost uncertainty.
- Exclusions: assess each reason against the source, including inconsistent vendor treatment.

Record verdicts as supported / contradicted / insufficient evidence. No automatic quality score.
A single arm is a source audit, not an A/B comparison. Keep unblinding.json separate
from reviewers until judgments are recorded. Source order, cache effects outside shared
shortlists, model/provider versions, decoding settings and prompt revisions require separate
provenance verification; passing packet checks does not prove causal preference improvement.
'''
    (output / 'review.md').write_text(instructions + '\n' + '\n'.join(render_arm(r, k) for k, r in arms.items()))
    evidence = {'pool_hash': runs[0]['pool_hash'], 'articles': runs[0]['pool'],
                'review_preferences': sorted({f for r in runs for f in r['brief'].get('editor_feedback', [])}),
                'arms': {k: {'shortlist': r['shortlist'], 'plan': r['plan'], 'material': without_feedback(r['context'])}
                         for k, r in arms.items()}}
    (output / 'source-evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    # This separate file is intentionally not linked from the blinded review document.
    (output / 'unblinding.json').write_text(json.dumps({chr(65 + i): str(paths[index]) + "#" + prefixes[index] for i, index in enumerate(order)}, indent=2) + '\n')
    return {'arms': len(arms), 'articles': len(runs[0]['pool']), 'pool_hash': runs[0]['pool_hash']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--experiment', help='completed experiment ID inside a single exported ZIP')
    args = parser.parse_args()
    if not 1 <= len(args.run) <= 2:
        parser.error('provide one source audit or two comparison arms')
    if args.experiment:
        if len(args.run) != 1 or not all(c.isalnum() or c in '-_' for c in args.experiment):
            parser.error('experiment requires one ZIP and a safe experiment ID')
        prefix = f'comparisons/{args.experiment}/'
        with zipfile.ZipFile(args.run[0]) as archive:
            manifest = json.loads(archive.read(prefix + 'manifest.json'))
        if manifest.get('status') != 'complete':
            parser.error('experiment is not complete; refusing a partial comparison')
        original = load_run(args.run[0])
        for arm in ('baseline', 'candidate'):
            if load_run(args.run[0], prefix + arm + '/')['pool_hash'] != original['pool_hash']:
                parser.error('experiment pool differs from its source run')
        print(json.dumps(prepare(args.run * 2, args.output, [prefix + 'baseline/', prefix + 'candidate/'])))
        (args.output / 'runtime-provenance.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    else:
        print(json.dumps(prepare(args.run, args.output)))
