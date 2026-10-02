# 项目验证

本文件维护可重复执行的验证流程；产品行为以 [SPEC.md](../SPEC.md) 为准，测试输入和断言随实现维护在源码中。

## 日常回归

在仓库根目录执行：

```bash
./scripts/verify-all.sh
```

该入口调用 [verify-android.sh](../scripts/verify-android.sh)，构建 Debug APK 并执行 JVM 测试。改动涉及特定模块时先运行对应测试；需要确认本次实际执行时使用 `--rerun`：

```bash
./android/gradlew -p android :app:testDebugUnitTest --tests '*受影响的测试类' --rerun
```

安装、配置和凭据导入脚本的回归入口：

```bash
node --test scripts/tests/*.test.mjs
```

## 设备验证

界面、系统权限、通知、响铃、地图与真实网络行为在适用的模拟器或物理设备上执行。按改动选择相关设备用例：

```bash
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=完整测试类名
```

需要在安装后配置设备权限时，先构建并安装，再执行同一个 instrumentation 用例：

```bash
./android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest
device_serial="设备序列号"
adb -s "$device_serial" install -r -t android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$device_serial" install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 此处检查并配置设备所需权限。
adb -s "$device_serial" shell am instrument -w -r \
  -e class 完整测试类名 \
  com.ljwzz.weathertrafficalarm.test/androidx.test.runner.AndroidJUnitRunner
```

MIUI／HyperOS 上若 Activity 启动被拒，检查后台弹出界面授权；重装后重新检查。需要凭据、联网或特殊设备状态的用例按其显式开关运行，前置条件不满足时说明验证范围。

## 判定与维护

- 先执行受影响的回归，再运行整体入口。已有结果只有在对应实现与测试输入未变时才可复用；零用例、失败或条件跳过不计为通过。
- 测试显式建立时钟、日期、配置和存储状态；交互前滚动并确认目标可见，按状态等待异步完成。
- 设备验证明确设备、运行范围、实际通过与失败情况；受控替身和离线原型的结果只证明各自覆盖的行为。测试结束后清理自建数据并恢复临时设备设置。
- 新增的行为断言进入正式测试；可复用的平台边界进入规格或本文件。运行日志、报告和按需截图使用被 Git 忽略的构建目录或系统临时目录，完成后清理。
- 文档改动检查本地链接和 `git diff --check`。涉及凭据的操作遵循 [SECURITY.md](../SECURITY.md)。
