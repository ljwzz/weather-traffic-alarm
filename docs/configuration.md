# 配置与凭据

## 当前配置

- 闹钟、日期规则、铃声、振动、贪睡、常用地点文字和出行方式均由应用本地保存；无需高德或彩云凭据即可创建并响铃闹钟。
- 高德与彩云凭据可在应用的「数据与凭据」页输入；开发时也可从根目录 `.env` 导入 debug App。
- 高德已实现首次专项授权、运行时 Web Service Key 与 Android SDK Key、Android Keystore 加密存储和连接测试；待用户提供两项真实 Key 后完成设备实网验收。原型仅演示离线 fixture，不发请求、不使用真实 Key。
- 彩云开发导入仅保存凭据，不代表连接验证成功。导入后在凭据页执行连接测试，再使用手动天气预览。

## 本地凭据文件

根目录 `.env.example` 是可提交的空值模板；`.env`、`.env.local` 以及其他根目录 `.env.*` 真实配置文件均由 Git 忽略。首次准备本地 `.env` 时使用 `0600` 权限，已有文件不覆盖。真实值仅在开发机本地填写。

```dotenv
AMAP_WEB_KEY=
AMAP_SDK_KEY=
CAIYUN_APP_KEY=
CAIYUN_APP_SECRET=
```

读取 `.env` 后再读取 `.env.local`，后者覆盖同名字段，空值也会覆盖；根目录 `.env` 必须存在。此顺序参照 https://vite.dev/guide/env-and-mode 。解析使用 Node.js `util.parseEnv`，支持空行、注释、引号和可选的 `export` 前缀，不执行 shell 命令或变量展开。仅接受上面四个配置名；凭据值须为单行文本，不包含控制字符，带引号的值须在同一行闭合。API 依据 https://nodejs.org/api/util.html 。

**完整替换规则：导入时未配置或空白的字段会清空，四项全部为空会清空手机全部凭据。** 彩云的连接验证状态每次重置为“未验证”。保存失败保留原有凭据。文件不会被复制进 APK，也不会被加载进构建进程的环境变量。

## 安装与导入

需要 Node.js 24.10+、JDK 21、项目要求的 Android SDK，以及 `PATH` 中可用的 `adb`。连接并授权用于开发的 Android 设备，在仓库根目录运行：

```bash
node scripts/install-debug.mjs
```

脚本在构建与安装前检查配置，然后构建 debug App 和测试 APK、覆盖安装，最后完整导入凭据。首次创建的空模板应先填写；确需清空时可保留空值执行导入。多设备必须明确选择：

```bash
node scripts/install-debug.mjs --serial <设备序列号>
```

重新导入已安装的 debug App（目标设备须已安装匹配的测试 APK）：

```bash
node scripts/import-debug-credentials.mjs --serial <设备序列号>
```

仅安装主 App，不读取 `.env`、不改变已有凭据：

```bash
node scripts/install-debug.mjs --skip-credentials
```

`--skip-credentials` 不清空已有手机数据；全新安装或使用凭据页的清空入口后可验证空配置。

配置缺失或格式错误、设备未授权、设备不唯一、构建或安装失败、导入超时均以非零状态退出。安装和导入分别报告状态；若安装成功而导入失败，修正问题后重新运行单独导入命令。输出仅包含固定状态、错误类别和凭据是否存在，不输出实际值。

## 传输与验证

脚本只启动指定的 instrumentation 导入用例，通过显式开关启用。ADB 标准输入将 JSON 送入应用私有目录的临时 FIFO；Android 在内存中读取后调用 Keystore 存储。数据不进入 shell 参数、日志或普通明文临时文件。传输设置大小与超时限制，并在退出时清理 FIFO。系统接口依据 https://developer.android.com/reference/android/system/Os ，instrumentation 命令依据 https://developer.android.com/studio/test/command-line?hl=en 。

```bash
node --test scripts/tests/*.test.mjs
cd android
./gradlew :core:data:testDebugUnitTest --tests '*CredentialStoreTest' :app:assembleDebug :app:assembleDebugAndroidTest
```

设备传输测试使用独立目录、隔离的凭据存储和假值，不覆盖日常使用的凭据。真实导入成功仅说明本地保存完成；高德和彩云服务可用性仍以各自连接测试为准。

已完成的构建、定向测试及真机隔离传输结果见 [2026-09-03 验收记录](../android/qa/credential-import-2026-09-03.md)。

## 凭据保护

- 使用 Android Keystore 管理应用密钥；官方说明该机制让密钥材料不进入应用进程，并可限制授权用途。https://developer.android.com/privacy-and-security/keystore
- 手机端明文只存在于使用凭据所需的短生命周期内存；开发机允许使用上述 Git 忽略的 `.env` 文件。清空只影响凭据，不修改闹钟、日期覆盖、地点或出行方式。
- 密文与相关偏好必须排除系统备份；凭据页禁止截图；日志、异常、诊断和导出不得含密钥、令牌、地址、坐标或铃声 URI。
