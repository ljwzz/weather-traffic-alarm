# 原型与原生交接

`prototype/` 与 Figma 设计用于历史交互和素材追溯。产品行为以 [SPEC.md](../../SPEC.md) 为准；后续状态见 [计划总览](README.md)，验证流程见 [项目验证](../validation.md)。设计节点与路由映射见 [设计交接](../design-handoff.md)。

## 有效目标与实现入口

| 内容 | 原生实现／回归 | 后续归属 |
|---|---|---|
| 天气摘要与预报地图 | [WEATHER 目的地](../../android/app/src/main/java/com/ljwzz/weathertrafficalarm/ui/zhitu/ZhituState.kt) | [N006](N006.md)：真实雷达帧、图层和时间轴 |
| 锁屏出发提醒与胶囊 | SPEC FR-009；现有响铃通知独立处理基础与提前闹钟 | [N005](N005.md)：通勤通知、动作、隐私及设备适配 |
| 单日到岗、准备、天气与通勤覆盖 | [日期解析](../../android/core/model/src/main/java/com/ljwzz/weathertrafficalarm/core/model/SingleDayOverride.kt)、[调度协调器](../../android/core/alarm/src/main/java/com/ljwzz/weathertrafficalarm/core/alarm/LocalAlarmCoordinator.kt)、[设备回归](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/SingleDayOverrideDeviceTest.kt) | 按规格维护字段继承、日期修订、冲突和补偿 |
| 决策快照、失败详情及导航 | [导航回归](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/DecisionNavigationDeviceTest.kt) | 历史记录按自身快照展示 |

## 用例承接

| 交互 | 原生回归入口 |
|---|---|
| 计划保存、日期规则、实例与恢复 | [AlarmScheduleResolverTest](../../android/core/model/src/test/java/com/ljwzz/weathertrafficalarm/core/model/AlarmScheduleResolverTest.kt)、[LocalAlarmCoordinatorTest](../../android/core/alarm/src/test/java/com/ljwzz/weathertrafficalarm/core/alarm/LocalAlarmCoordinatorTest.kt) |
| 时间滚轮、编辑草稿、取消 | [AlarmTimeWheelDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/AlarmTimeWheelDeviceTest.kt)、[MergedSettingsEditorDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/MergedSettingsEditorDeviceTest.kt) |
| 响铃、停止、贪睡及重复动作 | [LocalAlarmDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/LocalAlarmDeviceTest.kt)、[RingingScreenDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/RingingScreenDeviceTest.kt) |
| 权限引导与定位用途 | [PermissionGuideDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/PermissionGuideDeviceTest.kt)、[LocationPermissionScreenDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/LocationPermissionScreenDeviceTest.kt) |
| 首页天气与路线预览、刷新隔离 | [HomeProviderCardsDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/ui/zhitu/HomeProviderCardsDeviceTest.kt)、[HomePreviewIsolationDeviceTest](../../android/app/src/androidTest/java/com/ljwzz/weathertrafficalarm/HomePreviewIsolationDeviceTest.kt) |

原型的运行及测试命令见 [prototype/README.md](../../prototype/README.md)；这些用例检查冻结的离线演示。

## 素材依赖

[素材导入工具](../../scripts/import-prototype-assets.py) 从 [SVG](../../prototype/assets/figma-svg/) 转换图标，从 [字体](../../prototype/assets/fonts/) 复制字体及许可证。对应 Android 产物位于 `res/drawable/`、[res/font/](../../android/app/src/main/res/font/) 和 [assets/licenses/](../../android/app/src/main/assets/licenses/)。素材维护时按需执行导入工具。
