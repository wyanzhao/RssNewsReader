# 选题质量审查 — 2026-09-07

对象：S25 导出的 `rss-20260907T111459Z-428562a4-a1`，0.7.4。
原始池 47 篇，入选候选 21 篇，排除 26 篇，最终 18 条。
来源池 SHA-256：`5727027ef9eb8180bc562bc2a638320d386e7af3af09e8e78492ea2fba540880`。

这是对已提供材料的定点审查，不是外部事实核查，也不是已完成的盲测。
本次 `editor_feedback` 为空，不能据此评价个性化效果。

## 需要追踪的问题

1. **缓存摘要的证据范围：证据不足。** `An Alien Mind` 的入选摘要包含“思维链监控能力减弱”“递归自我改进”“第三方安全门槛”等细节；当前该原文的 `article_text` 片段和 `summary_en` 不直接包含全部细节。`part1_shortlist_context.json` 已注入同样的缓存摘要，而文章池内另有 AIHOT 转述包含这些内容。因此不能直接判为虚构，但当前主链接的截断材料不足以独立支持全部表述，缓存条目也没有逐项记录支持来源。后续应比较清空缓存与复用缓存的结果，并保留支持材料/来源归属，不能用格式校验代替事实归因审查。
2. **新闻价值与宣传内容的边界：待编辑判断。** `Supporting independent journalism in Ukraine` 的原文明确标记联合新闻稿，最终入选第 18 条；另一消费产品介绍因“产品宣传、重要性低”被排除。这不自动证明双重标准：公共利益项目可能有不同价值。但需要在盲评中给出可解释的事件影响依据，防止仅凭厂商品牌提升优先级。不要把所有官方公告按关键词机械排除。
3. **具体排除理由的证据强度：待逐项评审。** `Enthusiast says DLSS 5 ...` 被以“无可验证测试证据”排除，但标题同时声称存在测试。是否可信需要基于已抓取正文判断；仅凭标题中的 says 不能推出没有证据。此处应区分“来源证据不足以采信”和“没有测试证据”，避免把不确定性写成事实。

## 已验证与未验证

- 已验证：引用来自本次原始池；选入/排除无重叠，完整覆盖；每项排除理由非空且满足长度约束。
- 未验证：偏好对相关性的因果改善、重大事件遗漏率、所有摘要细节的充分来源支持、无新增进展重复率的跨运行改善。
- 后续比较必须使用相同原始池，并控制历史事件、共享缓存、模型/提示词/解码设置。单纯比较两天不同新闻的 Top N 没有意义。

## 可复用评审工具

从仓库根目录执行：

```sh
python3 android/tools/editorial_review.py --run /path/to/export.zip --output /new/review-directory
python3 android/tools/editorial_review.py --run /path/to/baseline.zip --run /path/to/candidate.zip --output /new/comparison-directory
python3 -m unittest discover -s android/tools/tests -v
```

工具只读取导出 ZIP，不读取密钥，也不发起模型请求。输出 `review.md`、`source-evidence.json` 和独立的 `unblinding.json`。评审者先看前两份，评分完成后再揭盲；不得提前给评审者 arm 与文件名的映射。工具校验不涵盖所有运行时混杂因素，具体边界写在评审说明中。输出目录必须是新目录，防止覆盖既有评审记录。


## Controlled preference trial on S25 (0.8.0)

Experiment `comparison-8cd52877-75aa-4882-aac5-7efb1e4f60fa` completed on the
47-article source snapshot, pool SHA-256
`5727027ef9eb8180bc562bc2a638320d386e7af3af09e8e78492ea2fba540880`.
All original exported artifacts are byte-for-byte unchanged. Both arms used
zero cached summaries, identical history/model/config/prompt revisions, and two
successful physical model calls each. The baseline selected 32 and excluded 15,
then produced 18 events; the preference arm selected 19 and excluded 28, then
produced 13 events. Reported OpenRouter charges were respectively USD 0.0067850
and USD 0.00453310 (total 0.01131810). This one sequential trial is not a latency,
cost-saving or causal quality benchmark.

The separate local `preference-blind-review/` packet passed identical-pool,
configuration, history and source-reference checks. No independent blind user
verdict has yet been received. The following inspection is **not blinded**:

- Preference relevance: Kioxia's CXL/XL-Flash article moved from rank 15 to 7;
  the AI data-center accountability article moved from 17 to 6. Phone-launch
  coverage and executive-event-video details disappeared. This supports a change
  toward the requested direction in this sample, not comprehensive improvement.
- Ranking remains weak: App Store monetization still leads, while OpenAI research
  acceleration moved from rank 3 to 13. The preference does not consistently
  control the final ordering.
