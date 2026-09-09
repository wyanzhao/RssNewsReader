#!/usr/bin/env python3
"""Install repo hooks without replacing an existing hook setup."""
import subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
old = subprocess.run(['git', 'config', '--get', 'core.hooksPath'], cwd=root, capture_output=True, text=True)
if old.returncode == 0 and old.stdout.strip() != '.githooks':
    raise SystemExit('Existing hooksPath found; integrate privacy hooks there before replacing it.')
if old.returncode != 0:
    hooks = subprocess.check_output(['git','rev-parse','--git-path','hooks'],cwd=root,text=True).strip()
    hooks = Path(hooks) if Path(hooks).is_absolute() else root/hooks
    if any(p.is_file() and not p.name.endswith('.sample') for p in hooks.glob('*')):
        raise SystemExit('Existing hooks found; integrate privacy checks without replacing them.')
subprocess.run(['git', 'config', '--local', 'core.hooksPath', '.githooks'], cwd=root, check=True)
print('Privacy hooks installed for this repository.')
