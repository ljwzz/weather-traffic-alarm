package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljwzz.weathertrafficalarm.core.alarm.check.AlarmCapabilityChecker
import com.ljwzz.weathertrafficalarm.core.alarm.check.CapabilityDiagnostic
import com.ljwzz.weathertrafficalarm.core.alarm.check.CapabilityLevel
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunConnectionTestResult
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunCredentialInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CredentialSettingsScreen(
    status: CredentialStatus,
    onSave: (CredentialInput, onComplete: (String?) -> Unit) -> Unit,
    onClear: (onComplete: (String?) -> Unit) -> Unit,
    onTestAmapWebKey: (((String?) -> Unit) -> Unit)? = null,
    onTestCaiyun: ((CaiyunCredentialInput?, (String?) -> Unit) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var amapWebKey by remember { mutableStateOf("") }
    var amapSdkKey by remember { mutableStateOf("") }
    var caiyunAppKey by remember { mutableStateOf("") }
    var caiyunSecret by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var clearConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(view) {
        val window = context.findActivity()?.window
        val previouslySecure = window?.let {
            it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        } ?: false
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun complete(successMessage: String) = { error: String? ->
        pending = false
        message = error ?: successMessage
        if (error == null) {
            amapWebKey = ""
            amapSdkKey = ""
            caiyunAppKey = ""
            caiyunSecret = ""
        }
    }

    fun testCaiyun(candidate: CaiyunCredentialInput?) {
        if (onTestCaiyun == null) {
            message = "天气服务正在初始化，请稍后重试。"
            return
        }
        pending = true
        message = null
        onTestCaiyun(candidate) { error ->
            pending = false
            message = error ?: if (candidate == null) "已保存的彩云凭据可用" else "连接成功，彩云凭据已加密保存"
            if (error == null && candidate != null) {
                caiyunAppKey = ""
                caiyunSecret = ""
            }
        }
    }

    fun saveAll() {
        val appKey = caiyunAppKey.trim()
        val secret = caiyunSecret.trim()
        if ((appKey.isEmpty()) != (secret.isEmpty())) {
            message = "彩云 App Key 和 Secret 必须同时填写。"
            return
        }
        pending = true
        message = null
        onSave(CredentialInput(amapWebKey, amapSdkKey)) { saveError ->
            if (saveError != null) {
                pending = false
                message = saveError
            } else if (appKey.isEmpty()) {
                complete("凭据已加密保存")(null)
            } else {
                testCaiyun(CaiyunCredentialInput(appKey, secret))
            }
        }
    }

    ScaffoldWithCredentialFooter(
        onSave = ::saveAll,
        onClear = { clearConfirmation = true },
        pending = pending,
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AdvancedNoticeCard(
                    text = if (status.storageError) {
                        "本机凭据无法读取，请清空后重新输入。"
                    } else {
                        "凭据仅加密保存在本机。高德 Web Key 可在保存后单独测试；Android SDK Key 用于地图与定位。"
                    },
                    background = if (status.storageError) ZhituColors.AmberBackground else ZhituColors.Sky,
                    foreground = if (status.storageError) ZhituColors.Amber else ZhituColors.Blue,
                )
            }
            item {
                AdvancedCard {
                    Text("地图、地点与路线 · 高德", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(14.dp))
                    CredentialField(
                        value = amapWebKey,
                        onValueChange = { amapWebKey = it; message = null },
                        label = "高德 Web 服务 Key",
                        placeholder = status.amapWebKeyMask ?: "未配置",
                        supporting = "用于地点搜索、逆地理和路线规划",
                    )
                    Spacer(Modifier.height(10.dp))
                    CredentialField(
                        value = amapSdkKey,
                        onValueChange = { amapSdkKey = it; message = null },
                        label = "高德 Android SDK Key",
                        placeholder = status.amapSdkKeyMask ?: "未配置",
                        supporting = "用于地图展示和单次定位",
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("天气评估 · 彩云", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(14.dp))
                    CredentialField(
                        value = caiyunAppKey,
                        onValueChange = { caiyunAppKey = it; message = null },
                        label = "彩云 App Key",
                        placeholder = status.caiyunAppKeyMask ?: "未配置",
                    )
                    Spacer(Modifier.height(10.dp))
                    CredentialField(
                        value = caiyunSecret,
                        onValueChange = { caiyunSecret = it; message = null },
                        label = "彩云 Secret",
                        placeholder = status.caiyunSecretMask ?: "未配置",
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = status.caiyunTestStateLabel(),
                        color = ZhituColors.Muted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val appKey = caiyunAppKey.trim()
                            val secret = caiyunSecret.trim()
                            if ((appKey.isEmpty()) != (secret.isEmpty())) {
                                message = "彩云 App Key 和 Secret 必须同时填写。"
                            } else if (appKey.isEmpty()) {
                                if (!status.hasCaiyunAppKey || !status.hasCaiyunSecret) {
                                    message = "请先填写并保存彩云 App Key 和 Secret。"
                                } else {
                                    testCaiyun(null)
                                }
                            } else {
                                testCaiyun(CaiyunCredentialInput(appKey, secret))
                            }
                        },
                        enabled = !pending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        val hasCandidate = caiyunAppKey.isNotBlank() || caiyunSecret.isNotBlank()
                        Text(if (pending) "处理中" else if (hasCandidate) "测试并保存彩云凭据" else "测试已保存的彩云凭据")
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            message = null
                            if (!status.hasAmapWebKey) {
                                message = "请先保存高德 Web Key，再测试已保存的凭据。"
                            } else if (onTestAmapWebKey == null) {
                                message = "地图服务正在初始化，请完成专项授权后重试。"
                            } else {
                                pending = true
                                onTestAmapWebKey { error ->
                                    pending = false
                                    message = error ?: "高德 Web Key 可用"
                                }
                            }
                        },
                        enabled = !pending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (pending) "处理中" else "测试已保存的高德 Web Key") }
                }
            }
            message?.let { result ->
                item {
                    AdvancedNoticeCard(
                        text = result,
                        background = if (result == "凭据已加密保存" || result == "凭据已清空") ZhituColors.Mint else ZhituColors.AmberBackground,
                        foreground = if (result == "凭据已加密保存" || result == "凭据已清空") ZhituColors.Brand else ZhituColors.Amber,
                    )
                }
            }
        }
    }

    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("清空本机凭据？") },
            text = { Text("清空后不能恢复；地图、地点、路线和天气功能需要重新配置凭据。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirmation = false
                        pending = true
                        message = null
                        onClear(complete("凭据已清空"))
                    },
                ) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ScaffoldWithCredentialFooter(
    onSave: () -> Unit,
    onClear: () -> Unit,
    pending: Boolean,
    onBack: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) = androidx.compose.material3.Scaffold(
    containerColor = ZhituColors.Background,
    topBar = { ZhituTopBar("数据与凭据", subtitle = "本机加密保存，状态清晰可见", navigation = onBack) },
    bottomBar = {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TonalButton("清空凭据", onClear, Modifier.weight(1f).height(52.dp))
            Button(
                onClick = onSave,
                enabled = !pending,
                modifier = Modifier.weight(1.3f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text(if (pending) "处理中" else "保存凭据") }
        }
    },
    content = content,
)

