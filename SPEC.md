# 通勤闹钟（weather-traffic-alarm）产品与技术规格

- 状态：实施基线
- 版本：1.1
- 日期：2026-07-23
- 仓库名：`weather-traffic-alarm`
- 中文名称：`通勤闹钟`
- 英文名称：`weather-traffic-alarm`
- Android `applicationId` / `namespace`：`com.ljwzz.weathertrafficalarm`
- `minSdk`：29
- `compileSdk` / `targetSdk`：36
- 可执行任务清单：[`IMPLEMENTATION_TASKS.md`](./IMPLEMENTATION_TASKS.md)

> 用户给出的 `com.ljwzz.weather-traffic-alarm` 不能作为 Android application ID：各段只能包含字母、数字或下划线，且每段必须以字母开头。因此包名规范化为 `com.ljwzz.weathertrafficalarm`；带连字符的写法只保留为仓库名和英文产品名。
>
> 依据：https://developer.android.com/build/configure-app-module

## 1. 产品目标

用户为每个计划设置默认起床时间、到岗时间、准备时长、家庭/工作地点和固定通勤方式。系统在每天 19:00 后评估下一工作日的天气及通勤时间，只允许自动提前同一次闹钟，不允许自动推迟。

### 1.1 首版范围

- 原生 Kotlin、Jetpack Compose，支持 Android 10–16（API 29–36）。
- 支持驾车、公交、步行、骑行、电动车；计划保存后不自动切换方式。
- 支持中国大陆法定节假日、调休上班日以及用户单日覆盖。
- 默认起床时间为 06:00，默认最多提前 60 分钟。
- 无用户账号；计划和历史保存在本机。
- 后端不持久化家庭/工作坐标、地址或完整计算请求。
- 只在用户主动点击“使用当前位置”时请求前台定位。
- 保存或启用计划时立即注册基础闹钟，天气、路线和网络均不能成为响铃前置条件。

### 1.2 非目标

- 不申请后台定位。
- 不持续监控路线，不提供导航。
- 不做跨设备同步、企业日历接入或 iOS 客户端。
- 不承诺绕过用户“强制停止”后的 Android 平台限制。
- 首版不自动选择或切换通勤方式。

## 2. 依赖复用结论

原则：优先使用平台 API、AndroidX、高德官方 SDK/API、彩云天气官方 API 及成熟基础库；业务规则、隐私边界、调度状态机和降级策略由本项目实现。默认不复制或分叉第三方源码。

| 能力 | 采用方案 | 项目自有实现边界 | 结论 |
|---|---|---|---|
| UI | Jetpack Compose + Material 3 | 页面、组件、主题、无障碍语义 | 采用 |
| 页面状态 | AndroidX ViewModel + StateFlow | 单向数据流、页面状态和事件约束 | 采用 |
| 导航 | Navigation Compose | 路由表和参数类型 | 采用 |
| 依赖注入 | Hilt | 作用域和 Provider 装配 | 采用 |
| 结构化数据 | Room 2 | 实体、DAO、迁移和事务 | 采用 |
| 偏好及 Direct Boot 快照 | Proto DataStore | schema、迁移和快照最小化 | 采用 |
| 非精确后台任务 | WorkManager | 19:00 调度、重试截止和幂等 | 采用 |
| 最终响铃 | `AlarmManager.setAlarmClock()` | occurrence 状态机、重调度和过期校验 | 采用平台 API |
| 通知 | AndroidX Core `NotificationCompat` | 渠道、动作、全屏降级 | 采用 |
| 响铃前台服务 | `systemExempted` foreground service | occurrence 校验、通知、停止和贪睡 | 采用平台能力 |
| 响铃音频 | 平台 `RingtoneManager` / `Ringtone` | 音源回退、循环、振动和停止 | 采用平台 API |
| HTTP | Retrofit + Kotlin Serialization converter + OkHttp | DTO、脱敏拦截器、错误分类 | 采用 |
| 地图/选点/前台定位 | 高德 Android 合包，通过 `AndroidView` 承载 `MapView` | Compose 适配层、隐私闸门 | 采用官方 SDK |
| 路线、POI | 高德 Web API，仅由后端调用 | Provider 适配、缓存、配额和降级 | 采用官方 API |
| 天气 | 彩云天气 v2.6 API，仅由后端调用 | HMAC 鉴权、天气规则、缓存、配额和降级 | 采用官方稳定 API |
| 年度工作日 | 国务院年度通知 → 后端版本化日历 | 抽取、复核、签名、覆盖优先级 | 不采用社区日历为真值 |
| 日历签名 | Tink Digital Signature | 固定公钥、轮换策略和规范化 JSON | 采用 |
| 后端业务框架 | Spring Boot 3.5.x | 领域服务和接口实现 | 采用 |
| 容错 | Resilience4j | 每个 Provider 的超时、重试、熔断策略 | 采用 |
| 分布式限流 | Bucket4j + Redis/Lettuce | 安装令牌和 Provider 配额维度 | 采用 |
| 数据库迁移 | Flyway | 版本化 SQL | 采用 |
| 指标/追踪 | Spring Boot Actuator + Micrometer；部署侧 OpenTelemetry Java Agent | 指标名、脱敏属性和告警 | 采用 |
| 契约生成 | OpenAPI Generator | 生成 Android DTO/API 和 Spring 接口；生成物禁止手改 | 有条件采用 |
| 安装证明 | Play Integrity | 仅 Play/GMS 渠道启用；非 GMS 使用低配额匿名令牌 | 可选 |
| 崩溃/ANR | Google Play Android vitals + 本地脱敏诊断 | 不接入额外采集 SDK | 首版采用 |

技术依据：

