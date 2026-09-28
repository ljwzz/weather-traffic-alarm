# 设计交接与原型参照

> 冻结说明（2026-09-28，代码基线 `48ed778`）：本文件和对应 Figma 原页面 [`218:2464`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=218-2464)、本地 `prototype/` 原位保留，记录当时的设计与离线验收。后续产品契约见 [`SPEC.md`](../SPEC.md)，Android 交付以原生构建、测试及设备记录为准；节点差异和后续任务见 [`N001 交接记录`](plans/N001-handoff.md)。以下“当前”“必须同步”等措辞均按历史基线阅读。

## 交接基线

- Figma 文件：`wN04BlxRelbJyBVF35DyXE`。节点是当前设计追溯依据，不声明已保存命名版本历史。
- 当前设计页：[`知途 · 完整设计 · 2026-09-28`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=218-2464)。主流程、交互状态和扩展页面集中在此页；页面内的「组件 / 知途基础」保留导航、工作日日历和路线选项组件。天气缓冲以 `253:3146` 为当前页面节点。
- 当前设计稿决定页面级需求；`SPEC.md` 决定领域、安全、调度和验收约束。
- 本地原型位于 [`prototype/`](../prototype/)。开发和界面验收必须参照其页面结构、布局、组件、文案与交互；除非用户明确要求修改，不得自行改动原型或另行设计。
- 原型路由以 `prototype/app.js` 的 `ROUTES` 为准：主页是 `home`，地点选择是 `place-search`，基础／提前响铃离线演示分别为 `ringing-basic`／`ringing`。Web 响铃交互见 [`原型验收`](../prototype/qa/ringing-2026-09-02/README.md)，Android 真实响铃与动作确认见 [`原生验收`](../android/qa/native-ringing-2026-09-02/README.md)；两类结果不互相替代。
- Figma 根节点不定义导航；下表的进入、返回和状态更新是实现契约，不从组件悬停或变体推断。

### 2026-09-07 当前有效设计／原型基线

本节保留设计合并追溯；第一阶段已在 Android 同步设置与闹钟编辑的信息组织、当前计划通勤草稿和必要导航。测试及设备证据见 [`第一阶段验收`](../android/qa/settings-editor-2026-09-07/README.md)。

| Figma 页面 | 页面节点 | 本轮用途 |
|---|---|---|
| `01 · 知途设计提案` | [`0:1`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=0-1) | 保留为源页面，提供主流程与交互状态的既有设计来源。 |
| `02 · 可点击原型` | [`16:180`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=16-180) | 保留为源页面，提供尚未接入 Android 的有效产品目标、状态和概念页面。 |
| `03 · 通勤路线方案` | [`79:600`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=79-600) | 保留为源页面，提供现有路线主方案。 |
| `知途 · 完整设计 · 2026-09-28` | [`218:2464`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=218-2464) | 当前可审阅基线：整合 Android 主流程、原型状态和保留目标。 |

- 合并页的当前主流程以 `home`、天气、路线与地点选择、计划列表与编辑、日历覆盖、设置、凭据、可靠性诊断、记录、引导和真实基础响铃为准。节点映射见下表。旧 21 个 Figma 页面不是 Android 已实现清单；当前实现范围和路径由下文实施状态及 [`SPEC.md`](../SPEC.md) 第 8 章共同约束。
- 旧设计中仍有效但尚未接入当前 Android 导航的天气地图、锁屏通知、胶囊摘要／展开详情和单日覆盖，保留在合并页目标区。它们不得从主设置页或当前主流程伪造为已可用功能。
- 设置页以通勤路线、日历、凭据、天气缓冲和隐私入口组织；“权限与诊断”作为摘要进入提醒权限检查。诊断／首次启用引导按设备条件展开标准系统状态、设备条件项、人工确认说明及设置入口结果；标准系统核验与人工确认必须分别显示。全屏提醒的标准可用性以 Android 平台状态为准：https://source.android.com/docs/core/permissions/fsi-limits
- 当前 Android 的“通知摘要”“锁屏摘要”开关没有已接入的行为消费者。本阶段从 Android 和原型设置页移除界面开关，保留 Android 现有存储字段与历史数据兼容性。
- “通勤路线”合并地图与常用地点；“隐私”合并隐私说明与地图授权。设置层不平铺每个品牌的专项设置。
- 计划编辑保留“通勤与提前提醒”折叠组，收纳到达时间、准备时间、最多提前和计划通勤覆盖。字段完整保存到当前计划；未实现的日级通勤覆写继续保留为设计目标，不能写成已有 Android 数据能力。

#### 第一阶段 Android 交接

