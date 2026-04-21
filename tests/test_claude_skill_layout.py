from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLAUDE_MD = ROOT / "CLAUDE.md"
README_MD = ROOT / "README.md"
SKILL_MD = ROOT / ".claude" / "skills" / "dailynews-report" / "SKILL.md"

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
        self.assertIn("/dailynews-report", text)
        self.assertIn(".claude/skills/dailynews-report/SKILL.md", text)

    def test_skill_file_has_expected_frontmatter(self):
        self.assertTrue(SKILL_MD.exists())

        frontmatter, _body = parse_frontmatter(SKILL_MD.read_text(encoding="utf-8"))
        self.assertEqual(frontmatter.get("name"), "dailynews-report")
        self.assertEqual(frontmatter.get("disable-model-invocation"), "true")

        description = frontmatter.get("description", "")
        self.assertIn("/dailynews-report", description)
        self.assertIn("DailyNews", description)

    def test_skill_body_references_repo_contract_and_prompt(self):
        _frontmatter, body = parse_frontmatter(SKILL_MD.read_text(encoding="utf-8"))

        self.assertIn("[AGENTS.md](../../../AGENTS.md)", body)
        self.assertIn("[PROMPT.md](../../../PROMPT.md)", body)
        self.assertIn("manual, write-producing project skill", body)
        self.assertIn("python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output", body)

    def test_readme_mentions_claude_code_entrypoint(self):
        text = README_MD.read_text(encoding="utf-8")
        self.assertIn("Claude Code", text)
        self.assertIn("CLAUDE.md", text)
        self.assertIn("/dailynews-report", text)
        self.assertIn(".claude/skills/dailynews-report/SKILL.md", text)


if __name__ == "__main__":
    unittest.main()
