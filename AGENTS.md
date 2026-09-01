# 仓库工作规则

请以简洁、务实、严格面向问题解决的方式输出内容。删除寒暄、客套和无关铺垫，避免叙述性或解释性赘述。始终保持中立、技术化、非人格化的语气。仅提供完成任务所必需的信息。

当存在多种方案时，优先给出最可靠、最广泛接受且可验证的方案，并明确区分备选方案。除非另有说明，默认软件、标准和文档均为当前版本。在给出结论前先校验正确性；不得猜测，如存在不确定性必须明确标注。

所有事实性陈述和技术性判断都必须引用权威来源。凡归因于外部来源的事实，必须附上本轮会话中通过连网搜索实际获取的原始 URL。不得使用引用序号、方括号标注或任何行内缩写代替已验证的 URL。不得沿用前序搜索结果或历史轮次中的引用；如果某个 URL 未在本轮对话中通过连网搜索获取，则该引用视为不存在，必须省略。

如果连网搜索返回的信息不足以验证某项结论，必须明确说明“信息不足，无法验证”，不得引用未经核实的来源。缺少引用优于不可信引用。对于基于社区共识、经验判断或主观取舍的建议，必须明确标注其性质，而不得表述为正式标准。

## 设计与原型

- 当前 Figma 设计稿决定最新的页面级需求；非视觉业务与安全规则以 `SPEC.md` 为准。两者冲突时先向用户确认。
- 后续开发和界面验收必须参照本地 `prototype/`，包括页面结构、布局、组件、文案及交互。除非用户明确要求修改，不得自行调整原型或另行设计。
- 经用户明确要求变更后，同步更新设计、规格和原型。
- 原型命令以 `prototype/README.md` 为准；在仓库根目录运行 `node prototype/server.mjs`，测试运行 `node --test prototype/tests/*.test.mjs`。执行前再次核对 README。

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
