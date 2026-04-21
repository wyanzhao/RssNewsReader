@AGENTS.md

## Claude Code Usage

- `AGENTS.md` remains the source of truth for the repository contract and maintainer guidance.
- `PROMPT.md` remains the runtime procedure for report generation and is loaded by the report skill when needed.
- For the full report workflow, use the project-local skill `/dailynews-report` at `.claude/skills/dailynews-report/SKILL.md`.
- The skill is intentionally manual-only because it runs a heavy, write-producing workflow that can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.
