# Android 原生响铃验收（2026-09-02）

本记录为模拟器验收；同日后续 Xiaomi 实体手机的 9 项验证与截图见 [`真机响铃记录`](../physical-ringing-2026-09-02/README.md)。

## 实现范围

- 基础本地实例与贪睡子实例使用原生全屏页面，沿用 Figma／原型的壁纸、字体、时间、原因卡和按钮布局；时间、日期与计划名称来自真实快照。
- 停止／贪睡先禁用按钮，等待设备保护快照和响铃服务确认结果；停止结果可在 Activity 重建后恢复。贪睡显示真实子实例时间，到点再次响铃。
- 无效、待响和已终结实例不显示可执行的响铃操作。结果页为“返回闹钟／关闭”，返回计划页前检查解锁状态。
- 动作失败留下静态错误回执并允许重试。贪睡子实例不继承父错误；停止与贪睡竞争只有一个结果，自动停止不会覆盖已成功的贪睡。

对应源码：`AlarmRingingActivity.kt`、`RingingUiState.kt`、`RingingScreen.kt`、`AlarmReceiver.kt`、`AlarmRingingService.kt`、`LocalAlarmCoordinator.kt`。

## 构建与测试

- 环境：只读、静音的 `Mi_15_Ultra` API 36 模拟器，412 × 892 dp；截图 1236 × 2676 px。
- APK：[`app-debug.apk`](../../app/build/outputs/apk/debug/app-debug.apk)。当前工程 `minSdk=36`、`targetSdk=36`、`compileSdk=37`，以 `android/app/build.gradle.kts` 为准。
- APK SHA-256：`7f35e40a9350efba31db8934b708368cfa757c1bed006ea5f3cf62c2537629d1`。
- Android 单元测试：205 项通过，0 失败／错误／跳过（app 11、model 44、alarm 42、data 59、network 38、map 11）。包括注册失败后成功重试、错误回执清除、两种停止／贪睡顺序、Direct Boot 子回执及超时守卫。
- Web 原型：31 项通过，0 失败。服务测试须允许本机回环监听；初次沙箱 `listen EPERM` 后已在获准环境重跑。
- 设备测试：12 项实际通过；1 项失败注入用例未完成设备验证，详见下方边界。
- 最后补充终态隐藏过期操作错误提示后，重新构建并通过全部 205 项单元测试；最终 APK 又通过 6 项界面测试及真实响铃／停止／重建测试，共 7 项冒烟回归，不重复计入上方 12 项。

| 设备测试组 | 实际通过 | 覆盖 |
| --- | ---: | --- |
| `RingingScreenDeviceTest` | 6 | 操作回调、处理中禁用、错误态重试入口、待响／失效态限制、大字体滚动 |
| `NativeRingingActivityDeviceTest` | 3 | 无效 Intent、真实到点响铃／播放状态／停止／重建、1 分钟真实贪睡再次响铃 |
| `LocalAlarmDeviceTest` 选定方法 | 3 | 响铃和历史、多闹钟隔离、编辑和停用仅影响目标计划 |

构建命令在 `android/` 执行：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest --offline --console=plain --quiet
```

设备测试筛选：

```sh
adb -s emulator-5554 shell am instrument -w -r -e class 'com.ljwzz.weathertrafficalarm.ui.zhitu.RingingScreenDeviceTest,com.ljwzz.weathertrafficalarm.NativeRingingActivityDeviceTest,com.ljwzz.weathertrafficalarm.LocalAlarmDeviceTest#actualAlarmRingsThenDismissesAndRecordsHistory,com.ljwzz.weathertrafficalarm.LocalAlarmDeviceTest#simultaneousAlarmsRemainIndependent,com.ljwzz.weathertrafficalarm.LocalAlarmDeviceTest#editingAndDisablingCancelOnlyTheSelectedAlarm' com.ljwzz.weathertrafficalarm.test/androidx.test.runner.AndroidJUnitRunner
```

## 截图

应用布局通过 Compose 根节点捕获，完整设备截图通过 UiAutomation 捕获。两类分别保存，后者保留系统悬浮通知叠层。

| 状态 | 应用布局 | 完整设备 |
| --- | --- | --- |
| 基础响铃 | [布局](./basic-firing-content.png) | [设备](./basic-firing.png) |
| 已停止 | [布局](./basic-stopped-content.png) | [设备](./basic-stopped.png) |
| 已贪睡 | [布局](./basic-snoozed-content.png) | [设备](./basic-snoozed.png) |
| 子实例再次响铃 | [布局](./snooze-firing-content.png) | [设备](./snooze-firing.png) |

## 验证边界

- 提前响铃尚无生产提前实例与统一评估调度，本次没有启用自动提前；Figma／Web 的提前 fixture 不等于 Android 实现。
- 当前模拟器对 `appops set … POST_NOTIFICATION ignore` 的读回仍为 `allow`，通知仍启用，失败注入未生效。设备用例在前置能力检查处明确跳过；该环境的真实注册失败→原界面重试路径信息不足，无法验证。失败／重试目前由 JVM 状态链和 Compose 错误态测试覆盖。
- 本轮没有重新执行未解锁重启、实体设备、厂商后台限制或发布环境矩阵。Direct Boot 的既有设备记录见 [`../README.md`](../README.md)，不计入本轮通过数。
- 模拟器以 `-no-audio` 运行，测试断言 `USAGE_ALARM` 活动播放配置；未人工听音或验证实体振动。
- 测试仅删除自己创建的唯一前缀计划；模拟器为只读副本，不保存本次安装、权限或显示配置到原 AVD。

## 设计与平台依据

- 设计到实现的映射见 [`docs/design-handoff.md`](../../../docs/design-handoff.md)，交互约束见 `SPEC.md` 8.7B。
- Direct Boot 存储限制：https://developer.android.com/privacy-and-security/direct-boot
- 系统安全区域：https://developer.android.com/develop/ui/compose/system/insets
- 可变字体的实际字重轴：https://developer.android.com/develop/ui/compose/text/fonts?hl=en