- 设置：`SettingsScreen.kt` 复用现有主题与导航，显示权限与诊断入口、独立天气缓冲入口、App 图标和实际版本数据；诊断中按设备条件显示人工确认项。
- 天气缓冲：独立页面展示工作日、周末、法定休息日，0–60 分钟校验后分别保存；现有 `LocalSettingsStore` 字段及 `EvaluationCoordinatorPolicy.weatherProfile` 消费路径继续使用。合并设置屏 `219:3948` 补入口 `242:3136`，设计及原型证据见 [`原型补齐验收`](../prototype/qa/phase1-android-2026-09-07/README.md)。
- 编辑：`AlarmEditorScreen` 默认收起“通勤与提前提醒”，保留全部字段与四周预览。`PLAN_COMMUTE` 绑定当前草稿，子页完成只回填，整份保存才写入；返回、取消与首次启用引导分别验证。
- 新建使用稳定草稿 ID；计划及通勤覆盖通过原协调器的保存边界同事务提交，已有计划编辑失败保留原配置。数据库表与版本保持现有契约。
- 状态与权限实现依据：https://developer.android.com/develop/ui/compose/state-hoisting https://developer.android.com/training/permissions/requesting-special

#### 第二阶段 Android 交接

- 新增 `DECISION_DETAIL` 目的地与 `DecisionDetailScreen`。首页最近评估、相关闹钟摘要及历史记录传入本条 `decisionId`；响铃通过当前 `occurrenceId` 读取其 ADVANCE 根和持久化决策。当前计划编辑或删除后仍展示原始快照。
- `DecisionDetailRepository` 校验实例计划、评估修订和目标日期，处理贪睡祖先、缺失与不唯一关联；`DecisionDetailUi` 统一首页／列表／详情中文状态。成功、无需提前、失败、过期、跳过、注册失败和提前额度不足分开显示。
- 详情结论与基础／建议／实际时间在前；分解、应用结果及实例状态随后；来源按需展开。计划名和时区由本次快照提供，旧记录不补当前配置或演示数据。数据库 v4→v5→v6 保留删除计划后的历史关联并增加可空展示快照字段。
- “重新评估”复用 `evaluateNow` 当前配置检查和 WorkManager 队列；重复请求合并，新任务不覆盖历史。只有与该决策明确关联的真实 retry Work 才提供下一次时间。凭据、授权、当前计划通勤和诊断返回原详情。
- 提前响铃只显示快照摘要与“查看提前原因”，解锁后查看完整详情，返回仍可操作原响铃；Direct Boot 快照、停止、贪睡和动作确认边界沿用既有实现。Android 系统解锁回执依据：https://developer.android.com/reference/android/app/KeyguardManager.KeyguardDismissCallback
- 合并页三个指定节点已同步为本阶段已实现界面；[Figma 台账](../android/qa/decision-details-2026-09-07/figma/README.md)、[Android 验证](../android/qa/decision-details-2026-09-07/README.md)、[原型测试与截图](../prototype/qa/decision-details-2026-09-07/README.md) 分别记录其验证边界。

#### 04 合并页主流程节点映射

| 源节点 | 合并页节点 | 内容 |
|---|---|---|
| `57:480` | [`219:2464`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-2464) | 今日 |
| `207:2389` | [`219:2677`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-2677) | 天气 |
| [`81:808`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=81-808) | [`219:2898`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-2898) | 路线主方案；`57:833` 保留为历史视觉来源。 |
| [`81:1208`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=81-1208) | [`230:3205`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=230-3205) | 路线备选方案 |
| [`81:1608`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=81-1608) | [`230:3308`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=230-3308) | 公交通勤方案 |
| `57:1000` | [`219:3112`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-3112) | 闹钟 |
| `57:1083` | [`219:3556`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-3556) | 编辑收起 |
| 当前新增 | [`273:666`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=273-666) | 时间选择：系统 24 小时制；小时与分钟循环。 |
| 当前新增 | [`273:701`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=273-701) | 时间选择：系统 12 小时制；AM/PM、小时与分钟循环。 |
| `176:2139` | [`219:3711`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-3711) | 工作日预览 |
| `3:49` | [`219:3948`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-3948) | 设置；包含版本、构建号与 App 图标 |
| `57:1226` | [`219:4138`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=219-4138) | 权限与诊断；承载提醒权限 |
| `57:1187` | [`220:2769`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=220-2769) | 凭据 |
| `37:225` | [`220:2907`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=220-2907) | 授权 |
| `37:273` | [`220:2951`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=220-2951) | 日历 |
| `64:554` | [`220:3101`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=220-3101) | 记录 |
| `42:281` | [`222:2787`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=222-2787) | 地点 |
| 当前新增 | [`277:670`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=277-670) | 地点搜索中；输入框右侧显示加载图标 |
| `171:2437` | [`222:2857`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=222-2857) | 通勤与提前提醒展开 |
| `95:601` | [`222:3022`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=222-3022) | 基础响铃 |

#### 04 合并页保留目标区

