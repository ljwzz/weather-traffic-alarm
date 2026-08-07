# 通勤闹钟（weather-traffic-alarm）产品与技术规格

- 状态：实施基线（纯 Android 本地优先架构，替代 2026-07-23 客户端+后端架构）
- 版本：2.1
- 日期：2026-08-07
- 仓库名：`weather-traffic-alarm`
- 中文名称：`通勤闹钟`
- Android `applicationId` / `namespace`：`com.ljwzz.weathertrafficalarm`
- `minSdk`：36（与当前构建一致；是否下探至 API 29–35 见第 14 章未确认项）
- `compileSdk` / `targetSdk`：36
- 可执行任务清单：[`IMPLEMENTATION_TASKS.md`](./IMPLEMENTATION_TASKS.md)

> 架构变更说明：本规格删除了后端（Spring Boot）、PostgreSQL、Redis、OpenAPI 契约、Play Integrity、Tink 日历签名与全部部署内容。第三方 API（高德、彩云天气）由 App 直接调用，凭证由用户在本机配置；工作日数据改用 holiday-cn 开源数据由 App 抓取缓存。旧的 API 29–36 支持范围改为 minSdk 36 单版本（Android 16 专属），理由与取舍见第 14 章。
>
> v2.1 闹钟架构变更：**正常起床闹钟不再由本 App 注册**，由用户在系统时钟 App 中设置并完全由系统持有；本 App 只在夜间评估完全成功且确实需要提前时，注册一个**一次性提前闹钟**。评估失败、凭证缺失或 App 被清理/强制停止时，正常起床闹钟不受任何影响，避免“本 App 失效导致没有任何闹钟响”的极端场景。本 App 无法读取系统时钟 App 的闹钟列表（无公开 API），只能做设置引导与 `getNextAlarmClock()` 启发式核对（见第 14 章取舍）。

## 1. 产品目标

用户为每个计划设置默认起床时间、到岗时间、准备时长、家庭/工作地点和固定通勤方式；默认起床时间同时是**系统时钟 App 中正常起床闹钟的引导时间**。系统在每天 19:00 后评估下一工作日的天气及通勤时间：只有评估完全成功且确实需要提前时，才注册一个一次性提前闹钟；不允许自动推迟任何闹钟。天气、路线或网络失败时不注册、不修改任何闹钟。正常起床闹钟由系统时钟 App 提供，与本 App 生命周期完全解耦。

### 1.1 首版范围

- 原生 Kotlin、Jetpack Compose，Android 16（API 36）。
- 支持驾车、公交、步行、骑行、电动车；计划保存后不自动切换方式。
- 工作日规则基于 holiday-cn 年度 JSON（法定假日 + 调休上班日），支持用户单日覆盖，失败时回退周规则。
- 默认起床时间为 06:00，默认最多提前 60 分钟。
- 无用户账号；计划、决策与日历缓存全部保存在本机，不上传任何服务端。
- 只在用户主动点击“使用当前位置”时请求前台定位。
- 保存或启用计划**不注册任何闹钟**；正常起床闹钟由用户在系统时钟 App 中设置（App 内提供引导与核对提示）；App 只在评估成功后注册一次性提前闹钟，天气、路线和网络失败不会阻止系统闹钟响铃。
- 高德与彩云凭证由用户在本机凭证配置页输入并测试连接，密钥经 Android Keystore 加密保存。

### 1.2 非目标

- 不申请后台定位，不做跨设备同步，不做 iOS/桌面客户端。
- 不提供导航、不持续监控路线。
- 不承诺绕过用户“强制停止”后的 Android 平台限制。
- 首版不自动选择或切换通勤方式。
- 不提供服务器端聚合、限流代理或任何形式的密钥托管服务。
- **不读取、不枚举、不修改系统时钟 App 的闹钟**：Android 没有公开 API 可以读取其他应用的闹钟列表；本 App 只提供设置引导与 `getNextAlarmClock()` 启发式核对。

## 2. 依赖复用结论

原则：优先使用平台 API、AndroidX、高德官方 API/SDK、彩云天气官方 API 及成熟基础库；业务规则、隐私边界、调度状态机和降级策略由本项目实现。默认不复制或分叉第三方源码。

| 能力 | 采用方案 | 项目自有实现边界 | 结论 |
|---|---|---|---|
| UI | Jetpack Compose + Material 3 | 页面、组件、主题、无障碍语义 | 采用 |
| 页面状态 | AndroidX ViewModel + StateFlow | 单向数据流、页面状态和事件约束 | 采用 |
| 导航 | Navigation Compose | 路由表和参数类型 | 采用 |
| 依赖注入 | Hilt | 作用域和 Provider 装配 | 采用 |
| 结构化数据 | Room 2 | 实体、DAO、迁移和事务 | 采用 |
| 偏好及 Direct Boot 快照 | Proto DataStore | schema、迁移和快照最小化 | 采用 |
| 非精确后台任务 | WorkManager | 19:00 调度、重试截止和幂等 | 采用 |
| 一次性提前闹钟 | `AlarmManager.setAlarmClock()` | 临时闹钟状态机、重调度和过期校验；正常起床闹钟由系统时钟 App 提供 | 采用平台 API |
| 通知 | AndroidX Core `NotificationCompat` | 渠道、动作、全屏降级 | 采用 |
| 响铃前台服务 | `systemExempted` foreground service | occurrence 校验、通知、停止和贪睡 | 采用平台能力 |
| 响铃音频 | 平台 `RingtoneManager` / `Ringtone` | 音源回退、循环、振动和停止 | 采用平台 API |
| HTTP | Retrofit + Kotlin Serialization converter + OkHttp | DTO、脱敏拦截器、错误分类 | 采用 |
| 地图/选点/前台定位 | 高德 Android 合包，通过 `AndroidView` 承载 `MapView` | Compose 适配层、隐私闸门 | 采用官方 SDK |
| 路线、POI、输入提示 | 高德 Web 服务 API，App 内直连 | 请求构造、缓存、错误码与降级 | 采用官方 API |
| 天气 | 彩云天气 v2.6 API，App 内直连 | HMAC 鉴权、天气规则、缓存与降级 | 采用官方稳定 API |
| 年度工作日 | holiday-cn 年度 JSON，App 抓取缓存 | 拉取策略、schema 校验、合并与兜底 | 采用（数据源见 2.1 评估） |
| 凭证加密 | Android Keystore AES-GCM（密钥不可导出） | 密钥管理、密文格式、备份排除 | 采用平台能力 |
| 崩溃/ANR | 本地脱敏诊断；不接入第三方采集 SDK | 环形诊断记录、凭证脱敏 | 首版采用 |