- Compose 和与 Android 架构组件的集成：https://developer.android.com/compose
- AndroidX 库在 Compose 中的使用：https://developer.android.com/develop/ui/compose/libraries
- Hilt：https://developer.android.com/training/dependency-injection/hilt-android
- Room 2.8.4：https://developer.android.com/jetpack/androidx/releases/room
- DataStore 1.2.1 及 Direct Boot 支持：https://developer.android.com/jetpack/androidx/releases/datastore
- WorkManager 2.11.2：https://developer.android.com/jetpack/androidx/releases/work
- 精确闹钟：https://developer.android.com/develop/background-work/services/alarms
- 闹钟前台服务类型：https://developer.android.com/develop/background-work/services/fgs/service-types
- `RingtoneManager` / `Ringtone`：https://developer.android.com/reference/android/media/RingtoneManager 和 https://developer.android.com/reference/android/media/Ringtone
- Retrofit 3.0.0 及 Kotlin Serialization converter：https://github.com/square/retrofit/releases
- OkHttp：https://github.com/square/okhttp
- Compose 嵌入 View 的 `AndroidView`：https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose
- 高德 Android 合包：https://lbs.amap.com/api/android-sdk/guide/create-project/android-studio-create-project
- 彩云天气 API 版本说明：https://docs.caiyunapp.com/weather-api/version-guide.html
- 彩云天气 v2.6 鉴权：https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html
- 彩云天气 v2.6 小时预报：https://docs.caiyunapp.com/weather-api/v2/v2.6/3-hourly.html
- 彩云天气现象代码：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/skycon.html
- Tink Android 和 Digital Signature：https://developers.google.com/tink/setup/java 和 https://developers.google.com/tink/digital-signature
- Resilience4j Spring Boot 3：https://resilience4j.readme.io/docs/getting-started-3
- Bucket4j：https://github.com/bucket4j/bucket4j
- Spring Boot Actuator/Micrometer：https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- OpenTelemetry Spring Boot 集成：https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/
- OpenAPI Kotlin/Spring 生成器：https://openapi-generator.tech/docs/generators/kotlin/ 和 https://openapi-generator.tech/docs/generators/spring/
- Android vitals：https://support.google.com/googleplay/android-developer/answer/9859174

### 2.1 明确不采用

- 不采用通用第三方 AlarmManager 封装：精确闹钟权限、PendingIntent 身份、Direct Boot 和厂商行为仍需由应用处理，封装不能替代领域状态机。
- 不采用 Media3 播放闹钟：首版只需本地闹铃；平台 `Ringtone` 已支持闹铃类型、AudioAttributes 和循环。Media3 ExoPlayer 的自动音频焦点限定于媒体/游戏用途，不适合作为 `USAGE_ALARM` 的默认实现。
  - https://developer.android.com/reference/android/media/Ringtone
  - https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer.Builder
  - https://developer.android.com/reference/android/media/AudioAttributes
- 不采用 Accompanist permissions：常规运行时权限直接使用平台 Activity Result API；精确闹钟和全屏 Intent 属于特殊访问能力，应走对应系统设置及能力检测。
  - https://developer.android.com/training/permissions/requesting-special
- 不采用社区节假日包作为生产真值：年度节假日和调休由国务院逐年发布，应用使用带来源、版本和签名的年度数据。
  - 2026 年官方通知：https://big5.www.gov.cn/gate/big5/www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm
- 不采用未经高德官方维护的 Compose 地图封装；使用 `AndroidView` 包装官方 `MapView`。
  - https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose
- 不在首版接入额外崩溃采集 SDK，以免引入新的个人信息披露和网络依赖；发布渠道先使用 Android vitals。
  - https://support.google.com/googleplay/android-developer/answer/9859174

### 2.2 源码复用规则

- AOSP DeskClock 只作为行为和异常测试参考，不直接复刻实现。其代码包含平台内部及历史兼容路径，当前项目仍需按 API 29–36 重新实现。
  - 仓库：https://android.googlesource.com/platform/packages/apps/DeskClock/
  - 历史响铃实现：https://android.googlesource.com/platform/packages/apps/DeskClock/+/65cfff020befe01baa0488a8e6c06d7a1fe81dc6/src/com/android/deskclock/AsyncRingtonePlayer.java
- 如确需复制小段第三方代码，必须固定来源 URL、提交号和许可证，在 `NOTICE` 记录修改；未经许可证核验不得复制。
- OpenAPI 生成代码视为构建产物：生成模板和生成器版本必须锁定，生成物不允许手改，CI 必须验证重新生成后无 diff。

## 3. 技术基线与版本锁定

以下为 2026-07-23 的初始化基线；升级依赖必须通过单元、契约、仪器和响铃回归测试。

### 3.1 Android

| 项 | 版本/策略 |
|---|---|
| Android Gradle Plugin | `9.3.0` |
| Gradle Wrapper | `9.5.0` |
| Kotlin | `2.3.21` |
| KSP | `2.3.4` |
| JDK | `21` |
| Compose BOM | `2026.06.00` |
| Navigation Compose | `2.9.8` |
| Room | `2.8.4` |
| DataStore | `1.2.1` |
| WorkManager | `2.11.2` |
| Hilt Core | `2.60.1` |
| AndroidX Hilt | `1.3.0` |
| Retrofit | `3.0.0` |
| OkHttp | 先使用 Retrofit 3.0.0 已验证的 `4.12.0` 依赖线；升级到 5.x 前做契约与 TLS 回归 |
| kotlinx-coroutines-test | `1.11.0` |
| Tink Android | `1.23.0` |
| 高德 Android 合包 | `com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0` |

版本依据：

