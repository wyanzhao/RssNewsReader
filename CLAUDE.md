@AGENTS.md

## Claude Code Usage

- `AGENTS.md` remains the source of truth for the repository contract and maintainer guidance.
- `TASKS.md` is the long-running tracker for Claude Code architecture changes in this repo.
- For the full report workflow, use the project-local orchestrator skill `/dailynews-report` at `.claude/skills/dailynews-report/SKILL.md`; Codex reaches the same file through `.agents/skills/dailynews-report/SKILL.md`.
- Codex Skill UI metadata lives at `.claude/skills/dailynews-report/agents/openai.yaml`; `.agents/skills/dailynews-report/agents` symlinks to the same metadata directory.
- `.claude/agents/*.md` defines the subagents used by the orchestrator skill.
- The skill is intentionally manual-only because it runs a heavy, write-producing workflow that can update `rss-report-*.md` and `runs/YYYY-MM-DD/`.
