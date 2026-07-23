# 安全策略

## 禁止

- 提交任何生产凭证（API key、secret、token、密码、证书私钥）到仓库。
- 在日志中输出地址、POI、坐标、令牌或请求/响应 body。
- 在客户端包含后端 API secret 或 Web API key。

## 报告安全问题

请通过以下渠道私下报告安全漏洞：

- 创建 GitHub Issue（标记 `security`），不公开漏洞细节。
- 或发送邮件至项目维护者。

在确认修复前，请不要公开披露漏洞。

## 依赖安全

- 所有依赖版本锁定，不使用 `+`、`latest.release` 或 `SNAPSHOT`。
- Gradle dependency locking 和 verification metadata 已启用。
- 新增依赖须确认许可证兼容性。
