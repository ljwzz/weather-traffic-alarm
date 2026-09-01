# 待核对事实清单（本地闹钟优先）

用途：把 SPEC.md / README.md 中依赖平台行为与第三方服务的断言逐条列出，供学习与验证。核对完一条，把状态改为 `✅ 已核实` 并附上证据链接或实验记录。

## 当前本地闹钟基线

- 本 App 直接使用 `AlarmManager.setAlarmClock()` 为有效本地实例注册下一次响铃；官方 API 将其定义为代表闹钟的调度方法，并说明系统可用即将触发的闹钟信息提醒用户。https://developer.android.com/reference/android/app/AlarmManager
- 精确闹钟能力应在注册前检查；Android 官方示例在系统设置返回的 `onResume()` 中再次检查并根据结果降级。https://developer.android.com/training/permissions/requesting-special
- Android Keystore 的密钥材料不进入应用进程；本地凭据的实现应把明文限制在输入和加密操作的短生命周期内。https://developer.android.com/privacy-and-security/keystore
- 以下历史“系统时钟引导／一次性提前闹钟”条目仅供后续通勤评估能力参考，不是当前本地闹钟验收条件。

状态标记：

- `✅ 已核实`：本轮会话已从官方/权威来源获取原文。
- `⚠️ 部分核实`：机制存在已确认，但具体页面或细节未获取。
- `❓ 待核实`：需要你查阅官方文档或向服务商确认。
- `🔬 需真机验证`：文档无法确定，必须在本项目目标设备上实测。

---

## A. 系统闹钟与 AlarmManager（新架构核心）

| # | 断言 | 状态 | 证据/核实方法 |
|---|---|---|---|
| A1 | 本 App 无法读取系统时钟 App 的闹钟列表：Android 无公开 API 枚举其他应用闹钟 | ⚠️ | `AlarmManager` 参考页仅提供 `getNextAlarmClock()`，无列表查询 API；结论为 API 能力推断 |
| A2 | `AlarmManager.getNextAlarmClock()` 自 API 21 起公开，返回全局下一个 alarm clock（时间与包名） | ✅ | https://developer.android.com/reference/kotlin/android/app/AlarmManager |
| A3 | `ACTION_NEXT_ALARM_CLOCK_CHANGED` 由系统独占发送，第三方应用只能注册 receiver 接收 | ✅ | 同上页 |
| A4 | 设备重启后所有 AlarmManager 闹钟被清除，需重新注册 | ✅ | 同上页 |
| A5 | 强制停止会清除本应用已注册的 PendingIntent 与闹钟，应用无法自启动恢复 | ✅ | 同上页 |
| A6 | `setAlarmClock()` 注册的闹钟在系统 UI（状态栏/锁屏）可见，属于闹钟类型 | ✅ | https://developer.android.com/develop/background-work/services/alarms |
| A7 | Android 13+ 可声明 `SCHEDULE_EXACT_ALARM` 或 `USE_EXACT_ALARM`；前者由用户在特殊访问设置中授予并可能撤销，后者自动授予但仅适用于以精确闹钟为核心的有限场景 | ✅ | https://developer.android.com/develop/background-work/services/alarms |
| A8 | 本 App 采用 `USE_EXACT_ALARM` 的前提是"核心功能是闹钟"（提前闹钟属于闹钟类型）——需要 Google Play 政策对该权限适用范围的书面确认 | ❓ | Play 政策中心"精确闹钟"声明页面（待查阅） |
| A9 | 系统时钟 App 设置的正常起床闹钟（`setAlarmClock` 类）会出现在 `getNextAlarmClock()` 返回值中 | 🔬 | 真机：在系统时钟 App 设闹钟，比对返回值 |
| A10 | 不同厂商系统时钟 App（Google Clock/小米/华为/OPPO/vivo 等）对 `getNextAlarmClock` 的一致性 | 🔬 | 目标设备矩阵实测 |
| A11 | 系统时钟 App 的闹钟在 Doze/省电模式下仍会响铃 | 🔬 | 真机实测（厂商差异大） |

## B. 后台任务与前台服务

