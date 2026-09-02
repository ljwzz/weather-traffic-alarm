# 知途（weather-traffic-alarm）产品与技术规格

- 状态：实施与设计交接基线（本地闹钟优先）
- 版本：4.0
- 日期：2026-08-31
- 仓库名：`weather-traffic-alarm`
- 显示名称：`知途`
- Android `applicationId` / `namespace`：`com.ljwzz.weathertrafficalarm`
- `minSdk`：36（与当前构建一致；是否下探至 API 29–35 见第 14 章未确认项）
- `compileSdk`：37（Android 17；依赖基线升级所致，见 3.1）/ `targetSdk`：36
- 可执行任务清单：[`IMPLEMENTATION_TASKS.md`](./IMPLEMENTATION_TASKS.md)
- 设计与原型交接：[`docs/design-handoff.md`](./docs/design-handoff.md)

> 2026-09-01 高德已接入：Android 已实现首次专项授权、Web Service Key／Android SDK Key的加密运行时存储、输入提示、POI 搜索、地图选点、单次定位、五种路线、最多三条备选、当前路况和计划覆盖。待用户提供两项真实 Key 后完成设备实网验收。`prototype/` 继续使用确定性离线 fixture，不发送请求、不使用真实 Key、不显示坐标。天气与提前计算仍未接入。
>
> 当前 Figma 设计稿决定页面级需求；开发和界面验收参照本地 [`prototype/`](./prototype/) 的页面结构、布局、组件、文案与交互。除非用户明确要求修改，不得自行调整原型或另行设计。非视觉业务与安全规则以本规格为准；两者冲突时先向用户确认。页面和节点见 [`docs/design-handoff.md`](./docs/design-handoff.md)。

## 0. 当前本地闹钟实施基线

本节优先于本文早期的通勤评估、系统时钟引导、提前闹钟和 Provider 页面描述。本文保留的路线、天气和提前计算接口是后续规划，不表示已实现或可验收；实际完成状态只能由本轮构建、测试和设备记录确认。

### 0.1 用户可用功能

- 创建、编辑、启用、停用和删除多个本地闹钟；首次安装无预置闹钟和预置记录。
- 每个闹钟包含名称、`Once(date)`、`Weekly(days)` 或 `Workdays` 日期规则、当地时区时间、铃声、振动和贪睡时长。新建默认单次 `06:00`；当天该时间已过时默认选次日；用户选择过去的单次日期时间不得保存。
- 保存或启用后由 `LocalAlarmCoordinator` 原子地写入计划、实例和实际注册状态，再通过 `AlarmManager.setAlarmClock()` 注册下一次实例；Android 将该方法定义为代表闹钟的调度方式，并预期应用在触发时进行响铃、振动等用户提醒。https://developer.android.com/reference/android/app/AlarmManager
- 状态必须区分用户的启用意图与实际调度结果：`DISABLED`、`NEEDS_RULE`、`NEEDS_PERMISSION`、`SCHEDULED`、`FAILED`、`COMPLETED`。未取得所需精确闹钟能力时，界面不得显示“已注册”；系统设置返回后重新读取状态。Android 官方要求在调度前检查精确闹钟能力，并在设置页返回后重新检查。https://developer.android.com/training/permissions/requesting-special
- 响铃服务循环播放可读铃声并按设置振动；停止、连续停止、贪睡和重复广播均按实例 ID 幂等。贪睡创建独立子实例，不改写后续重复实例；单次实例完成后自动停用，重复实例只安排其下一次。
- 日历提供真实月份和日期选择；本日覆盖按“计划 ID + 日期”保存为沿用计划、本日停用、本日启用或替代时间。基础兜底按周一至周五判为工作日，后续可由 holiday-cn 数据覆盖。

### 0.2 明确留白

- 天气页和提前计算仍显示“暂未接入”；高德地图与路线页展示授权、Key、加载、成功、拒绝和错误状态。
- 高德运行时凭据在原型仅保留当前页面会话；Android 已使用本地加密保存和清除。原型验证 fixture 时不得发送请求。Android Keystore 可将密钥材料保持在应用进程外，并限制密钥的授权用途。https://developer.android.com/privacy-and-security/keystore
- 权限与诊断页在 Android 应用中读取通知、精确闹钟、全屏提醒和闹钟音量等本机状态并提供设置入口；Web 原型只说明该行为，不读取系统状态。

### 0.3 领域与调度边界

```kotlin
sealed interface AlarmSchedule {
    data class Once(val date: LocalDate) : AlarmSchedule
    data class Weekly(val days: Set<DayOfWeek>) : AlarmSchedule
    data object Workdays : AlarmSchedule
}

enum class AlarmArmedState {
    DISABLED, NEEDS_RULE, NEEDS_PERMISSION, SCHEDULED, FAILED, COMPLETED
}
```

- `LocalAlarmCoordinator` 是计划保存、启停、删除、下一次计算、注册、触发、停止、贪睡、重启和时间变化恢复的唯一入口；页面不得直接调用系统调度器。
- 每个实例保存唯一 ID、父计划 ID、计划版本、触发时刻、父实例 ID（仅贪睡）和状态。接收器校验 ID、版本、状态与触发窗口；不匹配只记录本机诊断并结束。
- 新实例注册成功后才取消被替代的同计划实例；失败保留仍有效的既有实例并记录原因。设备重启、解锁、时区或时间变更、覆盖安装后均重算并恢复有效实例；迟到超过 10 分钟记为 `MISSED`，不补发。
- 响铃由前台服务承接。`setAlarmClock()` 是精确、面向用户的闹钟能力；是否能使用精确闹钟必须根据运行时能力检查处理。https://developer.android.com/develop/background-work/services/alarms

## 1. 产品目标

用户可创建多个由本 App 负责注册和响铃的本地闹钟。每个闹钟独立选择名称、日期时间、单次／每周／工作日规则、铃声、振动和贪睡。路线、天气和地图不参与当前闹钟调度。

### 1.1 首版范围