| 合并页节点 | 目标 |
|---|---|
| [`223:3721`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-3721) | 天气地图 |
| [`223:3919`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-3919) | 决策详情（第二阶段已实现） |
| [`223:4010`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-4010) | 锁屏 |
| [`223:4069`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-4069) | 胶囊摘要 |
| [`223:4183`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-4183) | 胶囊展开详情 |
| [`223:4317`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-4317) | 评估失败详情（第二阶段已实现） |
| [`253:3146`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=253-3146) | 设置独立天气缓冲页；三套配置分别保存。 |
| [`223:4501`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=223-4501) | 单日覆盖 |
| [`228:3078`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=228-3078) | 通知摘要／锁屏摘要设计目标说明板 |
| [`228:3086`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=228-3086) | 提前响铃详情入口（第二阶段已实现；源节点 `95:763`） |

合并页节点布局、72 项原型测试和浏览器 QA 已在 [`设计合并验收`](../prototype/qa/design-merge-2026-09-07/README.md) 记录。最新 Figma 导航审计确认主流程静态样例没有跨页失效的 `NAVIGATE` 连接；连续交互以浏览器 QA 为准。本节不将其表述为 Android 导航或功能验收。

### 2026-09-05 统一诊断记录

诊断主页面为 [`57:1226`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=57-1226)，沿用既有权限、音量、日历刷新与返回设置导航，在原有布局中补充“应用与系统”“铃声可读性”和“最近本地记录”。通用 Android 权限状态继续以 [`133:634`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=133-634) 为准；小米手工确认继续以 [`136:668`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=136-668) 为准。

- 应用与系统显示应用版本和 SDK API；铃声仅检查已选铃声是否可读取，不播放。首选铃声无法读取时显示“备用铃声可读取”及“可尝试默认回退”，不表示已改配置或播放。
- 最近记录由自动评估、注册、响铃、停止、贪睡、恢复、日历刷新和铃声检查汇总，按时间从新到旧显示。本页只显示事件中文名、结果中文名、跨日日期时间和可选耗时；空态固定为“尚无本地诊断记录”。
- 原型使用 `records` 与 `empty` 离线 fixture；可调用 `window.ZhituPrototype.setDiagnosticFixture('records' | 'empty')` 验收，不读取设备状态、不播放铃声，也不创建或改变闹钟。
- 本机环形记录最多 200 条，字段固定为 `eventType,resultCode,appVersion,sdkInt,planIdHash,occurrenceIdHash,durationMs,timestamp`。结果码为固定枚举，不附带自由文本；地址、坐标、URI、凭证和异常文本不进入记录或页面。

### 2026-09-05 首页天气与路线状态（稳定追溯组）

首页主节点为 [`57:480`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=57-480)，稳定状态组为 [`195:2153`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=195-2153)。该组覆盖主首页原有占位展示；节点、连接和截图见 [Figma 台账](../prototype/qa/home-preview-2026-09-05/figma-state.json) 与 [主首页截图](../prototype/qa/home-preview-2026-09-05/figma-home-success.png)。当前有效页面组织见本文件开头的 2026-09-07 基线。

- 页面顺序为天气、最近自动评估、最近有效闹钟、通勤路线。天气显示等级、地点、数据时间和来源，路线显示方式、距离和耗时；来源以实际网络／缓存结果为准。
- 配置读取、凭据缺失／存储错误、彩云待测试／测试失败、高德未授权、地点缺失、加载、成功、缓存、空路线、失败及有效旧结果保留分别呈现。配置、地点、授权、重试入口按当前原因显示。
- 首页进入／返回／前台恢复及输入变化时检查有效期；下拉刷新当前天气和路线。刷新动作进入加载态并返回结果，详情入口与刷新入口分别连接；Android 预览与后台评估使用独立操作。
- Figma 和 Web 原型使用标记为离线 fixture 的示例数据；Android 状态、并发、下拉和数据隔离验收见 [设备验收](../android/qa/home-preview-2026-09-05/README.md)。

### 2026-08-31 本地闹钟实施状态

> 2026-09-01 高德已接入：Android 已实现授权、加密运行时 Key、地图、单次定位、POI／输入提示、五种路线、最多三条备选、路况和计划覆盖；待用户提供 Web Service Key 与 Android SDK Key 后完成设备实网验收。`prototype/` 使用确定性离线 fixture 验收页面状态。彩云天气 Android 实网与界面验证见 [`android/qa/caiyun-device-2026-09-02.md`](../android/qa/caiyun-device-2026-09-02.md)；自动评估 fixture 与决策记录见下方状态组。

本状态组覆盖下表中相同页面的旧“系统时钟参考／提前闹钟／模拟 Provider”语义。Figma 节点均位于 `2026-08-31 / 基础本地闹钟（当前实施）`（`57:479`）；对应截图与节点台账位于 [`prototype/design/implementation-2026-08-31/`](../prototype/design/implementation-2026-08-31/)。