- Coverage loss: the preference shortlist retained `An Alien Mind`, while its
  exclusion reasons discarded secondary coverage promising to keep the official
  article. The final plan omitted that event altogether. Shortlist explanations
  do not yet account for every omission between shortlist and final digest.
- Faithfulness, insufficient evidence: the preference arm says the automated AI
  researcher goal has been achieved. Its sole cited source excerpt says “We aim
  to safely build an automated AI researcher” and ends the achieved-goal sentence
  at “of having an...”. That truncation cannot support the stronger completed-
  researcher claim. The baseline also cites secondary articles through
  `also_links`; these must be considered before judging its additional details.
- The Kioxia capacity/performance figures explicitly remain company claims in
  both summaries and are present in the article excerpt. No device-performance
  conclusion is inferred from them.

Next bounded correction: retain evidence and merged sources through final
selection, record final-stage omissions, and enforce the distinction between a
stated future goal and a source-supported completed milestone. Do not simply
increase item counts or declare preference tuning finished.


## 0.8.1 correction under test

Final shortlist-to-plan accounting is now a deterministic gate, with explicit
final exclusions and merged-source references in the review packet. The ranking
prompt now prioritizes explicit subject preference over ordinary unrelated
business news and calls out incomplete source sentences and future-goal claims.
The latter is a model instruction, not proof of semantic correctness.

S25 experiment `comparison-bff03596-5c9f-4f9a-a9e8-8c2ea45fe416` failed. Its baseline demonstrated
that invalid shortfall arithmetic and overlapping selected/excluded references
are rejected rather than silently published. Those retries are real additional
usage and must remain in the comparison cost/first-pass record.

The candidate exhausted three plan attempts with overlapping references or missing
final exclusions. The failed experiment consumed eight measured calls with total
reported charge USD 0.02917859849; the packet tool rejected its incomplete pair.
No preference or semantic improvement conclusion is possible from this trial.

The corrective retry feedback now enumerates actual missing/duplicate source IDs
and supplies the previous rejected draft as data, so repair need not reconstruct
an entire plan from a generic error. Revalidation still requires the complete
corrected object and every publication contract; no automatic dropping/deduping
or fabricated exclusion reason is permitted.


## Revised 0.8.1 device result

Experiment `comparison-50be25f1-951d-4436-add4-82a0c16382c4` completed. The
47-article pool and all original artifacts stayed unchanged. Both arms passed on
first attempts (two measured calls each). Baseline: 27 shortlisted, 21 events,
5 final exclusions and one merged source; USD 0.0055838. Preference: 25 shortlisted,
11 events, 13 final exclusions and one merged source; USD 0.00502530. Both passed
exact final accounting and paired packet validation. This observed first-pass
success does not prove the targeted repair path improved model reliability,
because neither arm needed it; that path has deterministic regression coverage.

Unblinded source review: Kioxia and the AI data-center accountability story now
rank first and second in the preference arm, while ordinary App Store news is
rank eight. However, it excludes the OpenAI research-acceleration report as
“official promotional/self-reported material rather than an independent event”.
That is an inconsistent source policy: the Kioxia technical claims are also
company-reported, and the supplied OpenAI summary explicitly describes early
agent-usage and experiment-velocity data. Company attribution and evidence limits
should constrain the summary, not automatically disqualify relevant technical
reporting. This remains the next B1 correction; the candidate's exclusion cannot
be counted as proof that its former researcher-completion overclaim was fixed.
Baseline wording is more cautious but still completes a truncated achieved-goal
sentence as a supervised research system, which remains insufficiently supported.
The major research item should be summarized from available complete statements
without inventing the missing milestone. Independent blind judgments remain open.


## 0.8.2 evidence boundary and failed acceptance

Official-source rules now preserve technical self-reports with attribution rather
than automatically rejecting them as promotional. The editorial input projection
withholds an explicitly ellipsis-terminated final fragment, records
`article_text_tail_omitted`, and withholds potentially contaminated cached
summaries. Raw source material remains available unchanged for audit.

S25 experiment `comparison-44c08cc3-19f1-4143-9763-afe5f9b6095a` verifies that the
research-acceleration achieved-goal fragment is absent from model input. It does
not verify final quality: the baseline exhausted three plan attempts on overlap,
more than 30 events and incorrect shortfall arithmetic; candidate never ran.
Four calls cost USD 0.01583322. No partial pair is publishable. The remaining
B1 acceptance is explicit, while full-chain A2/B2 accounting is the next bounded
implementation task. No new same-input paid retry is implied by this record.
