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

# pipeline-runner and artifact-auditor were demoted to direct deterministic
# orchestrator steps (same pattern as the earlier report-assembler /
# report-reviewer demotion); only judgment-bearing agents remain.
REMOVED_AGENTS = ("pipeline-runner", "artifact-auditor")

EXPECTED_AGENTS = {
    "network-debugger": [
        "fetch.stderr.txt",
        "validate.stderr.txt",
        "llm_context.stderr.txt",
        "render.stderr.txt",
        "python3 scripts/network_debug.py --limit 5",
        "不生成最终报告",
    ],
    "part1-editor": [
        "part1_brief.json",
        "part1_shortlist.json",
        "part1_shortlist_context.json",
        "Part 1",
        "Top 30",
        "part1_plan.json",
        "link-keyed",
        "also_links",
        "cached_summary_zh",
        "不要读取 `runs/_cache/",
        "绝对路径",
        "UTF-8 JSON",
    ],
    "part2-drafter": [
        "part2_context.json",
        "source_groups[].article_refs",
        "part2_missing_summaries.json",
        "merge-part2",
        "validation.json",
        "Part 2",
        "counts.articles",
        "part2_draft.json",
        "needs_summary",
        "不要读取 `runs/_cache/",
        "绝对路径",
        "UTF-8 JSON",
    ],
}

# Prose-only guardrails became mechanical: each agent gets an explicit tool
# whitelist (no network tools anywhere), and the mechanical Part 2 drafting
# is pinned to a cheaper model.
EXPECTED_TOOLS = {
    "network-debugger": "Read, Bash",
    "part1-editor": "Read, Write, Edit, Bash",
    "part2-drafter": "Read, Write, Edit",
}

EXPECTED_MODELS = {
    "part2-drafter": "haiku",
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
        for name in REMOVED_AGENTS:
            self.assertFalse(
                (AGENT_DIR / f"{name}.md").exists(),
                msg=f"{name} was demoted to a direct orchestrator step and must stay removed",
            )

    def test_each_agent_has_matching_frontmatter(self):
        for name in EXPECTED_AGENTS:
            path = AGENT_DIR / f"{name}.md"
            frontmatter, _body = parse_frontmatter(path.read_text(encoding="utf-8"))
            self.assertEqual(frontmatter.get("name"), name)
            description = frontmatter.get("description", "")
            self.assertTrue(description, msg=f"{name} description must not be empty")
            self.assertIn("DailyNews", description)
            self.assertEqual(
                frontmatter.get("tools"),
                EXPECTED_TOOLS[name],
                msg=f"{name} must declare its tool whitelist",
            )
            expected_model = EXPECTED_MODELS.get(name)
            if expected_model is not None:
                self.assertEqual(frontmatter.get("model"), expected_model)

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
        self.assertIn("part1-editor", readme_text)
        self.assertIn("editorial_runtime.py", readme_text)
        self.assertNotIn("- `report-assembler`", readme_text)
        self.assertNotIn("- `report-reviewer`", readme_text)
        for name in REMOVED_AGENTS:
            self.assertNotIn(name, readme_text)

        self.assertIn("TASKS.md", agents_text)
        self.assertIn(".claude/skills/dailynews-report/SKILL.md", agents_text)
        self.assertIn(".agents/skills/dailynews-report/SKILL.md", agents_text)
        self.assertIn(".claude/agents/*.md", agents_text)
        self.assertIn("part1_plan.json", agents_text)
        self.assertIn("part1_shortlist.json", agents_text)
        self.assertIn("part2_context.json", agents_text)
        self.assertIn("part2_missing_summaries.json", agents_text)
        self.assertIn("part2_draft.json", agents_text)
        self.assertIn("run pipeline -> editorial_runtime audit -> part1-editor + part2-drafter (parallel) -> editorial_runtime merge-part2 -> editorial_runtime assemble -> editorial_runtime review -> editorial_runtime top30", agents_text)
        self.assertIn("run pipeline -> network-debugger", agents_text)
        for name in REMOVED_AGENTS:
            self.assertNotIn(name, agents_text)
        self.assertNotIn(REMOVED_RUNTIME_DOC, agents_text)


if __name__ == "__main__":
    unittest.main()
