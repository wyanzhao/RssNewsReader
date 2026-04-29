from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLAUDE_MD = ROOT / "CLAUDE.md"
README_MD = ROOT / "README.md"
TASKS_MD = ROOT / "TASKS.md"
REMOVED_RUNTIME_DOC = "PROMPT" + ".md"
PROMPT_MD = ROOT / REMOVED_RUNTIME_DOC
SKILL_MD = ROOT / ".claude" / "skills" / "dailynews-report" / "SKILL.md"
SKILL_METADATA_DIR = SKILL_MD.parent / "agents"
SKILL_OPENAI_YAML = SKILL_METADATA_DIR / "openai.yaml"
CODEX_SKILL_MD = ROOT / ".agents" / "skills" / "dailynews-report" / "SKILL.md"
CODEX_SKILL_METADATA_DIR = CODEX_SKILL_MD.parent / "agents"
CODEX_SKILL_OPENAI_YAML = CODEX_SKILL_METADATA_DIR / "openai.yaml"

FRONTMATTER_RE = re.compile(r"\A---\n(.*?)\n---\n(.*)\Z", re.DOTALL)


def parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    match = FRONTMATTER_RE.match(text)
    if match is None:
        raise AssertionError("SKILL.md must start with YAML frontmatter")
    block, body = match.groups()
    data: dict[str, str] = {}
    for line in block.splitlines():
        line = line.strip()
        if not line:
            continue
        key, sep, value = line.partition(":")
        if sep != ":":
            raise AssertionError(f"invalid frontmatter line: {line!r}")
        data[key.strip()] = value.strip().strip('"').strip("'")
    return data, body


class ClaudeSkillLayoutTests(unittest.TestCase):
    def test_claude_md_is_regular_file_and_points_to_skill(self):
        self.assertTrue(CLAUDE_MD.exists())
        self.assertFalse(CLAUDE_MD.is_symlink(), "CLAUDE.md should be a regular file")

        text = CLAUDE_MD.read_text(encoding="utf-8")
        self.assertIn("@AGENTS.md", text)
        self.assertIn("TASKS.md", text)
        self.assertIn("/dailynews-report", text)
        self.assertIn(".claude/skills/dailynews-report/SKILL.md", text)
        self.assertIn(".agents/skills/dailynews-report/SKILL.md", text)
        self.assertIn(".claude/agents/", text)
        self.assertNotIn(REMOVED_RUNTIME_DOC, text)

    def test_shared_skill_file_has_expected_frontmatter(self):
        self.assertTrue(SKILL_MD.exists())

        frontmatter, _body = parse_frontmatter(SKILL_MD.read_text(encoding="utf-8"))
        self.assertEqual(set(frontmatter.keys()), {"name", "description"})
        self.assertEqual(frontmatter.get("name"), "dailynews-report")

        description = frontmatter.get("description", "")
        self.assertIn("/dailynews-report", description)
        self.assertIn("DailyNews", description)
        self.assertIn("Codex", description)

    def test_codex_skill_path_reuses_same_skill_file(self):
        self.assertTrue(CODEX_SKILL_MD.is_symlink())
        self.assertEqual(CODEX_SKILL_MD.resolve(), SKILL_MD.resolve())
        self.assertEqual(CODEX_SKILL_MD.read_text(encoding="utf-8"), SKILL_MD.read_text(encoding="utf-8"))

    def test_codex_skill_metadata_is_shared_and_manual_only(self):
        self.assertTrue(SKILL_OPENAI_YAML.exists())
        self.assertTrue(CODEX_SKILL_METADATA_DIR.is_symlink())
        self.assertEqual(CODEX_SKILL_METADATA_DIR.resolve(), SKILL_METADATA_DIR.resolve())

        text = SKILL_OPENAI_YAML.read_text(encoding="utf-8")
        self.assertEqual(CODEX_SKILL_OPENAI_YAML.read_text(encoding="utf-8"), text)
        self.assertIn('display_name: "DailyNews Report"', text)
        self.assertIn('short_description: "Run the DailyNews RSS report workflow"', text)
        self.assertIn('default_prompt: "Use $dailynews-report', text)
        self.assertIn("policy:", text)
        self.assertIn("allow_implicit_invocation: false", text)

    def test_skill_body_references_repo_contract_tracker_and_agents(self):
        _frontmatter, body = parse_frontmatter(SKILL_MD.read_text(encoding="utf-8"))

        self.assertIn("[AGENTS.md](../../../AGENTS.md)", body)
        self.assertIn("[TASKS.md](../../../TASKS.md)", body)
        self.assertIn("agents/openai.yaml", body)
        self.assertIn("manual, write-producing orchestrator skill", body)
        self.assertIn("single shared skill file for both Claude Code and Codex", body)
        self.assertIn("python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output", body)
        self.assertIn("part1_plan.json", body)
        self.assertIn("part2_draft.json", body)
        self.assertIn("summary_en", body)
        for name in (
            "pipeline-runner",
            "artifact-auditor",
            "network-debugger",
            "part1-editor",
            "part2-drafter",
            "report-assembler",
            "report-reviewer",
        ):
            self.assertIn(name, body)
        self.assertIn("success", body)
        self.assertIn("expected-block", body)
        self.assertIn("unexpected-error", body)
        self.assertNotIn(REMOVED_RUNTIME_DOC, body)

    def test_tasks_tracker_exists_with_required_sections(self):
        self.assertTrue(TASKS_MD.exists())
        text = TASKS_MD.read_text(encoding="utf-8")
        self.assertIn("# DailyNews TASKS", text)
        self.assertIn("## Current Architecture", text)
        self.assertIn("## Decisions Locked", text)
        self.assertIn("## Active Epics", text)
        self.assertIn("## Task Breakdown", text)
        self.assertIn("## Validation Checklist", text)
        self.assertIn("## Backlog", text)
        self.assertIn("feature/claude-skill-subagents-refactor", text)

    def test_prompt_file_is_removed(self):
        self.assertFalse(PROMPT_MD.exists())

    def test_readme_mentions_claude_code_entrypoint_and_subagents(self):
        text = README_MD.read_text(encoding="utf-8")
        self.assertIn("Claude Code", text)
        self.assertIn("Codex", text)
        self.assertIn("CLAUDE.md", text)
        self.assertIn("/dailynews-report", text)
        self.assertIn(".claude/skills/dailynews-report/SKILL.md", text)
        self.assertIn(".agents/skills/dailynews-report/SKILL.md", text)
        self.assertIn(".claude/skills/dailynews-report/agents/openai.yaml", text)
        self.assertIn(".claude/agents/", text)
        self.assertIn("pipeline-runner", text)
        self.assertIn("part1_plan.json", text)
        self.assertIn("part2_draft.json", text)
        self.assertIn("TASKS.md", text)
        self.assertNotIn(REMOVED_RUNTIME_DOC, text)


if __name__ == "__main__":
    unittest.main()