| # | 断言 | 状态 | 证据/核实方法 |
|---|---|---|---|
| B1 | WorkManager 适合延迟性、可重试的后台任务；精确响铃必须用 AlarmManager | ✅ | https://developer.android.com/develop/background-work/background-tasks/persistent |
| B2 | WorkManager 任务持久化（跨应用重启与设备重启），但应用被强制停止后任务不执行，直到用户再次打开应用 | ⚠️ | 强制停止对 WorkManager 的具体行为见官方文档（URL 待补充：任务调度页 "persistent work" 相关章节） |
| B3 | Android 12+ 一般禁止从后台启动前台服务（FGS）；`systemExempted` 类型豁免后台启动限制，适合闹钟响铃 | ✅ | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start |
| B4 | FGS 需在 Manifest 声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | ⚠️ | 同上页（具体权限名待在该页核实） |
| B5 | Direct Boot：设备重启后首次解锁前，应用只能访问设备保护存储（device-protected storage），可注册 `directBootAware=true` receiver 接收 `LOCKED_BOOT_COMPLETED` | ✅ | https://developer.android.com/privacy-and-security/direct-boot |
| B6 | `ACTION_LOCKED_BOOT_COMPLETED` 触发时不能启动 FGS/访问普通存储 | ✅ | 同上页 |

## C. 权限、通知与全屏 Intent

| # | 断言 | 状态 | 证据/核实方法 |
|---|---|---|---|
| C1 | Android 13+ 通知权限 `POST_NOTIFICATIONS` 为运行时权限 | ⚠️ | 通知权限官方文档（URL 待补充）；已被 https://developer.android.com/develop/ui/compose/notifications/create-notification 提及 |
| C2 | 所有通知必须属于通知渠道（API 26+）；渠道重要性不可修改 | ✅ | https://developer.android.com/develop/ui/compose/notifications/channels |
| C3 | Android 14+：`USE_FULL_SCREEN_INTENT` 仅对通话/闹钟类应用默认授予；Google Play 对不符合类别的新安装应用撤销该权限 | ✅ | https://developer.android.com/about/versions/14/behavior-changes-14 ；https://source.android.com/docs/core/permissions/fsi-limits |
| C4 | 发布前需在 Play Console 完成 FSI/FGS 声明 | ✅ | https://support.google.com/googleplay/android-developer/answer/13392821 |
| C5 | `NotificationManager.canUseFullScreenIntent()`（Android 14+）可检测 FSI 可用性 | ✅ | https://developer.android.com/about/versions/14/behavior-changes-14 |
| C6 | Android 16 强制 targetSdk 36（2026-08-31 起 Play 要求），本项目 minSdk/targetSdk=36 合规 | ⚠️ | https://developer.android.com/about/versions/16/behavior-changes-16 ；Play 要求 deadline 见第三方解读（非官方页面，仅作参考） |
| C7 | Android 16 行为变化中与本项目相关的项（后台任务、JobScheduler 配额收紧等）需逐一核对 | ❓ | https://developer.android.com/about/versions/16/behavior-changes-all |

## D. 数据、加密与备份

| # | 断言 | 状态 | 证据/核实方法 |
|---|---|---|---|
| D1 | Android Keystore 中生成的密钥不可导出（key material 不进入应用进程） | ✅ | https://developer.android.com/privacy-and-security/keystore |
| D2 | `KeyGenParameterSpec` 生成 AES-GCM 密钥、`setKeyPurpose(ENCRYPT\|DECRYPT)` 是标准做法 | ⚠️ | 同 D1 页（API 细节在参考页） |
| D3 | API 31+ 用 `android:dataExtractionRules` 控制备份，API 30- 用 `fullBackupContent`；两者需同时声明 | ✅ | https://developer.android.com/identity/data/autobackup |
| D4 | 备份/恢复后凭证文件必须不存在且应用不崩溃（本项目验收项） | 🔬 | 真机备份/恢复实测 |
| D5 | Room 2.x 文档已标记 deprecated，官方新指南为 Room 3；本项目当前依赖基线为 Room 3.0.1 | ✅ | https://developer.android.com/training/data-storage/room （Room 3 指南 URL 在该页内；版本以本地 version catalog 为准） |
| D6 | Proto DataStore 可用作设备保护存储中的快照载体 | ⚠️ | https://developer.android.com/topic/libraries/architecture/datastore （结合 Direct Boot 用法待核实） |

## E. 第三方 API