- Android 官方构建示例中的 AGP、Kotlin 和 compileSdk：https://developer.android.com/build
- AGP 9.3 与 Gradle 9.5.0 兼容性：https://developer.android.com/build/releases/agp-9-3-0-release-notes
- AGP 9 内置 Kotlin，不再应用 `org.jetbrains.kotlin.android`：https://developer.android.com/build/migrate-to-built-in-kotlin
- Kotlin 2.3.21 Compose Compiler plugin：https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- KSP 配置：https://developer.android.com/build/migrate-to-ksp
- Compose BOM：https://developer.android.com/develop/ui/compose/bom
- Navigation 2.9.8：https://developer.android.com/jetpack/androidx/releases/navigation
- Room 2.8.4：https://developer.android.com/jetpack/androidx/releases/room
- DataStore 1.2.1：https://developer.android.com/jetpack/androidx/releases/datastore
- WorkManager 2.11.2：https://developer.android.com/jetpack/androidx/releases/work
- Hilt 2.60.1：https://dagger.dev/hilt/gradle-setup.html
- AndroidX Hilt 发布记录：https://developer.android.com/jetpack/androidx/releases/hilt
- Retrofit 3.0.0：https://github.com/square/retrofit/releases
- OkHttp 当前发布线：https://github.com/square/okhttp
- kotlinx-coroutines-test 1.11.0：https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
- Tink Android 1.23.0：https://developers.google.com/tink/setup/java
- 高德合包官方坐标：https://lbs.amap.com/api/android-sdk/guide/create-project/android-studio-create-project
- 高德合包 Maven 元数据：https://repo1.maven.org/maven2/com/amap/api/3dmap-location-search/maven-metadata.xml

AndroidX Hilt `1.4.0` 的 AAR 声明 `minCompileSdk=37`，与本项目固定 `compileSdk=36` 不兼容，因此基线锁定 `1.3.0`。

- 1.4.0 AAR：https://dl.google.com/dl/android/maven2/androidx/hilt/hilt-navigation-compose/1.4.0/hilt-navigation-compose-1.4.0.aar
- 发布记录：https://developer.android.com/jetpack/androidx/releases/hilt

项目必须使用 Gradle version catalog 管理版本；禁止 `+`、`latest.release` 或 `latest.integration`。

AGP 9.3 默认使用内置 Kotlin：Android 模块不得再应用 `org.jetbrains.kotlin.android`。Compose 模块应用 `org.jetbrains.kotlin.plugin.compose` 2.3.21；Room 和 Hilt 代码生成统一使用 KSP，不引入 kapt。

### 3.2 后端

| 项 | 版本/策略 |
|---|---|
| JDK | `21` |
| Spring Boot | `3.5.16` |
| API 描述 | OpenAPI `3.1`，`contract/openapi.yaml` 为唯一接口真值 |
| PostgreSQL | 部署环境固定受支持的小版本 |
| Redis | 部署环境固定受支持的小版本 |
| Resilience4j | 使用官方 Spring Boot 3 starter 的当前锁定版本 |
| Bucket4j | `8.18.0` |
| 数据迁移 | Flyway，由 Spring Boot dependency management 锁定 |
| 集成测试 | Spring Boot Testcontainers 支持 |

依据：

- Spring Boot 3.5.16 文档：https://docs.spring.io/spring-boot/3.5/how-to/actuator.html
- Bucket4j 8.18.0：https://github.com/bucket4j/bucket4j
- Spring Boot Testcontainers：https://docs.spring.io/spring-boot/reference/testing/testcontainers.html

PostgreSQL、Redis、Resilience4j 和 Flyway 的具体版本在仓库初始化时由部署兼容矩阵及 Spring Boot dependency management 锁定；本规格不猜测未核验的版本。

## 4. 仓库与模块

```text
weather-traffic-alarm/
├── android/
│   ├── app/
│   ├── core/model/
│   ├── core/data/
│   ├── core/network/
│   ├── core/alarm/
│   ├── core/map/
│   └── feature/{onboarding,home,plan,place,calendar,history,diagnostics}/
├── backend/
│   ├── app/
│   ├── domain/
│   ├── provider-amap/
│   ├── provider-caiyun/
│   └── persistence/
├── contract/
│   ├── openapi.yaml
│   └── examples/
├── calendar-data/
│   ├── sources/
│   └── generated/
├── docs/
└── infra/
```

依赖方向：

```text
Android feature -> core model/data/network/alarm/map
backend app -> domain -> provider/persistence
android + backend generated interfaces <- contract/openapi.yaml
```

`core/model` 不依赖 Android UI、Room、Retrofit、高德或彩云类型。高德 DTO 不得跨出 `provider-amap` / `core/map`；彩云 DTO 不得跨出 `provider-caiyun`。

## 5. 领域模型

### 5.1 `AlarmPlan`

```text
id: UUID
revision: Long
name: String
enabled: Boolean
zoneId: IANA ZoneId
defaultWakeLocalTime: LocalTime
arrivalLocalTime: LocalTime
preparationMinutes: Int
maxAdvanceMinutes: Int
commuteMode: CommuteMode
origin: PlaceRef
destination: PlaceRef
waypoints: List<PlaceRef>
routePolicy: RoutePolicy
weatherRuleVersion: String
sound: AlarmSound
vibration: VibrationPattern
snoozeMinutes: Int
createdAt/updatedAt: Instant
```

约束：

- `preparationMinutes`：0–240。
- `maxAdvanceMinutes`：0–180；UI 默认 60。
- `snoozeMinutes`：1–30；UI 默认 10。
- `origin != destination`。
- 驾车以外模式首版不接受途经点。
- `revision` 在任何影响决策或调度的编辑后递增。

### 5.2 `PlaceRef`

```text
poiId?: String
name: String
displayAddress: String
longitudeGcj02: Decimal
latitudeGcj02: Decimal
adcode: String
citycode: String
```

坐标只在本地 Room 中保存，并在一次计算请求中传输；不得进入 Android 诊断日志、后端访问日志、指标标签、追踪属性或 Redis 明文 key。

### 5.3 `CommuteMode`

```text
DRIVING
TRANSIT
WALKING
BICYCLING
ELECTRIC_BICYCLE
```

高德 Web 路径规划 v5 提供这些模式的对应接口；未来路径规划只覆盖驾车且属于高级服务。

