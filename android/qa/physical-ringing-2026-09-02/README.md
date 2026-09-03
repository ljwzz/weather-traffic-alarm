# 真机响铃调试（2026-09-02）

## 设备与安装

- USB 真机：Xiaomi `25019PNF3C` / `xuanyuan`，Android 16、API 36、系统属性 `OS3.0`。
- 物理屏幕：1440 × 3200 px，density 600；保持设备原显示配置。
- 以 `adb install -r` 覆盖安装主 APK 和测试 APK，均返回 `Success`。没有卸载应用或清空数据。ADB 的 `-r` 保留数据语义：https://developer.android.com/tools/adb?hl=zh-CN
- 主 APK SHA-256：`7f35e40a9350efba31db8934b708368cfa757c1bed006ea5f3cf62c2537629d1`。

## 权限预检

- 安装前本应用没有运行中的响铃服务。
- 通知已授权，`POST_NOTIFICATION=allow`；`USE_EXACT_ALARM` 已授予。
- 闹钟音量读取值为 6，`zen_mode=0`；本次没有修改音量或勿扰设置。
- Android 全屏提醒原为 `USE_FULL_SCREEN_INTENT=ignore`；经用户授权后设置并读回 `allow`。
- 小米“其他权限”页面初始实读：“锁屏显示”和“后台弹出界面”均为“拒绝”；用户手动修改后，两项 AppOps 读回均为 `allow`。同一主 APK 复跑后测试页面可正常进入。
- 本应用通知页实读：允许通知、悬浮通知、锁屏通知、发声与振动均已开启。

全屏提醒的检查与授权依据：https://developer.android.com/reference/android/app/NotificationManager.html 。小米独立锁屏显示授权见其开发文档 https://dev.mi.com/docs/appsmarket/technical_docs/adaptation_FAQ/ （该文档为旧 MIUI 说明；本机开关与状态以上述现场读取为准）。

## 验证结果

- 首次 8 项测试停在首个纯 Compose 用例：`ComponentActivity` 启动请求返回 102，页面未进入前台。该轮已主动结束，没有创建测试闹钟，不能计为通过。
- 已新增独立 `LockedRingingDeviceTest`：真实注册独立计划后熄屏，等待系统自然启动响铃页，不预先启动 Activity；仅清理自身计划，核对其他计划未被修改。该测试默认跳过，只有显式传入 `-e runLockedRinging true` 才会熄屏执行。
- 权限就绪后，6 项 Compose 界面测试、真实响铃／活动播放配置／停止／Activity 重建，以及 1 分钟贪睡再次响铃，共 **8 项通过**，耗时 104.842 秒。
- 锁屏测试首轮因用户手动解锁，未满足持续锁屏条件，不能据此判定应用失败；该轮自有计划已删除。保持锁定后重跑 **1 项通过**，耗时 24.474 秒。
- 合计 **9 项真机验证通过**。另验证不传 opt-in 参数时锁屏用例明确跳过（0.036 秒），不计入 9 项通过数。
- 锁屏成功回执：`alarmTriggered=true`、`keyguardLockedAtTrigger=true`、`fullScreenPresented=true`、`stopped=true`、`ownedPlanDeleted=true`；测试前非自有计划数为 0，结束后比较一致。
- 结束后未发现本应用响铃服务，音量仍为 6、`zen_mode` 仍为 0；保留获授权的应用显示权限和最新 APK。仅移除了本次权限检查用的临时 XML，以及测试自身创建的闹钟计划。

模拟器结果见 [`原生响铃验收`](../native-ringing-2026-09-02/README.md)，与本轮真机结果分开计数。

## 复现命令

在 `android/` 编译测试 APK：

```sh
./gradlew :app:assembleDebugAndroidTest --offline --console=plain --quiet
```

仅连接一台实体手机后，保持解锁执行基础测试：

```sh
adb -d shell am instrument -w -r -e class 'com.ljwzz.weathertrafficalarm.ui.zhitu.RingingScreenDeviceTest,com.ljwzz.weathertrafficalarm.NativeRingingActivityDeviceTest#scheduledAlarmRingsStopsAndKeepsStoppedReceiptAfterRecreate,com.ljwzz.weathertrafficalarm.NativeRingingActivityDeviceTest#snoozeCreatesRealChildWhichReturnsToTheSameActivityAndRingsAgain' com.ljwzz.weathertrafficalarm.test/androidx.test.runner.AndroidJUnitRunner
```

单独运行锁屏验证（会熄屏；直到自动停止和清理结束前不要解锁）：

```sh
adb -d shell am instrument -w -r -e runLockedRinging true -e class com.ljwzz.weathertrafficalarm.LockedRingingDeviceTest com.ljwzz.weathertrafficalarm.test/androidx.test.runner.AndroidJUnitRunner
```

## 截图

应用布局和完整设备截图均来自本轮真机，保持原始屏幕分辨率；共 12 张。

| 状态 | 应用布局 | 完整设备 |
| --- | --- | --- |
| 基础响铃 | [布局](./basic-firing-content.png) | [设备](./basic-firing.png) |
| 已停止 | [布局](./basic-stopped-content.png) | [设备](./basic-stopped.png) |
| 已贪睡 | [布局](./basic-snoozed-content.png) | [设备](./basic-snoozed.png) |
| 贪睡后再次响铃 | [布局](./snooze-firing-content.png) | [设备](./snooze-firing.png) |
| 锁屏自动全屏响铃 | [布局](./locked-firing-root.png) | [设备](./locked-firing-device.png) |
| 锁屏停止结果 | [布局](./locked-stopped-root.png) | [设备](./locked-stopped-device.png) |

## 边界

- 本次只验证此机型、当前系统与权限配置；未执行重启未解锁、长期待机、Doze 或完整厂商矩阵。
- 自动化已确认 `USAGE_ALARM` 活动播放配置。实际听感和物理振动仍待用户人工确认，不以页面或播放状态代替。
- 本轮没有改动生产业务代码或应用界面；新增的是默认关闭的自然锁屏测试与验收记录。
