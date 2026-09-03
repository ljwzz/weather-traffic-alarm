# 权限提交快照验证

日期：2026-09-03。从 Git 暂存区导出独立临时目录，保留仓库已提交的 AGP `9.3.2`，只加入本次权限任务的代码与测试。

## 构建与 JVM 测试

```bash
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest --offline --console=plain --quiet
```

- 命令退出码为 0；Debug APK 与测试 APK 构建通过。
- 218 项 JVM 测试通过，0 失败、0 错误、0 跳过。
- 按模块：app 28、alarm 38、data 59、map 11、model 44、network 38。数量由临时目录中的 JUnit XML 汇总。
- 此快照 APK SHA-256：`708c657cda3260844a21edbc1d6bd872184a0225ca4f203a172d23ff20d25fe4`。
- 暂存差异空白检查通过；权限源文件与测试的交叉调用已核对。

## 验证边界

本次提交检查只构建和运行 JVM 测试，没有将该临时 APK 安装到手机。开发阶段完整工作区的 233 项 JVM、模拟器和小米真机记录保留在[原验收记录](./README.md)，对应 APK 哈希与本快照不同，不合并为同一包的设备验证结果。

小米两项显示权限的中断后恢复核对仍待解锁完成；定位从系统设置授权返回后的续办未完成观察，详见[真机记录](../physical-permissions-2026-09-03/README.md)。
