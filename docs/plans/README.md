# 后续迭代计划

- 编写日期：2026-09-28。
- 代码核对基线：`48ed778`；开始编写时工作树干净。
- 范围：原型退役、评估测试修复、高德实网验收、单日覆盖、通勤通知和天气地图。
- N001 已完成开发规则切换、原位冻结及交接验收，记录见 [N001 交接记录](N001-handoff.md)；其他任务按各自实现与验收结果更新。
- 本目录中的优先级、接口提案和默认行为属于项目实施取舍，不是外部平台标准。

## 任务、状态与依赖

本表是 N 系列任务执行状态的唯一维护位置：`[ ]` 待执行、`[~]` 进行中、`[!]` 已进入执行但受外部条件阻塞、`[x]` 验收完成。分项文档只维护步骤、证据和验收条件。

| 编号 | 计划 | 优先级 | 状态 | 实现前置条件 | 原任务关联 |
|---|---|---|---|---|---|
| N001 | [原型退役与开发流程调整](N001.md) | P0 | [x] 验收完成 | 无 | L008、L011、T110、任务完成定义；[交接记录](N001-handoff.md) |
| N002 | [评估测试基线修复](N002.md) | P0 | [x] 验收完成 | N001 | T100、T104、T130；[验收记录](../../android/qa/evaluation-baseline-2026-09-28/README.md) |
| N003 | [高德设备实网验收](N003.md) | P1 | [x] 验收完成 | N002；合适的设备及有效凭据 | T086、T114、T131、T132；[验收记录](../../android/qa/amap-device-2026-09-29/README.md)（[补充验证](../../android/qa/amap-device-2026-09-29/supplement/README.md)；SDK Key 边界见 SPEC FR-012） |
| N004 | [单日覆盖扩展](N004.md) | P1 | [ ] 待执行 | N002 | T010、T011、T025、T076、T100、T103 |
| N005 | [通勤通知与胶囊呈现](N005.md) | P2 | [ ] 待执行 | N002；通知行为和设备能力核实 | FR-009、T036、T124、T132 |
| N006 | [天气预报地图](N006.md) | P2 | [ ] 待执行 | N003；雷达授权、协议及坐标验证 | T090–T097、T112A、T114、T131 |

原任务定义见 [IMPLEMENTATION_TASKS.md](../../IMPLEMENTATION_TASKS.md)。N 系列承接增量实现和专项验收，不以完成一项 N 任务推定关联的整个旧任务范围已经完成。旧任务条目引用新验收记录，N 系列状态只更新本表，避免重复维护。

执行顺序：N001 → N002 → N003、N004；N003 与 N004 可独立排期。N005、N006 的资料和能力调查可提前进行，实现阶段必须满足各自前置条件。N005、N006 的外部条件失败不阻断其他已具备条件的任务。

## 当前证据与历史验证快照

| 事项 | 已核对内容 | 证据 |
|---|---|---|
| 原生主流程 | 首页、闹钟、路线、地点、日历、设置、天气和决策详情已有导航装配 | [ZhituApp](../../android/app/src/main/java/com/ljwzz/weathertrafficalarm/ui/zhitu/ZhituApp.kt)、[调度协调器](../../android/core/alarm/src/main/java/com/ljwzz/weathertrafficalarm/core/alarm/LocalAlarmCoordinator.kt) |
| 验证入口 | 根验证脚本运行 Android 构建及 JVM 测试 | [verify-all.sh](../../scripts/verify-all.sh)、[verify-android.sh](../../scripts/verify-android.sh) |
| 素材依赖 | 导入工具仍读取原型字体及 SVG，Android 使用已导入资源 | [素材导入脚本](../../scripts/import-prototype-assets.py) |
| 高德实网 | Web Key 连接测试、SDK 初始化与原生地图渲染、五种出行方式真实路线与地点选型已在 Xiaomi `25019PNF3C`（API 36）实测；公交折线缺陷已修复 | [N003 验收记录](../../android/qa/amap-device-2026-09-29/README.md)、[只读设备用例](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/AmapReadOnlyDeviceTest.kt) |
| 目标差异 | 天气地图、锁屏出发提醒、胶囊与日级扩展仍需专项计划 | [设计交接](../design-handoff.md)、[规格](../../SPEC.md) |

2026-09-28、代码 `48ed778` 的审查快照如下。该提交时间为 16:48:35 +08:00；失败套件报告时间为 16:50:57 +08:00。本表记录当时结果，不能直接作为后续提交的验收结果。

| 命令／范围 | 当时结果 |
|---|---|
| `./scripts/verify-all.sh` | `:app:assembleDebug` 通过；执行到评估集成测试时因失败退出 |
| 在 `android/` 执行 `./gradlew testDebugUnitTest --continue --offline --console=plain` | 共 386 项：385 通过、1 失败、0 跳过 |
| JVM 模块分布 | app 110、alarm 71、data 109、map 11、model 45、network 40 |
| `node --test prototype/tests/*.test.mjs` | 92 项通过 |
| 唯一失败 | `EvaluationCoordinatorIntegrationTest` 的 `driving fallback queries once and uses the latest fifteen minute candidate`，期望 08:20、实际 08:10 |

快照来自当次命令输出及各模块 `build/test-results/testDebugUnitTest/TEST-*.xml`；构建目录中的报告会被后续运行覆盖。失败复现线索另见 [2026-09-07 历史记录](../../android/qa/settings-editor-2026-09-07/README.md)，该历史记录的数量不与本表合并。N002 执行时重新生成带提交版本的持久验收记录。

Figma 在 2026-09-28 通过连接工具实读，当前页为“知途 · 完整设计 · 2026-09-28”，相关目标节点见各分项计划。原始页面地址：https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=218-2464 。

## 共用交付与验收约定

1. 执行前记录提交版本、工作树差异和环境；仅在所需凭据、设备或协议条件实际满足后进入对应实网阶段。
2. 每项交付实现／规则变更、受影响规格、测试及验收记录。验收记录放在 `android/qa/` 或对应文档中，并从计划反向链接；记录日期、提交版本、命令、实际计数、失败和阻塞项。
3. 文档中标为“拟新增”的类型、用例和命令前置条件，在实现前不视为已有能力。运行新测试前须确认用例已存在；零测试或条件跳过不算通过。
4. 先完成定向回归，再运行项目验证入口；UI、系统通知、地图和权限行为使用对应设备验收。Android 官方将本机测试与设备／模拟器上的 instrumented tests 分别定义：https://developer.android.com/training/testing/fundamentals 。
5. 截图、日志和导出材料遵循 [SECURITY.md](../../SECURITY.md)；使用可公开的测试地点及脱敏证据，检查凭据、签名图片 URL 和个人地点泄露。
6. 所有新任务均从待执行开始。外部条件未成立时记录具体缺项，不将演示图、假数据或请求发出视为实网通过。

## 文档维护检查

- 六项计划都有目标、当前证据、接口影响、步骤、依赖、成功／失败／边界场景、验收命令及完成条件。
- 本地链接必须存在；拟新增文件和未来交付目录使用代码标识，不伪造可点击文件链接。
- 关联 T／L 编号能在原清单中找到；任务依赖无环，任务状态只维护于本页。
- 外部事实使用编写时实际联网核实的原始官方 URL，逐项标注核实日期；不能验证的账号权限、设备支持和协议条件写明“信息不足，无法验证”。
- 只修改文档时检查 Markdown 链接、编号、状态、来源和 `git diff --check`；分项命令在对应任务执行阶段运行。
