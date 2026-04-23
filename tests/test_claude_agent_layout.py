from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
README_MD = ROOT / "README.md"
AGENTS_MD = ROOT / "AGENTS.md"
AGENT_DIR = ROOT / ".claude" / "agents"
REMOVED_RUNTIME_DOC = "PROMPT" + ".md"

FRONTMATTER_RE = re.compile(r"\A---\n(.*?)\n---\n(.*)\Z", re.DOTALL)

EXPECTED_AGENTS = {
    "pipeline-runner": [
        "python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output",
        "success",
        "expected-block",
        "unexpected-error",
        "8 个控制面字段",
    ],
    "artifact-auditor": [
        "llm_context.json",
        "validation.json",
        "counts.articles",
        "source_groups",
        "不写文件",
    ],
    "network-debugger": [
        "fetch.stderr.txt",
        "validate.stderr.txt",
        "llm_context.stderr.txt",
        "render.stderr.txt",
        "python3 scripts/network_debug.py --limit 5",
        "不生成最终报告",
    ],
    "part1-editor": [
        "all_articles",
        "Part 1",
        "Top 30",
        "part1_plan.json",
        "绝对路径",
        "UTF-8 JSON",
    ],
    "part2-drafter": [
        "source_groups",
        "validation.json",
        "Part 2",
        "counts.articles",
        "part2_draft.json",
        "绝对路径",
        "UTF-8 JSON",
    ],
    "report-assembler": [
        "part1_plan.json",
        "part2_draft.json",
        "report_path",
        "*.failed.md",
        "summary_en",
        "唯一可以写 `report_path`",
    ],
    "report-reviewer": [
        "标题保持英文原文",
        "原始 link",
        "source order",
        "validation.counts.articles",
        "part1_plan.json",
        "part2_draft.json",
        "summary_en",
        "只读",
    ],
}


def parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    match = FRONTMATTER_RE.match(text)
    if match is None:
        raise AssertionError("agent file must start with YAML frontmatter")
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


class ClaudeAgentLayoutTests(unittest.TestCase):
    def test_agents_directory_and_expected_files_exist(self):
        self.assertTrue(AGENT_DIR.is_dir())
        actual = {path.stem for path in AGENT_DIR.glob("*.md")}
        self.assertEqual(actual, set(EXPECTED_AGENTS))

    def test_each_agent_has_matching_frontmatter(self):
        for name in EXPECTED_AGENTS:
            path = AGENT_DIR / f"{name}.md"
            frontmatter, _body = parse_frontmatter(path.read_text(encoding="utf-8"))
            self.assertEqual(frontmatter.get("name"), name)
            description = frontmatter.get("description", "")
            self.assertTrue(description, msg=f"{name} description must not be empty")
            self.assertIn("DailyNews", description)

    def test_each_agent_body_contains_its_role_keywords(self):
        for name, keywords in EXPECTED_AGENTS.items():
            path = AGENT_DIR / f"{name}.md"
            _frontmatter, body = parse_frontmatter(path.read_text(encoding="utf-8"))
            for keyword in keywords:
                self.assertIn(keyword, body, msg=f"{name} missing keyword {keyword!r}")

    def test_docs_describe_skill_and_subagents_architecture(self):
        readme_text = README_MD.read_text(encoding="utf-8")
        agents_text = AGENTS_MD.read_text(encoding="utf-8")

        self.assertIn(".claude/agents/", readme_text)
        self.assertIn("skill + subagents", readme_text)
        self.assertIn("pipeline-runner", readme_text)
        self.assertIn("part1-editor", readme_text)
        self.assertIn("report-assembler", readme_text)

        self.assertIn("TASKS.md", agents_text)
        self.assertIn(".claude/skills/dailynews-report/SKILL.md", agents_text)
        self.assertIn(".agents/skills/dailynews-report/SKILL.md", agents_text)
        self.assertIn(".claude/agents/*.md", agents_text)
        self.assertIn("part1_plan.json", agents_text)
        self.assertIn("part2_draft.json", agents_text)
        self.assertIn("pipeline-runner -> artifact-auditor -> part1-editor + part2-drafter -> report-assembler -> report-reviewer", agents_text)
        self.assertIn("pipeline-runner -> network-debugger", agents_text)
        self.assertNotIn(REMOVED_RUNTIME_DOC, agents_text)


if __name__ == "__main__":
    unittest.main()
