# DailyNews TASKS

`TASKS.md` 是本仓库的长期任务看板与统一规划入口；任何 Claude Code 架构、skill、subagent、测试或文档实现前，先更新这里。

## Current Architecture

- `CLAUDE.md` 是 Claude Code 入口，只导入 `AGENTS.md` 并把运行时任务指向 `/dailynews-report`
- `AGENTS.md` 定义仓库 contract、artifact schema、agent 边界、文档角色与维护约束
- `.claude/skills/dailynews-report/SKILL.md` 是唯一 runtime procedure 入口，负责 orchestrator 编排
- `.claude/agents/*.md` 定义 `pipeline-runner`、`artifact-auditor`、`network-debugger`、`part1-editor`、`part2-drafter`、`report-assembler`、`report-reviewer`
- success 分支通过 `runs/<date>/part1_plan.json` 与 `runs/<date>/part2_draft.json` 做 machine-readable handoff，`report-assembler` 只负责最终 `report_path` 写入
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
- [ ] Epic F — success-path handoff hardening
- [x] Epic G — validator / source-group contract hardening
- [x] Epic H — unexpected-error schema parity
- [x] Epic I — editorial core decoupling
- [x] Epic J — `rss_news_monitor.py` 模块化拆分

## Review-Driven Refactor Plan

- 本轮 review 结论：不做一次性“大重写”，而是做有边界的定向重构
- 执行顺序固定为：`G -> H -> I -> J`
- 优先级解释：
  - `G` 先修真实 contract hole，避免 validator 放过自相矛盾的 source-group 数据
  - `H` 再修 unexpected-error 分支 schema 漂移，保证 `validation.json` 在所有分支都同形
  - `I` 再把 `build_llm_context.py` 从 `render_report.py` 的内部实现中解耦，降低后续改动的连带风险
  - `J` 最后再拆 `rss_news_monitor.py`，因为它主要是可维护性问题，不是当前最高风险的数据正确性问题
- 非目标：
  - 不修改 `rss_daily_report.py --json-output` 的 8 字段 contract
  - 不改变 `llm_context.json` 的对外字段名，除非同时更新 golden fixture、contract tests 与 runtime docs
  - 不把 deterministic 规则重新塞回 Claude runtime 层

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
- `F1` | `done` | 将 success 分支 handoff 固定成结构化中间产物 | `part1-editor` / `part2-drafter` 改为产出 `part1_plan.json` / `part2_draft.json`，`report-assembler` 只消费它们并写 final report
- `F2` | `done` | 禁止 success 分支在 handoff 缺失或截断时 silent fallback | `AGENTS.md`、skill、agents 已明确：不得回退到 `summary_en` 或聊天文本拼装“差不多”的正式报告
- `F3` | `done` | 同步 README 与布局测试到新的 handoff contract | README 与 `tests/test_claude_skill_layout.py` / `tests/test_claude_agent_layout.py` 已覆盖中间产物与 no-silent-fallback 约束
- `G1` | `done` | 在 `qc_validate.py` 中加入 per-source consistency 校验 | validator 现在会校验每个 `feed_results[].source` 的 `article_count` / `status` / `error` 是否与 `raw.json.articles` 的真实 source 聚合一致，并阻断 duplicate / missing source
- `G2` | `done` | 为 source-group contradiction 增加定向回归测试 | `tests/test_qc_offline.py` 已覆盖“总数没错但 source 归属错位”与 `status=ok` 携带 `error` 的阻断场景
- `G3` | `done` | 将 source-group consistency 纳入 `llm_context` / renderer 侧防御性断言 | `_common/editorial.py` 增加 defensive consistency check，`build_llm_context.py` / `render_report.py` 遇到自相矛盾的 source-group metadata 时会 fail fast
- `G4` | `done` | 在 contract docs 中补充 per-source integrity 规则 | `AGENTS.md` 已写明 per-source `article_count` / `status` / `error` 自洽要求
- `H1` | `done` | 统一 `rss_daily_report.py` fallback validation schema | fallback `validation.json` 的 `policy` 键名与默认值已对齐 `qc_validate.py` / `_common/schemas.py`
- `H2` | `done` | 为 unexpected-error / fallback path 增加 contract 测试 | 新增 `tests/test_pipeline_fallback_contract.py`，锁住 unreadable validator 输出时的 fallback schema
- `H3` | `done` | 明确 fallback artifact 也是正式 contract 的一部分 | `AGENTS.md` 已写明 unexpected-error 分支的 fallback `validation.json` 也必须保持正常 schema 形状
- `I1` | `done` | 提取 shared editorial helpers 到 `scripts/_common/` | 已新增 `_common/editorial.py` 承载文章标准化、flags、金额提取、评分、Top-N、source-group roster 与 consistency checks
- `I2` | `done` | 让 `build_llm_context.py` 停止依赖 `render_report.py` 私有实现 | `build_llm_context.py` 已改为直接依赖 `_common/editorial.py`
- `I3` | `done` | 保持 `llm_context` golden 与 Top 30 行为稳定 | contract snapshot、editorial core tests、全量 unittest 与真实 smoke 均已通过
- `I4` | `done` | 收窄 renderer 职责到“只消费规范化数据并渲染 Markdown” | `render_report.py` 现主要保留 CLI / Top 30 cap / Markdown render，与 shared editorial core 分离
- `J1` | `done` | 规划 `rss_news_monitor.py` 的模块边界 | 已拆出 feed config、parse、fetch、output 四组 helper 模块
- `J2` | `done` | 先抽纯函数，再保留原 CLI surface 不变 | `rss_news_monitor.py` 保留兼容入口与原 CLI flags，主脚本只做薄分派
- `J3` | `done` | 为 fetch-path 拆分补一轮真实 smoke checklist | 已完成真实 `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output --no-cleanup` smoke
- `J4` | `done` | 将 `rss_news_monitor.py` 从“大一统脚本”收敛为薄 CLI | 主脚本已从 850 行收敛到约 300 行，主要承担兼容层与 CLI 分派
- `J5` | `done` | 将摘要阈值从硬编码迁移到 repo-level config | 已新增 `pipeline_config.json`，fetch / render 读取配置并把生效值快照写入 `raw.json.runtime_config`
- `K1` | `done` | 让 `part1-editor` 从全量 `all_articles` 自主选 Top 30 | `.claude/agents/part1-editor.md` 把 `all_articles` 升为权威池；`candidate_articles` / `heuristic_score` / `audit_flags` 降为提示信号；`hard_noise` 仍作为硬过滤；`AGENTS.md` contract surface、architecture、editorial policy 段落同步
- `K2` | `done` | 彻底移除静态打分机制，Top 30 编辑判断完全交给 LLM | `scripts/_common/editorial.py` 删除 `analyze_article` / `score_article` / `choose_top_articles` / 关键词常量 / 金额提取；`scripts/build_llm_context.py` 不再输出 `candidate_articles` 或接受 `--candidate-limit`；`scripts/render_report.py` 的 Top 30 fallback 改为纯时间倒序切片；per-article contract 从 9 字段收敛到 6 字段（去除 `heuristic_score` / `audit_flags` / `amount_millions`）；`scripts/_common/schemas.py` 同步；`part1-editor.md` 重写为完整编辑提示词（去噪、优先级、聚类、来源多样性、中文摘要）；`AGENTS.md`、`artifact-auditor.md`、`report-assembler.md` 同步；更新 `llm_context_golden.json` / `markdown_render_golden.md` / `test_contracts_snapshot.py` / `test_qc_offline.py` / `test_pipeline_fallback_contract.py` / `test_claude_agent_layout.py`；全量 98 个测试绿
- `K3` | `done` | 合并上游 PR #3（`article_text` 正文抽取）与 K2（去静态打分） | 正交合并：K2 删掉 `heuristic_score` / `audit_flags` / `amount_millions` 三字段和 `candidate_articles`，PR #3 新增 `article_text` 字段，最终 per-article contract 为 7 字段；直接拷贝 PR #3 独有文件 `scripts/_common/article_extract.py` 与 `tests/test_article_extract.py`；手工 patch 共享文件 `pipeline_config.json`、`scripts/_common/runtime_config.py`（新增 `article_text` 配置与 `resolve_article_text_settings`）、`scripts/_common/feed_fetch.py`（`enrich_article_text`）、`scripts/_common/feed_output.py`、`scripts/rss_news_monitor.py`（调用 enrichment）、`scripts/_common/editorial.py`（`Article` 与 payload 增加 `article_text`）、`scripts/_common/schemas.py`；agents：`part1-editor` 叠加 `article_text` 为中文摘要首选素材的指引（保持 K2 完整编辑框架），`part2-drafter` / `report-assembler` / `report-reviewer` / `SKILL.md` 同步；`AGENTS.md` 同步 contract surface、新增 Article Body Extraction 章节、editorial policy；金标 `llm_context_golden.json` 与 `test_contracts_snapshot.py` 扩到 7 字段；全量 111 个测试绿

