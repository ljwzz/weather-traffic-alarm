# N001 原型冻结与原生交接记录

执行日期：2026-09-28。代码基线：`48ed778`。本记录对应 [N001 计划](N001.md)；执行结果见末节。`SPEC.md` 是后续产品及交互契约，Android 构建、测试和适用设备记录是交付证据。Figma 与 Web 原型在原路径冻结，用于追溯历史设计、离线交互和素材。

## 设计目标、原型路由与 Android 状态

本轮通过 Figma 连接工具读取原设计页 [`218:2464`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=218-2464) 的目标节点及文字，读取 [`prototype/app.js`](../../prototype/app.js) 的路由和 [`ZhituDestination`](../../android/app/src/main/java/com/ljwzz/weathertrafficalarm/ui/zhitu/ZhituState.kt)。原页面已增设归档说明节点 [`290:683`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=290-683)，并标注冻结日期、代码基线及目标归属。

| 设计目标与原型 | Android 当前状态及证据 | 后续归属 |
|---|---|---|
| 天气预报地图 `223:3721`；`weather` 页面显示离线天气状态和示意图 | `WEATHER` 目的地提供天气摘要，[彩云设备记录](../../android/qa/caiyun-device-2026-09-02.md)覆盖现有天气；导航中无雷达图层页面或时间轴 | [N006](N006.md)：雷达授权、真实帧、图层、时间轴和设备校准 |
| 锁屏出发提醒 `223:4010`；`lock` | 当前响铃通知属基础／提前闹钟；本地设置保留摘要字段，但通勤提醒尚无消费者 | [N005](N005.md)：标准通勤通知、动作、隐私和恢复 |
| 胶囊摘要 `223:4069`、展开 `223:4183`；`island`、`island-expand` | Android 导航无对应目的地；Figma 画板注明概念适配 | [N005](N005.md)：设备能力核实及适配验收 |
| 单日时间与缓冲覆盖 `223:4501`；`overtime-select`、`overtime-active` | [`LocalCalendarScreen`](../../android/app/src/main/java/com/ljwzz/weathertrafficalarm/ui/zhitu/ZhituApp.kt) 和 [`LocalAlarmCoordinator`](../../android/core/alarm/src/main/java/com/ljwzz/weathertrafficalarm/core/alarm/LocalAlarmCoordinator.kt) 已处理指定计划／日期的启停和时间；[调度测试](../../android/core/alarm/src/test/java/com/ljwzz/weathertrafficalarm/core/alarm/LocalAlarmCoordinatorTest.kt)覆盖单日变更。到岗、准备、天气缓冲及通勤的日级字段尚未接入 | [N004](N004.md)：扩展字段、继承、迁移、评估及原生日期编辑 |
| 决策详情 `223:3919`、失败详情 `223:4317`、提前响铃详情 `228:3086`；`why`、`failure`、`ringing` | `DECISION_DETAIL` 已接入，[第二阶段原生验收](../../android/qa/decision-details-2026-09-07/README.md)及[设备测试](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/DecisionNavigationDeviceTest.kt)记录关联与返回 | 已实现；后续按原生证据维护 |

原型 `weather` 与 Android `WEATHER` 同名但覆盖范围不同：现有 Android 只提供天气摘要；降水图层属于 N006。单日基础启停／时间与 N004 的日级多字段也分别记录，避免用已有覆盖推定扩展字段已交付。

## 交互用例承接