技术依据（Android 平台机制部分在实施任务中按官方文档逐步核对，本节只列本轮已核验的外部事实）：

- holiday-cn 数据格式、数据地址与注意事项：https://github.com/NateScarlet/holiday-cn/blob/master/README.md
- holiday-cn 2025 年数据（示例）：https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/2025.json
- holiday-cn 2026 年数据（示例）：https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/2026.json
- 高德 Web 服务 API 错误码说明（10001/10003/10019–10021/10044 等）：https://lbs.amap.com/api/webservice/guide/tools/info
- 彩云天气 API 计费与套餐（按量/包月/企业，QPS 差异）：https://docs.caiyunapp.com/weather-api/billing.html
- 彩云天气 API 数据与价目介绍（免费版调用量、套餐构成）：https://caiyunapp.com/api/weather_intro.html
- 彩云天气 API 官网入口：https://caiyunapp.com/api/weather
- Android `AlarmManager` 文档（`setAlarmClock()`、`getNextAlarmClock()` 自 API 21 公开、`ACTION_NEXT_ALARM_CLOCK_CHANGED` 为系统独占发送且只投递已注册 receiver、闹钟在重启后清除、强制停止会清除本应用 PendingIntent）：https://developer.android.com/reference/kotlin/android/app/AlarmManager

### 2.1 数据源评估：holiday-cn