- 基础路径规划：https://lbs.amap.com/api/webservice/guide/api/newroute
- 未来驾车：https://developer.amap.com/api/webservice/guide/api-advanced/advanced-path

### 5.4 工作日

```text
CalendarVersion(
  country, year, version, publishedAt, sourceUrl,
  payloadSha256, signatureAlgorithm, signature, days
)
CalendarDay(date, status: WORKDAY|HOLIDAY, label?)
WorkdayOverride(planId, date, status)
```

工作日状态优先级固定为：

1. 用户单日覆盖。
2. 已验证签名的官方年度日历。
3. 周一至周五为工作日、周末为休息日的本地兜底。

年度日历由后端生成规范化 JSON，用 Tink `PublicKeySign` 签名；Android 内置公钥并用 `PublicKeyVerify` 验证，签名失败时拒绝覆盖当前有效版本。Tink 的签名原语和 Android 依赖依据：

- https://developers.google.com/tink/digital-signature
- https://developers.google.com/tink/setup/java

应用随安装包内置当年日历，以保证首次离线运行；后端更新必须包含来源 URL、发布时间、payload hash 和签名。

### 5.5 `AlarmDecision`

```text
decisionId: UUID
planId: UUID
planRevision: Long
targetDate: LocalDate
workdayStatus: WORKDAY|HOLIDAY
estimatedDepartureAt: ZonedDateTime?
commuteSeconds: Long?
weatherSeverity: WeatherSeverity
weatherBufferMinutes: Int
recommendedWakeAt: ZonedDateTime
routeProvider: String?
routeProviderReportTime: Instant?
weatherProvider: String?
weatherProviderReportTime: Instant?
weatherWindowStart: ZonedDateTime?
weatherWindowEnd: ZonedDateTime?
fallbackReason: FallbackReason?
insufficientAdvance: Boolean
generatedAt: Instant
expiresAt: Instant
```

客户端只接受 `planId`、`planRevision`、`targetDate` 均与当前计划和待调度日期一致且未过期的响应。

### 5.6 `AlarmOccurrence`

```text
occurrenceId: UUID
planId: UUID
planRevision: Long
targetDate: LocalDate
scheduledWakeAt: Instant
state: DEFAULT_REGISTERED|ADVANCED|FIRING|SNOOZED|DISMISSED|MISSED|CANCELLED
decisionId?: UUID
updatedAt: Instant
```

每个启用计划只注册其下一次 occurrence；所有系统闹钟均为一次性闹钟，不使用重复 Alarm。多个计划可以各自拥有一个下一次 occurrence。

设备保护存储保存 `NextAlarmSnapshot` 列表，而不是包含地点的完整计划：

```text
occurrenceId, planId, planRevision, triggerAt,
soundUri, vibrationPattern, snoozeMinutes
```

## 6. 功能规格

### FR-001 创建和启用计划

1. 用户完成名称、时间、地点、方式、天气规则和工作日规则配置。
2. 保存事务先写 Room，再递增 `revision`。
3. 调度器计算下一工作日，并立即以默认起床时间创建 occurrence。
4. 将最小 Direct Boot 快照写入设备保护存储。
5. 注册一次性 `setAlarmClock()`。
6. 19:00 后保存时，除基础闹钟外立即请求一次评估。

验收：断网保存后，系统“下一闹钟”仍显示基础闹钟。

### FR-002 工作日计算

- 按计划 `zoneId` 计算日期，禁止以服务端默认时区替代。
- 按第 5.4 节优先级逐日查找下一个工作日。
- 用户覆盖的修改即时取消旧 occurrence 并注册新 occurrence。
- 年度日历不可用、过期或签名失败时使用本地兜底并展示原因。

### FR-003 闹钟计算

统一使用目标日期的计划时区：

```text
calculatedWake = estimatedDeparture - preparation - weatherBuffer
earliestAllowed = defaultWake - maxAdvance
clampedWake = max(earliestAllowed, calculatedWake)
recommendedWake = min(defaultWake, clampedWake)
finalWake = min(alreadyScheduledWake, recommendedWake)
```

不变量：

- 自动结果不得晚于默认起床时间。
- 同一 `occurrenceId + planRevision` 的自动更新不得晚于已注册时间。
- 只有用户主动编辑计划并生成新 `revision`，才允许把闹钟推迟。
- 结果早于 `earliestAllowed` 时取 `earliestAllowed`，并设置 `insufficientAdvance=true`。
- 所有分钟运算先转为 `ZonedDateTime` / `Instant`，禁止只对 `LocalTime` 做跨日减法。

### FR-004 天气缓冲

取家庭地和工作地中较严重的一端：

| 等级 | 示例 | 默认缓冲 |
|---|---|---:|
| 0 | 晴、多云、阴 | 0 分钟 |
| 1 | 小雨、小雪 | 10 分钟 |
| 2 | 中到大雨雪、雾、沙尘、强风 | 20 分钟 |
| 3 | 暴雨、暴雪；冻雨仅在已开通的预警数据明确返回时 | 30 分钟 |