- 原生 Kotlin、Jetpack Compose，Android 16（API 36）。
- 支持多个本地闹钟、单次／每周／工作日规则、启停、删除、停止与贪睡。
- 新闹钟默认单次 `06:00`；当天时间已过自动选择次日。每周规则至少选择一天；用户指定的过去单次时间无效。
- 工作日由本地 `WorkdayCalendarRepository` 读取 holiday-cn 缓存并结合每计划每日期覆盖判定；缓存缺失或刷新失败时按星期规则兜底。
- Figma 的 21 个页面是视觉素材基线；当前 Android 范围为 12 个主页面及路线／日历功能整合，不得将设计素材数等同于原生实现页面数。
- 无用户账号；计划、决策与日历缓存全部保存在本机，不上传任何服务端。
- 首次启动要求用户选择同意高德授权或仅用基础功能；只在用户主动点击“使用当前位置”时请求前台定位。
- 保存或启用闹钟按实际能力注册下一次本地实例；注册失败保留失败原因和可重新检查入口。
- 高德 Web Service Key、Android SDK Key 与彩云 App Key/App Secret 均由用户在凭证页配置。Android 已实现加密保存、Provider 连接测试与手动天气预览；彩云设备实网与界面验证结果见 [2026-09-02 验证记录](./android/qa/caiyun-device-2026-09-02.md)，高德两项 Key 的设备实网验收仍待完成。原型仅使用离线 fixture。

### 1.2 非目标

- 不申请后台定位，不做跨设备同步，不做 iOS/桌面客户端。
- 不提供导航、不持续监控路线。
- 不承诺绕过用户“强制停止”后的 Android 平台限制。
- 首版不自动选择或切换通勤方式。
- 不提供服务器端聚合、限流代理或任何形式的密钥托管服务。
- 不使用路线或天气自动修改本地闹钟；彩云 Provider 本阶段只支持连接测试、天气评估与手动预览。

## 2. 依赖复用结论

原则：优先使用平台 API、AndroidX、高德官方 API/SDK、彩云天气官方 API 及成熟基础库；业务规则、隐私边界、调度状态机和降级策略由本项目实现。默认不复制或分叉第三方源码。

| 能力 | 采用方案 | 项目自有实现边界 | 结论 |
|---|---|---|---|
| UI | Jetpack Compose + Material 3 | 页面、组件、主题、无障碍语义 | 采用 |
| 页面状态 | AndroidX ViewModel + StateFlow | 单向数据流、页面状态和事件约束 | 采用 |
| 导航 | Navigation Compose | 路由表和参数类型 | 采用 |
| 依赖注入 | Hilt | 作用域和 Provider 装配 | 采用 |
| 结构化数据 | Room 3 | 实体、DAO、迁移和事务 | 采用 |
| 偏好及 Direct Boot 快照 | Proto DataStore | schema、迁移和快照最小化 | 采用 |
| 非精确后台任务 | WorkManager | 后续 Provider 评估、重试截止和幂等 | 后续能力 |
| 本地闹钟 | `AlarmManager.setAlarmClock()` | 实例状态机、重调度、过期校验、停止和贪睡 | 采用平台 API |
| 通知 | AndroidX Core `NotificationCompat` | 渠道、动作、全屏降级 | 采用 |
| 响铃前台服务 | `systemExempted` foreground service | occurrence 校验、通知、停止和贪睡 | 采用平台能力 |
| 响铃音频 | 平台 `RingtoneManager` / `Ringtone` | 音源回退、循环、振动和停止 | 采用平台 API |
| HTTP | Retrofit + Kotlin Serialization converter + OkHttp | 后续 DTO、脱敏拦截器和错误分类 | 预留，不发 Provider 请求 |
| 地图/选点/前台定位 | 高德 Android SDK | Compose 适配层、首次授权和隐私闸门 | 已实现；待真实 Key 设备实网验收 |
| 路线、POI、输入提示 | 高德 Web 服务 API | 请求构造、缓存、错误码与降级 | 已实现；待真实 Key 设备实网验收 |
| 天气 | 彩云天气 v2.6 API | 后续鉴权、天气规则、缓存与降级 | 预留，不发 Provider 请求 |
| 年度工作日 | holiday-cn 年度 JSON，App 抓取缓存 | 拉取策略、schema 校验、合并与兜底 | 采用（数据源见 2.1 评估） |
| 凭证加密 | Android Keystore AES-GCM（密钥不可导出） | 密钥管理、密文格式、备份排除 | 采用平台能力 |
| 崩溃/ANR | 本地脱敏诊断；不接入第三方采集 SDK | 环形诊断记录、凭证脱敏 | 首版采用 |

技术依据：彩云链接已于 2026-09-01 核验；其余历史 Android/社区链接仍须按 `docs/facts-to-verify.md` 逐项核对。

- holiday-cn 数据格式、数据地址与注意事项：https://github.com/NateScarlet/holiday-cn/blob/master/README.md
- holiday-cn 2025 年数据（示例）：https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/2025.json
- holiday-cn 2026 年数据（示例）：https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/2026.json
- 彩云天气 v2.6 鉴权（App Key 路径段、签名请求头、HMAC 输入与编码）：https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html
- 彩云天气 v2.6 综合天气接口（坐标顺序、`hourlysteps`、响应结构）：https://docs.caiyunapp.com/weather-api/v2/v2.6/6-weather.html
- 彩云天气 v2.6 小时级接口（逐小时字段与 `hourlysteps` 范围）：https://docs.caiyunapp.com/weather-api/v2/v2.6/3-hourly.html
- 彩云天气 v2.6 错误模型：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/errors.html
- 彩云天气开放平台协议（数据来源标注）：https://platform.caiyunapp.com/user/user_agreement/
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
| Android Gradle Plugin | `9.3.1` |
| Gradle Wrapper | `9.6.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11`（KSP 2.3.0 起版本不再与 Kotlin 编译器绑定） |
| JDK | `21` |
| `compileSdk` | `37`（Android 17；Room 3.0.1 / androidx-hilt 1.4.0 / lifecycle 2.11.0 要求 compileSdk ≥ 37） |
| `targetSdk` | `36`（Android 16） |
| `minSdk` | `36`（Android 16 专属，待确认项见 14 章） |
| Compose BOM | `2026.06.00` |
| Navigation Compose | `2.9.8` |
| Room | `3.0.1`（`androidx.room3` 新坐标，Kotlin-first；`@TypeConverter/@TypeConverters` 已更名为 `@ColumnTypeConverter/@ColumnTypeConverters`） |
| DataStore | `1.2.1` |
| WorkManager | `2.11.2` |
| Hilt | `2.60.1`（AndroidX Hilt `1.4.0`） |
| Retrofit | `3.0.0`（OkHttp `5.4.0`，官方确认与 Retrofit 3.x 二进制兼容） |
| kotlinx-serialization-json | `1.11.0` |
| kotlinx-coroutines-test | `1.11.0` |
| 高德 Android 合包 | `com.amap.api:3dmap-location-search:11.2.100_loc11.2.100_sea9.8.1` |
| 凭证加密 | Android Keystore（平台能力，无第三方库） |

