# Android 学习路径（面向零基础，服务于通勤闹钟项目）

目标：学完后能先完成本地闹钟的可靠实现，再核对 [`facts-to-verify.md`](./facts-to-verify.md) 中的后续 Provider 项。

原则：

- 全部使用官方文档，按序学习，不跳步。
- 每个阶段有"完成标志"：能用自己的话回答标志问题才算过。
- 学习过程中随手把官方原文中的关键事实补充到 facts-to-verify.md。

---

## 阶段 0：先读项目文档（半天）

- 通读 `SPEC.md`（重点：header 架构变更说明、第 2 章依赖表、第 5 章领域模型、FR-001/003/006/007、第 8 章 UI、第 14 章未决项）。
- 通读 `README.md`、`docs/configuration.md` 与 [`design-handoff.md`](./design-handoff.md)；页面开发与界面验收参照本地 `prototype/`，除非用户明确要求不得自行改动原型。
- 通读 `docs/facts-to-verify.md`，标记你不确定的断言——它们是学习动机清单。

完成标志：能解释本 App 如何为单次、每周和工作日规则计算下一次本地响铃，以及为什么未接入路线和天气时不应生成模拟提前量。

## 阶段 1：Kotlin 语言基础（1–2 周）

教材：

- Kotlin 官方入门（环境 + 基础语法）：https://kotlinlang.org/docs/getting-started.html
- Kotlin 官方语法参考（按需查）：https://kotlinlang.org/docs/android-overview.html （页面内有语言文档入口）

必学主题：变量与类型、空安全（`?`/`!!`）、函数与 lambda、数据类（对照 SPEC 5.1 `AlarmPlan`）、枚举（对照 `CommuteMode`）、`when` 表达式、集合与 `map/filter`、协程基础（`suspend`、`launch`、`runBlocking`）、`sealed class`（对照 `ProviderError`）。

完成标志：

- 能读懂仓库 `android/core/model/AlarmOccurrence.kt` 与 `OccurrenceStateMachine.kt` 的代码（当前是旧架构实现，仅用于读代码练习）。
- 能解释 `data class` 与 `sealed class` 的区别。

## 阶段 2：Android 基础 + Jetpack Compose（2–3 周）

教材（官方课程，含实操，优先做）：

- Android Basics with Compose（零基础完整课程）：https://developer.android.com/courses/android-basics-compose/course
- Compose 快速教程：https://developer.android.com/develop/ui/compose/tutorial

必学主题：Activity 与生命周期、Manifest 与权限声明、Compose 可组合函数与状态、ViewModel + StateFlow（对照 SPEC 2 章"页面状态"）、Navigation。

补充（ViewModel 专题，理解"页面状态"这一行）：

- https://developer.android.com/topic/libraries/architecture/viewmodel
- https://developer.android.com/topic/libraries/architecture/views/viewmodel

完成标志：

- 能画出"用户打开首页 → ViewModel 读数据 → StateFlow → Compose 渲染"的数据流。
- 能解释配置变更（旋转屏幕）为什么数据不丢。

## 阶段 3：数据持久化（1 周）

教材：

- Room：https://developer.android.com/training/data-storage/room （注意：Room 2.x 指南已标记 deprecated，页面内有 Room 3 新指南链接；本项目当前基线为 Room 3.0.1）
- DataStore：https://developer.android.com/topic/libraries/architecture/datastore

完成标志：能对照 SPEC 0.3 设计出 `AlarmPlan`、本地闹钟实例和贪睡子实例的实体／DAO／关系，以及 Direct Boot 快照放 DataStore 还是 Room 的理由。

## 阶段 4：后台任务、闹钟与 Direct Boot（1–2 周，本项目核心）

按顺序学：

1. 任务调度与 WorkManager：
   - https://developer.android.com/develop/background-work/background-tasks/persistent
   - https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started
2. 闹钟 AlarmManager（重点）：
   - https://developer.android.com/develop/background-work/services/alarms
   - API 参考：https://developer.android.com/reference/kotlin/android/app/AlarmManager
3. 前台服务与后台启动限制：
   - https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
4. Direct Boot：
   - https://developer.android.com/privacy-and-security/direct-boot

