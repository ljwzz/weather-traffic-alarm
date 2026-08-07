# 开发环境记录

- 日期：2026-08-07
- macOS 架构：darwin/arm64
- JDK：Corretto-21.0.12.8.1 (OpenJDK 21.0.12 LTS)
- Git：2.55.0
- Android SDK：Platform 36、Build Tools 36.0.0、platform-tools、模拟器（如缺失记录为阻塞项）

## 说明

- 本仓库为纯 Android 工程，不需要 Docker 或任何本地服务。
- Android 构建与验证：`./scripts/verify-all.sh`。
- 缺失工具只记录阻塞项，不把本机绝对路径写入 Gradle 文件。