- 移除旧基线的 Tink（`1.23.0`）：日历签名职责删除。
- AGP 9.x 使用内置 Kotlin：Android 模块不得再应用 `org.jetbrains.kotlin.android`；Compose 模块应用 `org.jetbrains.kotlin.plugin.compose`；Room 与 Hilt 代码生成统一使用 KSP。

### 3.2 第三方 API 固定要点

| 项 | 固定值 |
|---|---|
| 高德 REST 根 | `https://restapi.amap.com/`（路径规划 v5、POI 搜索 v5、输入提示 v3） |
| 彩云 REST 根 | `https://api.caiyunapp.com/`（v2.6） |
| 彩云鉴权 | App Key 位于 `/v2.6/{app_key}/...` 路径段；每个 GET 请求使用 16–40 字符、不可复用的 `x-cy-nonce`，以及 `x-cy-timestamp`、`x-cy-signature`。签名为排序并 URL 编码的 query 与含 App Key 的 path 组成的 HMAC-SHA256、URL-safe Base64：https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html |
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
DayClassification(
  date: LocalDate,
  baseDayKind: WORKDAY | WEEKEND_REST | STATUTORY_REST,
  source: HOLIDAY_CN | WEEKDAY_FALLBACK,
  effectiveStatus: WORKDAY | HOLIDAY
)
SingleDayOverride(
  planId: UUID,
  date: LocalDate,
  baseDayKind: WORKDAY | WEEKEND_REST | STATUTORY_REST,
  source: HOLIDAY_CN | WEEKDAY_FALLBACK,
  status: WORKDAY | HOLIDAY,
  defaultWakeLocalTime?: LocalTime,
  arrivalLocalTime?: LocalTime,
  preparationMinutes?: Int,
  weatherProfile?: WeatherBufferProfile
)
```

日期状态优先级固定为：

1. 用户单日覆盖。
2. holiday-cn 当年（必要时合并下一年）数据。
3. 周一至周五为工作日、周末为休息日的本地兜底。

判定规则：

- `isOffDay=false` 的日期 → 工作日（调休上班）；`isOffDay=true` 的日期 → 法定休息日。
- 未出现在 `days` 中的日期 → 默认周规则：周一至周五为工作日，周六日为普通周末。
- 目标日期为 12 月时，同时并入下一年缓存中 12 月条目后再判定（holiday-cn 注意事项）。
- 当年缓存缺失、过期校验失败或解析失败时自动使用默认周规则，并在首页与日历页标注原因；不要求用户额外确认，也不阻塞响铃。
- 日期分类用于选择天气缓冲：法定休息日优先于普通周末；调休上班日与普通工作日使用工作日缓冲。单日加班把指定休息日覆盖为工作日，但保留其原始休息日分类以选择周末或法定休息日缓冲。
- `DayClassification` 始终保留日历得到的原始类别和来源；`effectiveStatus` 再应用单日覆盖。现有 `WorkdayOverride` 是旧模型名称，v3 目标模型以 `SingleDayOverride` 替代，不声明现有 Android 存储已迁移。

### 5.5 天气缓冲配置

```text
WeatherBufferProfile(
  severity1Minutes: Int,
  severity2Minutes: Int,
  severity3Minutes: Int
)
WeatherBufferProfiles(
  workday: WeatherBufferProfile = 10/20/30,
  weekend: WeatherBufferProfile = 5/10/20,
  statutoryRest: WeatherBufferProfile = 10/15/25
)
```

- 三套配置均按天气严重等级 1/2/3 保存，每档范围为 0–60 分钟。
- 三套配置互斥，不累加。默认工作日与原有天气等级缓冲一致；普通周末、法定休息日分别采用上述默认值。
- 单日加班只对所选日期生效，不修改日常计划或每周安排；撤销时删除该日期覆盖。
- `SingleDayOverride` 的时间、准备时长和 `weatherProfile` 均为可选值；为空时继承 `AlarmPlan` 和 `baseDayKind` 对应的三档 profile。非空 `weatherProfile` 仅替换该日期三档缓冲，不改动任一全局 profile，也不与其叠加。

### 5.6 `ProviderCredential`

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

### 5.6.1 `WeatherProvider`

```text
WeatherProvider.evaluate(WeatherRequest): WeatherEvaluation
```

- `WeatherRequest` 由家庭地、工作地、目标时间窗口、天气缓冲 profile、规则版本和请求时间组成；不携带 DTO 或凭证。
- `WeatherEvaluation` 保存最高严重等级、缓冲分钟、规则版本、两地参与窗口、最旧 `providerReportTime`、数据来源、未知 `skycon` 代码和 `fallbackReason`；`isUsableForScheduling=false` 的结果不得被后续统一评估采用。
- 网络与 Provider 失败抛 `ProviderError`；超出小时预报范围则返回 `WEATHER_HORIZON_UNAVAILABLE` 的 0 分钟评估，不伪造 Provider 数据。

### 5.7 `AlarmDecision`

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
evaluationOutcome: SUCCESS | FAILED    // 未来 Provider 评估字段，当前不参与本地闹钟调度
fallbackReason: FallbackReason?
insufficientAdvance: Boolean
generatedAt: Instant
expiresAt: Instant
```