- 用户可把等级 1–3 的缓冲分别设置为 0–60 分钟。
- 天气提供方固定为彩云天气 v2.6；Android 不直接调用彩云 API。
- 后端分别查询家庭地和工作地，从 `[defaultWake-maxAdvance, arrivalTime]` 小时窗口内取最高严重等级，再取两地中较严重的一端。
- 使用小时级接口，并按当前时间到 `arrivalTime` 动态计算 `hourlysteps`；允许范围为 1–360 小时。请求固定使用 `unit=metric:v2` 和 `lang=zh_CN`。
- 彩云请求路径坐标顺序为 `{longitude},{latitude}`，响应 `location` 数组示例顺序为 `[latitude, longitude]`；Provider DTO 必须用命名字段转换，禁止在领域层传播裸数组。
- 规则输入至少包括 `skycon`、逐小时降水概率/强度、风速和能见度；天气现象映射以 `skycon` 枚举为主，不解析自然语言描述。
- `CLEAR_*`、`PARTLY_CLOUDY_*`、`CLOUDY` 为等级 0；`LIGHT_RAIN`、`LIGHT_SNOW` 为等级 1；`MODERATE_*`、`HEAVY_*`、`FOG`、`DUST`、`SAND`、`WIND` 为等级 2；`STORM_RAIN`、`STORM_SNOW` 为等级 3。未知代码不默认视为晴天，返回 `WEATHER_UNKNOWN_CODE` 并使用 0 分钟缓冲。
- 严重等级映射必须带 `weatherRuleVersion`，每个彩云枚举都必须有契约测试。
- 保存响应顶层 `server_time` 为 `weatherProviderReportTime`，保存参与决策的小时数据时间范围，不保存完整 Provider 响应。
- 彩云预警数据属于增值能力，首版核心计算不得依赖 `alert=true`；开通后只能作为等级上调信号。
- 彩云 v2.6 官方 `skycon` 表未提供独立冻雨代码，信息不足，无法仅凭常规小时数据可靠识别冻雨；不得自行用温度和降水组合推断。只有增值预警返回明确冰冻类预警时才能上调为等级 3。
- 目标工作日超过 360 小时、Provider 无对应小时数据或响应过期时，返回 `WEATHER_HORIZON_UNAVAILABLE` 和 0 分钟天气缓冲；后续每日评估进入预报范围后只能提前。
- 彩云 v2.6 使用 App Key + App Secret 的 HMAC-SHA256 鉴权；App Secret 只存在于后端密钥管理系统，禁止使用 URL 路径 Token 认证。
- 彩云文档只明确“彩云天气 App 使用 GCJ-02”，未明确一般 v2.6 天气查询接口接受的坐标基准。信息不足，无法验证高德 GCJ-02 坐标能否无转换用于生产；实现任务必须以官方书面确认或已知控制点对照测试关闭该门禁。

依据：

- v2.6 为稳定推荐版本：https://docs.caiyunapp.com/weather-api/version-guide.html
- v2.6 App Key + App Secret 鉴权：https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html
- 小时预报范围、字段和 `hourlysteps`：https://docs.caiyunapp.com/weather-api/v2/v2.6/3-hourly.html
- 天气现象枚举：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/skycon.html
- 降水强度和 `unit=metric:v2`：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/precip.html
- 坐标系现有说明：https://docs.caiyunapp.com/weather-api/q.html

### FR-005 路线计算

统一接口：

```kotlin
interface RouteProvider {
    suspend fun estimate(request: RouteRequest): RouteEstimate
}
```

实现规则：

- 驾车：优先未来路径规划；从到岗前 180 分钟起按 15 分钟步长生成候选，一次请求获取时间序列，选择可准时到达的最晚出发点。
- 未来驾车服务未开通、目标超出服务时间范围、配额耗尽或调用失败时，退化为基础驾车耗时并标记 `CURRENT_TRAFFIC_FALLBACK`。
- 公交：传入目标日期和时间；以历史缓存或 90 分钟为初始估算，最多向前重试三次，每次 15 分钟，选择可准时到达的最晚方案。
- 步行、骑行、电动车：查询一次静态路径耗时，不声明未来拥堵预测。
- Provider 必须设置连接、读取和总超时；只对可重试错误执行有限重试。
- 每种方式使用独立缓存 key、配额和熔断器，模式间不得相互拖垮。

高德 Web API 的模式、日期/时间参数及未来驾车约束依据：

- https://lbs.amap.com/api/webservice/guide/api/newroute
- https://developer.amap.com/api/webservice/guide/api-advanced/advanced-path

### FR-006 夜间评估

- 保存或启用计划时立即安排基础闹钟。
- 使用唯一 `OneTimeWorkRequest` 计算下一次本地 19:00，并增加 0–15 分钟抖动。
- Worker 完成后安排下一天任务，不使用长时间常驻服务。
- 网络约束为 `CONNECTED`。
- 失败后分别在 15、30、60 分钟重试；本地 23:30 后停止主动重试。
- Worker 输入只含计划 ID；执行时读取最新 `revision`。
- 每个计划使用幂等键 `planId:revision:targetDate`。
- 过期响应不得修改调度。

WorkManager 不能保证精确执行时间，因此只承担提前数小时的数据刷新，最终响铃必须交给 AlarmManager。Android 16 对 WorkManager/JobScheduler 运行额度有额外约束，Worker 必须保持为短任务。

- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/about/versions/16/behavior-changes-all

### FR-007 精确闹钟注册

- 使用 `AlarmManager.setAlarmClock()` 注册用户可见的一次性精确闹钟。
- 每个 occurrence 使用显式、不可变 `PendingIntent`。
- PendingIntent 身份不得只依赖 extras；使用唯一 request code，并把 occurrence ID 放入唯一 data URI。
- 注册前写入 occurrence 和 Direct Boot 快照；注册成功后更新状态。
- 重新计算时先注册新 PendingIntent，确认无异常后取消旧 PendingIntent，再提交新状态；异常时保留旧闹钟。
- Receiver 收到 Intent 后必须按 occurrence ID、计划 revision、状态和触发时间窗口重新校验。

`PendingIntent` 的匹配不比较 extras，显式且不可变的 Intent 可减少被篡改风险：

- https://developer.android.com/reference/android/app/PendingIntent
- https://developer.android.com/privacy-and-security/risks/pending-intent

### FR-008 响铃服务

- `AlarmReceiver` 只完成校验和启动 `AlarmRingingService`，不得进行网络请求。
- `AlarmRingingService` 为前台服务，声明 `foregroundServiceType="systemExempted"`。
- Manifest 声明 `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`。
- API 34+ 使用 `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED` 启动；API 29–33 使用兼容的无类型 `startForeground` 路径。
- 使用 `RingtoneManager.TYPE_ALARM` 解析声音；自定义 URI 失效时依次回退到系统 alarm、notification，再到应用内置声音。
- 使用 `AudioAttributes.USAGE_ALARM`，循环播放并按计划振动。
- 通知提供停止和贪睡；操作 Intent 同样显式、不可变且带 occurrence 身份。
- 停止或贪睡必须幂等；重复点击不会创建多个 occurrence。
- 贪睡创建新的 `SNOOZED` occurrence，并仍使用 `setAlarmClock()`。

