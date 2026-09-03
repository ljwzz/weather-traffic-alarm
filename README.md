# 知途（weather-traffic-alarm）

一个纯 Android、本地优先的闹钟应用。本 App 创建、注册和响铃本地闹钟，支持单次、每周和工作日规则，以及铃声、振动和贪睡。首次安装没有预置闹钟或记录。

页面级需求以当前 Figma 设计稿为主；开发和界面验收必须参照本地 [`prototype/`](./prototype/)。非视觉业务与安全规则以 [`SPEC.md`](./SPEC.md) 为准，冲突时先向用户确认。

**包名 / applicationId：** `com.ljwzz.weathertrafficalarm`

## 架构

- **纯 Android 本地优先**：无后端；计划、实例、记录、设置和日历覆盖只存本机。
- **本地响铃**：通过系统闹钟能力注册下一次实例，Receiver 与前台响铃服务按实例 ID 处理停止和贪睡。
- **高德已接入**：Android 已实现专项授权、加密运行时 Web Service Key／Android SDK Key、地图、单次定位、POI／输入提示、五种路线、最多三条候选、路况与计划覆盖。待用户提供两项真实 Key 后完成设备实网验收。
- **高德接入契约**：原型覆盖地图、输入提示／POI、地图选点、单次定位、五种路线、最多三条备选与当前路况的成功、加载、无 Key、拒绝、错误 fixture；不会发送请求、使用真实 Key 或输出坐标。
- **彩云天气已接入**：Android 已实现候选凭证连接测试、双地点小时天气评估、15 分钟故障缓存和手动天气预览；设备实网与界面验证结果见 [2026-09-02 验证记录](./android/qa/caiyun-device-2026-09-02.md)。天气 Provider 只返回天气结果；统一评估协调器接通工作日、路线、天气和独立提前提醒。v2.6 鉴权契约见 https://docs.caiyunapp.com/weather-api/v2/v2.6/auth.html 。

## 目录结构

```
weather-traffic-alarm/
├── android/                     # Android 工程（Gradle 多模块）
│   ├── app/                     # 应用壳
│   ├── core/model/              # 领域模型与纯计算（无 Android 依赖）
│   ├── core/data/               # Room、DataStore、凭证存储、日历缓存、仓库
│   ├── core/network/            # 高德／天气 Provider HTTP 接口
│   ├── core/alarm/              # 精确闹钟、响铃、状态机、快照
│   ├── core/map/                # 高德地图能力适配层
│   └── feature/*                # 功能模块骨架；当前页面实现位于 app/ui/zhitu
├── docs/                        # 环境与配置记录
└── scripts/                     # 验证脚本
```

## 功能要点

- 闹钟 CRUD：名称、日期、时间、单次／每周／工作日规则、启停、删除。
- 真实状态：待授权、已注册、注册失败、已完成；首页显示下一次有效闹钟，记录按日期和结果筛选。
- 响铃：系统铃声、振动、停止和默认 10 分钟贪睡；同一时刻多个闹钟独立处理。
- 日历：真实月份、日期选择与按计划按日期覆盖；无节假日数据时按周一至周五兜底。
- 自动评估：已启用且配置通勤的计划按计划时区 19:00 后评估次日，有限重试并在 23:30 截止；过期结果不应用。基础闹钟与提前提醒分别调度。
- 决策记录：保留最近 30 天计算分解、缓存／日历来源、失败原因及真实应用状态。
- 设置与诊断：本地设置持久化、常用地点文字管理、出行方式保存，以及 Android 权限／音量状态检查入口。

## 最低工具版本

- JDK 21
- Android SDK Platform 37 + Build Tools 36.0.0
- Gradle 9.6.1（wrapper）
- Node.js 24.10+（开发安装与凭据导入脚本）

## 开发安装与凭据导入

在根目录 `.env` 中填写高德、彩云凭据，字段见 [`.env.example`](./.env.example)。`.env.local` 可覆盖同名字段；真实配置已由 Git 忽略。

```bash
node scripts/install-debug.mjs
node scripts/import-debug-credentials.mjs
node scripts/install-debug.mjs --skip-credentials
```

第一条命令构建、覆盖安装 debug App 和测试 APK，再自动导入；第二条仅重新导入；第三条仅安装 App。多设备时增加 `--serial <设备序列号>`。

**每次导入完整替换手机凭据：缺失或空白字段会清空，四项全空会清空全部凭据。** 彩云导入后需在凭据页执行连接测试。文件语法、故障处理与验证命令见 [配置与凭据](./docs/configuration.md)。

## 验证

```bash
./scripts/verify-all.sh
```

Debug APK、测试明细和截图见 [`android/qa/README.md`](./android/qa/README.md)。

## 文档

- 产品与技术规格：[`SPEC.md`](./SPEC.md)
- 可执行实施任务：[`IMPLEMENTATION_TASKS.md`](./IMPLEMENTATION_TASKS.md)
- 设计与原型交接：[`docs/design-handoff.md`](./docs/design-handoff.md)
- 安全策略：[`SECURITY.md`](./SECURITY.md)
- 本地原型：[`prototype/README.md`](./prototype/README.md)