### 5.8 `AlarmOccurrence`（本地闹钟实例）

```text
occurrenceId: UUID
planId: UUID
planRevision: Long
targetDate: LocalDate
scheduledWakeAt: Instant
state: REGISTERING|SCHEDULED|FAILED|FIRING|SNOOZED|DISMISSED|MISSED|CANCELLED
kind: REGULAR|SNOOZE
parentOccurrenceId?: UUID
updatedAt: Instant
```

- 每个 occurrence 是本 App 注册的本地闹钟实例。每个启用计划只保留下一次有效的常规实例；每次重复触发后再计算并注册下一次。
- `SNOOZE` 是独立子实例，记录 `parentOccurrenceId`，不改变该计划后续常规实例。
- 设备保护存储保存 `NextAlarmSnapshot` 列表（不含地点），用于重启后恢复未触发的本地实例：

```text
occurrenceId, planId, planRevision, triggerAt,
soundUri, vibrationPattern, snoozeMinutes
```

## 6. 功能规格

### FR-001 创建和启用计划

1. 用户输入名称、日期、时间、日期规则、铃声、振动和贪睡；地点、出行方式和天气配置均为可选本地设置，不是创建闹钟前提。
2. `Once(date)`、`Weekly(days)` 和 `Workdays` 是唯一可启用的日期规则；每周至少一天；单次日期时间必须晚于保存时刻。
3. 保存由 `LocalAlarmCoordinator` 执行：写入计划与 revision、计算下一次实例、写入快照、调用调度器。注册成功后状态为 `SCHEDULED`；权限或注册失败必须保留 `NEEDS_PERMISSION`／`FAILED` 和真实原因。
4. 启停、删除、修改日期覆盖和修改计划均重新计算本计划下一次实例。新实例注册成功后才取消旧实例，失败时保留旧的有效实例。

验收：断网、未配置 Provider 或 Provider 连接失败均不影响本地闹钟的保存、注册和响铃。

### FR-002 工作日计算

- 按计划 `zoneId` 计算日期，禁止以系统默认时区替代。
- 按 5.4 节优先级逐日查找下一个工作日。
- 用户覆盖按 `planId + date` 保存为沿用计划、本日停用、本日启用或替代时间；修改后立即重算该计划下一次实例。
- holiday-cn 已由本地 `WorkdayCalendarRepository` 接入：先读取已校验缓存，按年度数据覆盖周规则；刷新失败保留有效缓存，缺失时按周一至周五自动兜底并在 UI 展示来源或失败原因。
- 打开日历相关页面可刷新年度数据；调度计算只读本地缓存，不等待网络。

### FR-003 未来通勤提前计算（未启用）

统一使用目标日期的计划时区：

```text
calculatedWake = estimatedDeparture - preparation - weatherBuffer
earliestAllowed = defaultWake - maxAdvance
clampedWake = max(earliestAllowed, calculatedWake)
recommendedWake = min(defaultWake, clampedWake)
finalWake = min(existingTempWake?, recommendedWake)
```

该算法仅定义后续路线／天气接入后的建议时间，不参与当前本地闹钟注册、替换、取消或验收。接入时必须以 `ZonedDateTime`／`Instant` 处理跨日、时区和夏令时，并由单独的用户确认策略决定是否调整已注册闹钟。

### FR-004 天气缓冲

取家庭地和工作地中较严重的一端，再按目标日期的缓冲配置取值：

| 等级 | 示例 | 默认缓冲 |
|---|---|---:|
| 0 | 晴、多云、阴 | 0 分钟 |
| 1 | 小雨、小雪 | 10 分钟 |
| 2 | 中到大雨雪、雾、沙尘、强风 | 20 分钟 |
| 3 | 暴雨、暴雪；冻雨仅在预警数据明确返回时 | 30 分钟 |

