# DailyNews TASKS

`TASKS.md` 是本仓库的长期任务看板与统一规划入口；任何 Claude Code 架构、skill、subagent、测试或文档实现前，先更新这里。

## Current Architecture

- `CLAUDE.md` 是 Claude Code 入口，只导入 `AGENTS.md` 并把运行时任务指向 `/dailynews-report`
- `AGENTS.md` 定义仓库 contract、artifact schema、agent 边界、文档角色与维护约束
- `.claude/skills/dailynews-report/SKILL.md` 是唯一 runtime procedure 入口，负责 orchestrator 编排
- `.claude/agents/*.md` 定义 `pipeline-runner`、`artifact-auditor`、`network-debugger`、`part1-editor`、`part2-drafter`、`report-assembler`、`report-reviewer`
- `README.md` 面向仓库使用者说明 `skill + subagents` 架构与使用方式
- `tests/test_claude_skill_layout.py` 与 `tests/test_claude_agent_layout.py` 负责 Claude Code 布局校验

## Decisions Locked

- [x] runtime procedure 的唯一 source of truth 是 `skill-only`
- [x] Claude Code 架构固定为 `orchestrator skill + subagents`
- [x] 顶层编排入口继续保留 `/dailynews-report`
- [x] 旧运行时文件已移除，不再作为运行时入口
- [x] 文档主语言以中文为主，保留必要英文术语和命令
- [x] 本轮实施分支固定为 `feature/claude-skill-subagents-refactor`
- [x] Claude Code 架构变更默认走 branch workflow，不直接在 `main` 上堆叠未验证改动

## Active Epics

- [x] Epic A — 建立长期跟踪面板
- [x] Epic B — 迁移 runtime procedure 到 skill-only
- [x] Epic C — 引入 subagents 架构
- [x] Epic D — 文档统一同步
- [ ] Epic E — 布局与回归验证

## Task Breakdown

- `A1` | `done` | 新建 `TASKS.md` 首版结构 | 包含长期说明、当前架构、锁定决策、Epics、Validation Checklist、Backlog
- `A2` | `done` | 将本次重构拆成 Epics、Tasks、Acceptance Criteria | 当前文件可脱离聊天记录独立说明目标与状态
- `A3` | `done` | 在 `TASKS.md` 中写入 branch workflow 和验证清单 | branch 与验证要求已固定在本文件中
- `B1` | `done` | 将 runtime procedure 拆入 orchestrator skill 与 subagents | `.claude/skills/dailynews-report/SKILL.md` 与 `.claude/agents/*.md` 已承载执行规则
- `B2` | `done` | skill 中删除对旧运行时文件的引用 | skill 仅引用 `AGENTS.md`、`TASKS.md` 与 subagents
- `B3` | `done` | 删除旧运行时文件 | 仓库根目录不再保留该文件
- `B4` | `done` | 修复文档与测试中的旧引用 | 全仓不再把旧文件当作运行时入口
- `C1` | `done` | 新建 `.claude/agents/` | 目录已创建并纳入布局测试
- `C2` | `done` | 创建 `pipeline-runner` | 职责、分类规则、只读边界已写入 agent 文件
- `C3` | `done` | 创建 `artifact-auditor` | 只读审计 `llm_context.json` / `validation.json` 的规则已固定
- `C4` | `done` | 创建 `network-debugger` | 异常分支诊断与 `network_debug.py` 调用条件已固定
- `C5` | `done` | 将旧 `report-writer` 拆成 `part1-editor`、`part2-drafter`、`report-assembler` | Part 1 编辑、Part 2 草拟、最终组装的边界已分离，且只有 `report-assembler` 可写 success `report_path`
- `C6` | `done` | 创建 / 更新 `report-reviewer` | 最终只读审查规则已固定，并改为跟在 `report-assembler` 后执行
- `C7` | `done` | orchestrator skill 改成只做分派与收口 | skill 中已固定 success / expected-block / unexpected-error 编排链路
- `D1` | `done` | 更新 `README.md` | README 说明 `skill + subagents` 架构与使用方式
- `D2` | `done` | 更新 `CLAUDE.md` | 入口保持轻量，只指向 `AGENTS.md`、`TASKS.md`、`/dailynews-report`
- `D3` | `done` | 更新 `AGENTS.md` | contract、artifact schema、agent 边界、文档角色已同步
- `D4` | `done` | 删除单一 skill 旧叙述 | 文档不再描述旧的单文件运行时结构
- `D5` | `done` | 在 `AGENTS.md` 中加入 `TASKS.md` 角色定义 | `TASKS.md` 已被定义为长期 tracker
- `E1` | `done` | 更新 Claude layout 测试 | `tests/test_claude_skill_layout.py` 已更新，`tests/test_claude_agent_layout.py` 已新增
- `E2` | `done` | 运行定向布局与 network debug 测试 | `python3 -m unittest tests.test_claude_skill_layout tests.test_claude_agent_layout tests.test_network_debug` 已通过
- `E3` | `done` | 运行全量 `unittest discover` | `python3 -m unittest discover -s tests -p 'test_*.py'` 已通过
- `E4` | `blocked` | 在分支上做一次 `/dailynews-report` 手动 smoke walkthrough | `claude agents` 已识别 7 个 project agents；`claude -p "/dailynews-report"` 被本机 `~/.claude.json` / `~/.claude.lock` 的 `EPERM` 与 `401` 认证错误阻断

## Validation Checklist

- [x] `TASKS.md` 存在且结构固定
- [x] `.claude/skills/dailynews-report/SKILL.md` 存在并作为唯一 runtime procedure 入口
- [x] `.claude/agents/` 下 7 个 agent 文件存在
- [x] 仓库根目录不再保留旧运行时文件
- [x] `README.md`、`CLAUDE.md`、`AGENTS.md` 已统一为 `skill + subagents` 架构
- [x] `AGENTS.md` 已明确 `TASKS.md` 的长期跟踪角色
- [x] `python3 -m unittest tests.test_claude_skill_layout tests.test_claude_agent_layout tests.test_network_debug`
- [x] `python3 -m unittest discover -s tests -p 'test_*.py'`
- [x] `claude agents` 已确认 7 个 project agents 可见
- [ ] Claude Code `/help` 中确认 `/dailynews-report` 可见
- [ ] 条件允许时完成一次 `/dailynews-report` 手动 smoke

## Backlog

- [ ] 将 orchestrator skill 的可配置参数进一步显式化，例如时间窗口、摘要长度、保留天数
- [ ] 为常见失败模式补充 supporting skills 或 agent variants
- [ ] 增加更细粒度的自动化验证，覆盖 README / AGENTS / TASKS 的一致性
- [ ] 评估是否为 report-reviewer 增加更严格的结构化审查输出格式
