# Android 权限引导验收

日期：2026-09-03。范围：首次启用、系统权限诊断、小米手工确认、单次前台位置申请与设置恢复。

提交前已另行导出权限暂存快照：APK／测试 APK 构建通过，218 项 JVM 测试通过，见[提交快照验证](./commit-check.md)。以下 233 项与设备记录来自开发阶段完整工作区，不能当作同一 APK 的结果。

## 开发工作区构建与单元测试

```bash
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest --offline --console=plain --quiet
```

构建通过，233 项 JVM 测试通过，0 失败、0 跳过（app 34、alarm 47、data 59、map 11、model 44、network 38）。包含启用流程取消／检查回程／单次消费／失败恢复、粗精定位和服务关闭、系统设置回退、通知渠道初始创建与保留用户配置。

安装包：[app-debug.apk](../../app/build/outputs/apk/debug/app-debug.apk)。SHA-256：`f0ae1f711ef6f83faca12aa69c7f174688b3722140a6e796d76e037373e06105`。

## 设备验证

使用本轮启动的 `Mi_15_Ultra` AVD 只读实例 `emulator-5556`，Android 16 / API 36，系统制造商为 Google。AVD 名称不代表小米系统。模拟器以 `-read-only -no-snapshot-save -no-audio` 运行，不保存本轮设置和数据变化。

独立 Compose 测试覆盖首次启用引导、通用 Android 诊断、小米确认文案、设置按钮与返回回调。完整应用测试使用 UUID 自有计划，清理时仅删除该测试计划并恢复原设置；不修改其他计划或凭据。

权限专项 7 项通过，0 失败、0 跳过，完整输出见 [device-tests.txt](./device-tests.txt)：

- 引导的检查／继续／取消独立动作。
- 通用 Android 不展示小米专属项；小米长确认状态、手工确认与返回按钮。
- 完整应用：通知未授权时启用前提示，取消不写入，诊断回程及 Activity 重建后保留草稿，继续只保存一份计划。
- 定位：首次主动点按才展示用途说明，取消不定位；未同意高德时阻断请求。

另完成既有闹钟编辑 2 项、响铃界面 6 项回归，8/8 通过，输出见 [regression-tests.txt](./regression-tests.txt)。本轮共 15 项模拟器测试通过。编辑测试等待条件已改为确认离开编辑页后再查询列表，避免把草稿输入文字误认为已保存结果。

### 截图

- [实际应用启用引导](./permission-qa/permission-guide-integrated.png)
- [通用 Android 诊断](./permission-qa/permission-diagnostics-general.png)
- [小米手工确认分支（测试快照）](./permission-qa/permission-diagnostics-xiaomi.png)
- [定位用途说明](./permission-qa/location-permission-purpose.png)

已复核弹层按钮、长文案与底部安全区域。定位截图在弹层动画稳定后采集。

## 实现边界与依据

- 标准 Android 读取真实权限、通知总开关、渠道与全屏能力；设置返回重新检查。官方文档：https://developer.android.com/develop/ui/compose/notifications/notification-permission https://developer.android.com/develop/background-work/services/alarms https://developer.android.com/about/versions/14/behavior-changes-14
- 小米通用权限页采用 `miui.intent.action.APP_PERM_EDITOR`、`CATEGORY_DEFAULT`、`extra_pkgname` 的兼容尝试；失败回退到应用详情。小米锁屏显示与后台弹出界面只记录会话内用户确认，明确标注未自动核验。官方边界：https://dev.mi.com/docs/appsmarket/technical_docs/adaptation_FAQ/ https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625
- Manifest 仅增加小米权限页 intent 的窄范围可见性查询，用于解析设置入口。官方文档：https://developer.android.com/training/package-visibility/declaring
- 定位只从“使用当前位置”发起，接受粗略位置，设置恢复只续办当前请求。官方文档：https://developer.android.com/develop/sensors-and-location/location/permissions/runtime
- 上述自动化设备测试覆盖通用 Android 模拟器；后续小米 `OS3.0.312.0.WOACNXM` 真机已完成实际入口、手工确认和返回验证。获准临时切换后还验证了通知申请／拒绝恢复、位置申请／拒绝恢复入口及小米两项拒绝状态，见[真机记录](../physical-permissions-2026-09-03/README.md)。定位从设置授权后续办未完成观察；中断后小米两项最终恢复核对待解锁完成，其他系统版本仍需验证。高德实际位置精度与实网返回不由权限状态机测试证明。