- 用户可分别配置工作日、普通周末和法定休息日的等级 1–3 缓冲（均为 0–60 分钟）；配置互斥不叠加。默认值依次为工作日 `10/20/30`、普通周末 `5/10/20`、法定休息日 `10/15/25`。
- `isOffDay=false` 的调休上班日使用工作日缓冲；法定休息日与周末重合时使用法定休息日缓冲；日历缓存缺失时按周几选择工作日或周末缓冲。
- 单日加班可保存本日专用三档缓冲；该值替换本日应选 profile，不修改任何全局 profile，不与其叠加。
- 天气提供方固定为彩云天气 v2.6，App 内直连，凭证来自本机凭证存储。
- 天气 Provider 只产生 `WeatherEvaluation` 与建议缓冲；不得创建、取消或修改 `AlarmOccurrence`、本地响铃或当前本地闹钟状态。统一评估调度属于后续 P9 能力。
- 分别查询家庭地和工作地，从 `[defaultWake-maxAdvance, arrivalTime]` 小时窗口内取最高严重等级，再取两地中较严重的一端。
- 使用 `/weather` 综合接口，按当前时间到 `arrivalTime` 动态计算 `hourlysteps`，并固定 `dailysteps=1`、`unit=metric:v2`、`lang=zh_CN`；`hourlysteps` 的可请求范围为 1–360，实际返回可按套餐截断：https://docs.caiyunapp.com/weather-api/v2/v2.6/6-weather.html
- 彩云请求为 `/v2.6/{app_key}/{longitude},{latitude}/weather`；响应 `location` 数组为 `[latitude, longitude]`；DTO 必须用命名字段转换，禁止传播裸数组：https://docs.caiyunapp.com/weather-api/v2/v2.6/6-weather.html
- 规则输入至少包括 `skycon`、逐小时降水概率/强度、风速和能见度；映射以 `skycon` 枚举为主，不解析自然语言描述。
- `CLEAR_*`、`PARTLY_CLOUDY_*`、`CLOUDY` 为等级 0；`LIGHT_RAIN`、`LIGHT_SNOW`、`LIGHT_HAZE` 为等级 1；`MODERATE_RAIN`、`MODERATE_SNOW`、`MODERATE_HAZE`、`HEAVY_RAIN`、`HEAVY_SNOW`、`HEAVY_HAZE`、`FOG`、`DUST`、`SAND`、`WIND` 为等级 2；`STORM_RAIN`、`STORM_SNOW` 为等级 3。未知代码不默认视为晴天，返回 `WEATHER_UNKNOWN_CODE` 并使用 0 分钟缓冲。彩云枚举定义见：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/skycon.html
- 严重等级映射带 `weatherRuleVersion`，每个彩云枚举必须有契约测试。
- 保存响应顶层 `server_time` 为 `weatherProviderReportTime`，保存参与决策的小时数据时间范围，不保存完整响应。
- 彩云预警仅在 `alert=true` 且凭证具备预警权限时返回；核心计算不得依赖它，获得权限后也只能作为等级上调信号：https://docs.caiyunapp.com/weather-api/v2/v2.6/5-alert.html
- 彩云 v2.6 官方 `skycon` 表未提供独立冻雨代码，信息不足，无法仅凭常规小时数据可靠识别冻雨；不得自行用温度和降水组合推断。只有增值预警返回明确冰冻类预警时才能上调为等级 3。
- 目标工作日超出 360 小时或 Provider 无对应小时数据时，返回 `WEATHER_HORIZON_UNAVAILABLE` 和 0 分钟天气缓冲；`server_time` 超出 15 分钟新鲜度窗口时返回陈旧响应错误。两类结果均不可用于调度，后续评估成功后只能提前。
- 彩云 v2.6 使用 App Key + App Secret 的 HMAC-SHA256 鉴权；App Secret 只存在于 Keystore 加密的本地凭证存储，禁止写入源码、构建产物或日志。
- 家庭地与工作地分别使用进程内缓存，成功结果按坐标和请求参数缓存 15 分钟；缓存不落盘、进程终止即失效，失败响应不得写入缓存。
- 彩云文档只明确“彩云天气 App 使用 GCJ-02”，未明确一般 v2.6 天气查询接口接受的坐标基准；实现必须保留坐标基准门禁（见 14 章），未关闭前不得进入生产发布：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/q.html

### FR-005 高德路线计算契约

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

### FR-006 未来 Provider 评估（未启用）

- 当前版本不安排夜间评估，不创建 `AlarmDecision`，也不因 Provider 状态修改本地闹钟。
- 使用唯一 `OneTimeWorkRequest` 计算下一次本地 19:00，并增加 0–15 分钟抖动；Worker 完成后安排下一天任务，不使用长时间常驻服务。
- 网络约束为 `CONNECTED`；失败后分别在 15、30、60 分钟重试；本地 23:30 后停止主动重试。
- Worker 输入只含计划 ID；执行时读取最新 `revision`；每个计划使用幂等键 `planId:revision:targetDate`。
- 评估流水线：工作日判定 → 路线估算 → 天气缓冲 → 计算 `recommendedWake`。
- 将来 Provider 评估失败不得伪造路线、天气或提前量，也不得替换当前已注册的本地实例。该规则不改变 FR-001 的本地闹钟注册行为。
- WorkManager 不承担精确响铃职责；精确响铃始终由 `AlarmManager` 与 `AlarmRingingService` 承担。

### FR-007 本地闹钟注册

- 使用 `AlarmManager.setAlarmClock()` 注册下一次有效本地实例。
- 每个 occurrence 使用显式、不可变 `PendingIntent`；身份不得只依赖 extras，使用唯一 request code，并把 occurrence ID 放入唯一 data URI。
- 注册前写入 occurrence 和 Direct Boot 快照；注册成功后更新状态。
- 重新计算时先注册新 PendingIntent，确认无异常后取消旧 PendingIntent，再提交新状态；异常时保留旧闹钟。
- Receiver 收到 Intent 后必须按 occurrence ID、计划 revision、状态和触发时间窗口重新校验。
- 取消语义：用户关闭／删除计划、修改计划或日期覆盖、完成一次性实例、停止／贪睡的实例转换，以及恢复时判定为过期，均可取消相应实例。

### FR-008 响铃服务

- `AlarmReceiver` 只完成校验和启动 `AlarmRingingService`，不得进行网络请求。
- `AlarmRingingService` 为前台服务，声明 `foregroundServiceType="systemExempted"`；Manifest 声明 `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`。
- 使用 `RingtoneManager.TYPE_ALARM` 解析声音；自定义 URI 失效时依次回退到系统 alarm、notification，再到应用内置声音。
- 使用 `AudioAttributes.USAGE_ALARM`，循环播放并按计划振动。
- 通知提供停止和贪睡；操作 Intent 显式、不可变且带 occurrence 身份；停止或贪睡必须幂等。
- 贪睡创建新的 `SNOOZE` 子实例，并仍使用 `setAlarmClock()`。
- 响铃内容是计划名称和本地闹钟时间；不依赖系统时钟 App、路线或天气服务。

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

- 应用核心功能属于本地闹钟；精确闹钟权限方案、应用商店声明和设备差异按当前 Manifest 与目标发布渠道复核。
- Android 13+ 请求通知运行时权限；全屏 Intent 只用于正在响铃的 occurrence。
- 能力诊断约束本 App 的本地闹钟：精确闹钟能力缺失时显示 `NEEDS_PERMISSION`，通知或全屏提醒缺失时展示实际回退状态；不得显示已注册。
- 每次回到前台和启用计划前重新诊断，不能只依赖首次启动结果。

### FR-010 重启、时间变化和强制停止

监听：`LOCKED_BOOT_COMPLETED`、`BOOT_COMPLETED`、`TIME_CHANGED`、`TIMEZONE_CHANGED`、`LOCALE_CHANGED`、`MY_PACKAGE_REPLACED`、`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`。