| 功能 | Figma 节点 | 原型路由 | 当前交互契约 |
|---|---|---|---|
| 首页与状态 | `57:480`、`195:2153` | `home` | 按 2026-09-05 状态契约展示配置、凭据、请求与结果；支持自动检查和下拉刷新。 |
| 天气 | `207:2389` | `weather` | 详情与首页共享天气摘要及状态；Figma 与原型使用离线 fixture。 |
| 路线与地点 | `207:2598` | `route` | 详情与首页共享路线摘要；全局通勤、计划覆盖、地图、五种路线和最多三条备选由高德 fixture 演示。 |
| 闹钟空列表 | `57:1000` | `plans` | 首次安装无预置项；显示添加入口与实际注册状态说明。 |
| 添加／编辑 | 当前 `219:3556` | `plan-edit` | 可选备注、日期、循环时间滚轮、动态倒计时、指定日期／每周／工作日、铃声、振动、贪睡、保存并注册、删除。新建默认单次 06:00；当天已过默认次日。 |
| 高德运行时凭据 | `57:1187` | `credentials` | Web Service Key／Android SDK Key仅当前页面会话；选择 fixture 状态，不发送请求。 |
| 诊断 | `57:1226` | `diagnostics` | Android 读取通知、精确闹钟、全屏提醒和音量，系统设置返回后重新检查；Web 原型不读取系统状态。 |
| 记录空态 | `64:554` | `history` | 首次安装无记录；保留日期与结果筛选，后续只展示实际注册、触发、停止、贪睡和异常事件。 |

本段记录 2026-09-07 的页面开发和验收方式；2026-09-28 起的有效流程见本文件顶部冻结说明。

### 2026-09-03 法定工作日日历预览

2026-09-05 Android 已实现该预览，入口为计划编辑的“法定工作日”；卡片独立填充编辑内容区，七列等宽排列。实现与模拟器截图见 [Android 四周日历验收](../android/qa/workday-preview-2026-09-05/README.md)。

Figma 状态组为 [`183:2152`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=183-2152)，选中态为 [`176:2139`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=176-2139)，日期组件为 `177:2132`。计划编辑入口 `57:1083` 与 `171:2437` 的“法定工作日”选项已连接选中态；选中态点击“指定日期”返回收起预览的页面。

用户在计划编辑中选择“法定工作日”后，立即显示上周、本周、下周和下下周的日历预览。日历按周一至周日排布，共 28 天；预览卡片填充计划编辑内容区，七列等分其内宽度。当前 Figma 主状态以 `2026-09-03` 为今天，展示区间为 `2026-08-24` 至 `2026-09-20`；其中 `2026-09-20` 为特殊工作日。2026 年节假日与调休安排依据国务院办公厅通知：https://big5.www.gov.cn/gate/big5/www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm 。

| 日期类别 | 视觉规则 | 判定来源 |
|---|---|---|
| 普通工作日 | 文字 `Primary Text`（`#303133`），无背景 | 周规则工作日，且年度节假日数据未标记为调休上班 |
| 休息日 | 文字 `--el-color-primary-light-3`（`#79bbff`），无背景 | 周规则周末，且年度节假日数据未标记 |
| 特殊节假日 | 文字 `--el-color-primary-light-3`（`#79bbff`），背景 `--el-color-primary-light-9`（`#ecf5ff`） | 年度节假日数据 `isOffDay=true` |
| 特殊工作日 | 文字 `--el-color-warning`（`#e6a23c`），背景 `--el-color-warning-light-9`（`#fdf6ec`） | 年度节假日数据 `isOffDay=false` |

“今天”仅增加轮廓标记，日期仍保留其类别对应的文字色和背景色。上述 Element Plus 色值以主题变量定义为准：https://github.com/element-plus/element-plus/blob/dev/packages/theme-chalk/src/common/var.scss 。

### 2026-09-03 自动评估与决策记录（离线 fixture）

Figma 状态组为 [`167:2099`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=167-2099)，位于文件 `wN04BlxRelbJyBVF35DyXE`、页面 `0:1`。该组复用首页、天气和记录页面结构，交接原型的路线、天气、工作日规则到提前提醒决策的完整可见链路。

| 状态 | Figma 节点 | 原型入口 | 当前交互契约 |
|---|---|---|---|
| 待评估／评估中 | [`168:2272`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=168-2272) | `weather` | 选择已启用计划；没有已启用计划时使用标记为 fixture 的上班闹钟。点击“立即评估”才生成会话内结果。 |
| 成功提前／无需提前 | [`168:2099`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=168-2099) | `home`、`why` | 决策分解分别显示路线时长、天气缓冲、工作日规则和调度动作。成功只创建独立提前提醒，基础闹钟时间保持不变。 |
| 失败重试／已过截止／结果过期 | [`168:2481`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=168-2481) | `failure`、`history` | 重试状态记录下一次尝试与已存在提前提醒的保留；截止或过期结果不新增或调整提前提醒。历史页保留输入摘要和决策原因。 |
| 计划编辑评估规则 | [`171:2437`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=171-2437) | `plan-edit` | 期望到达时间默认 `09:00`；准备时间为 `0–240` 分钟，默认 `30`；最多提前为 `0–180` 分钟，默认 `60`。保存后回填到同一计划，并用于下一次离线评估。 |