对照核对：读完以上四组文档后，逐条核对 facts-to-verify.md 的 A、B 组；`❓` 项给出原文出处，`🔬` 项设计最小验证实验。

完成标志：

- 能解释 `setExactAndAllowWhileIdle` 与 `setAlarmClock` 的差异，以及本地闹钟为何选 `setAlarmClock()`。
- 能解释接收器、前台响铃服务、停止和贪睡为何必须按实例 ID 幂等。
- 能解释重启后恢复与 10 分钟迟到窗口的边界。

## 阶段 5：通知、渠道与全屏 Intent（1 周）

- 创建通知（Compose 版）：https://developer.android.com/develop/ui/compose/notifications/create-notification
- 通知渠道：https://developer.android.com/develop/ui/compose/notifications/channels
- FSI 平台限制：https://source.android.com/docs/core/permissions/fsi-limits
- Android 14 行为变化（FSI 章节）：https://developer.android.com/about/versions/14/behavior-changes-14
- Play 政策声明要求：https://support.google.com/googleplay/android-developer/answer/13392821

完成标志：能解释 FR-009 中"全屏 Intent 只用于正在响铃的 occurrence"为什么合理（对照 C3/C5）。

## 阶段 6：安全（Keystore 与备份排除）（3–4 天）

- Android Keystore 系统：https://developer.android.com/privacy-and-security/keystore
- Auto Backup 与 dataExtractionRules：https://developer.android.com/identity/data/autobackup

完成标志：能解释 FR-013 的密文格式（版本号+IV+GCM tag）为什么安全，以及备份排除为什么必须同时声明 `dataExtractionRules` 与 `fullBackupContent`。

## 阶段 7：网络与第三方服务（1–2 周）

- Retrofit 官方文档：https://square.github.io/retrofit/
- OkHttp 官方文档：https://square.github.io/okhttp/
- 高德 Web 服务路径规划 v5：https://lbs.amap.com/api/webservice/guide/api/direction
- 高德错误码：https://lbs.amap.com/api/web-service/tools/info
- 彩云 v2.6 文档入口：https://docs.caiyunapp.com/weather-api/ （重点看 auth、ratelimit、billing、v2/v2.6/tables/skycon.html）
- 彩云官网入口：https://caiyunapp.com/api/weather
- holiday-cn：https://github.com/NateScarlet/holiday-cn/blob/master/README.md

对照核对：Provider 接入前逐条核对 facts-to-verify.md 的 E 组；当前本地闹钟功能不依赖这些结论。

完成标志：能写出"彩云请求 → HMAC 签名 → 解析 → 错误分类（对照 SPEC 7.3）"的伪代码。

## 阶段 8：测试（3–5 天）

- 测试基础（单元/仪器测试分类）：https://developer.android.com/training/testing/fundamentals
- 仪器测试：https://developer.android.com/training/testing/instrumented-tests

完成标志：能对照 SPEC 11.1 判断日期规则、实例状态机和恢复为何适合纯 JVM 单元测试，以及响铃回归为何需要仪器测试。

## 阶段 9：Android 16 专项（2–3 天）

- https://developer.android.com/about/versions/16/behavior-changes-16 （targetSdk 36 行为）
- https://developer.android.com/about/versions/16/behavior-changes-all （所有应用）

完成标志：能列出 Android 16 对本项目有影响的 2–3 项变化，并核对 C6/C7。

---

## 学成后正向输出（回到项目）

1. 用已核实的事实更新 `docs/facts-to-verify.md`，删除/改写不成立的断言。
2. 把结论同步回 `SPEC.md` 第 2 章技术依据与第 14 章未决项。
3. 基于事实正向输出实现方案：优先处理 `android/` 下仍是旧架构的 Kotlin 骨架（`core/model/AlarmOccurrence.kt`、`OccurrenceStateMachine.kt`、`NextAlarmSnapshot.kt`、`ExactAlarmScheduler.kt`）与新架构（v3.0）的差距，再对照 `IMPLEMENTATION_TASKS.md` 排实施顺序。

## 引用说明

本文件所有 URL 均为 2026-08-07 会话中通过官方文档检索实际获取；未在此列的官方页面（如 Kotlin 协程参考、Hilt 指南）请在需要时自行检索并补充，不要凭记忆引用。