| # | 断言 | 状态 | 证据/核实方法 |
|---|---|---|---|
| E1 | 高德 Web 服务：路径规划 v5（含电动车）和输入提示路径已核对 | ✅ | https://lbs.amap.com/api/webservice/guide/api/newroute ；https://lbs.amap.com/api/cooperation/jkd |
| E2 | 高德响应错误模型与配额码 | 🔬 | 待使用当前官方错误码页面与真实 Key 设备实网验收核对 |
| E3 | 高德 Web Key 只能绑定 IP 白名单（错误码 10005/10010 佐证）；客户端直连移动网络 IP 不固定 → 无法启用白名单 | ⚠️ | 机制已由错误码表证实；"移动网络不可行"为推断，SPEC 已列为已知风险 |
| E4 | 高德 Android SDK Key 的包名与签名配置 | 🔬 | 待用户提供 Android SDK Key 后进行设备实网验收 |
| E5 | 高德"未来路径规划"（驾车未来服务）接口存在、需开通、超范围时退化为当前路况 | ❓ | 方向 API 页中该能力说明（待你在该页确认具体章节） |
| E6 | 彩云 v2.6 文档结构：入口页 + start/auth/billing/ratelimit/version-guide + skycon 等表 | ✅ | https://docs.caiyunapp.com/weather-api/ |
| E7 | 彩云 v2.6 鉴权为 App Key + App Secret 的 HMAC-SHA256（`x-cy-token`/`x-cy-timestamp`/`x-cy-signature`） | ❓ | 待核对 https://docs.caiyunapp.com/weather-api/auth.html |
| E8 | 彩云 v2.6 `skycon` 枚举表无独立"冻雨"代码 | ❓ | 待核对 https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/skycon.html |
| E9 | 彩云天气查询接口接受的坐标基准（GCJ-02 vs WGS-84）未在文档明确 | ❓ | SPEC 14 章未确认项：需官方书面确认或控制点对照测试 |
| E10 | 彩云免费版调用量/套餐构成（10000 次等） | ⚠️ | 计费页已确认存在：https://docs.caiyunapp.com/weather-api/billing.html ；具体额度待该页核实 |
| E11 | 彩云条款要求展示"数据来自彩云天气" | ❓ | 条款/常见问题页（待核实 URL） |
| E12 | holiday-cn 数据格式：`{year, papers[], days[{name, date, isOffDay}]}`；年份按国务院文件标题，12 月可能受次年文件影响；"与周末连休"的周末不含；数据地址 raw.githubusercontent/jsDelivr；MIT 许可 | ✅ | https://github.com/NateScarlet/holiday-cn/blob/master/README.md |
| E13 | holiday-cn 发布节奏（通常 10 月底/11 月发布次年安排） | ❓ | 社区经验判断（SPEC 14 章已标注为取舍，非官方承诺） |

## F. 需真机/实验验证的厂商行为

| # | 事项 | 说明 |
|---|---|---|
| F1 | 厂商后台清理（小米神隐模式、华为/OPPO/vivo 智能省电等）对 WorkManager 与 AlarmManager 的干扰 | 直接决定"评估失败不注册"与"提前闹钟被清理"的实际行为 |
| F2 | 强制停止后系统时钟 App 闹钟是否照常响铃 | 新架构的核心假设，必须实测 |
| F3 | `getNextAlarmClock()` 返回内容与 ±10 分钟启发式核对的误报率 | 决定首页"已确认"提示的可靠性 |
| F4 | 从本 App 跳转系统时钟 App 闹钟设置页的可行性（厂商包名/Activity 不同） | 决定引导功能的实现方式 |
| F5 | 锁屏状态下 FSI 响铃表现（权限已授予时） | 影响响铃体验设计 |

## G. 项目内已确认的取舍（不是待核对事实，仅作背景）

- 系统闹钟核对采用 `getNextAlarmClock()` ±10 分钟启发式；UI 一律用"请确认"措辞，不依赖该核对做任何调度（SPEC 8.2、14 章）。
- "评估失败不注册/不修改提前闹钟"是设计不变量，不是平台事实（SPEC FR-006）。
- minSdk=36 单版本为当前决策；是否下探 API 29–35 是待确认的产品决策（SPEC 14 章），不是平台问题。
- 凭证用 Keystore 加密 + 备份排除是产品安全策略；Keystore 本身不可导出（D1）已核实。

## 核对方法建议

1. 每个 `❓` 项打开官方页面原文核对后改状态并附链接。
2. 每个 `🔬` 项在目标设备上写最小验证程序（设置/读取 `getNextAlarmClock`、注册/取消闹钟、强制停止、重启）记录结果。
3. 表格更新后同步回 SPEC 第 2 章"技术依据"与第 14 章未决外部条件。
