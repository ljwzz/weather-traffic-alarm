# 开发凭据导入验收（2026-09-03）

## 结果

- Node 脚本测试：18 个通过，覆盖配置解析、完整替换输入、安装顺序、设备选择、跳过导入、失败阶段、超时、中断与回执校验。
- `CredentialStoreTest`：16 个通过，覆盖原有保存行为、完整替换及加密／写入／清除失败后保留旧值。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`：通过。
- 真机隔离测试：7 个通过；2 个导入入口在未显式启用时按预期跳过。
- 真实安装脚本、ADB 标准输入、FIFO 和 Keystore 串联验证：非空输入与全空输入均通过。验证使用内存生成的假值，并将导入目标限定为 `importFromPipeIntoIsolatedStore`，凭据目录与日常使用目录隔离。
- 验证前后日常凭据密文摘要一致；没有残留 FIFO 或隔离凭据目录。未读取或输出真实凭据。
- 解压检查主 APK 和测试 APK：没有运行时生成的假凭据或 `.env` 文件；主 APK 不含 `CredentialImportDeviceTest`。
- `.env` 权限为 `0600` 且被 Git 忽略，`.env.example` 可提交；`git diff --check` 通过。

## 定向验证命令

```bash
node --test scripts/tests/*.test.mjs
cd android
./gradlew :core:data:testDebugUnitTest --tests '*CredentialStoreTest' --offline --console=plain
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
adb -s <设备序列号> shell am instrument -w -r \
  -e class com.ljwzz.weathertrafficalarm.CredentialImportPipeDeviceTest,com.ljwzz.weathertrafficalarm.CredentialImportDeviceTest \
  com.ljwzz.weathertrafficalarm.test/androidx.test.runner.AndroidJUnitRunner
```

最后一条命令未传 `importCredentials=true`，因此只运行隔离用例；两个导入入口不会修改凭据。真实传输验收另以显式开关运行隔离入口，复用宿主导入函数，仅将固定测试方法切换为 `importFromPipeIntoIsolatedStore`。开发使用命令见 [配置与凭据](../../docs/configuration.md)。

本次验证本地保存与传输行为；高德和彩云的真实服务鉴权须使用用户填写的凭据单独执行连接测试。

## 接口依据

- `.env` 文件覆盖顺序：https://vite.dev/guide/env-and-mode
- Node.js `.env` 解析：https://nodejs.org/api/util.html
- Android FIFO、非阻塞读与 poll：https://developer.android.com/reference/android/system/Os
- 指定 instrumentation 方法及参数：https://developer.android.com/studio/test/command-line?hl=en
