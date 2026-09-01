# 知途（weather-traffic-alarm）

一个纯 Android、本地优先的闹钟应用。本 App 创建、注册和响铃本地闹钟，支持单次、每周和工作日规则，以及铃声、振动和贪睡。首次安装没有预置闹钟或记录。

页面级需求以当前 Figma 设计稿为主；开发和界面验收必须参照本地 [`prototype/`](./prototype/)。非视觉业务与安全规则以 [`SPEC.md`](./SPEC.md) 为准，冲突时先向用户确认。

**包名 / applicationId：** `com.ljwzz.weathertrafficalarm`

## 架构

- **纯 Android 本地优先**：无后端；计划、实例、记录、设置和日历覆盖只存本机。
- **本地响铃**：通过系统闹钟能力注册下一次实例，Receiver 与前台响铃服务按实例 ID 处理停止和贪睡。
- **本地凭据**：高德和彩云凭据可加密保存、清除；当前不发起 Provider 请求，连接测试不会返回成功。
- **未接入容器**：地图、路线和天气保持页面入口与留白提示，不显示模拟数据。路线与天气的 Provider 接口保留为后续能力。

## 目录结构

```
weather-traffic-alarm/
├── android/                     # Android 工程（Gradle 多模块）
│   ├── app/                     # 应用壳
│   ├── core/model/              # 领域模型与纯计算（无 Android 依赖）
│   ├── core/data/               # Room、DataStore、凭证存储、日历缓存、仓库
│   ├── core/network/            # 后续 Provider HTTP 接口；当前不由 App 使用
│   ├── core/alarm/              # 精确闹钟、响铃、状态机、快照
│   ├── core/map/                # 地图能力占位；当前无 SDK 依赖
│   └── feature/*                # 功能模块骨架；当前页面实现位于 app/ui/zhitu
├── docs/                        # 环境与配置记录
└── scripts/                     # 验证脚本
```

## 功能要点

- 闹钟 CRUD：名称、日期、时间、单次／每周／工作日规则、启停、删除。
- 真实状态：待授权、已注册、注册失败、已完成；首页显示下一次有效闹钟，记录按日期和结果筛选。
- 响铃：系统铃声、振动、停止和默认 10 分钟贪睡；同一时刻多个闹钟独立处理。
- 日历：真实月份、日期选择与按计划按日期覆盖；无节假日数据时按周一至周五兜底。
- 设置与诊断：本地设置持久化、常用地点文字管理、出行方式保存，以及 Android 权限／音量状态检查入口。

## 最低工具版本

- JDK 21
- Android SDK Platform 37 + Build Tools 36.0.0
- Gradle 9.6.1（wrapper）

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