@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supporting: String? = null,
) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    label = { Text(label) },
    placeholder = { Text(placeholder) },
    supportingText = supporting?.let { { Text(it) } },
    visualTransformation = PasswordVisualTransformation(),
)

@Composable
fun AlarmDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val checker = remember(context) { AlarmCapabilityChecker(context.applicationContext) }
    var diagnostics by remember { mutableStateOf(context.readAlarmDiagnostics(checker)) }

    fun refresh() {
        diagnostics = context.readAlarmDiagnostics(checker)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.material3.Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("权限与诊断", subtitle = "从系统设置返回后自动重新检查", navigation = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AdvancedNoticeCard(
                    text = if (diagnostics.hasFallback) "存在降级能力，闹钟会按当前系统授权条件处理。" else "系统能力满足当前本地闹钟注册条件。",
                    background = if (diagnostics.hasFallback) ZhituColors.AmberBackground else ZhituColors.Mint,
                    foreground = if (diagnostics.hasFallback) ZhituColors.Amber else ZhituColors.Brand,
                )
            }
            item {
                AdvancedCard {
                    Text("通知", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    DiagnosticRow("应用通知", diagnostics.notificationsEnabled.toChineseStatus(), onClick = {
                        context.openSystemSettings(checker.notificationSettingsIntent())
                    })
                    DiagnosticRow("闹钟通知渠道", diagnostics.channelStatus, onClick = {
                        context.openSystemSettings(checker.notificationSettingsIntent())
                    })
                    Text("诊断：${diagnostics.capability.notification.toChineseLabel()}", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AdvancedCard {
                    Text("闹钟与提醒", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    DiagnosticRow("精确闹钟", diagnostics.exactAlarmEnabled.toChineseStatus(), onClick = {
                        context.openSystemSettings(checker.exactAlarmSettingsIntent())
                    })
                    DiagnosticRow("全屏提醒", diagnostics.fullScreenEnabled.toChineseStatus(), onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= 34) {
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(android.net.Uri.parse("package:${context.packageName}"))
                        } else null
                        context.openSystemSettings(intent)
                    })
                    Text("精确闹钟诊断：${diagnostics.capability.exactAlarm.toChineseLabel()}\n全屏提醒诊断：${diagnostics.capability.fullScreenIntent.toChineseLabel()}", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AdvancedCard {
                    Text("声音", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    DiagnosticRow("闹钟音量", diagnostics.alarmVolume, onClick = {
                        context.openSystemSettings(Intent(Settings.Panel.ACTION_VOLUME))
                    })
                }
            }
        }
    }
}

private data class AlarmDiagnosticsState(
    val capability: CapabilityDiagnostic,
    val notificationsEnabled: Boolean,
    val channelStatus: String,
    val exactAlarmEnabled: Boolean,
    val fullScreenEnabled: Boolean,
    val alarmVolume: String,
) {
    val hasFallback: Boolean
        get() = !notificationsEnabled || !exactAlarmEnabled || !fullScreenEnabled ||
            capability.notification != CapabilityLevel.AVAILABLE ||
            capability.exactAlarm != CapabilityLevel.AVAILABLE ||
            capability.fullScreenIntent != CapabilityLevel.AVAILABLE
}

private fun Context.readAlarmDiagnostics(checker: AlarmCapabilityChecker): AlarmDiagnosticsState {
    val notificationManager = getSystemService(NotificationManager::class.java)
    val alarmManager = getSystemService(AlarmManager::class.java)
    val audioManager = getSystemService(AudioManager::class.java)
    val channel = notificationManager?.getNotificationChannel(AlarmCapabilityChecker.ALARM_CHANNEL_ID)
    val channelStatus = when {
        channel == null -> "尚未创建"
        channel.importance == NotificationManager.IMPORTANCE_NONE -> "已关闭"
        else -> "已启用"
    }
    val volume = if (audioManager == null) "不可用" else {
        "${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)}/${audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)}"
    }
    return AlarmDiagnosticsState(
        capability = checker.check(),
        notificationsEnabled = notificationManager?.areNotificationsEnabled() == true,
        channelStatus = channelStatus,
        exactAlarmEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true,
        fullScreenEnabled = Build.VERSION.SDK_INT < 34 || notificationManager?.canUseFullScreenIntent() == true,
        alarmVolume = volume,
    )
}

private fun Context.openSystemSettings(intent: Intent?) {
    if (intent == null || intent.resolveActivity(packageManager) == null) return
    runCatching { startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun DiagnosticRow(title: String, value: String, onClick: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth().height(52.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, Modifier.weight(1f), color = ZhituColors.Ink)
    TextButton(onClick = onClick) { Text(value) }
}

@Composable
private fun AdvancedCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) = Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White),
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
}

@Composable
private fun AdvancedNoticeCard(text: String, background: Color, foreground: Color) = Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = background),
) {
    Text(text, Modifier.fillMaxWidth().padding(20.dp), color = foreground)
}

private fun Boolean.toChineseStatus(): String = if (this) "已启用" else "未启用"

private fun CredentialStatus.caiyunTestStateLabel(): String {
    val result = when (caiyunTestResult) {
        CaiyunConnectionTestResult.PASSED -> "连接测试通过"
        CaiyunConnectionTestResult.FAILED -> "最近连接测试失败"
        CaiyunConnectionTestResult.NEVER_TESTED -> "尚未连接测试"
    }
    val testedAt = caiyunLastTestedAtEpochMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    return if (testedAt == null) result else "$result · $testedAt"
}

private fun CapabilityLevel.toChineseLabel(): String = when (this) {
    CapabilityLevel.AVAILABLE -> "可用"
    CapabilityLevel.DEGRADED -> "降级"
    CapabilityLevel.BLOCKING -> "阻塞"
}