Android 14+ 要求声明前台服务类型。持有 `SCHEDULE_EXACT_ALARM` 或 `USE_EXACT_ALARM` 的闹钟应用符合 `systemExempted` 的平台条件；精确闹钟回调也属于从后台启动前台服务的例外：

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/reference/android/content/pm/ServiceInfo

闹铃音频平台 API：

- https://developer.android.com/reference/android/media/RingtoneManager
- https://developer.android.com/reference/android/media/Ringtone
- https://developer.android.com/reference/android/media/AudioAttributes

### FR-009 通知、全屏和能力诊断

Manifest：

```text
android.permission.USE_EXACT_ALARM
android.permission.POST_NOTIFICATIONS
android.permission.USE_FULL_SCREEN_INTENT
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED
android.permission.WAKE_LOCK
android.permission.VIBRATE
android.permission.INTERNET
android.permission.ACCESS_COARSE_LOCATION
android.permission.ACCESS_FINE_LOCATION
```

- 应用核心功能属于闹钟，采用 `USE_EXACT_ALARM`，发布前完成 Google Play 对应声明。
- Android 13+ 请求通知运行时权限。
- 全屏 Intent 只用于正在响铃的 occurrence。
- 精确闹钟能力或通知能力缺失时，不允许启用新计划，并提供系统设置入口。
- 全屏能力缺失时允许启用，但在 UI 明确标记“锁屏界面可能不自动展开”，并降级为高优先级通知。
- 每次回到前台和启用计划前重新诊断，不能只依赖首次启动结果。

依据：

- 精确闹钟及权限选择：https://developer.android.com/develop/background-work/services/alarms
- Google Play 精确闹钟政策：https://support.google.com/googleplay/android-developer/answer/17105854
- 全屏 Intent 政策：https://support.google.com/googleplay/android-developer/answer/16965181
- 通知：https://developer.android.com/develop/ui/compose/notifications

### FR-010 重启、时间变化和强制停止

监听：

```text
LOCKED_BOOT_COMPLETED
BOOT_COMPLETED
TIME_CHANGED
TIMEZONE_CHANGED
LOCALE_CHANGED
MY_PACKAGE_REPLACED
ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
```

- Direct Boot receiver 标记 `directBootAware=true`，只访问设备保护存储。
- `LOCKED_BOOT_COMPLETED` 后恢复快照中的一次性闹钟，不启动响铃前台服务。
- 用户解锁后读取 Room，校验 revision 并完整重算。
- 系统时间被调到触发点之后：10 分钟宽限期内立即响铃；超过 10 分钟记录 `MISSED` 并安排下一次。
- 时区变化后以计划 `zoneId` 重算。
- 强制停止会取消待处理 Intent，应用无法自启动恢复；下次用户主动打开时重建调度并在帮助页说明限制。

依据：

- Direct Boot：https://developer.android.com/privacy-and-security/direct-boot
- Android 15 强制停止后的 PendingIntent 行为：https://developer.android.com/about/versions/15/behavior-changes-all
- 隐式广播例外：https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions

### FR-011 地点与地图

- 高德 SDK 在用户同意隐私政策前不得初始化或调用任何 API。
- 同意后先调用 `updatePrivacyShow` 和 `updatePrivacyAgree`，再创建地图或定位客户端。
- 地图使用 `AndroidView` 包装官方 `MapView`，适配层完整转发生命周期。
- “使用当前位置”由用户点击触发，只请求前台粗略/精确定位。
- POI 搜索经 `/v1/places/search` 走后端，Android Web API key 不落客户端。
- Android 地图 SDK key 由渠道配置注入，并按包名和签名限制；不得提交生产 key。
- 隐私政策列出高德 SDK、用途和可能处理的数据。

依据：

- 高德隐私合规初始化：https://lbs.amap.com/api/compliance-center/check-and-reference/sdkhgsy
- 高德定位开发注意事项：https://lbs.amap.com/api/android-location-sdk/guide/create-project/dev-attention
- `AndroidView`：https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose

## 7. 后端 API 契约

`contract/openapi.yaml` 使用 OpenAPI 3.1，并作为客户端、服务端和测试的唯一接口真值。

### 7.1 `POST /v1/installations/attest`

请求：

```text
installationId, platform, appVersion, integrityToken?
```

响应：

```text
installationToken, quotaTier, expiresAt
```

- Play/GMS 渠道可以校验 Play Integrity standard request。
- 无 GMS、校验不可用或用户从非 Play 渠道安装时，签发短期低配额匿名令牌。
- 闹钟基础功能不得依赖证明成功。

Play Integrity standard request 需要服务端验证；非 GMS 路径必须保留：

- https://developer.android.com/google/play/integrity/standard
- https://developer.android.com/google/play/integrity/classic

Play Integrity 客户端库的精确版本须在实施时从官方发布记录锁定；当前检索信息不足，无法在本规格中可靠固定版本：

- https://developer.android.com/google/play/integrity/reference/com/google/android/play/core/release-notes

### 7.2 `GET /v1/calendars/CN/{year}`

响应：

```text
country, year, version, publishedAt, sourceUrl,
payloadSha256, signatureAlgorithm, signature, days[]
```

- 支持 `ETag` / `If-None-Match`。
- 日历发布流水线需要双人复核来源和调休日期。
- 历史版本不可覆盖同年更高版本。

### 7.3 `POST /v1/places/search`

请求：

```text
query, cityCode?, pageToken?
```

响应：

