# 通勤闹钟 (weather-traffic-alarm)

一个纯 Android、本地优先的通勤闹钟：根据工作日、通勤耗时和天气状况，在夜间自动评估次日是否需要提前起床。**正常起床闹钟完全由系统时钟 App 提供**（App 只做设置引导）；评估成功且确实需要提前时，App 才注册一个一次性提前闹钟。评估失败或 App 被清理都不会影响正常闹钟响铃。

**包名 / applicationId：** `com.ljwzz.weathertrafficalarm`

## 架构

- **纯 Android 本地优先**：无后端、无 Spring Boot、无数据库服务、无服务端 API 契约。所有数据（计划、决策历史、日历缓存）只存本机。
- 第三方 API 由 App **直接调用**：
  - **高德**（Web 服务 API）：路径规划、POI 搜索、输入提示；高德 Android SDK 用于地图选点与前台定位。
  - **彩云天气**（v2.6，App Key + App Secret HMAC-SHA256）：小时级天气预报。
- 第三方凭证由用户在**凭证配置页**手动输入，连接测试可校验，密钥用 **Android Keystore 不可导出密钥**加密后存本地专用凭证存储，排除备份。
- 工作日规则使用 [holiday-cn](https://github.com/NateScarlet/holiday-cn)（MIT）的年度 JSON 数据，由 App 主动抓取并缓存，按 10 月 1 日分界控制拉取年份，具备数据校验与本地兜底。

## 目录结构

```
weather-traffic-alarm/
├── android/                     # Android 工程（Gradle 多模块）
│   ├── app/                     # 应用壳
│   ├── core/model/              # 领域模型与纯计算（无 Android 依赖）
│   ├── core/data/               # Room、DataStore、凭证存储、日历缓存、仓库
│   ├── core/network/            # 高德/彩云 HTTP 客户端、脱敏
│   ├── core/alarm/              # 精确闹钟、响铃、状态机、快照
│   ├── core/map/                # 高德 SDK 适配（Compose AndroidView）
│   ├── core/security/           # Android Keystore 加密凭证存储
│   └── feature/{onboarding,home,plan,place,calendar,history,credentials,diagnostics}/
├── docs/                        # 环境与配置记录
└── scripts/                     # 验证脚本
```

## 功能要点

- 正常起床闹钟：由用户在系统时钟 App 中设置，App 不做任何注册；首页提供“请在系统时钟 App 设置闹钟”引导与 `getNextAlarmClock()` 启发式核对，避免 App 被清理后没有任何闹钟生效。
- 自动提前：每晚 19:00 后评估次日天气与通勤；**只有评估完全成功且确实需要提前时**，才注册一个一次性提前闹钟（只提前、不推迟），失败时不做任何调度修改。
- 工作日日历：抓取 holiday-cn 年度 JSON 并缓存；打开日历相关页面时刷新（先清理去年数据，10 月 1 日前只拉当年、之后拉当年与次年），失败回退周规则。
- 凭证管理：高德（Web Key + 可选 Android SDK Key）与彩云（App Key + App Secret）分别配置，均带连接测试。
- 安全：凭证用 Android Keystore 生成不可导出密钥加密，专用存储排除备份，禁止日志/截图/崩溃信息泄露凭证。

## 最低工具版本

- JDK 21
- Android SDK Platform 36 + Build Tools 36.0.0
- Gradle 9.5.0（wrapper）

## 验证

```bash
./scripts/verify-all.sh
```

## 文档

- 产品与技术规格：[`SPEC.md`](./SPEC.md)
- 可执行实施任务：[`IMPLEMENTATION_TASKS.md`](./IMPLEMENTATION_TASKS.md)
- 安全策略：[`SECURITY.md`](./SECURITY.md)
