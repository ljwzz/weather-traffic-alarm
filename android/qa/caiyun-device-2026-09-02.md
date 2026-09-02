# 彩云天气真机验证记录

- 日期：2026-09-02
- 设备：25019PNF3C，Android 16 / API 36
- 应用：0.1.0（versionCode 1）
- APK SHA-256：`0b10b60a620e167586496837a8aa3ac0b21f17ac71057593fec756200a993c48`

## 结果

本轮 `verify-all` 通过（446 个任务），instrumentation 测试包构建通过。

首次实网请求返回 HTTP 429，分类为 `RATE_LIMITED`；Provider 的 HTTP 429 映射已核对。用户报告将 QPS 提高到 8 后，单类只读用例通过：1 个测试、0 失败、耗时 0.554 秒。未读取账号设置，无法验证该调整与复测结果的因果关系。HTTP 429 与 `Retry-After` 的服务端语义见[彩云天气错误信息](https://docs.caiyunapp.com/weather-api/v2/v2.6/tables/errors.html)。

运行证据：

- `credentialStorageReadable=true`
- `hasCaiyunAppKey=true`
- `hasCaiyunSecret=true`
- `distinctCoordinateEndpoints=true`
- `connectionNetwork=true`
- `evaluationSource=NETWORK`
- `evaluationUsable=true`
- `expectedNetworkRequestCount=3`
- `reportTimeEpochMillis=1788332442000`

测试的 finally 校验确认：凭据掩码元数据和本地设置在运行前后完全一致。错误状态仅输出 phase、HTTP 状态和脱敏的 `Retry-After` 信息。

## 界面验证

ADB 点击恢复可用后，已实测：

- 首页的彩云入口可打开天气页。
- 手动刷新成功显示“晴好天气”、“数据时间：2026-09-02 15:00”和“数据来自彩云天气”。地点标签存在，但不记录地点内容。
- 设置 → 接口凭据 → 测试已保存的彩云凭据成功提示“已保存的彩云凭据可用”；状态时间更新为 2026-09-02 15:04。

界面中的已保存凭据测试会按设计更新连接测试元数据；该 UI 阶段不适用前述 instrumentation 的元数据不变性断言。

## 进程重载与缓存验证

系统 events 日志确认应用旧进程已终止，并启动了新实例。新进程进入天气页后手动刷新成功，显示“晴好天气”、“数据时间：2026-09-02 15:06”和“数据来自彩云天气”。该结果确认已保存的凭据连接状态与地点配置可在新进程中重新加载。当前进程的 crash buffer 无记录。

短时断网测试确认默认网络不可用，但设备自动锁屏导致无法完成缓存 UI 状态验证。缓存回退项暂列为待验证，不判定为缺陷。网络开关已恢复并核对：`wifi_on=0`、`mobile_data0=1`、`mobile_data1=1`、`airplane_mode_on=0`。

## 执行方式

用例默认跳过，只有明确传入 `verifyCaiyunNetwork=true` 才会发送请求：

```bash
adb shell am instrument -w -r \
  -e class com.ljwzz.weathertrafficalarm.CaiyunReadOnlyDeviceTest \
  -e verifyCaiyunNetwork true \
  com.ljwzz.weathertrafficalarm.test/androidx.test.runner.AndroidJUnitRunner
```

该命令只执行彩云单类用例。`AndroidJUnitRunner` 的参数化运行方式见[AndroidJUnitRunner 参考](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner)。

## 安全边界

只读 instrumentation 用例仅读取已保存凭据的掩码状态和本地设置；不启动 Activity，不保存或清除凭据，不修改地点、权限、闹钟或设置，不输出密钥、地址或设备序列号。成功评估仅会写入 Provider 的进程内缓存。

界面验证通过应用正常流程测试已保存凭据，因此会更新该凭据的连接测试时间和状态；不记录密钥、地址或设备序列号。

本次实网成功证明已保存凭据、签名请求和双地点天气评估可用；不作为坐标基准或生产发布门禁的关闭依据。