```text
items[{poiId,name,address,longitudeGcj02,latitudeGcj02,adcode,citycode}],
nextPageToken?
```

- `query` 长度限制为 1–80。
- 返回数量上限 20。
- 禁止记录原始 query、地址或坐标。

### 7.4 `POST /v1/alarm-evaluations`

请求：

```text
requestId, planId, planRevision, targetDate, timezone,
defaultWakeTime, arrivalTime,
preparationMinutes, maxAdvanceMinutes,
commuteMode, origin, destination,
waypoints, routePolicy, weatherRuleVersion
```

响应：

```text
decisionId, planId, planRevision, targetDate,
workdayStatus, estimatedDepartureAt, commuteSeconds,
weatherSeverity, weatherBufferMinutes, weatherRuleVersion,
recommendedWakeAt, routeProvider, routeProviderReportTime,
weatherProvider, weatherProviderReportTime,
weatherWindowStart, weatherWindowEnd,
fallbackReason, insufficientAdvance, generatedAt, expiresAt
```

错误模型：

```text
code, message, retryable, retryAfterSeconds?, correlationId
```

标准 `fallbackReason`：

```text
NONE
CURRENT_TRAFFIC_FALLBACK
FUTURE_ROUTE_NOT_ENTITLED
ROUTE_HORIZON_UNAVAILABLE
ROUTE_PROVIDER_TIMEOUT
ROUTE_PROVIDER_QUOTA
ROUTE_NOT_FOUND
WEATHER_HORIZON_UNAVAILABLE
WEATHER_PROVIDER_TIMEOUT
WEATHER_PROVIDER_AUTH
WEATHER_PROVIDER_QUOTA
WEATHER_UNKNOWN_CODE
CALENDAR_FALLBACK
STALE_RESPONSE
```

### 7.5 后端隐私和安全

- 高德 Web API key、彩云 App Key 和彩云 App Secret 只存在于服务端密钥管理系统。
- 访问日志不记录请求/响应 body。
- 日志、指标和 trace 不记录令牌、地址、POI、坐标或完整缓存 key。
- 需要关联请求时使用随机 `correlationId`。
- Redis key 对规范化输入使用服务端 HMAC 摘要，不拼接明文地点。
- 安装令牌只用于限流，不建立用户画像。
- Provider 错误正文先清洗再写日志。
- 限流至少区分安装令牌、来源 IP、接口和第三方 Provider 配额。
- 后端向彩云传输家庭地和工作地坐标只用于当次天气查询，不持久化 Provider 请求或响应。
- 天气展示必须标注“数据来自彩云天气”。彩云开放平台条款要求在应用显著位置标注数据来源：
  - https://platform.caiyunapp.com/user/user_agreement/

## 8. UI 规格

### 8.1 首次启动

1. 展示隐私政策和高德 SDK 披露。
2. 用户同意前不初始化高德。
3. 隐私政策同时说明：计算天气时，后端会把家庭地和工作地坐标临时传给彩云天气，但 Android 不直接连接彩云天气。
4. 依次完成通知、精确闹钟和全屏 Intent 能力诊断。
5. 可跳过地点和计划创建进入只读首页。

### 8.2 首页

- 下一次响铃日期和时间。
- 默认起床时间、实际提前分钟数。
- 工作日、通勤、天气三项计算分解。
- 路线 Provider、彩云天气数据时间和降级原因。
- 天气区域显著显示“数据来自彩云天气”。
- 权限或调度异常横幅。

### 8.3 计划编辑

- 名称、启用状态、到岗时间、默认起床、准备时长、最大提前量。
- 家庭/工作地点、通勤方式和驾车路线策略。
- 天气等级缓冲。
- 工作日规则、声音、振动和贪睡。
- 保存前展示“基础闹钟”和当前可计算的“建议闹钟”。

### 8.4 地点选择

- POI 搜索。
- 地图点选。
- 用户触发的当前位置。
- 展示坐标系为 GCJ-02 的内部约束，不向用户显示技术字段。

### 8.5 日历覆盖

- 月历显示官方工作日、官方休息日、普通工作日和普通周末。
- 用户可将任意日期覆盖为上班或休息。
- 提供“恢复官方规则”操作。

### 8.6 决策历史

- 本地保存最近 30 天决策。
- 展示计算分解、数据时间、fallback 和 occurrence 最终状态。
- 不显示或导出完整坐标和安装令牌。

### 8.7 可靠性诊断

- 通知、精确闹钟、全屏 Intent 状态。
- 通知渠道是否被关闭。
- 闹钟音量和选定铃声可读性。
- 最后一次基础注册、自动提前、Worker、响铃和重启恢复结果。
- 强制停止不可自动恢复说明。

## 9. 可观测性

### 9.1 Android

本地环形诊断记录最多 200 条：

```text
eventType, resultCode, appVersion, sdkInt,
planIdHash, occurrenceIdHash, durationMs, timestamp
```

禁止写入地址、POI、坐标、令牌、铃声 URI 或请求/响应 body。

发布后使用 Android vitals 查看崩溃和 ANR；其可提供崩溃率、ANR 率及受影响设备信息：

- https://support.google.com/googleplay/android-developer/answer/9859174
- https://support.google.com/googleplay/android-developer/answer/9844486

### 9.2 后端

指标：

```text
http_server_duration
provider_request_duration{provider,operation,outcome}
provider_quota_remaining{provider}
cache_hit{provider,operation}
circuit_state{provider,operation}
evaluation_fallback{reason}
rate_limit_rejected{endpoint,tier}
```

使用 Actuator/Micrometer 导出指标；部署环境通过 OpenTelemetry Java Agent 采集 trace，业务代码只在确有缺口时添加自定义 span。

- https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/

## 10. 测试规格

### 10.1 Android 单元测试

