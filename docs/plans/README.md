# 后续迭代计划

产品行为以 [SPEC.md](../../SPEC.md) 为准，验证统一使用 [项目验证](../validation.md)。本表维护 N 系列状态：`[ ]` 待执行、`[~]` 进行中、`[!]` 外部条件阻塞、`[x]` 完成。

| 编号 | 内容 | 优先级 | 状态 | 依赖／实现入口 |
|---|---|---|---|---|
| N001 | 原型退役与开发流程调整 | P0 | [x] 完成 | [原型与原生交接](N001-handoff.md)、[仓库规则](../../AGENTS.md) |
| N002 | 评估测试基线修复 | P0 | [x] 完成 | [评估集成回归](../../android/app/src/test/java/com/ljwzz/weathertrafficalarm/evaluation/EvaluationCoordinatorIntegrationTest.kt) |
| N003 | 高德设备实网验收 | P1 | [x] 完成 | [设备实网用例](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/AmapReadOnlyDeviceTest.kt)、SPEC FR-012 |
| N004 | 单日覆盖扩展及逻辑修复 | P1 | [x] 完成 | [调度回归](../../android/core/alarm/src/test/java/com/ljwzz/weathertrafficalarm/core/alarm/DayOverrideCommitRegressionTest.kt)、[设备用例](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/SingleDayOverrideDeviceTest.kt)、SPEC FR-007 |
| N005 | [通勤通知与胶囊呈现](N005.md) | P2 | [ ] 待执行 | N002；通知行为与目标设备能力 |
| N006 | [天气预报地图](N006.md) | P2 | [ ] 待执行 | N003；雷达接口与账号授权 |

原任务关联见 [IMPLEMENTATION_TASKS.md](../../IMPLEMENTATION_TASKS.md)。N005 与 N006 可独立推进各自前置调查；完成一项增量不代表关联旧任务的全部范围完成。

## 维护约定

- 需求变更先更新规格，再修改实现及正式测试；状态只维护于本表。
- 待执行计划保留目标、接口影响、依赖和完成条件；已交付行为维护在规格及源码中。
- 平台、账号和设备条件按实际能力核实；外部条件不满足时写明具体阻塞项。
- 执行按通用验证流程进行，在交付说明中报告实际范围与结果。