采用 [holiday-cn](https://github.com/NateScarlet/holiday-cn)（MIT 许可）作为年度工作日数据源，理由与本项目边界：

- 数据由 CI 每日抓取国务院公告生成，JSON 顶层字段为 `year`（整数）、`papers`（所用国务院文件 URL 数组）、`days`（`name` 节日名、`date` ISO 8601 日期、`isOffDay` 是否休息日）。
- `days` 只包含法定假日与调休上班日，不包含普通周末；未列出日期按默认周规则判定。
- 官方注意事项：年份按国务院文件标题年份，12 月日期可能受下一年文件影响，应检查两个文件；“与周末连休”的周末不是法定节假日，数据中不含。
- 数据地址：`https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{年份}.json`，或 jsDelivr 镜像 `https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{年份}.json`、`https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{年份}.json`。
- 本项目的职责：拉取策略（10 月 1 日分界）、schema 校验、年份合并（12 月）、本地缓存、版本覆盖与兜底。数据真值归属国务院公告，以 `papers` 字段保留来源。
- 不采用签名机制：数据无本项目签名的需求，Tink 依赖随之移除（旧后端架构遗留）。

依据：https://github.com/NateScarlet/holiday-cn/blob/master/README.md ；数据样例 2025/2026 年文件见本节上述 URL。

### 2.2 明确不采用

- 不采用通用第三方 AlarmManager 封装：精确闹钟权限、PendingIntent 身份、Direct Boot 和厂商行为仍需由应用处理，封装不能替代领域状态机。
- 不采用 Media3 播放闹钟：首版只需本地闹铃；平台 `Ringtone` 已支持闹铃类型、循环和 AudioAttributes。
- 不采用社区节假日库作为直接依赖：直接抓取 holiday-cn 的 JSON 数据（保留 `papers` 来源），避免引入未审计代码。
- 不采用任何密钥托管/集中代理服务（如自建网关转发第三方请求）：与本地优先架构冲突。
- 不采用 Tink 等额外签名库：日历数据改为第三方 JSON 直取，签名与验签职责删除。
- 不在首版接入额外崩溃采集 SDK，以免引入新的个人信息披露和网络依赖；崩溃信息必须不含凭证（见第 9 章）。

### 2.3 源码复用规则

- AOSP DeskClock 只作为行为和异常测试参考，不直接复刻实现。
- 如确需复制小段第三方代码，必须固定来源 URL、提交号和许可证，在 `NOTICE` 记录修改；未经许可证核验不得复制。
- 依赖版本一律锁定在 version catalog；禁止 `+`、`latest.release` 或 `latest.integration`。

## 3. 技术基线与版本锁定

以下为 2026-08-07 按当前仓库 `android/gradle/libs.versions.toml` 核对的基线；升级依赖必须通过单元、仪器和响铃回归测试。

### 3.1 Android

| 项 | 版本/策略 |
|---|---|
| Android Gradle Plugin | `9.3.0` |
| Gradle Wrapper | `9.5.0` |
| Kotlin | `2.3.21` |
| KSP | `2.3.10` |
| JDK | `21` |
| `compileSdk` / `targetSdk` | `36` |
| `minSdk` | `36`（Android 16 专属，待确认项见 14 章） |
| Compose BOM | `2026.06.00` |
| Navigation Compose | `2.9.8` |
| Room | `2.8.4` |
| DataStore | `1.2.1` |
| WorkManager | `2.11.2` |
| Hilt | `2.60.1`（AndroidX Hilt `1.3.0`） |
| Retrofit | `3.0.0`（OkHttp `4.12.0` 依赖线） |
| kotlinx-serialization-json | `1.8.1` |
| kotlinx-coroutines-test | `1.11.0` |
| 高德 Android 合包 | `com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0` |
| 凭证加密 | Android Keystore（平台能力，无第三方库） |

- 移除旧基线的 Tink（`1.23.0`）：日历签名职责删除。
- AGP 9.x 使用内置 Kotlin：Android 模块不得再应用 `org.jetbrains.kotlin.android`；Compose 模块应用 `org.jetbrains.kotlin.plugin.compose`；Room 与 Hilt 代码生成统一使用 KSP。

### 3.2 第三方 API 固定要点

| 项 | 固定值 |
|---|---|
| 高德 REST 根 | `https://restapi.amap.com/`（路径规划 v5、POI 搜索 v5、输入提示 v3） |
| 彩云 REST 根 | `https://api.caiyunapp.com/`（v2.6） |
| 彩云鉴权 | App Key + App Secret 的 HMAC-SHA256（`x-cy-token` / `x-cy-timestamp` / `x-cy-signature` 请求头），不使用 URL 路径 Token 认证 |
| 天气单位 | `unit=metric:v2`，`lang=zh_CN` |
| 坐标基准 | GCJ-02（高德 SDK 内部）；彩云输入坐标基准存在未确认项（见 14 章） |

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
│   ├── core/security/
│   └── feature/{onboarding,home,plan,place,calendar,history,credentials,diagnostics}/
├── docs/
└── scripts/
```

依赖方向：

```text
feature/* -> core/{model,data,network,alarm,map,security}
core/data -> core/model
core/network -> core/model
core/alarm -> core/model
core/map -> core/model
```

约束：

- `core/model` 不依赖 Android UI、Room、Retrofit、高德或彩云类型。
- 高德 DTO 不得跨出 `core/network`（HTTP）与 `core/map`（SDK）；彩云 DTO 不得跨出 `core/network`。
- `core/security` 只暴露加密/解密与密钥生命周期 API，不得暴露明文凭证给 UI 之外的模块；UI 只允许把明文写回 `core/security`，读取时仅在连接测试等受控路径短暂可见。
- `feature/credentials` 依赖 `core/security`、`core/network`（连接测试）、`core/data`，不得被其他 feature 依赖。

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

- `preparationMinutes`：0–240；`maxAdvanceMinutes`：0–180（UI 默认 60）；`snoozeMinutes`：1–30（UI 默认 10）。
- `origin != destination`；驾车以外模式首版不接受途经点。
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

坐标只在本地 Room 中保存，参与一次本地评估计算；不得进入诊断日志或任何输出。

### 5.3 `CommuteMode`

```text
DRIVING
TRANSIT
WALKING
BICYCLING
ELECTRIC_BICYCLE
```

### 5.4 工作日

```text
CalendarYearCache(
  year: Int,
  fetchedAt: Instant,
  sourceUrl: String,
  papers: List<String>,
  days: List<CalendarDay(date, name, isOffDay)>
)
CalendarDay(date: LocalDate, name: String, isOffDay: Boolean)
WorkdayOverride(planId: UUID, date: LocalDate, status: WORKDAY|HOLIDAY)
```

工作日状态优先级固定为：

1. 用户单日覆盖。
2. holiday-cn 当年（必要时合并下一年）数据。
3. 周一至周五为工作日、周末为休息日的本地兜底。

判定规则：

- `isOffDay=false` 的日期 → 工作日（调休上班）；`isOffDay=true` → 休息日。
- 未出现在 `days` 中的日期 → 默认周规则。
- 目标日期为 12 月时，同时并入下一年缓存中 12 月条目后再判定（holiday-cn 注意事项）。
- 当年缓存缺失、过期校验失败或解析失败时使用兜底，并在首页与日历页标注原因，不阻塞响铃。

### 5.5 `ProviderCredential`

```text
provider: AMAP_WEB | AMAP_SDK | CAIYUN
fields: Map<String, String>          // 字段名 -> 密文（base64，IV+密文+版本）
configuredAt: Instant
lastTestedAt: Instant?
lastTestResult: TEST_PASSED | TEST_FAILED | NEVER_TESTED
testFailReason: String?              // 只存错误类别，不存 body
```

- 明文只允许出现在凭证配置页输入框与连接测试执行线程内。
- 密文存 `filesDir/credentials/` 下按 provider 命名的文件；`core/security` 负责 Keystore 密钥与密文读写。
- 提供“清除凭证”操作：删除密文文件，可选删除 Keystore 别名。

### 5.6 `AlarmDecision`

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
evaluationOutcome: SUCCESS | FAILED    // 决定是否允许注册/替换一次性提前闹钟
fallbackReason: FallbackReason?
insufficientAdvance: Boolean
generatedAt: Instant
expiresAt: Instant
```

### 5.7 `AlarmOccurrence`（一次性提前闹钟）

```text
occurrenceId: UUID
planId: UUID
planRevision: Long
targetDate: LocalDate
scheduledWakeAt: Instant
state: ARMED|FIRING|SNOOZED|DISMISSED|MISSED|CANCELLED
decisionId?: UUID
updatedAt: Instant
```

- 本 App 的 occurrence 只表示**一次性提前闹钟**：正常起床闹钟由系统时钟 App 持有，不在本 App 数据模型内。
- 每个启用计划对同一目标日期最多存在一个待触发的 occurrence；所有系统闹钟均为一次性，不使用重复 Alarm。
- 正常起床闹钟的引导时间（默认起床时间）保存在计划上（`defaultWakeLocalTime`），供首页展示与“系统闹钟已设置”核对提示使用。
- 设备保护存储保存 `NextTempAlarmSnapshot` 列表（不含地点），仅用于重启后恢复未触发的提前闹钟：

```text
occurrenceId, planId, planRevision, triggerAt,
soundUri, vibrationPattern, snoozeMinutes
```

## 6. 功能规格

### FR-001 创建和启用计划

1. 用户完成名称、时间、地点、方式、天气规则和工作日规则配置。
2. 保存事务先写 Room，再递增 `revision`。
3. 调度器计算下一工作日并落库；**不注册任何闹钟**（正常起床闹钟由用户在系统时钟 App 中按默认起床时间设置，App 只做引导）。
4. 更新系统闹钟引导状态（记录期望的系统闹钟时间与最近核对结果）。
5. 19:00 后保存时立即请求一次评估（评估规则见 FR-006），评估成功且需要提前时才注册一次性提前闹钟。

验收：断网保存后，本 App 不产生任何系统闹钟；系统“下一闹钟”完全由系统时钟 App 决定；网络不可用、凭证未配置或 Provider 失败均不影响正常起床闹钟。

### FR-002 工作日计算

- 按计划 `zoneId` 计算日期，禁止以系统默认时区替代。
- 按 5.4 节优先级逐日查找下一个工作日。
- 用户覆盖的修改后重算下一次工作日；目标日期已注册但未触发的临时 occurrence 立即取消（该日期改为休息日时不再提前响铃）。
- holiday-cn 数据不可用、校验失败或过期时使用兜底并在 UI 展示原因。
- 打开日历相关页面时执行 FR-015 的刷新策略，但工作日判定本身不得等待网络。

### FR-003 提前闹钟计算

统一使用目标日期的计划时区：

```text
calculatedWake = estimatedDeparture - preparation - weatherBuffer
earliestAllowed = defaultWake - maxAdvance
clampedWake = max(earliestAllowed, calculatedWake)
recommendedWake = min(defaultWake, clampedWake)
finalWake = min(existingTempWake?, recommendedWake)
```

判定与注册：

- `finalWake < defaultWake` 时注册（或替换）一次性提前闹钟；`finalWake == defaultWake`（无需提前）时不注册，并取消该目标日期待触发的临时 occurrence（若有）。
- `existingTempWake` 为同一目标日期已注册且未触发的临时闹钟时间；替换后的时间不得晚于已注册时间。

不变量：

- 自动结果不得晚于默认起床时间。
- 同一 `occurrenceId + planRevision` 的自动更新不得晚于已注册时间。
- 任何自动过程都不得把已注册的提前闹钟推后；正常起床闹钟（系统时钟 App）不参与任何计算。
- 结果早于 `earliestAllowed` 时取 `earliestAllowed`，并设置 `insufficientAdvance=true`。
- 所有分钟运算先转为 `ZonedDateTime` / `Instant`，禁止只对 `LocalTime` 做跨日减法。

### FR-004 天气缓冲

取家庭地和工作地中较严重的一端：

| 等级 | 示例 | 默认缓冲 |
|---|---|---:|
| 0 | 晴、多云、阴 | 0 分钟 |
| 1 | 小雨、小雪 | 10 分钟 |
| 2 | 中到大雨雪、雾、沙尘、强风 | 20 分钟 |
| 3 | 暴雨、暴雪；冻雨仅在预警数据明确返回时 | 30 分钟 |

- 用户可把等级 1–3 的缓冲分别设置为 0–60 分钟。
- 天气提供方固定为彩云天气 v2.6，App 内直连，凭证来自本机凭证存储。
- 分别查询家庭地和工作地，从 `[defaultWake-maxAdvance, arrivalTime]` 小时窗口内取最高严重等级，再取两地中较严重的一端。
- 使用小时级接口，按当前时间到 `arrivalTime` 动态计算 `hourlysteps`；请求固定使用 `unit=metric:v2` 和 `lang=zh_CN`。
- 彩云请求路径坐标顺序为 `{longitude},{latitude}`；响应 `location` 数组为 `[latitude, longitude]`；DTO 必须用命名字段转换，禁止传播裸数组。
- 规则输入至少包括 `skycon`、逐小时降水概率/强度、风速和能见度；映射以 `skycon` 枚举为主，不解析自然语言描述。
- `CLEAR_*`、`PARTLY_CLOUDY_*`、`CLOUDY` 为等级 0；`LIGHT_RAIN`、`LIGHT_SNOW` 为等级 1；`MODERATE_*`、`HEAVY_*`、`FOG`、`DUST`、`SAND`、`WIND` 为等级 2；`STORM_RAIN`、`STORM_SNOW` 为等级 3。未知代码不默认视为晴天，返回 `WEATHER_UNKNOWN_CODE` 并使用 0 分钟缓冲。
- 严重等级映射带 `weatherRuleVersion`，每个彩云枚举必须有契约测试。
- 保存响应顶层 `server_time` 为 `weatherProviderReportTime`，保存参与决策的小时数据时间范围，不保存完整响应。
- 彩云预警数据属于增值能力，首版核心计算不得依赖 `alert=true`；开通后只能作为等级上调信号。
- 彩云 v2.6 官方 `skycon` 表未提供独立冻雨代码，信息不足，无法仅凭常规小时数据可靠识别冻雨；不得自行用温度和降水组合推断。只有增值预警返回明确冰冻类预警时才能上调为等级 3。
- 目标工作日超出 360 小时、Provider 无对应小时数据或响应过期时，返回 `WEATHER_HORIZON_UNAVAILABLE` 和 0 分钟天气缓冲；后续每日评估进入预报范围后只能提前。
- 彩云 v2.6 使用 App Key + App Secret 的 HMAC-SHA256 鉴权；App Secret 只存在于 Keystore 加密的本地凭证存储，禁止写入源码、构建产物或日志。
- 彩云文档只明确“彩云天气 App 使用 GCJ-02”，未明确一般 v2.6 天气查询接口接受的坐标基准；实现必须保留坐标基准门禁（见 14 章），未关闭前不得进入生产发布。

### FR-005 路线计算

统一接口（`core/model` 定义，`core/network` 实现高德适配）：

```kotlin
interface RouteProvider {
    suspend fun estimate(request: RouteRequest): RouteEstimate
}
```

实现规则：

- 驾车：使用高德路径规划 v5 驾车接口查询；可选高级能力“未来路径规划”首版不作为依赖。从到岗前 180 分钟起按 15 分钟步长生成候选，选择可准时到达的最晚出发点。
- 未来驾车服务未开通、目标超出服务时间范围、配额耗尽或调用失败时，退化为基础驾车耗时并标记 `CURRENT_TRAFFIC_FALLBACK`。
- 公交：传入目标日期和时间；以历史缓存或 90 分钟为初始估算，最多向前重试三次，每次 15 分钟，选择可准时到达的最晚方案。
- 步行、骑行、电动车：查询一次静态路径耗时，不声明未来拥堵预测。
- Provider 必须设置连接、读取和总超时；只对可重试错误执行有限重试。
- 每种方式使用独立缓存 key 与失败统计，模式间不得相互拖垮。
- 高德返回 `ROUTE_NOT_FOUND`（如 20801/20802/20803）、超时或配额错误时按错误码归类并停止本轮（FR-006）。

### FR-006 夜间评估与“成功才注册提前闹钟”不变量

- 保存或启用计划**不注册任何闹钟**；正常起床闹钟由系统时钟 App 提供（FR-001）。
- 使用唯一 `OneTimeWorkRequest` 计算下一次本地 19:00，并增加 0–15 分钟抖动；Worker 完成后安排下一天任务，不使用长时间常驻服务。
- 网络约束为 `CONNECTED`；失败后分别在 15、30、60 分钟重试；本地 23:30 后停止主动重试。
- Worker 输入只含计划 ID；执行时读取最新 `revision`；每个计划使用幂等键 `planId:revision:targetDate`。
- 评估流水线：工作日判定 → 路线估算 → 天气缓冲 → 计算 `recommendedWake`。
- **核心不变量：只有流水线完全成功（`evaluationOutcome=SUCCESS`）才允许注册或替换一次性提前闹钟；任一步失败（网络、超时、配额、凭证无效、数据校验失败、`ROUTE_NOT_FOUND`、`WEATHER_HORIZON_UNAVAILABLE` 等）时，本轮不产生任何调度修改，已注册的同日临时 occurrence 保持原样。**
- 成功时按 FR-003 判定并执行：需要提前 → 按 FR-007 原子注册/替换；无需提前 → 取消该目标日期待触发的临时 occurrence。
- 每次评估无论成败都写一条 `AlarmDecision`（`evaluationOutcome` 区分），供决策历史页展示。
- WorkManager 不承担精确响铃职责，只负责提前的数据刷新；提前响铃必须交给 AlarmManager，正常起床闹钟由系统时钟 App 负责。
- App 被清理（后台回收）或强制停止后：评估不再发生、已注册的提前闹钟被清除（强制停止场景），但系统时钟 App 的正常起床闹钟始终生效；此边界见 FR-010 与第 12 章验收。

### FR-007 一次性提前闹钟注册

- 使用 `AlarmManager.setAlarmClock()` 注册用户可见的一次性精确闹钟，仅用于提前提醒。
- 每个 occurrence 使用显式、不可变 `PendingIntent`；身份不得只依赖 extras，使用唯一 request code，并把 occurrence ID 放入唯一 data URI。
- 注册前写入 occurrence 和 Direct Boot 快照；注册成功后更新状态。
- 重新计算（成功评估后提前）时先注册新 PendingIntent，确认无异常后取消旧 PendingIntent，再提交新状态；异常时保留旧闹钟。
- Receiver 收到 Intent 后必须按 occurrence ID、计划 revision、状态和触发时间窗口重新校验。
- 取消语义：只有“用户主动修改计划/覆盖工作日”“成功评估且无需提前”和“响铃结束”允许取消待触发或已触发的临时 occurrence。

### FR-008 响铃服务

- `AlarmReceiver` 只完成校验和启动 `AlarmRingingService`，不得进行网络请求。
- `AlarmRingingService` 为前台服务，声明 `foregroundServiceType="systemExempted"`；Manifest 声明 `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`。
- 使用 `RingtoneManager.TYPE_ALARM` 解析声音；自定义 URI 失效时依次回退到系统 alarm、notification，再到应用内置声音。
- 使用 `AudioAttributes.USAGE_ALARM`，循环播放并按计划振动。
- 通知提供停止和贪睡；操作 Intent 显式、不可变且带 occurrence 身份；停止或贪睡必须幂等。
- 贪睡创建新的 `SNOOZED` occurrence，并仍使用 `setAlarmClock()`。
- 响铃内容为提前提醒（如“天气/通勤导致今日需提前出发”）；正常起床响铃由系统时钟 App 完成，不在本 App 范围。

### FR-009 通知、全屏和能力诊断

Manifest 权限：

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

- 应用核心功能属于闹钟（提前闹钟为闹钟类型），采用 `USE_EXACT_ALARM`，发布前完成 Google Play 对应声明。
- Android 13+ 请求通知运行时权限；全屏 Intent 只用于正在响铃的 occurrence。
- 能力诊断只约束本 App 的提前闹钟：精确闹钟能力或通知能力缺失时，提前闹钟不可用并在 UI 标记降级（正常起床闹钟由系统时钟 App 提供，不受影响）；不因能力缺失阻止计划启用。
- 每次回到前台和启用计划前重新诊断，不能只依赖首次启动结果。

### FR-010 重启、时间变化和强制停止

监听：`LOCKED_BOOT_COMPLETED`、`BOOT_COMPLETED`、`TIME_CHANGED`、`TIMEZONE_CHANGED`、`LOCALE_CHANGED`、`MY_PACKAGE_REPLACED`、`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`。

- Direct Boot receiver 标记 `directBootAware=true`，只访问设备保护存储；`LOCKED_BOOT_COMPLETED` 后从快照恢复**未触发的提前闹钟**，不启动响铃前台服务。
- 用户解锁后读取 Room，校验 revision 并完整重算。
- 系统时间被调到触发点之后：10 分钟宽限期内立即响铃；超过 10 分钟记录 `MISSED` 并取消该次提前闹钟（正常起床闹钟仍由系统时钟 App 负责，无需本 App 补位）。
- 时区变化后以计划 `zoneId` 重算。
- 强制停止会取消本 App 待处理的 PendingIntent 与 Worker，应用无法自启动恢复；此时提前闹钟与评估均失效，但**系统时钟 App 的正常起床闹钟不受影响**，仍能保证响铃；下次用户主动打开时重建调度并在帮助页说明限制。
- 应用覆盖安装（`MY_PACKAGE_REPLACED`）不丢失 Room 数据、凭证密文与日历缓存；凭证密文格式升级由 `core/security` 的密文版本字段驱动迁移。

### FR-011 地点与地图

- 高德 SDK 在用户同意隐私政策前不得初始化或调用任何 API；同意后先调用 `updatePrivacyShow` 和 `updatePrivacyAgree`，再创建地图或定位客户端。
- 地图使用 `AndroidView` 包装官方 `MapView`，适配层完整转发生命周期。
- 地图渲染与“使用当前位置”需要高德 Android SDK Key（凭证页配置，可选）；未配置时隐藏地图与定位入口，地点选择退化为 POI 搜索 + 输入提示（仅需 Web Key）。
- “使用当前位置”由用户点击触发，只请求前台粗略/精确定位。
- POI 搜索与输入提示走高德 Web 服务 API（需 Web Key），密钥来自凭证存储。
- Android SDK Key 绑定包名与签名，泄露面受 Keystore 与备份排除保护。

### FR-012 凭证配置与连接测试

凭证配置页（`feature/credentials`）分两个区块：

1. **高德**：
   - Web 服务 Key（必填，用于路线、POI、输入提示）。
   - Android SDK Key（可选，用于地图选点与定位；若未配置，FR-011 相关功能隐藏）。
   - “测试连接”：用 Web Key 调用一次轻量接口（输入提示或地理编码），按返回码判定：`10000 OK` 通过；`10001` key 无效；`10003/10044` 配额超限；网络错误单独提示。SDK Key 无 HTTP 探活，测试项显示“保存后在地点页验证”。
2. **彩云天气**：
   - App Key（必填）与 App Secret（必填，HMAC 签名用）。
   - “测试连接”：构造一次带签名的 v2.6 天气查询（家庭地坐标），校验签名正确性、`status=ok` 与响应解析；失败时按类别提示（鉴权失败/网络/解析/配额）。
3. 通用规则：
   - 保存前必须完成一次成功的连接测试，或用户显式跳过并确认风险。
   - 任一 Provider 未配置或测试失败时，评估流水线（FR-006）跳过对应步骤，不注册/不修改提前闹钟；正常起床闹钟由系统时钟 App 提供，不受影响。
   - 输入框使用密码掩码；页面设置 `FLAG_SECURE` 禁止截图与录屏；粘贴与输入值不进入任何日志。
   - 提供“清除凭证”。

### FR-013 凭证加密存储与防泄露

- 密钥生成：`KeyGenParameterSpec` 在 Android Keystore 中生成 AES-256-GCM 对称密钥，`setKeyPurpose(ENCRYPT|DECRYPT)`，**不设置任何可导出标志**；别名固定如 `wtalarm_credentials_v1`。API 36 下无需用户凭据绑定（首版不要求锁屏认证门槛）。
- 加密：每次写入使用随机 12 字节 IV；密文文件格式为 `[版本号 1B][IV 12B][AES-GCM 密文+tag]`，base64 存储。
- 存储位置：`Context.filesDir/credentials/`（应用私有目录），文件名与 provider 对应；明文不落 Room、不落 SharedPreferences/DataStore、不落系统日志。
- 备份排除：声明 `android:allowBackup` 与 `dataExtractionRules`（API 31+）/ `fullBackupContent`（API 29–30 兼容路径），排除 `filesDir/credentials/` 与任何含密文或明文的路径；同时排除凭证相关 SharedPreferences 路径。验收必须包含“备份/恢复后凭证不存在且应用不崩溃”的测试。
- 防泄露四原则：
  1. 日志：OkHttp 拦截器与全局日志对 Authorization 头、URL 中的 key/token 参数、`x-cy-*` 头做强制脱敏；诊断环形记录不含凭证字段。
  2. 截图：凭证配置页 `FLAG_SECURE`。
  3. 崩溃：不接入第三方崩溃采集；系统崩溃报告不含私有文件内容；本地诊断不记录凭证。
  4. 导出：任何“导出数据”功能不得包含凭证明文或密文。
- 密钥轮换：提供“清除凭证并重置密钥”操作；密文版本字段支持将来换密钥格式。

### FR-015 工作日日历抓取与刷新（holiday-cn）

触发时机：打开日历相关页面（日历覆盖页、计划编辑中的工作日预览）时执行一次刷新；评估流水线（FR-006）只读取缓存，不触发网络。

刷新算法（单次进入日历页执行，全部失败不阻塞页面渲染）：

```text
1. 清理：删除本地所有 year < 当前年份 的 CalendarYearCache（含陈旧文件缓存）。
2. 目标年份：今天 < 10月1日 → [当前年]；今天 ≥ 10月1日 → [当前年, 当前年+1]。
3. 对每个目标年份 y：
   a. 若本地已有 y 的缓存且校验通过（见 4）→ 跳过（不产生网络请求）。
   b. 否则按源顺序抓取：raw.githubusercontent.com → cdn.jsdelivr.net → fastly.jsdelivr.net；
      前一个失败（网络/超时/HTTP 错误/校验失败）自动切换下一个。
4. 校验（每份数据必须全部通过，否则丢弃本次数据、保留旧缓存并记录原因）：
   - JSON 可解析；顶层含 year（整数）、papers（字符串数组）、days（数组）。
   - year 必须等于请求年份 y。
   - days 每项：date 为合法 ISO 日期且 `date.year == y`（12 月合并场景允许跨年，见 5.4）；
     isOffDay 为布尔；name 为非空字符串。
   - days 数量 ≥ 5（法定假日最少 5 个休息日的下界）。
   - papers 非空（保留官方来源 URL）。
5. 覆盖：校验通过后原子覆盖 y 的缓存（更新 fetchedAt、sourceUrl、papers），不合并旧数据。
6. 兜底：目标年份缓存全部缺失时，工作日判定使用周规则；首页与日历页标注“日历数据不可用，使用默认规则”。
7. 记录：每次刷新写入本地诊断（success/failed、失败类别、耗时），不写 body。
```

边界处理：

- 长时间未打开/跨年：步骤 1 清理去年数据；步骤 2 按打开时的当前年份重新计算目标集合；旧年份残留数据由清理步骤兜底。
- 网络失败：保留旧缓存；连续失败计数写入诊断；下次打开日历页重试；不阻塞响铃与工作日兜底。
- 数据校验失败：丢弃新数据、保留旧数据、记录原因；同源多次失败后仍保留旧数据（只要旧数据通过校验）。
- 版本覆盖：新数据总是覆盖同一年旧数据（fetch 到更新版本时）；App 覆盖安装不触发强制重抓，依赖步骤 3a 的“已通过校验即跳过”与 12 月合并语义。
- 12 月语义：判定 12 月日期时合并当年与次年缓存（holiday-cn 注意事项）；若次年数据缺失，仅用当年数据并接受官方“可能受次年文件影响”的已知窗口。
- 并发：刷新与评估可能并发读取，写入采用单事务覆盖；同一年份的重复抓取以最后成功者为准。
- 请求节制：单日对同一数据源失败超过 3 次后，当天不再重试该源（避免无限重试），次日自动恢复。

## 7. 第三方 API 契约要点

### 7.1 高德 Web 服务 API（App 内直连）

| 用途 | 接口 | 关键参数 |
|---|---|---|
| 驾车/步行/骑行/公交路径 | v5 `direction/{driving,walking,cycling,transit}` | `origin,destination,waypoints,strategy,date,time`（公交） |
| POI 搜索 | v5 `place/text` | `keywords,region,citycode,offset,page` |
| 输入提示 | v3 `assist/inputtips` | `keywords,city,location` |
| 连接测试 | v3 输入提示或 v3 地理编码 | 固定测试用例，不记录输入值 |

- 全部请求携带 `key`（来自凭证存储）；响应顶层 `status`/`info`/`infocode` 三字段错误模型。
- 已知错误码归类：`10001` key 无效；`10003/10044` 日/账号配额超限；`10019/10020/10021` QPS 超限；`20800–20803` 路线不可用；其余按 `info` 归类为失败。

### 7.2 彩云天气 v2.6 API（App 内直连）

| 用途 | 接口 | 关键参数 |
|---|---|---|
| 天气实况+小时预报 | `v2.6/{lng},{lat}/weather` 及 `hourly` 视图 | `hourlysteps,unit=metric:v2,lang=zh_CN` |
| 连接测试 | 同上（家庭地坐标或固定测试坐标） | 同上 |

- 鉴权：App Key + App Secret，HMAC-SHA256 生成 `x-cy-signature`，配合 `x-cy-token`、`x-cy-timestamp` 请求头；App Secret 只存在于 Keystore 加密凭证。
- 响应含 `status`、`api_version`、`location`、`server_time`、`result`；`skycon` 枚举映射见 FR-004。
- 套餐与 QPS 以账号后台为准（免费版 10000 次等介绍见第 2 章依据）。
- 数据来源标注：天气展示区域必须显著标注“数据来自彩云天气”（彩云开放平台条款要求）。

### 7.3 错误模型（App 内部）

```text
ProviderError(
  category: NETWORK | TIMEOUT | HTTP | AUTH | QUOTA | NOT_FOUND | PARSE | UNKNOWN,
  providerCode: String?,
  retryable: Boolean
)
```

- `QUOTA`（高德 10003/10044 等、彩云限流错误）与 `AUTH` 视为不可重试；本轮评估直接结束且不注册/不修改提前闹钟。
- 所有请求超时、`HTTP != 200` 或解析失败时按错误类别结束本轮评估。

## 8. UI 规格

### 8.1 首次启动

1. 展示隐私政策（高德 SDK 披露、彩云数据来源披露）。
2. 用户同意前不初始化高德 SDK。
3. 依次完成通知、精确闹钟和全屏 Intent 能力诊断（缺失只影响提前闹钟，不阻止计划启用）。
4. 引导进入凭证配置页（可跳过）；未配置凭证不阻塞计划创建与系统闹钟引导。
5. 展示“正常起床闹钟请在系统时钟 App 中设置”的引导（计划创建页再次展示）。
6. 可跳过地点和计划创建进入只读首页。

### 8.2 首页

- 下一次响铃信息：默认起床时间（系统时钟 App 引导时间）与系统闹钟核对状态；已注册的一次性提前闹钟时间与实际提前分钟数（若有）。
- “系统闹钟核对”为启发式：`AlarmManager.getNextAlarmClock()` 的全局下一闹钟落在计划默认起床时间 ±10 分钟内时提示“已确认”；无法确认时显示“请确认已在系统时钟 App 设置 X:XX 闹钟”的常驻提醒，不假设已设置。
- 工作日、通勤、天气三项计算分解；评估失败时展示原因与“正常闹钟不受影响，本次无提前提醒”的说明。
- 路线 Provider、彩云数据时间与降级原因；天气区域显著显示“数据来自彩云天气”。
- 权限、凭证缺失或调度异常横幅。

### 8.3 计划编辑

- 名称、启用状态、到岗时间、默认起床、准备时长、最大提前量。
- 家庭/工作地点、通勤方式和驾车路线策略；天气等级缓冲；工作日规则、声音、振动和贪睡（贪睡仅作用于本 App 的提前闹钟）。
- 保存前展示“系统闹钟引导时间”（默认起床时间，需用户在系统时钟 App 设置）与当前可计算的“建议提前闹钟”（建议值来自最近一次成功评估，不实时联网）。

### 8.4 地点选择

- POI 搜索与输入提示（Web Key）；已配置 SDK Key 时提供地图选点与“使用当前位置”。
- 展示坐标系为 GCJ-02 的内部约束，不向用户显示技术字段。

### 8.5 日历覆盖

- 月历显示官方休息日、调休上班日、普通工作日和普通周末（区分数据来源：holiday-cn / 兜底规则）。
- 顶部显示数据状态（最后抓取时间、来源 URL、本次刷新结果）。
- 用户可将任意日期覆盖为上班或休息；提供“恢复官方规则”操作。

### 8.6 凭证配置

- 高德/彩云两个区块，字段、掩码输入、保存、连接测试（FR-012）、清除凭证。
- 页面 `FLAG_SECURE`。

### 8.7 决策历史

- 本地保存最近 30 天决策；展示计算分解、数据时间、`evaluationOutcome`、fallback 与提前闹钟 occurrence 最终状态。
- 不显示或导出完整坐标与凭证。

### 8.8 可靠性诊断

- 通知、精确闹钟、全屏 Intent 状态；通知渠道状态；闹钟音量与铃声可读性。
- 系统闹钟引导与核对状态（期望时间、最近一次 `getNextAlarmClock()` 结果）；最后一次提前闹钟注册/替换/取消、Worker、响铃、重启恢复和日历刷新结果。
- 凭证配置状态（是否已配置、上次测试时间与结果，不显示任何密钥）。
- 强制停止不可自动恢复说明（提前闹钟失效，正常闹钟不受影响）。

## 9. 隐私与安全

- 本地优先：所有数据（计划、决策、日历缓存）只存本机，无任何网络上传路径。
- 凭证：Android Keystore 不可导出密钥加密，密文存应用私有目录，备份排除（FR-013）；明文只存在于输入框与测试执行线程。
- 防泄露四原则（日志脱敏、截图禁止、崩溃信息隔离、导出排除）见 FR-013。
- 日志：本地环形诊断记录最多 200 条，字段限定为事件类型/结果码/版本/哈希 ID/耗时/时间戳；禁止写入地址、POI、坐标、凭证、铃声 URI 或请求/响应 body。
- 高德 SDK 隐私合规：同意前不初始化；隐私政策列出高德 SDK 与彩云数据来源。
- 数据来源标注：天气区域“数据来自彩云天气”。
- 发布渠道使用 Android vitals 查看崩溃与 ANR；不接入第三方采集 SDK。

## 10. 可观测性

- 本地环形诊断（最多 200 条）：

```text
eventType, resultCode, appVersion, sdkInt,
planIdHash, occurrenceIdHash, durationMs, timestamp
```

- 不设置任何远端上报通道。

## 11. 测试规格

### 11.1 纯单元测试

- 工作日判定：holiday-cn 语义（调休上班日、未列出日期周规则）、三层优先级、12 月跨年合并、缓存缺失/校验失败兜底。
- 日历刷新算法：10 月 1 日分界、去年清理、目标年份集合、源切换顺序、重复抓取跳过、连续失败节制、原子覆盖。
- 提前闹钟计算：跨日、跨时区、夏令时边界、最大提前、只提前不推迟、`insufficientAdvance`、无需提前时取消待触发 occurrence。
- occurrence 状态机与 PendingIntent 身份；系统闹钟引导核对逻辑（`getNextAlarmClock()` 窗口判定）。
- 彩云 `skycon` 全枚举、降水/风速/能见度、两地取高、时间窗口、规则版本、360 小时范围、签名向量（HMAC 固定测试向量）。
- Provider 错误分类：高德错误码表全量、网络/超时/解析。
- 凭证存储：加解密往返、密文版本迁移、密钥别名缺失恢复、损坏密文拒绝。
- Worker：重试、23:30 截止、重复执行、幂等键、评估失败不注册/不改提前闹钟的不变量测试（用 fake provider 强制失败验证 occurrence 不变）。

### 11.2 仪器和系统测试

设备矩阵：API 36（当前 minSdk 单版本；若下探见 14 章）。

场景：

- 进程被杀、锁屏、Doze、省电模式、无网、弱网。
- 通知权限、全屏能力、精确闹钟能力撤销。
- 重启后未解锁、解锁后完整重算；修改系统时间、时区、语言和应用升级。
- 凭证页截图被阻止（`FLAG_SECURE` 生效）、备份恢复后凭证不存在。
- 静音、不同闹钟音量、蓝牙耳机、来电占用、损坏的自定义铃声。
- 停止、连续停止、贪睡、重复 Intent 和同分钟多计划。
- 日历页断网/脏数据/跨年场景的 UI 状态。

### 11.3 契约与回归

- 彩云/高德 MockWebServer fixture 与错误码用例。
- Room 与 DataStore 迁移测试；日志与导出隐私测试（扫描关键词：key、secret、坐标、地址）。
- 响铃回归必须覆盖“评估失败后已注册提前闹钟时间未变化”与“评估成功且无需提前时待触发提前闹钟被取消”的断言。

## 12. 验收标准

- 保存计划后，本 App 不注册任何闹钟；系统“下一闹钟”完全由系统时钟 App 决定，不受本 App 影响。
- 正常联网且凭证有效时，首页能解释工作日、通勤、天气和最终提前量；提前闹钟只在需要提前时注册。
- 任一第三方接口失败（超时/配额/凭证无效/解析失败）时，已注册的一次性提前闹钟不被取消或推迟，且不会新注册。
- 后续自动刷新不会推迟同一 revision 的已注册提前闹钟；只有成功评估才能提前；成功评估且无需提前时取消待触发提前闹钟。
- 每个启用计划对同一目标日期最多保有一个待触发的一次性提前闹钟；正常起床闹钟由系统时钟 App 持有。
- 重启后未首次解锁时能从设备保护存储恢复待触发的提前闹钟；正常起床闹钟不受重启影响（系统时钟 App 负责）。
- 首次隐私同意前，高德 SDK 未初始化且无高德网络请求。
- 凭证页不可截图；应用备份/迁移后设备上不存在凭证；日志、崩溃与导出内容不含凭证。
- 日历页按 10 月 1 日分界正确拉取年份；断网或脏数据时不阻塞页面，工作日兜底可用。
- 天气详情显著显示“数据来自彩云天气”；Android 安装包内不存在任何硬编码密钥。
- 强制停止下列为平台不可恢复边界：提前闹钟与夜间评估失效，但系统时钟 App 的正常起床闹钟仍然生效，UI 帮助页明示该边界。

## 13. 里程碑

以下为工程估算，不是外部标准；按 2 名 Android 开发，目标 8 周。

1. 第 1 周：仓库清理、构建基线、version catalog 锁定、验证脚本。
2. 第 2 周：领域模型、工作日引擎（holiday-cn 语义）、闹钟纯计算、occurrence 状态机。
3. 第 3 周：数据层（Room/DataStore/快照）与一次性提前闹钟（注册、响铃、贪睡、Direct Boot、系统闹钟引导与核对）。
4. 第 4 周：凭证安全（Keystore、密文存储、备份排除、脱敏）与凭证配置页（连接测试）。
5. 第 5 周：日历抓取与刷新策略（FR-015）、日历覆盖页。
6. 第 6 周：高德 Provider（路线/POI/输入提示）、彩云 Provider（HMAC、天气规则）、地点与地图。
7. 第 7 周：统一评估与 WorkManager 夜间任务、首页/决策历史/诊断页。
8. 第 8 周：系统矩阵、隐私合规、发布门禁。

## 14. 实施前置项和未决外部条件

以下事项不阻塞本规格落地，但阻塞对应功能或公开发布：

- **彩云输入坐标基准（未确认项）**：彩云文档只明确 App 使用 GCJ-02，未明确一般 v2.6 天气查询接口接受的坐标基准；实现必须以官方书面确认或已知控制点对照测试关闭门禁，未关闭前不得进入生产发布。
- **minSdk 收窄（待确认）**：当前代码基线为 minSdk 36（Android 16 专属），旧规格为 API 29–36；是否支持更早版本由发布目标决定，若需下探需补充兼容矩阵与回归。
- **holiday-cn 数据为社区维护（确认的取舍）**：数据真值在国务院公告，holiday-cn 负责抓取整理（MIT 许可）；本项目以 `papers` 保留官方来源并接受其维护节奏（通常 10 月底/11 月发布次年安排，故 10 月 1 日后预拉次年允许“文件暂未发布”的失败并回退）。
- **高德 Web Key 直连客户端（已知风险）**：Web 服务 Key 只能绑定 IP 白名单，客户端直连（移动网络 IP 不固定）无法启用；风险由 Keystore 加密 + 备份排除 + 用户自持 Key 承担，文档与 UI 需明确提示。
- **高德 Android SDK Key 与正式签名**：SDK Key 绑定包名+签名；正式签名证书未配置前 SDK Key 只能用于 debug 签名。
- **彩云套餐**：正式 App Key/App Secret、套餐配额、商用授权与数据来源标注；未购买预警增值能力时核心天气等级必须完整工作。
- **Google Play 声明材料**：精确闹钟、全屏 Intent、隐私政策、数据安全表；完成前不允许公开发布。
- **系统闹钟核对为启发式（确认的取舍）**：Android 无公开 API 读取系统时钟 App 的闹钟列表；`AlarmManager.getNextAlarmClock()` 只返回全局下一个 alarm clock，且 `ACTION_NEXT_ALARM_CLOCK_CHANGED` 为系统独占发送（第三方应用仅能注册 receiver 接收）。因此“已确认设置了系统闹钟”只能基于时间窗口匹配做启发式判断，存在其他应用闹钟误匹配的可能；UI 一律以“请确认”而非“已设置”措辞表达，且不依赖该核对做任何调度。

## 15. 发布门禁

- 依赖版本全部锁定且无动态版本；无 `backend`、`contract`、`calendar-data`、`infra` 等遗留目录。
- Room 和 DataStore 迁移测试通过；API 36 核心响铃矩阵通过。
- “评估失败不注册/不改提前闹钟”与“断网保存不产生任何系统闹钟（正常闹钟由系统时钟 App 提供）”验收通过。
- Direct Boot、系统时间/时区变化、升级恢复通过。
- 凭证安全四项验收（日志/截图/崩溃/导出无凭证）与备份排除测试通过。
- 高德隐私初始化顺序通过网络抓包验证；彩云签名向量与坐标控制点验证通过。
- 日历刷新算法（10 月 1 日分界、去年清理、源切换、兜底）单元与仪器测试通过。
- Google Play 相关声明和政策材料完成；发布包不包含任何密钥、调试开关或测试端点。