- 工作日三层优先级、年度跨越和签名失败。
- 跨日、跨时区、夏令时边界和系统时区变化。
- 最大提前、只提前不推迟、用户 revision 允许推迟。
- 彩云 `skycon` 全枚举、降水/风速/能见度、两地取高、时间窗口、规则版本和超出 360 小时范围。
- 各 Provider fallback。
- PendingIntent identity 和旧 occurrence 拒绝。
- Worker 重试、23:30 截止、重复执行和 stale response。

使用 JUnit、`kotlinx-coroutines-test` 和与 OkHttp 版本一致的 MockWebServer。协程测试依赖依据：

- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
- https://square.github.io/okhttp/3.x/mockwebserver/okhttp3/mockwebserver/MockWebServer.html

### 10.2 Android 仪器和系统测试

设备矩阵：API 29、31、33、34、35、36。

场景：

- 进程被杀、锁屏、Doze、省电模式、无网、弱网。
- 通知权限、全屏能力、精确闹钟能力撤销。
- 重启后未解锁、解锁后完整重算。
- 修改系统时间、时区、语言和应用升级。
- 静音、不同闹钟音量、蓝牙耳机、来电占用、损坏的自定义铃声。
- 停止、连续停止、贪睡、重复 Intent 和同分钟多计划。
- `adb shell dumpsys deviceidle force-idle`。

Robolectric 只用于有限的本地 Android 行为测试，不能替代真实设备/模拟器上的系统调度、Doze、重启和音频测试：

- https://developer.android.com/training/testing/local-tests/robolectric
- https://developer.android.com/training/testing/instrumented-tests

### 10.3 后端测试

- OpenAPI schema、兼容性和生成代码无 diff。
- PostgreSQL/Redis Testcontainers 集成测试。
- 高德路线/POI：成功、超时、空路线、错误码、配额、企业权限不足和跨城公交。
- 彩云天气：签名向量、成功、过期时间戳、重复 nonce、未知 `skycon`、缺失小时、超时、鉴权失败、配额和缓存。
- 缓存隔离、HMAC key、限流、熔断、半开恢复和并发。
- 年度日历规范化、hash、签名、验签、降级和版本回滚拒绝。
- 日志、trace、指标及数据库导出无敏感数据。

Testcontainers 依据：https://docs.spring.io/spring-boot/reference/testing/testcontainers.html

## 11. 验收标准

- 保存计划后，即使断网，也能在系统下一闹钟中看到基础闹钟。
- 正常联网时，首页能解释工作日、通勤、天气和最终提前量。
- 任一第三方接口失败时，已注册基础闹钟不被取消或推迟。
- 后续自动刷新不会推迟同一 revision 的同一次闹钟。
- 多个计划各自保有一个下一次一次性 occurrence。
- API 29–36 在进程不存在、锁屏和 Doze 条件下通过响铃测试。
- 重启后未首次解锁时能从设备保护存储恢复下一次响铃。
- 首次隐私同意前，高德 SDK 未初始化且无高德网络请求。
- 天气详情显著显示“数据来自彩云天气”，Android 安装包中不存在彩云 App Secret。
- Android 和后端日志均不包含明文地址、坐标、令牌或完整 body。
- 强制停止列为平台不可恢复边界，不计入自动恢复通过条件。
- 精确闹钟、全屏 Intent、隐私政策和 Google Play 数据安全材料完成后才允许公开发布。

## 12. 里程碑

以下为工程估算，不是外部标准；按 2 名 Android/后端开发和 1 名兼职 QA，目标 12 周。

1. 第 1 周：仓库、CI、version catalog、OpenAPI、Android/后端骨架。
2. 第 2–3 周：本地计划、工作日引擎、基础精确闹钟、Direct Boot。
3. 第 4–5 周：Compose 页面、能力诊断、地点和地图。
4. 第 6–7 周：后端、日历发布、彩云天气 Provider 及高德基础路线 Provider。
5. 第 8–9 周：未来驾车、公交迭代、多方式计算和决策解释。
6. 第 10 周：响铃服务、全屏通知、贪睡和异常恢复。
7. 第 11 周：系统矩阵、隐私合规、配额和性能测试。
8. 第 12 周：封闭测试、Play 审核材料、灰度发布和回滚演练。

## 13. 实施前置项和未决外部条件

以下事项不阻塞本规格落地，但阻塞对应功能或公开发布：

- 高德企业未来路径规划的账号资格、配额、费用和生产授权；未开通时必须使用已定义 fallback。
- 高德 Android SDK 与 Web API 的正式 key、包名/签名限制和密钥轮换流程。
- 彩云开放平台正式 App Key/App Secret、套餐配额、商用授权、数据来源标注和密钥轮换流程。
- 彩云 v2.6 天气查询输入坐标系的官方确认或控制点对照验证；关闭前不得进入生产。
- 是否购买彩云预警增值能力；未购买时核心天气等级必须完整工作。
- 后端生产域名、TLS 证书、PostgreSQL/Redis 受支持版本及备份策略。
- 日历签名私钥的保管和轮换方案；私钥不得进入仓库或 Android 客户端。
- Android 正式签名证书和 Google Play App Signing 配置。
- 隐私政策正式 URL、数据安全表、精确闹钟和全屏 Intent 声明。
- Play Integrity 是否启用及其正式依赖版本；无 GMS fallback 必须始终可用。

## 14. 发布门禁

- 依赖版本全部锁定且无动态版本。
- OpenAPI 重新生成无未提交 diff。
- Room 和 DataStore 迁移测试通过。
- API 29–36 核心响铃矩阵通过。
- 第三方断网/超时/配额/无权限 fallback 通过。
- Direct Boot、系统时间/时区变化、升级恢复通过。
- 隐私自动化扫描和人工导出检查通过。
- 高德隐私初始化顺序通过网络抓包验证。
- 彩云鉴权、坐标控制点、配额降级和“数据来自彩云天气”标注通过验收。
- Google Play 相关声明和政策材料完成。
- 发布包不包含开发 key、后端密钥、调试日志开关或测试服务器地址。