`prototype/state.mjs` 的自动评估状态是确定性、会话内 fixture。它不请求路线或天气服务，不注册系统闹钟，不读取凭据；手动天气预览切换不会自动改写已经生成的评估结果。基础响铃和提前响铃仍属于独立离线演示路由。

### 2026-09-02 基础与提前响铃交互（离线演示）

Figma 状态组为 [`95:600`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=95-600)，位于文件 `wN04BlxRelbJyBVF35DyXE`、页面 `0:1`。原 `3:73` 已同步提前响铃文案与首轮按钮连接；当前交接以本状态组的八个状态为准。Figma 覆盖响铃、停止、首次贪睡及首次子实例的状态跳转；子实例之后的连续操作和跨日由 Web 原型验收。截图与验证记录见 [`prototype/qa/ringing-2026-09-02/`](../prototype/qa/ringing-2026-09-02/README.md)。

| 状态 | Figma 节点 | 原型路由 | 当前交互契约 |
|---|---|---|---|
| 基础响铃 | `95:601` | `ringing-basic` | 固定 `2026-09-02 07:30`、贪睡 10 分钟；仅表示本 App 基础本地闹钟，不归属系统时钟 App。 |
| 基础已停止 | `95:655` | `ringing-basic` | 停止仅结束当前演示实例；仅显示“返回闹钟／重新演示”。 |
| 基础已贪睡 | `95:709` | `ringing-basic` | 贪睡创建独立演示子实例；首次再次响铃为 `07:40`，仅显示“返回闹钟／模拟再次响铃”，重复操作按实例 ID 幂等。 |
| 基础贪睡后再次响铃 | `119:624` | `ringing-basic` | `07:40` 的子实例显示“贪睡后再次响铃”和“贪睡提醒”；Web 原型继续支持停止和再次贪睡。 |
| 提前响铃 | `95:763` | `ringing` | 固定 `07:18`，显示提前 12 分钟及 fixture 理由；不表示自动提前计算已启用。 |
| 提前已停止 | `95:817` | `ringing` | 停止仅结束当前演示实例；仅显示“返回闹钟／重新演示”。 |
| 提前已贪睡 | `95:871` | `ringing` | 贪睡创建独立演示子实例；首次再次响铃为 `07:28`，仅显示“返回闹钟／模拟再次响铃”。连续贪睡由 Web 原型按选择时长继续递增。 |
| 提前贪睡后再次响铃 | `119:678` | `ringing` | `07:28` 的子实例显示“贪睡后再次响铃”，不沿用初始提前量文案；Web 原型继续支持停止和再次贪睡。 |

两条路由均为独立全屏，不能叠加原型外层状态栏；贪睡时长必须为 1–30 分钟整数。离开重进、重置或刷新重建演示会话。演示不得写入 `alarmPlans`、`alarmEvents` 或 `dateOverrides`，不得播放音频、振动、注册系统闹钟或发网络请求。

### 2026-09-03 权限与可靠性引导（稳定追溯组）

Figma 权限状态组为 [`133:632`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=133-632)，位于文件 `wN04BlxRelbJyBVF35DyXE`、页面 `0:1`。本组保留为权限主要分支的稳定追溯依据；当前设置页 `3:49` 与诊断页 `57:1226` 已接入本组。设置层级和设备条件项的当前有效组织以本文件开头的 2026-09-07 基线为准。Figma 固定演示场景用于追溯权限主要分支；连续组合与数据生命周期以可运行原型测试为准。节点与连接台账见 [`figma-state.json`](../prototype/qa/permissions-2026-09-03/figma-state.json)，浏览器离线流程记录见 [`权限原型浏览器验收`](../prototype/qa/permissions-2026-09-03/README.md)。

