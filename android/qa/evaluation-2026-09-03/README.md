# 自动通勤评估验收 · 2026-09-03

## 交付行为

- 启用且已配置通勤的计划按计划时区安排夜间评估；工作日／日期覆盖 → 路线 → 双地点天气 → 提前量 → 独立 `ADVANCE` 提醒。基础 `REGULAR` 闹钟保留。
- 19:00 加 0–15 分钟抖动；可重试错误按 15／30／60 分钟重试，尊重 `Retry-After`，23:30 截止。首页“立即评估”使用下一基础实例的目标日期。
- 应用前检查计划版本、启用状态、通勤／日期／天气配置、授权和时效；失败或过期结果不调整有效提醒。更早的新实例注册成功后才取消旧提前实例。
- 决策保存计算分解、来源、失败／过期原因、尝试次数及实际应用结果，保留最近 30 天。Room v3→v4 显式迁移；时间输入兼容旧毫秒值，输出统一为 ISO Instant。
- 提前实例及贪睡分别处理；恢复保留目标日期、基础时间，修复缺失基础实例并消除重复待触发提前实例。日期覆盖变更取消受影响的提前提醒及后代。

实现入口：`app/evaluation/EvaluationCoordinator.kt`、`EvaluationWorkScheduler.kt`、`EvaluationWorker.kt`；系统调度统一经 `core/alarm/LocalAlarmCoordinator.kt`。

## 验证结果

构建命令在 `android/` 执行：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :core:alarm:testDebugUnitTest :core:data:testDebugUnitTest :core:model:testDebugUnitTest --console=plain
```

结果：构建通过；app 51、core/alarm 60、core/data 64、core/model 45，共 220 项测试通过。证据：[构建日志](build.log)、[测试汇总](unit-results.json)。

模拟器 `Mi_15_Ultra`（`emulator-5556`，Asia/Shanghai）：

- `EvaluationDeviceTest`：1 项通过。真实 Hilt Worker 执行，未授权 Provider 产生失败决策，基础实例时间与状态不变。测试恢复原设置并清理自建计划／任务。[日志](worker-device.log)
- `AlarmUiDeviceTest`：2 项通过，验证添加、保存、取消编辑。[日志](ui-device.log)

原型：`node --test prototype/tests/*.test.mjs`，56 项通过；仅使用离线 fixture。

Figma：[评估状态组](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=167-2099)、[编辑参考](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=171-2437)。

本轮成功链路通过 fake Provider 与真实领域／Room／调度协调器验证；模拟器 Worker 验证失败保护。自动评估的真实高德／彩云联网组合、实际隔夜执行及厂商后台限制尚未在本轮设备实测。

## 平台依据

- WorkManager 的网络约束、初始延迟与重试不保证精确执行时刻：https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- 唯一任务及任务状态：https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work
- Hilt WorkerFactory 与初始化配置：https://developer.android.com/training/dependency-injection/hilt-jetpack
