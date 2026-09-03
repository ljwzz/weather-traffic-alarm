# 安全策略

## 凭证保护

- 高德（Web 服务 Key、Android SDK Key）与彩云（App Key、App Secret）凭证由用户在应用内配置，或通过开发脚本导入 debug App，**禁止写入源码、构建产物、version catalog、manifest 或任何文档**。
- 开发凭据可保存在根目录 `.env`、`.env.local`；真实文件必须由 Git 忽略，提交的 `.env.example` 仅含空值。首次创建本地模板使用 `0600` 权限，已有文件原样保留。
- 手机端凭证使用 Android Keystore 生成的不可导出密钥和 AES-GCM 加密，密文存应用私有目录，明文不落磁盘。开发导入只通过标准输入与应用私有 FIFO 传输明文，不把值传入命令参数、子进程环境变量或临时普通文件。
- 导入入口仅存在于 debug 对应的测试 APK，须由脚本显式启用；导入完整替换全部凭据，写入失败保留旧值，彩云验证状态重置为未验证。正常退出与可处理的异常均清理 FIFO，进程被强制终止时残留 FIFO 节点不保存凭据内容。
- 凭证存储必须排除在系统备份/迁移之外；备份恢复后设备上不允许残留凭证。
- 凭证配置页禁止截图与录屏（`FLAG_SECURE`）；凭证不进入日志、崩溃堆栈、诊断记录或任何导出内容。

## 禁止

- 提交任何生产凭证（API key、secret、token、密码、证书私钥）到仓库。
- 在日志中输出地址、POI、坐标、令牌、请求/响应 body 或任意第三方密钥。
- 在应用包内硬编码第三方 API 密钥。

## 报告安全问题

请通过以下渠道私下报告安全漏洞：

- 创建 GitHub Issue（标记 `security`），不公开漏洞细节。
- 或发送邮件至项目维护者。

在确认修复前，请不要公开披露漏洞。

## 依赖安全

- 所有依赖版本锁定，不使用 `+`、`latest.release` 或 `SNAPSHOT`。
- Gradle dependency locking 和 verification metadata 已启用。
- 新增依赖须确认许可证兼容性（如 holiday-cn 为 MIT，数据真值归国务院公告，见 SPEC 2.1）。