| 冻结的原型用例 | 对应原生测试／验收 | 增量待补 |
|---|---|---|
| 闹钟创建、计划保存、单日启停／时间、工作日四周预览；[`state.test.mjs`](../../prototype/tests/state.test.mjs)、[`legal-calendar.test.mjs`](../../prototype/tests/legal-calendar.test.mjs) | [`AlarmScheduleResolverTest`](../../android/core/model/src/test/java/com/ljwzz/weathertrafficalarm/core/model/AlarmScheduleResolverTest.kt)、[`LocalAlarmCoordinatorTest`](../../android/core/alarm/src/test/java/com/ljwzz/weathertrafficalarm/core/alarm/LocalAlarmCoordinatorTest.kt)、[四周预览设备记录](../../android/qa/workday-preview-2026-09-05/README.md) | N004 补日级多字段编辑、持久化迁移和设备用例 |
| 时间滚轮、编辑草稿与取消；[`time-wheel.test.mjs`](../../prototype/tests/time-wheel.test.mjs)、[`app-permission.integration.test.mjs`](../../prototype/tests/app-permission.integration.test.mjs) | [`AlarmTimeWheelDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/AlarmTimeWheelDeviceTest.kt)、[设置与编辑设备记录](../../android/qa/settings-editor-2026-09-07/README.md) | N004 补按日期保存／撤销的原生交互 |
| 基础／提前响铃停止、贪睡、子实例及重复动作；[`ringing-state.test.mjs`](../../prototype/tests/ringing-state.test.mjs)、[`app-ringing.integration.test.mjs`](../../prototype/tests/app-ringing.integration.test.mjs) | [`LocalAlarmDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/LocalAlarmDeviceTest.kt)、[`RingingScreenDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/RingingScreenDeviceTest.kt)、[原生响铃记录](../../android/qa/native-ringing-2026-09-02/README.md) | 原型的“模拟再次响铃”按钮仅属历史演示，不作为原生行为 |
| 权限引导、返回复查、手工确认、定位拒绝；[`permission-state.test.mjs`](../../prototype/tests/permission-state.test.mjs)、[`app-permission.integration.test.mjs`](../../prototype/tests/app-permission.integration.test.mjs) | [`PermissionGuideDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/PermissionGuideDeviceTest.kt)、[`LocationPermissionScreenDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/LocationPermissionScreenDeviceTest.kt)、[权限设备记录](../../android/qa/permissions-2026-09-03/README.md) | 特定设备实际设置效果仍以对应设备记录为准 |
| 首页天气／路线预览、失败旧结果、刷新隔离；[`screens-travel.test.mjs`](../../prototype/tests/screens-travel.test.mjs) | [`HomeProviderCardsDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/HomeProviderCardsDeviceTest.kt)、[`HomePreviewIsolationDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/HomePreviewIsolationDeviceTest.kt)、[首页原生验收](../../android/qa/home-preview-2026-09-05/README.md) | N003 补高德真实凭据、路线与地图设备实网；N006 补真实天气图层 |
| 决策快照、失败详情及关联导航；[`screens-alarm-decision.test.mjs`](../../prototype/tests/screens-alarm-decision.test.mjs) | [`DecisionNavigationDeviceTest`](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/DecisionNavigationDeviceTest.kt)、[第二阶段原生验收](../../android/qa/decision-details-2026-09-07/README.md) | N002 修复评估 JVM 基线；N004／N005 增量分别补日期输入与提醒快照 |
| 锁屏通勤动作、胶囊展开；`lock`、`island`、`island-expand` | 原生无对应通勤通知设备测试 | N005 新增标准通知及能力适配的原生测试和设备矩阵 |
| 未来两小时降水地图、图层播放；`weather` 的历史演示 | 原生无雷达帧和图层设备测试 | N006 新增 Provider、地图叠加及真实服务设备记录 |

原型测试从日常产品回归转为归档完整性检查。其断言仅证明冻结的离线样例可运行；表中增量在对应 N 计划交付前仍标为待补。

## 素材与历史 QA 追溯

[`scripts/import-prototype-assets.py`](../../scripts/import-prototype-assets.py) 是按需维护工具，从 [`prototype/assets/figma-svg/`](../../prototype/assets/figma-svg/) 的 10 个指定 SVG 生成 `android/app/src/main/res/drawable/ic_figma_*.xml`，并从 [`prototype/assets/fonts/`](../../prototype/assets/fonts/) 复制 4 个字体及 2 个许可证文件。源目录及已导入 Android 资源均原位保留。

| 来源 | Android 产物 | 对应关系 |
|---|---|---|
| `NotoSansCJKsc-{Regular,Medium,Bold}.otf` | [`res/font/`](../../android/app/src/main/res/font/) 的 `noto_sans_sc_{regular,medium,bold}.otf` | 文件复制；许可证 [`LICENSE-Noto-CJK.txt`](../../android/app/src/main/assets/licenses/LICENSE-Noto-CJK.txt) 来自原型字体目录 |
| `Roboto-Variable.ttf` | [`roboto_variable.ttf`](../../android/app/src/main/res/font/roboto_variable.ttf) | 文件复制；许可证 [`LICENSE-Roboto-OFL.txt`](../../android/app/src/main/assets/licenses/LICENSE-Roboto-OFL.txt) 来自原型字体目录 |
| 导入脚本 `ICONS` 字典指定的 `home`、`route`、`plans`、`settings`、`shield`、`sound`、`calendar`、`preparation`、`snooze`、`weather` SVG | [`res/drawable/ic_figma_*.xml`](../../android/app/src/main/res/drawable/) | 脚本按原 SVG 路径转换；每个 XML 头部保留源 SVG 文件名 |

历史 Web QA 路径仍在 [`prototype/qa/`](../../prototype/qa/)；Figma 节点和当时的浏览器验收见 [`docs/design-handoff.md`](../design-handoff.md)。历史原生 QA 仍在 [`android/qa/`](../../android/qa/)；两类记录的验证对象分别标明，旧结论不批量改写。

## 生效范围与本轮验证

自 2026-09-28 起，`AGENTS.md`、`README.md`、`SPEC.md`、`IMPLEMENTATION_TASKS.md` 的有效规则已改为规格、Android 实现及原生验收。`docs/design-handoff.md`、`prototype/README.md` 和原 Figma 页面有冻结说明。后续复杂新交互可按需另建设计稿，确认的行为写入规格。归档素材工具仅在资源维护时执行。

本轮结果（仓库根目录执行）：

| 检查 | 实际结果 |
|---|---|
| `git diff --check` | 通过 |
| 六份有效文档中 `Figma`／`prototype/`／`原型`／`同步` 逐处复核及新增 Markdown 本地链接检查 | 有效规则一致；新增及受影响文档的本地链接均存在。历史段落保留原意，并由文件顶部冻结说明限定适用时间 |
| 素材来源检查 | 脚本指定 10 个 SVG 源和 10 个 Android XML 均存在，XML 注释包含源文件名；4 对字体和 2 对许可证的源／目标 SHA-256 内容一致 |
| `node --test prototype/tests/*.test.mjs` | 92 通过、0 失败；本机回环服务用例亦通过 |
| `./scripts/verify-all.sh` | `assembleDebug` 完成；JVM 386 项中 385 通过、1 失败，脚本退出码 1。失败为 `EvaluationCoordinatorIntegrationTest` 的 `driving fallback queries once and uses the latest fifteen minute candidate`，期望时间戳 `1790641200000`、实际 `1790640600000`，与 [N002 计划](N002.md)已登记的基线问题相同；本任务未修改业务代码 |

首次在受限运行环境执行时，本机 `127.0.0.1` 监听及用户 Gradle 缓存锁文件分别触发 `EPERM`；获得对应运行权限后使用原命令复测，以上表中复测结果为准。Android 构建和所有其他 JVM 模块均通过，N002 负责修复剩余评估断言。