| 功能 | Figma 节点 | 交互契约 |
|---|---|---|
| 设置权限入口 | [`157:2223`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=157-2223)、[`144:1450`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=144-1450) | 设置页展示统一“权限与诊断”摘要并进入诊断；诊断／启用引导按设备条件展开标准 Android 项和厂商条件项。 |
| 通用 Android 诊断 | [`133:634`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=133-634) | 展示通知、精确闹钟与全屏提醒的演示状态、授权入口和设置返回后的刷新状态。 |
| 小米诊断 | [`136:668`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=136-668) | 作为设备条件项示例，展示锁屏显示与后台弹出页面的手工设置说明及用户确认状态；不在设置层单独平铺。 |
| 首次启用引导 | [`137:899`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=137-899) | 缺失状态说明用途；继续、检查与取消保留各自会话语义。 |
| 全屏提醒 | [`137:728`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=137-728) → [`149:1879`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=149-1879) → [`149:1966`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=149-1966) → [`149:2053`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=149-2053) | 展示全屏提醒引导、模拟设置、选择与返回检查；通用设置页与已选择结果见 [`138:836`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=138-836)、[`143:1250`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=143-1250)。 |
| 小米手工确认 | [`139:884`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=139-884) → [`137:799`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=137-799) | 展示厂商设置说明与双方确认；单项和组合分支见 [`145:1523`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=145-1523)、[`145:1623`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=145-1623)、[`145:1930`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=145-1930)。 |
| 设置入口状态 | [`139:1000`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=139-1000) | 展示当前演示设备无法匹配设置入口时的返回与检查路径。 |
| 当前位置请求 | [`139:1115`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=139-1115) | 仅从“使用当前位置”发起；展示大致、精确、拒绝和定位服务关闭。 |
| 位置不可用与恢复 | [`141:1053`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=141-1053)、[`141:1141`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=141-1141) → [`141:1228`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=141-1228) | 分别展示拒绝、服务关闭与模拟设置恢复。 |
| 位置许可结果 | [`142:1120`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=142-1120)、[`142:1190`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=142-1190) | 大致与精确位置的单次使用结果。 |
| 位置入口回程 | [`150:2339`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=150-2339)、[`150:2409`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=150-2409) | 分别覆盖地点入口与编辑地点的有效返回路径。 |
| 引导检查 | [`142:1260`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=142-1260)、[`142:1334`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=142-1334) | 通用 Android 与小米的检查入口。 |
| 保存结果与位置按需说明 | [`144:1640`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=144-1640)、[`147:1835`](https://www.figma.com/design/wN04BlxRelbJyBVF35DyXE?node-id=147-1835) | 展示保存后结果和当前位置按需请求的说明。 |

同一会话内，明确点按继续启用后，同一缺失状态不再重复提示；取消不写入也不消费确认，后续主动保存仍需确认。Figma QA 实读验证 33 个固定场景、109 条预期连接与全部覆盖层边界；浏览器离线流程记录覆盖工具栏、入口、引导、确认、位置与返回状态。系统设置均为原型演示，Figma 图形连线不构成对设备授权状态的实际验证。

Android 原生交接：`PermissionAccess` 提供标准 Android 能力快照和设置页回退；`PermissionScreens`／`ZhituApp` 在设置返回后刷新快照。`AlarmPermissionFlow` 保留启用前的草稿或待执行动作并处理会话内继续／取消；`LocationPermissionFlow` 只处理“使用当前位置”的用途说明、粗精位置、拒绝、定位服务关闭和设置恢复。小米手工确认是当前会话状态，和 Android 能力快照分别呈现。`miui.intent.action.APP_PERM_EDITOR` 仅按 MIUI 官方 FAQ 作为通用应用权限页尝试；HyperOS 的版本覆盖与两项专项设置效果以真机记录确认。https://dev.mi.com/docs/appsmarket/technical_docs/adaptation_FAQ/ https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625

Android 权限实现与验收见 [`2026-09-03 原生权限记录`](../android/qa/permissions-2026-09-03/README.md)。本次实现复用 Compose 组件与知途主题；系统授权使用 Android 原生界面，应用内启用引导和定位说明沿用设计稿底部弹层。

## 主页面契约

### Android 响铃实现映射（2026-09-02）

Android 复用 `95:601`、`95:655`、`95:709`、`119:624` 的基础／贪睡视觉结构。时间、日期和计划名称来自真实设备保护快照；状态栏与导航手势由系统绘制。原型的“重新演示／模拟再次响铃”属于 Web fixture，原生结果页对应为“返回闹钟／关闭”；再次响铃由已注册子实例真实触发。第二阶段已接入真实 ADVANCE 摘要及解锁详情入口；原有离线演示仍单独标记。构建、设备结果和验证边界见 [`原生响铃验收`](../android/qa/native-ringing-2026-09-02/README.md)。

下表的 21 个旧 Figma 页面是历史视觉素材清单，非 Android 页面完成清单。对应实施证据和后续差异见 [`N001 交接记录`](plans/N001-handoff.md)。

| 编号 | 页面 / Figma 节点 | 原型 route | SPEC | 原型导航/交互 | 关键状态 |
|---:|---|---|---|---|---|
| 01 | 今日·出行总览 `3:10` | `home` | 8.2 | 首次引导完成或底部“今日”进入；本地闹钟空态进入添加；底部导航不返回上一页。 | 下一次有效本地闹钟、真实注册状态、天气与高德 fixture 状态。 |
| 02 | 天气·预报地图 `3:15` | `weather` | 8.2、8.4 | 从今日天气进入；返回今日。 | 原型为离线天气 fixture；Android 彩云实网与界面验证见 `android/qa/caiyun-device-2026-09-02.md`。 |
| 03 | 路线·上班通勤维护 `3:20` | `route` | 8.4 | 从今日或底部“路线”进入；管理全局通勤并查看最多三条路线 fixture。 | 驾车、公交、步行、骑行、电动车五选一；展示当前路况 fixture。 |
| 04 | 路线·编辑与选点 `3:25` | `route-edit` | 8.4、FR-011 | 从路线页或闹钟计划覆盖进入；起点/终点进入 `place-search`；保存回所属页面。 | 运行时 SDK Key 与授权均满足时允许地图选点／单次定位 fixture。 |
| 05 | 闹钟·计划总览 `3:34` | `plans` | 8.3 | 从底部“闹钟”进入；空态进入添加、列表进入编辑；底部导航离开。 | 启用意图与实际注册状态、下一次响铃、单日覆盖摘要。 |
| 06 | 闹钟·规则设置 `219:3556` | `plan-edit` | 8.3、FR-001 | 从计划总览进入；编辑可选备注、日期时间、规则、铃声、振动和贪睡；保存返回计划总览，返回/取消不写入草稿。 | 显示动态倒计时；保存合法计划后注册下一次本地实例。 |
| 07 | 查看·提前原因 `3:44` | `why` | 8.7C | 通过 decisionId 或 occurrenceId 查看本次快照，返回入口页。 | 评估／应用／实例分别展示，来源可展开。 |
| 08 | 设置·通知与可靠性 `3:49` | `settings` | 8.8 | 从底部“设置”进入；进入凭证、日历、诊断和系统概念页；底部导航离开。 | 通知、精确闹钟、全屏能力摘要与回退说明。 |
| 09 | 锁屏·出发提醒 `3:58` | `lock` | 8.8、FR-009 | 原型从设置进入；页面无应用内返回按钮，“稍后提醒”回到今日，“查看通勤”进入路线。 | 仅展示系统通知概念，不新增厂商接口、常驻提醒或上传。 |
| 10 | 超级岛·胶囊摘要 `3:63` | `island` | 8.8、FR-009 | 原型可从设置直接进入；点按胶囊展开详情，页面无设置返回按钮。 | 概念展示；实际可用性以设备能力诊断为准。 |
| 11 | 超级岛·展开详情 `3:68` | `island-expand` | 8.8、FR-009 | 从胶囊摘要进入；收起返回胶囊摘要，“稍后提醒”回到今日，“查看路线”进入路线。 | 概念展示不等于真实系统集成。 |
| 12 | 响铃·本地闹钟 `3:73`；基础／提前响铃状态组 `95:600` | `ringing-basic`／`ringing` | FR-008、8.7A | 原型分别演示基础与提前 fixture 的停止、贪睡、结果和再次响铃；Android 由有效基础本地实例触发并按 FR-008 处理。 | 演示与 Android 基础响铃分开；提前 fixture 不表示自动提前已启用。 |
| 13 | 查看·闹钟记录 `14:154` | `history` | 8.7 | 从设置或闹钟进入；返回来源页。 | 首次安装为空态；展示实际注册、触发、停止、贪睡和异常事件，不展示坐标或凭证。 |
| 14 | 异常·评估未完成 `14:157` | `failure` | 8.7C | 进入本次失败记录；重新评估当前计划，恢复页面返回详情。 | 失败、过期、跳过及调度失败按记录展示。 |
| 15 | 休息日·独立缓冲 `22:207` | `rest` | 5.5、8.5、FR-004 | 从路线/计划入口进入；保存缓冲或选择单日加班。直接进入时保存到计划页；从规则设置嵌套进入时保存返回父规则编辑页。 | 周末和法定休息日 profile 分别保存，不叠加，保存不自动启用。 |
| 16 | 单日加班·选择日期 `22:210` | `overtime-select` | 5.4、5.5、8.5、FR-002 | 从休息日规则或计划进入；选择日期并启用；返回/取消不新增覆盖。 | 继承日常路线；可选覆写本日默认起床、到岗、准备时长和三档天气缓冲；未填写字段继承日常计划和原始休息日 profile。 |
| 17 | 单日加班·已生效 `24:187` | `overtime-active` | 5.4、5.5、8.5、FR-002 | 启用成功后进入；编辑返回选择页；撤销删除覆盖并返回计划。 | 覆盖仅影响指定计划和日期；保存后重算该计划下一次本地实例。 |
| 18 | 首次启动与隐私引导 `37:225` | `onboarding` | 8.1 | 首次启动进入；“同意并开始设置”在勾选后进入凭证页，“仅浏览”直接进入首页；原型可用返回到设置查看。 | 当前原型仅对开始设置校验勾选；产品隐私同意约束以 8.1 为准。 |
| 19 | 数据与凭证 `37:249` | `credentials` | 5.6、8.6、FR-012、FR-013 | 从首次引导或设置进入；保存留在当前页；清空走确认覆盖层；仅页头返回设置。 | Web／SDK Key字段掩码；fixture 验证不发请求、不显示密钥。 |
| 20 | 工作日日历 `37:273` | `calendar` | 5.4、8.5、FR-015 | 从设置直接进入时保存/返回设置；从规则设置嵌套进入时保存返回父规则编辑页。选日期后选自动/工作日/休息日并保存。 | 显示来源、刷新结果和四类日期；数据缺失时按星期自动兜底并显示警告。 |
| 21 | 可靠性诊断 `37:297` | `diagnostics` | 8.8、FR-009、FR-010 | 从设置或异常页进入；重新检查后留在本页；返回设置。 | 通知、精确闹钟、全屏提醒、音量、铃声、天气与高德配置状态和最近本机事件；不显示密钥、完整地址或经纬度。 |

## 地点选择子状态

| 状态 / Figma 节点 | 原型 route | 进入、返回与主操作 | 状态与校验 |
|---|---|---|---|
| 地点·搜索与管理 `42:281` | `place-search` | 从全局或计划覆盖路线编辑的起点／终点进入；输入提示／POI、常用地点或单次当前位置后点击“使用这个地点”返回来源。 | 成功、加载、无 Key、地图选点、定位拒绝和服务错误均为 fixture 状态；正式应用仅在用户点按当前位置时请求前台定位。 |

## 通用覆盖层契约

覆盖层不另计主页面。取消始终丢弃未保存草稿；编辑页保存合法本地闹钟时由 `LocalAlarmCoordinator` 注册下一次实例，其他设置保存只更新所属配置。

| 覆盖层 / Figma 节点 | 进入 | 保存与取消 | 校验与边界 |
|---|---|---|---|
| 时间选择 `273:666`、`273:701` | 在闹钟编辑页选择时间；按系统制式切换 24 小时或 AM/PM，小时和分钟循环滚动。 | 确定只更新编辑草稿；编辑页整体保存后注册下一次实例；取消保留已保存值。 | 倒计时随草稿时间及设备时间更新；单次日期时间不得过去。 |
| 时长与校验 `42:394` | 在闹钟编辑页设置贪睡时长。 | 合法保存；取消保留已保存值。 | 贪睡为 1–30 分钟整数；错误输入不写入计划。 |
| 天气缓冲 `42:504` | 在规则设置编辑当前日期类型的三级缓冲，或在单日加班编辑本日三级缓冲。 | 规则设置中保存仅更新当前全局 profile；单日加班中保存仅更新该日 profile；取消不改变已有值。 | 三值均为 0–60；工作日、周末、法定休息日互斥保存，不叠加；日级 profile 替换本日默认 profile，不改全局配置，不自动开启休息日或调度。 |
| 铃声与振动 `42:614` | 在闹钟编辑页编辑铃声与振动。 | 保存写入本地闹钟配置；取消保留已保存配置。 | 原型只展示试听状态；Android 响铃遵循 FR-008。 |
| 清空凭据确认 `42:724` | 在数据与凭据页点按清空。 | 取消不改输入；确认清除凭据并显示“已清空”。 | 只影响凭据；不得修改闹钟、日期覆盖、地点或出行方式。 |

## 休息日规则

- 三套天气缓冲独立保存、互不叠加：工作日默认 `10/20/30` 分钟，普通周末默认 `5/10/20` 分钟，法定休息日默认 `10/15/25` 分钟；顺序均为严重等级 1/2/3。
- 日历优先级仍为单日覆盖、holiday-cn、周规则。日历缓存缺失、校验失败或无法刷新时，按周一至周五工作日、周六日普通周末自动兜底，不要求用户额外确认。
- 调休上班日按工作日。法定休息日与周末重合时选用法定休息日缓冲。单日加班只覆盖所选日期，不改变日常计划或每周安排。
- 单日覆盖记录日期、原始日期类别和来源；可选保存本日默认起床、到岗、准备时长和三档天气缓冲。缺省值继承日常计划和对应日期 profile。

## 2026-09-28 凭据交互更新

- 高德和彩云按钮统一为“测试连接”，读取当前输入；请求期间仅当前测试按钮显示“处理中”，另一项测试按钮禁用并保留“测试连接”文案；测试成功不保存候选凭据。彩云未修改 Secret 时可测试当前已保存的完整凭据，修改 App Key 必须同时输入 Secret；保存由底部“保存凭据”执行，彩云候选值通过连接验证后写入。
- 彩云连接测试固定使用重庆渝中区解放碑附近测试点（经度 106.574、纬度 29.561），不依赖通勤配置。参考区政府公布的解放碑步行街坐标：https://www.cqyz.gov.cn/zwxx_229/gggs/202501/P020250124353352402531.pdf 。请求经纬度顺序依据：https://docs.caiyunapp.com/weather-api/v2/v2.6/1-realtime.html 。
- 验证、保存和清空结果通过横幅通知：成功使用浅绿背景，失败使用浅橙背景；出现 60 秒后自动关闭，新结果重新计时。
