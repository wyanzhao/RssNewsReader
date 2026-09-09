#!/usr/bin/env python3
"""Fail-closed local/CI/release privacy checks. Never print matching values."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

RULES = {
    'personal-home': re.compile(rb'(?:/Users/|/home/)(?!example\b|user\b|username\b|runner\b)[A-Za-z0-9._-]+'),
    'personal-mailbox': re.compile(rb'[A-Za-z0-9._%+-]+@(?:gmail\.com|hotmail\.com|outlook\.com|icloud\.com|qq\.com|163\.com|126\.com)\b', re.I),
    'device-serial': re.compile(rb'\b(?:RFC[A-Z0-9]{8,16}|AG[A-Z0-9]{12,18})\b'),
    'adb-device': re.compile(rb'\badb\s+-s\s+[A-Za-z0-9][A-Za-z0-9._:-]{7,}'),
    'private-key': re.compile(rb'-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----'),
    'credential-token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-(?:or-v1-)?[A-Za-z0-9_-]{25,}|AKIA[A-Z0-9]{16})\b'),
}
FORBIDDEN = re.compile(r'(^|/)(?:\.env(?:\..+)?|keystore\.properties|local\.properties|.*\.(?:jks|p12|pfx|apk|zip|7z|tar|gz)|AGENT_HANDOFF.*|OPTIMIZATION_PROGRESS.*|.*\.local\.(?:md|txt)|runs|digest_runs|acceptance)(/|$)', re.I)
NOREPLY = re.compile(r'^[A-Za-z0-9+_.-]+@users\.noreply\.github\.com$|^noreply@github\.com$')
MAX_BYTES = 32 * 1024 * 1024

# Release signers are approved by exact SHA-256 certificate fingerprint, never
# by subject string. The original personal-subject certificate below was
# approved by the maintainer (2026-09-09) as a bounded exception: every already
# published release carries it, and rotating keys would force an
# uninstall/reinstall on installed devices. Rotation to a neutral-subject key
# remains a separately reviewed plan; adding any new fingerprint needs the
# documented exception process (approval, reason, bounded scope, tests).
APPROVED_CERT_SHA256 = {
    'a61be7168894d812b9cc6d4a32cdb77a5de684e0bbada2b6535c0dff89b5e759',
}
class GateError(RuntimeError): pass

def git(repo, *args):
    p = subprocess.run(['git', '-C', str(repo), *args], capture_output=True)
    if p.returncode: raise GateError('git inspection failed (details suppressed)')
    return p.stdout

def safe_path(path):
    data = path.encode('utf-8', 'replace')
    for pattern in RULES.values(): data = pattern.sub(b'<redacted>', data)
    return data.decode('utf-8', 'replace').replace('\n', '?')

def inspect_bytes(data, path):
    return [{'rule': rule, 'path': safe_path(path), 'line': data[:m.start()].count(b'\n') + 1,
             'fingerprint': hashlib.sha256(m.group()).hexdigest()[:12]}
            for rule, pattern in RULES.items() for m in pattern.finditer(data)]

def check_identity(raw, path):
    findings = inspect_bytes(raw, path)
    for email in re.findall(rb'<([^<>\n]+)>', raw):
        if not NOREPLY.fullmatch(email.decode('utf-8', 'replace')):
            findings.append({'rule': 'commit-email-not-noreply', 'path': path})
    return findings

def run_gitleaks(directory):
    if not shutil.which('gitleaks'): raise GateError('gitleaks is required; install it before retrying')
    with tempfile.TemporaryDirectory(prefix='privacy-engine-') as tmp:
        tmp = Path(tmp)
        config = tmp/'rules.toml'
        config.write_text('[extend]\nuseDefault = true\n')
        report = tmp/'result.json'
        env = {k:v for k,v in os.environ.items() if not k.startswith('GITLEAKS_')}
        result = subprocess.run(['gitleaks', 'dir', str(directory), '--config', str(config),
                                 '--gitleaks-ignore-path', str(tmp/'no-ignore'), '--ignore-gitleaks-allow',
                                 '--redact=100', '--no-banner', '--report-format', 'json',
                                 '--report-path', str(report)], capture_output=True, env=env)
        if result.returncode not in (0, 1) or not report.exists():
            raise GateError('secret scanner failed; raw diagnostics suppressed')
        rows = json.loads(report.read_text())
        if result.returncode == 1 and not rows: raise GateError('secret scanner failed without findings')
        return [{'rule': 'gitleaks:'+r['RuleID'], 'path': safe_path(Path(r['File']).name),
                 'line': r['StartLine']} for r in rows]

def collect_git(repo, staged=False, revision='HEAD', base=None, history=False):
    findings, files, seen = [], {}, set()
    if git(repo, 'rev-parse', '--is-shallow-repository').strip() == b'true' and not staged:
        raise GateError('full git history required; shallow checkout refused')
    if staged:
        specs = [None]
        for role in ('GIT_AUTHOR_IDENT', 'GIT_COMMITTER_IDENT'):
            findings += check_identity(git(repo, 'var', role), 'pending-commit/'+role)
    else:
        # Resolve user/event refs before passing them to revision walkers.
        if revision.startswith('-'): raise GateError('invalid source ref')
        object_id = git(repo, 'rev-parse', '--verify', revision).decode().strip()
        while git(repo, 'cat-file', '-t', object_id).strip() == b'tag':
            tag = git(repo, 'cat-file', 'tag', object_id)
            findings += inspect_bytes(tag, 'tag/'+object_id[:12])
            tagger = next((line for line in tag.splitlines() if line.startswith(b'tagger ')), b'')
            findings += check_identity(tagger, 'tag/'+object_id[:12])
            object_id = tag.splitlines()[0].split()[1].decode()
        head = git(repo, 'rev-parse', '--verify', revision+'^{commit}').decode().strip()
        if base:
            start = git(repo, 'rev-parse', '--verify', base+'^{commit}').decode().strip()
            ancestry = subprocess.run(['git','-C',str(repo),'merge-base','--is-ancestor',start,head],capture_output=True)
            if ancestry.returncode: raise GateError('base must be an ancestor of head')
            commit_range = start+'..'+head
        elif history: commit_range = head
        else: commit_range = head+'^!'
        specs = git(repo, 'rev-list', commit_range).decode().splitlines()
        if head not in specs: specs.append(head)
        for commit in specs:
            identities = git(repo, 'show', '-s', '--format=%an <%ae>%n%cn <%ce>', commit)
            findings += check_identity(identities, 'commit/'+commit[:12])
            findings += inspect_bytes(git(repo,'show','-s','--format=%B',commit), 'commit-message/'+commit[:12])
    for spec in specs:
        listing = git(repo, 'ls-files', '--stage', '-z') if staged else git(repo, 'ls-tree', '-r', '-z', spec)
        for row in listing.split(b'\0'):
            if not row: continue
            meta, path = row.split(b'\t', 1); parts = meta.split()
            mode, oid = parts[0], parts[1 if staged else 2].decode()
            name = path.decode('utf-8', 'replace')
            if staged and parts[2] != b'0': raise GateError('unmerged index refused')
            if mode == b'160000': raise GateError('submodules require a separate reviewed privacy scan')
            if (oid, name) in seen: continue
            seen.add((oid,name))
            if FORBIDDEN.search(name): findings.append({'rule':'forbidden-artifact','path':safe_path(name)})
            size = int(git(repo, 'cat-file', '-s', oid))
            if size > MAX_BYTES: raise GateError('oversized git blob requires separate review')
            data = git(repo, 'cat-file', 'blob', oid)
            if data.startswith(b'version https://git-lfs.github.com/spec/'): raise GateError('LFS objects require a separate reviewed privacy scan')
            findings += inspect_bytes(data, name)
            files[oid] = data
    return findings, files

def inspect_apk(apk, apksigner):
    findings, files = [], {}
    result = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], capture_output=True)
    if result.returncode: raise GateError('APK signature verification failed')
    text = result.stdout.decode('utf-8','replace')
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', text, re.M)
    if not digests or any(digest not in APPROVED_CERT_SHA256 for digest in digests):
        findings.append({'rule':'apk-certificate-not-approved','path':'APK signing certificate'})
    if not re.search(r'Verified using v[23] scheme[^:]*:\s*true',text): raise GateError('APK requires v2/v3 signature')
    total = 0
    with zipfile.ZipFile(apk) as archive:
        for index, item in enumerate(archive.infolist()):
            total += item.file_size
            if item.file_size > MAX_BYTES or total > 256*1024*1024: raise GateError('APK expansion exceeds scan limit')
            data = archive.read(item)
            findings += inspect_bytes(data, 'APK/'+item.filename)
            strings = re.findall(rb'[\x20-\x7e]{4,}',data)
            strings += [s.replace(b'\0',b'') for s in re.findall(rb'(?:[\x20-\x7e]\x00){4,}',data)]
            extracted = b'\n'.join(strings)
            findings += inspect_bytes(extracted,'APK/'+item.filename)
            if extracted: files['apk-'+str(index)] = extracted
    return findings, files

def check(repo, *, staged=False, revision='HEAD', base=None, history=False, apk=None, apksigner=None):
    findings, files = collect_git(repo, staged, revision, base, history)
    if apk:
        if not apksigner: raise GateError('apksigner path is required for APK scan')
        apk_findings, apk_files = inspect_apk(apk, apksigner)
        findings += apk_findings; files.update(apk_files)
    with tempfile.TemporaryDirectory(prefix='privacy-content-') as temp:
        for name, data in files.items():
            # Materialize safe hash names, never repository paths/symlinks.
            Path(temp, name+'.txt').write_bytes(data)
        findings += run_gitleaks(temp)
    unique = {json.dumps(row, sort_keys=True):row for row in findings}
    return list(unique.values())

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root', default=str(Path(__file__).resolve().parents[1]))
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--staged',action='store_true')
    mode.add_argument('--history',action='store_true')
    parser.add_argument('--ref',default='HEAD')
    parser.add_argument('--base')
    parser.add_argument('--apk',type=Path)
    parser.add_argument('--apksigner',type=Path)
    args=parser.parse_args(argv)
    try:
        rows=check(args.repo_root, staged=args.staged, revision=args.ref, base=args.base,
                   history=args.history, apk=args.apk, apksigner=args.apksigner)
        if rows:
            print('PRIVACY BLOCK: '+str(len(rows))+' findings (values redacted)')
            for row in rows[:40]: print(json.dumps(row,ensure_ascii=True))
            return 1
        print('PRIVACY PASS: checked content and commit identities; no rule matches')
        return 0
    except Exception:
        # Even exceptions can contain credentials, private filenames or certificate subjects.
        print('PRIVACY ERROR: inspection incomplete; check full history, gitleaks, git state and APK tools. Raw details suppressed.',file=sys.stderr)
        return 2

if __name__ == '__main__': sys.exit(main())
