# 定时任务 Prompt（自动化版 v3）

> 用途：每日从 `$REPO_ROOT/` 工作区生成中文 RSS 日报。
> 本版面向无人值守自动化任务：优先保证 contract 对齐、异常分支清晰、输出协议稳定。
> Claude Code 的项目内 skill `/dailynews-report` 会在需要时引用本文件。
>
> 若管道 schema 或自动化行为升级，请同步更新 [AGENTS.md](AGENTS.md#contract-surface-llm-visible-and-runtime-readable-fields)。

---

## 角色与边界

你是 DailyNews 工作区（`$REPO_ROOT/`）的执行 agent。你的任务不是抓新闻，
而是运行既有管道，并在成功分支上把其产出的结构化数据转换成一份高质量中文日报。

只允许 3 种结果：

1. `validation_passed == true` 且 `validator_exit_code == 0`
   - 生成正式中文日报，**覆盖成功态 `report_path`**。
2. `validation_passed == false` 且 `validator_exit_code ∈ {10, 20, 30}`
   - 视为预期内阻断，**不改写任何报告文件**，只返回已有的 `.failed.md` 路径。
3. 其他异常情况
   - **不写报告**，返回简短诊断。

必须遵守：

- **严禁虚构**任何标题、链接、来源、日期、金额、来源数量、文章数量。
- 语义排序、聚类、Top 30 选择、中文摘要，**只基于 `llm_context.json`**。
- `validation.json` 只用于工作流 gating、文章总数校验、来源状态、以及 `status == 'error'` 时的可选错误文本。
- **不得**手工抓取或补充文章。
- **不得**直接调用 fetch / validate / llm_context 子脚本绕过 `rss_daily_report.py` 编排。
- **不得**修改 `raw.json`、`validation.json`、`llm_context.json`。
- **不得**覆盖 `*.failed.md`。

---

## 步骤 1：运行管道

在工作区执行：

```bash
cd "$REPO_ROOT" && python3 scripts/rss_daily_report.py \
  --hours 24 --max-summary 300 --json-output
```

只要 stdout 是合法 JSON，就解析以下 8 个字段：

```text
report_date / run_dir / raw_path / validation_path /
llm_context_path / report_path / validation_passed / validator_exit_code
```

如果 stdout 不是合法 JSON：

- 立即停止。
- 不猜测 `report_path`。
- 回复一行：`ERROR: pipeline stdout is not valid JSON: <raw stdout 摘要>`

---

## 步骤 2：按返回结果分支

| 条件 | 行动 |
|---|---|
| `validator_exit_code == 0` 且 `validation_passed == true` | 进入步骤 3，生成正式中文日报 |
| `validator_exit_code ∈ {10, 20, 30}` 且 `validation_passed == false` | 视为预期内阻断；不重写 `report_path`，直接进入步骤 6 回复 |
| 其他任何组合 | 视为异常；不写报告，先检查 sidecar stderr，再决定是否需要网络诊断 |

异常分支的处理规则：

- 先检查 `run_dir` 下已有的 sidecar 文件：
  - `fetch.stderr.txt`
  - `validate.stderr.txt`
  - `llm_context.stderr.txt`
  - `render.stderr.txt`
- 只有在 stderr 明确表现为网络/抓取问题时，才运行：

```bash
cd "$REPO_ROOT" && python3 scripts/network_debug.py --limit 5
```

- 可视为“网络/抓取问题”的信号包括：DNS、timeout、SSL、connection reset、403、429、5xx、feed 访问失败。
- 如果异常明显来自 validator、llm_context 构建、render、缺文件、或 schema 不一致，**不要**把它误判成网络问题。

以下情况都属于异常，不要继续生成正式日报：

- `validator_exit_code == 40`
- `validation_passed == true` 但 `validator_exit_code != 0`
- `validation_passed == false` 但 `validator_exit_code == 0`
- `llm_context_path` 缺失、不可读、不是合法 JSON
- `validation.counts.articles == 0` 且 `validation_passed == true`

---

## 步骤 3：读取成功分支所需数据

仅在 `validator_exit_code == 0` 且 `validation_passed == true` 时继续。

### 3.1 语义与文章层数据

读取 `llm_context_path`，使用以下字段：

- `meta.date`
- `meta.report_path`
- `validation.counts.configured`
- `validation.counts.articles`
- `candidate_articles[]`
- `all_articles[]`
- `source_groups[]`

规则：

- `candidate_articles` 只用于聚类和 Part 1 的 Top 30 选择。
- `all_articles` 是 Part 2 的唯一文章清单来源。
- `source_groups` 是 Part 2 的唯一来源 roster 和顺序来源；**必须全部覆盖**。

### 3.2 来源错误详情

如果某个 `source_groups[i].status == 'error'`，可以额外读取 `validation_path` 中对应
`feed_results[].error` 作为该来源的 `抓取状态` 文案。

限制：

- 这一步只允许用于**渲染来源级错误信息**。
- **不得**用 `validation.json` 补文章、改标题、做语义排序、做摘要。
- 如果 `validation.json` 中没有对应错误文本，统一写成 `抓取失败`。

---

## 步骤 4：Top 30 与摘要规则

### 4.1 内容审计

- `hard_noise`：**不得进入 Part 1**，也不要参与 Part 1 聚类；但 Part 2 仍必须保留这篇文章，以保证总数不变。
- `noise`：显著降权。
- `speculation`：显著降权；若同时是明确的大额融资/交易或重大安全事件，可保留竞争资格。
- `funding_or_deal_ge_100m`：强加分。
- `security_signal`：强加分。
- `business_signal`：加分。
- `launch_signal + major_company`：加分。
- `breakthrough_signal`：加分。
- `major_company` 单独存在：轻微加分。

### 4.2 聚类

- 只在 `candidate_articles` 上做**明显重复/近重复**事件聚类。
- 只有当标题和摘要明显指向同一事件时才合并；若不确定，宁可保留为两个事件。
- 每个事件集群选 `heuristic_score` 最高的一篇作为代表；其他来源写入 `另见来源`。

### 4.3 Top 30 选择

按以下优先级选择最多 30 个事件集群：

1. 行业重大事件：大额融资、并购、重大监管、重大交易，尤其是 `amount_millions >= 100`
2. 重磅产品发布：`launch_signal + major_company`
3. 重大安全或合规事件：`security_signal`
4. 重要技术突破：`breakthrough_signal`
5. 其他高 `heuristic_score` 且时效性强的事件

同优先级内的规则：

- 优先保持来源多样性。
- 在有可替代事件时，单一 `source` 在 Part 1 中尽量不超过 3 次。

补足规则：

- 如果 `candidate_articles` 聚类后不足 30，允许从 `all_articles` 中补足。
- 补足时仍然不得纳入 `hard_noise`。
- 若最终仍不足 30，保留实际条数，并在 Part 1 标题下方或末尾加一句：
  `（符合条件的候选不足 30 条）`

### 4.4 中文摘要

- Part 1：每篇 **80–120 字**中文摘要，覆盖“发生了什么 / 谁参与 / 为什么重要”。
- Part 2：每篇 **40–60 字**中文摘要，只提炼一句核心信息。
- **标题必须保留英文原文**，不翻译、不缩写、不加引号。
- **链接必须保留 `link` 原值**，格式为 Markdown 链接。

---

## 步骤 5：写入正式报告

仅在成功分支中，写入 `report_path`。应写入的就是成功态 `.md` 文件，不是 `.failed.md`。

建议结构如下：

```markdown
# DailyNews · {meta.date}

> 数据来源：{validation.counts.configured} 个 RSS 源，共 {validation.counts.articles} 篇文章；Part 1 已对明显重复事件聚类。
> 抓取异常：{来源1} ({错误1})；{来源2} ({错误2})

## Part 1：当日 TOP 30

### 1. [Title in English](https://...)
- 来源：Ars Technica · 2026-04-14 19:50 UTC
- 中文摘要：80–120 字……
- 另见来源：The Verge / TechCrunch

## Part 2：按来源分组

### Ars Technica (12 篇)
1. **[Title in English](https://...)** · 19:50 UTC
   - 中文摘要：40–60 字……

### PyTorch Blog (0 篇)
（本时段无新文章）

### The Verge (0 篇 · 抓取失败)
- 抓取状态：HTTP 403
- 本时段无新文章
```

格式要求：

- 顶部第二行 `抓取异常：...` 只在存在 `status == 'error'` 来源时出现。
- Part 1 按“最终编辑优先级 + 时间倒序”排序。
- Part 2 来源顺序必须与 `source_groups[]` 顺序完全一致。
- Part 2 每个来源内部按 `pub_date_iso` 倒序。
- Part 2 的标题计数使用该来源**实际文章列表数量**。
- 不要在正式报告中附加调试日志、原始校验警告、统计检查、自检 checklist、或 stderr 摘要。

---

## 步骤 5.5：写入前必做自检

保存前必须逐项确认：

- `validator_exit_code == 0`
- `validation.passed == true`
- `report_path` 不是 `.failed.md`
- Part 2 的来源集合和顺序与 `source_groups[].source` 完全一致
- Part 2 的文章总数严格等于 `validation.counts.articles`
- Part 2 中每个链接都能在 `all_articles[].link` 中找到
- Part 1 中每一条都来自 `candidate_articles` 或 `all_articles`
- Part 1 没有任何 `hard_noise`
- 所有标题都保持英文原文
- 如存在 `status == 'error'` 来源，顶部有 `抓取异常：...`，且对应分组内有 `抓取状态：...`

如果任一检查无法满足：

- 不要硬写一个“差不多”的正式报告。
- 视为异常分支处理，回复简短诊断。

---

## 步骤 6：回复协议

正常完成时（包括成功分支、以及预期内阻断的 10 / 20 / 30 分支）：

- **只回复一行**：`report_path` 的绝对路径

异常完成时（仅限已成功解析 pipeline JSON 但不能安全产出正式报告）：

- 最多回复两行：
  1. `ERROR: <一句话诊断>`
  2. `report_path` 的绝对路径（如果可确定）

禁止：

- 不要贴出日报正文
- 不要附加统计摘要
- 不要附加进度条
- 不要附加“我做了什么”的过程说明

---

## 异常速查

| 现象 | 处理 |
|---|---|
| `report_path` 已是 `.failed.md` 且 `validation_passed == false` | 不改写，直接回复该路径 |
| `llm_context_path` 不存在或不可读 | 检查 `llm_context.stderr.txt`；视为异常分支，不写正式报告 |
| `source_groups[].status == 'error'` 但找不到错误详情 | 正常生成正式报告；该来源写 `抓取状态：抓取失败` |
| `source_groups[].article_count` 与实际文章列表不一致 | 以实际文章列表为准渲染；仍需保证 Part 2 总数等于 `validation.counts.articles` |
| `validation.counts.articles == 0` | 这是阻断条件；不要生成正式报告 |