- Direct Boot receiver 标记 `directBootAware=true`，只访问设备保护存储；`LOCKED_BOOT_COMPLETED` 后从快照恢复未触发的本地实例，不启动响铃前台服务。
- 用户解锁后读取 Room，校验 revision 并重算下一次本地实例。
- 系统时间被调到触发点之后：10 分钟宽限期内立即响铃；超过 10 分钟记录 `MISSED` 并取消该实例；重复计划随后安排下一次。
- 时区变化后以计划 `zoneId` 重算。
- 强制停止是平台不可自动恢复边界：本 App 待处理实例会失效；用户下次主动打开应用后重建有效实例，UI 必须说明该限制。
- 应用覆盖安装（`MY_PACKAGE_REPLACED`）不丢失 Room 数据、凭证密文与日历缓存；凭证密文格式升级由 `core/security` 的密文版本字段驱动迁移。

### FR-011 地点与地图

- 全局通勤保存起点、终点和五种出行方式；单个计划可选择继承全局通勤或保存自身覆盖。
- 高德能力在同意授权后初始化：输入提示与 POI 搜索使用 Web Service Key；地图选点与单次定位使用 Android SDK Key。定位只在点击“使用当前位置”时请求前台权限，禁止后台持续定位。
- 路线支持驾车、公交、步行、骑行和电动车；每次最多显示三条候选路线并显示当前路况。候选卡和地图折线都可选择路线，二者更新同一个 `selectedRouteId`，并同步候选卡选中态与地图高亮折线。
- Android 模拟器使用 `goldfish`／`ranchu` 等虚拟图形栈时不得创建高德原生地图容器；页面显示渲染不可用状态，地点搜索与路线结果继续可用。真机仍使用 `TextureMapView` 并执行完整生命周期。容器规则：https://lbs.amap.com/api/maps-sdk-for-android/guide/create-map/show-map ；模拟器图形配置：https://developer.android.com/studio/run/emulator-acceleration
- 原型必须展示成功、加载、无 Key、定位拒绝和服务错误 fixture，不得发送请求、使用真实 Key 或输出坐标。

### FR-012 凭证配置与连接测试

凭证配置页分两个区块：

1. **高德**：
   - Web 服务 Key（必填，用于路线、POI、输入提示）。
   - Android SDK Key（可选，用于地图选点与定位；若未配置，FR-011 相关功能隐藏）。
   - 原型“验证 fixture”显示离线状态且不发送请求；Android 连接测试使用固定用例且不记录输入值。
2. **彩云天气**：
   - App Key（必填）与 App Secret（必填，HMAC 签名用）。
   - 连接测试优先使用已配置的家庭地，缺失时使用工作地；两者均无有效坐标时提示先配置地点，不发送请求。
   - 连接测试使用 `/v2.6/{app_key}/{longitude},{latitude}/weather`，每次生成新的 nonce；测试期间明文仅在内存中存在，不得写入或记录 URL 路径中的 App Key：https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html
3. 通用规则：
   - 新的彩云候选凭证只在连接测试成功后保存；候选测试失败不得覆盖已有凭证或其通过状态。已保存凭证可重新测试，失败时标记不可用于天气预览。
   - Provider 状态不影响本地闹钟创建、注册或响铃；本阶段不得生成或修改提前闹钟。
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

官方路径规划 v5（含电动车与 `alternative_route`）说明：https://lbs.amap.com/api/webservice/guide/api/newroute

官方输入提示路径说明：https://lbs.amap.com/api/cooperation/jkd

| 用途 | 接口 | 关键参数 |
|---|---|---|
| 驾车/步行/骑行/公交路径 | v5 `direction/{driving,walking,cycling,transit}` | `origin,destination,waypoints,strategy,date,time`（公交） |
| 电动车路径 | v5 `direction/electrobike` | `origin,destination,alternative_route=1..3` |
| POI 搜索 | v5 `place/text` | `keywords,region,citycode,offset,page` |
| 输入提示 | `/v3/assistant/inputtips` | `keywords,city,location` |
| 连接测试 | v3 输入提示或 v3 地理编码 | 固定测试用例，不记录输入值 |

- 全部请求携带 `key`（来自凭证存储）；响应顶层 `status`/`info`/`infocode` 三字段错误模型。
- 已知错误码归类：`10001` key 无效；`10003/10044` 日/账号配额超限；`10019/10020/10021` QPS 超限；`20800–20803` 路线不可用；其余按 `info` 归类为失败。

### 7.2 彩云天气 v2.6 API（App 内直连）

| 用途 | 接口 | 关键参数 |
|---|---|---|
| 天气实况+小时预报 | `/v2.6/{app_key}/{lng},{lat}/weather` | `dailysteps=1,hourlysteps,unit=metric:v2,lang=zh_CN` |
| 连接测试 | 同上（家庭地优先、工作地兜底；无坐标不请求） | 同上 |

- 鉴权：App Key 置于路径；请求头为 `x-cy-nonce`、`x-cy-timestamp`、`x-cy-signature`。对排序且 URL 编码后的 query 生成 `GET:{path}:{query}:{app_key}:{nonce}:{timestamp}` 的 HMAC-SHA256，结果采用 URL-safe Base64；`path` 包含 App Key。App Secret 只存在于 Keystore 加密凭证：https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html
- 响应至少解析并校验 `status`、`api_version`、`api_status`、`unit`、`timezone`、`tzshift`、`location`、`server_time`、`result.hourly`；`hourly` 中的 `precipitation`、`wind`、`visibility` 与 `skycon` 按各自 `datetime` 对齐。`precipitation.probability` 的连续值仅前两小时可用：https://docs.caiyunapp.com/weather-api/v2/v2.6/3-hourly.html
- 套餐、可访问 API 和 QPS 以当前账号后台为准；不得将固定免费额度写入运行时判断或发布条件：https://docs.caiyunapp.com/weather-api/billing.html
- 天气展示区域必须显著标注“数据来自彩云天气”；开放平台协议要求在显著位置标注彩云 LOGO 或该数据来源文字：https://platform.caiyunapp.com/user/user_agreement/

### 7.3 错误模型（App 内部）

