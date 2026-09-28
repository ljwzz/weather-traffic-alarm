# 仓库工作规则

请以简洁、务实、严格面向问题解决的方式输出内容。删除寒暄、客套和无关铺垫，避免叙述性或解释性赘述。始终保持中立、技术化、非人格化的语气。仅提供完成任务所必需的信息。

当存在多种方案时，优先给出最可靠、最广泛接受且可验证的方案，并明确区分备选方案。除非另有说明，默认软件、标准和文档均为当前版本。在给出结论前先校验正确性；不得猜测，如存在不确定性必须明确标注。

所有事实性陈述和技术性判断都必须引用权威来源。凡归因于外部来源的事实，必须附上本轮会话中通过连网搜索实际获取的原始 URL。不得使用引用序号、方括号标注或任何行内缩写代替已验证的 URL。不得沿用前序搜索结果或历史轮次中的引用；如果某个 URL 未在本轮对话中通过连网搜索获取，则该引用视为不存在，必须省略。

如果连网搜索返回的信息不足以验证某项结论，必须明确说明“信息不足，无法验证”，不得引用未经核实的来源。缺少引用优于不可信引用。对于基于社区共识、经验判断或主观取舍的建议，必须明确标注其性质，而不得表述为正式标准。

## 产品开发与验收

- `SPEC.md` 是业务、交互、安全和页面行为契约；需求变更先更新规格，再直接修改 Android 实现及相应测试。
- 界面验收依据 Android 构建、自动化测试和适用设备的实际操作记录；设备能力与实网行为必须保留对应设备证据。新复杂交互可按需另建设计稿，确认后将行为契约写入规格。
- `docs/design-handoff.md`、现有 Figma 页面和 `prototype/` 已于 2026-09-28 原位冻结，作为历史设计、离线交互和素材来源的追溯资料；不作为后续逐次开发的同步清单。差异及未完成目标见 `docs/plans/N001-handoff.md`。
- 需要复核归档原型时，先核对 `prototype/README.md` 中的运行和测试命令；原型测试只证明归档内容可运行。

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
