@AGENTS.md

## Claude Code Usage

- `AGENTS.md` remains the source of truth for the repository contract and maintainer guidance.
- `TASKS.md` is the long-running tracker for Claude Code architecture changes in this repo.
- For the full report workflow, use the project-local orchestrator skill `/dailynews-report` at `.claude/skills/dailynews-report/SKILL.md`; Codex reaches the same file through `.agents/skills/dailynews-report/SKILL.md`.
- Codex Skill UI metadata lives at `.claude/skills/dailynews-report/agents/openai.yaml`; `.agents/skills/dailynews-report/agents` symlinks to the same metadata directory.
- `.claude/agents/*.md` defines the subagents used by the orchestrator skill.
- The skill is intentionally manual-only because it runs a heavy, write-producing workflow that can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.

## Version Bump Rule

The app version lives in exactly one place — `versionCode` / `versionName` in
`android/app/build.gradle.kts`. Do not introduce a second version string
anywhere else in the repo.

- **Every iteration bumps `versionName` by 0.01.** Read `0.M.P` as the decimal
  `0.MP`: `0.2.0` → `0.2.1` → `0.2.2` → … The patch component therefore never
  exceeds `9`; `0.2.9 + 0.01` carries into `0.3.0`.
- **A major iteration bumps by 0.1 instead**: `0.2.x` → `0.3.0`, and `0.9.x` →
  `1.0.0`. Major means a whole Epic, a Room schema migration, a new top-level
  screen, or any user-visible change big enough to deserve its own release
  note. When in doubt, ask rather than guessing — a version is a promise to the
  device that already has the old APK installed.
- **`versionCode` is +1 on every bump, without exception.** Android refuses to
  install an APK whose `versionCode` is not strictly greater than the installed
  one, so a forgotten bump does not fail the build — it fails silently on the
  user's phone during upgrade testing.
- **The bump ships in the same commit as the work it versions**, never as a
  separate "bump version" commit. Reviewing a diff should show what changed and
  which version carries it, together.
- **What does not bump:** doc-only, test-only, or tooling-only changes, and
  anything confined to `scripts/` (the Python pipeline is versionless and does
  not share this number).
- Before handing over an APK, confirm the built `versionName` matches the
  iteration just completed. The version printed in the delivery message must be
  read back from the build, not from memory.