```text
ProviderError(
  category: CONSENT_REQUIRED | MISSING_KEY | INVALID_REQUEST | INVALID_KEY |
            QUOTA_EXCEEDED | RATE_LIMITED | ROUTE_NOT_FOUND | NETWORK |
            TIMEOUT | MALFORMED_RESPONSE | PROVIDER_FAILURE,
  providerCode: String?,
  retryAfterSeconds: Long?,
  retryable: Boolean
)
```

- `INVALID_KEY` 视为不可重试。HTTP 429 可能表示额度耗尽或 QPS 限流，必须读取 `Retry-After` 并作为 `RATE_LIMITED` 处理，不能仅凭状态码细分为 `QUOTA_EXCEEDED`。HTTP 400 含签名/凭证/版本错误，401 为无权限，403 为禁用或 IP 白名单，422 为参数错误，500 为服务端错误：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/errors.html
- 仅 HTTP 200 可视为成功；网关可能返回 HTML/TXT 错误体，`HTTP != 200`、超时或解析失败均按错误类别结束本轮 Provider 评估，且不得伪造或修改当前本地闹钟状态：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/errors.html

## 8. UI 规格

### 8.0 设计与原型参照

- Figma 旧 21 个页面是视觉素材基线；当前 Android 实现范围为 12 个主页面及其本地路线／日历功能整合，不得据此声称 21 个原生页面已实现。
- 页面级需求和当前状态以 `docs/design-handoff.md` 的 2026-08-31 本地闹钟状态组及本地 `prototype/` 为准。
- 开发和界面验收必须参照本地 `prototype/`；未经用户明确要求不得调整原型或另行设计。修改获得确认后，同步更新设计、规格和原型。

### 8.1 首次启动与隐私引导（页面 18）

1. 说明本地保存和后续 Provider 的数据边界。
2. 引导用户创建本地闹钟；凭据、地点和路线设置均可跳过。
3. 诊断页读取通知、精确闹钟、全屏提醒和闹钟音量，缺失时显示回退和设置入口。

### 8.2 首页（页面 01）

- 展示下一次有效本地闹钟及 `NEEDS_PERMISSION`、`SCHEDULED`、`FAILED`、`COMPLETED` 等真实状态。
- 首次安装显示本地闹钟空态和添加入口；天气区域显示未接入说明，路线区域按高德授权、Key 与 fixture 状态显示。
- 权限、调度异常和恢复结果以本机诊断为准；不展示 Provider 数据时间或提前计算。

### 8.3 计划与规则（页面 05–07）

- 名称、启用状态、日期、时间、单次／每周／工作日规则、铃声、振动和贪睡。
- 保存合法草稿会注册下一次本地实例；取消编辑不写入。保存后界面显示实际注册状态或失败原因。
- 全局通勤和计划覆盖是本地设置。

### 8.4 路线与地点选择（页面 03–04 + 地点选择子状态）

- 当前支持全局通勤、计划覆盖和驾车、公交、步行、骑行、电动车的选择；保存后不自动切换方式。
- 同意授权并配置相应运行时 Key 后，地点页显示 POI 搜索／输入提示，路线编辑页显示地图选点和单次当前位置。无 Key、拒绝、加载和错误状态必须明确展示；原型以确定性 fixture 验收，不发网络请求。

### 8.5 工作日日历与单日加班（页面 15–17、20）

- 月历显示 holiday-cn 缓存、周规则兜底和每计划每日期覆盖；刷新采用本地缓存优先和多源回退。
- 选中日期后可保存沿用计划、本日停用、本日启用或替代本日时间；保存后立即重算该计划下一次本地实例。
- 日期覆盖只影响指定计划和日期；撤销覆盖恢复日历判定。

### 8.6 凭证配置（页面 19）

- 高德区块提供 Web Service Key、Android SDK Key和 fixture 状态；原型仅会话保存，Android 已实现加密持久化。彩云仍为未接入区块；首次引导和设置页均可进入。
- 页面 `FLAG_SECURE`。
- 高德原型验证固定不发送请求；“清空凭据”先进入确认覆盖层，取消不改变输入，确认只清除凭据且不得修改闹钟、日期覆盖、地点或出行方式。

### 8.7 闹钟记录与异常（页面 13–14）

- 首次安装为空态；记录本地注册、触发、停止、贪睡、错过、取消和异常事件，支持日期及结果筛选。
- 不显示或导出完整坐标、凭证或铃声 URI。

### 8.8 通知摘要与可靠性诊断（页面 08、21）

- 通知、精确闹钟、全屏 Intent 状态；通知渠道状态；闹钟音量与铃声可读性。
- 显示通知、精确闹钟、全屏提醒、闹钟音量和铃声可读性，以及最近本地注册、响铃、停止、贪睡、恢复和日历刷新结果。
- 凭证配置状态只显示已配置／未配置；服务未接入时不显示测试成功。
- 强制停止不可自动恢复本 App 本地闹钟的限制必须明示。
- “重新检查”只刷新能力、配置状态与本机诊断展示；不创建或改变闹钟。异常页可进入诊断，诊断页返回设置。
- 通知摘要页只展示实际能力与回退说明；锁屏、胶囊等系统概念页面不定义额外厂商接口、常驻提醒或上传行为。

## 9. 隐私与安全

- 本地优先：所有数据（计划、决策、日历缓存）只存本机，无任何网络上传路径。
- 凭证：Android Keystore 不可导出密钥加密，密文存应用私有目录，备份排除（FR-013）；明文只存在于输入框与测试执行线程。
- 防泄露四原则（日志脱敏、截图禁止、崩溃信息隔离、导出排除）见 FR-013。
- 日志：本地环形诊断记录最多 200 条，字段限定为事件类型/结果码/版本/哈希 ID/耗时/时间戳；禁止写入地址、POI、坐标、凭证、铃声 URI 或请求/响应 body。
- 高德 SDK 隐私合规：同意前不初始化；隐私政策列出高德 SDK 与彩云数据来源。
- Provider 接入前不展示天气数据来源；后续实际接入时才显示对应数据来源。
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