## Validation Checklist

- [x] `TASKS.md` 存在且结构固定
- [x] `.claude/skills/dailynews-report/SKILL.md` 存在并作为唯一 runtime procedure 入口
- [x] `.claude/agents/` 下 7 个 agent 文件存在
- [x] success 分支 handoff artifact（`part1_plan.json` / `part2_draft.json`）已写入 runtime docs
- [x] 仓库根目录不再保留旧运行时文件
- [x] `README.md`、`CLAUDE.md`、`AGENTS.md` 已统一为 `skill + subagents` 架构
- [x] `AGENTS.md` 已明确 `TASKS.md` 的长期跟踪角色
- [x] layout tests 已覆盖 structure handoff / no-silent-fallback 关键词
- [x] `python3 -m unittest tests.test_claude_skill_layout tests.test_claude_agent_layout tests.test_network_debug`
- [x] `python3 -m unittest discover -s tests -p 'test_*.py'`
- [x] `claude agents` 已确认 7 个 project agents 可见
- [ ] Claude Code `/help` 中确认 `/dailynews-report` 可见
- [ ] 条件允许时完成一次 `/dailynews-report` 手动 smoke
- [x] validator 新增覆盖：source-level `article_count` / `status` contradiction 会被阻断
- [x] fallback `validation.json` 在 unexpected-error 分支与正常 schema 同形
- [x] `build_llm_context.py` 不再 import `render_report.py` 的私有 helper
- [x] `tests/test_contracts_snapshot.py`、`tests/test_qc_offline.py`、`tests/test_common_text.py`、`tests/test_pipeline_step.py` 全绿
- [x] 至少一次真实 `python3 scripts/rss_daily_report.py --hours 24 --max-summary 300 --json-output` smoke 通过
- [x] 摘要相关阈值已从硬编码迁移到 `pipeline_config.json`，且测试固定到 fixture config

## Backlog

- [ ] 将 orchestrator skill 的可配置参数进一步显式化，例如时间窗口、摘要长度、保留天数
- [ ] 为常见失败模式补充 supporting skills 或 agent variants
- [ ] 增加更细粒度的自动化验证，覆盖 README / AGENTS / TASKS 的一致性
- [ ] 评估是否为 report-reviewer 增加更严格的结构化审查输出格式
- [ ] 评估是否为 `part1_plan.json` / `part2_draft.json` 增加本地 schema 校验与装配前 fail-fast