- 日期规则：跨日、月底、闰日、时区、夏令时、单次过去时间、每周空集合、工作日和每计划每日期覆盖。
- 实例与调度：唯一 PendingIntent 身份、注册成功后替换、注册失败保留旧实例、多个计划隔离、重复触发幂等、停止幂等、贪睡子实例和单次完成。
- 恢复：重启、解锁、时间／时区变化、覆盖安装、10 分钟迟到窗口和 `MISSED`。
- 日历：holiday-cn 缓存校验、刷新源回退、失效缓存、周规则兜底和覆盖变化后重算。
- 凭证：Keystore 加解密、密文损坏、清除、备份排除和日志脱敏；高德 fixture 与天气未接入状态均不发请求。

### 11.2 仪器和系统测试

设备矩阵：API 36（当前 minSdk 单版本；若下探见 14 章）。

- 到点响铃、锁屏通知、停止、贪睡、多个同分钟计划、进程回收、重启恢复和无网响铃。
- 通知、精确闹钟、全屏提醒和音量状态变化后从设置返回重新检查。
- 日期时间校验、空态、启停、删除、日历覆盖、天气未接入留白、高德 fixture 状态与凭据验证不发请求。
- 真机与模拟器结果分开记录；未执行的场景不得标记通过。

### 11.3 契约与回归

- Room 迁移、计划修改回滚和诊断事件回归；日志与导出隐私扫描。
- Provider 接入前的回归断言：不产生网络请求，不显示模拟路线、天气、提前量或连接成功。

## 12. 验收标准

- 保存或启用合法计划后，本 App 为下一次有效本地实例注册闹钟；状态与系统调度结果一致。
- 单次、每周和工作日规则在预期时刻触发；停止、贪睡、修改、删除和重复广播不会误取消其他计划实例。
- 注册失败、权限撤销、恢复失败与迟到窗口均显示真实状态和原因，不显示已注册。
- 重启、解锁、时间／时区变化和覆盖安装后恢复或重算有效实例；强制停止是明示的不可自动恢复边界。
- 日历刷新失败不阻塞工作日计算，保留有效缓存或按周规则兜底；日期覆盖只影响指定计划与日期。
- 高德 fixture 与天气未接入状态均无网络请求；fixture 不得被标注为 Android 实网成功。
- 凭证密文不进入备份、日志、截图、崩溃或导出；应用包不含硬编码密钥。

## 13. 里程碑

以下为工程估算，不是外部标准；按 2 名 Android 开发，目标 8 周。

1. 第 1 周：仓库清理、构建基线、version catalog 锁定、验证脚本。
2. 第 2 周：领域模型、工作日引擎（holiday-cn 语义）、闹钟纯计算、occurrence 状态机。
3. 第 3 周：数据层（Room/DataStore/快照）与本地闹钟（注册、响铃、贪睡、Direct Boot 和恢复）。
4. 第 4 周：凭证安全（Keystore、密文存储、备份排除、脱敏）与凭证配置页（连接测试）。
5. 第 5 周：日历抓取与刷新策略（FR-015）、日历覆盖页。
6. 第 6 周：高德 Provider（路线/POI/输入提示）、彩云 Provider（HMAC、天气规则）、地点与地图。
7. 第 7 周：统一评估与 WorkManager 夜间任务、首页/决策历史/诊断页。
8. 第 8 周：系统矩阵、隐私合规、发布门禁。

## 14. 实施前置项和未决外部条件

以下事项不阻塞本规格落地，但阻塞对应功能或公开发布：

- **彩云输入坐标基准（未确认项）**：彩云文档只明确 App 使用 GCJ-02，未明确一般 v2.6 天气查询接口接受的坐标基准。只有彩云对一般 v2.6 天气查询接口的书面确认可以关闭门禁；控制点测试只能补充风险证据，不能证明坐标基准。未关闭前不得进入生产发布：https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/q.html
- **minSdk 收窄（待确认）**：当前代码基线为 minSdk 36（Android 16 专属），旧规格为 API 29–36；是否支持更早版本由发布目标决定，若需下探需补充兼容矩阵与回归。
- **holiday-cn 数据为社区维护（确认的取舍）**：数据真值在国务院公告，holiday-cn 负责抓取整理（MIT 许可）；本项目以 `papers` 保留官方来源并接受其维护节奏（通常 10 月底/11 月发布次年安排，故 10 月 1 日后预拉次年允许“文件暂未发布”的失败并回退）。
- **高德 Web Key 直连客户端（已知风险）**：Web 服务 Key 只能绑定 IP 白名单，客户端直连（移动网络 IP 不固定）无法启用；风险由 Keystore 加密 + 备份排除 + 用户自持 Key 承担，文档与 UI 需明确提示。
- **高德 Android SDK Key 与正式签名**：SDK Key 绑定包名+签名；正式签名证书未配置前 SDK Key 只能用于 debug 签名。
- **彩云套餐**：正式 App Key/App Secret、套餐配额、商用授权与数据来源标注；未获预警权限时核心天气等级必须完整工作。
- **Google Play 声明材料**：精确闹钟、全屏 Intent、隐私政策、数据安全表；完成前不允许公开发布。

## 15. 发布门禁

- 依赖版本全部锁定且无动态版本；无 `backend`、`contract`、`calendar-data`、`infra` 等遗留目录。
- Room 和 DataStore 迁移测试通过；API 36 核心响铃矩阵通过。
- 高德错误／无 Key、成功、加载和拒绝状态均须完成 UI 与设备实网验收；天气能力另行验收。
- Direct Boot、系统时间/时区变化、升级恢复通过。
- 凭证安全四项验收（日志/截图/崩溃/导出无凭证）与备份排除测试通过。
- 高德隐私初始化顺序通过网络抓包验证；彩云签名向量通过，且已取得一般 v2.6 天气查询接口坐标基准的官方书面确认。
- 日历刷新算法（10 月 1 日分界、去年清理、源切换、兜底）单元与仪器测试通过。
- Google Play 相关声明和政策材料完成；发布包不包含任何密钥、调试开关或测试端点。
