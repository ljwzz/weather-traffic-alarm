package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialEditorKeys
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunCredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshDiagnostic
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityCheck
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEvent
import kotlinx.coroutines.delay

private enum class CredentialTestProvider { AMAP, CAIYUN }

@Composable
fun CredentialSettingsScreen(
    status: CredentialStatus,
    onLoadKeys: suspend () -> CredentialEditorKeys,
    onSave: (CredentialInput, onComplete: (String?) -> Unit) -> Unit,
    onClear: (onComplete: (String?) -> Unit) -> Unit,
    onTestAmapWebKey: ((String, (String?) -> Unit) -> Unit)? = null,
    onTestCaiyun: ((CaiyunCredentialInput?, (String?) -> Unit) -> Unit)? = null,
    onSaveCaiyun: ((CaiyunCredentialInput?, (String?) -> Unit) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var amapWebKey by remember { mutableStateOf("") }
    var amapSdkKey by remember { mutableStateOf("") }
    var caiyunAppKey by remember { mutableStateOf("") }
    var savedCaiyunAppKey by remember { mutableStateOf("") }
    var caiyunSecret by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var testingProvider by remember { mutableStateOf<CredentialTestProvider?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageSuccess by remember { mutableStateOf(false) }
    var messageRevision by remember { mutableStateOf(0) }
    LaunchedEffect(message, messageRevision) {
        if (message != null) { delay(60_000); message = null }
    }
    var clearConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(status.loaded, status.storageError, status.amapWebVersion, status.amapSdkVersion, status.caiyunVersion) {
        if (status.loaded && !status.storageError) {
            runCatching { onLoadKeys() }
                .onSuccess { keys ->
                    amapWebKey = keys.amapWebKey
                    amapSdkKey = keys.amapSdkKey
                    caiyunAppKey = keys.caiyunAppKey
                    savedCaiyunAppKey = keys.caiyunAppKey
                }
                .onFailure { message = "凭据读取失败，请重试。" }
        }
    }

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
        messageSuccess = error == null
        message = error ?: successMessage
        if (error == null) {
            caiyunSecret = ""
            focusManager.clearFocus()
        }
    }

    fun testCaiyun(candidate: CaiyunCredentialInput?, save: Boolean = false) {
        val action = if (save) onSaveCaiyun else onTestCaiyun
        messageSuccess = false
        if (action == null) {
            message = "天气服务正在初始化，请稍后重试。"
            return
        }
        pending = true
        message = null
        testingProvider = if (save) null else CredentialTestProvider.CAIYUN
        action(candidate) { error ->
            testingProvider = null
            pending = false
            messageSuccess = error == null
            message = error ?: if (save) "彩云凭据已保存" else "彩云连接测试成功"
            if (error == null && save && candidate != null) {
                caiyunSecret = ""
            }
            if (error == null) focusManager.clearFocus()
        }
    }

    fun saveAll() {
        messageRevision++
        messageSuccess = false
        val appKey = caiyunAppKey.trim()
        val secret = caiyunSecret.trim()
        val appKeyChanged = appKey != savedCaiyunAppKey
        if ((appKeyChanged && secret.isEmpty()) || (secret.isNotEmpty() && appKey.isEmpty())) {
            message = "彩云 App Key 和 Secret 必须同时填写。"
            return
        }
        pending = true
        message = null
        onSave(CredentialInput(amapWebKey, amapSdkKey)) { saveError ->
            if (saveError != null) {
                pending = false
                message = saveError
            } else if (secret.isEmpty()) {
                complete("凭据已加密保存")(null)
            } else {
                testCaiyun(CaiyunCredentialInput(appKey, secret), save = true)
            }
        }
    }

    ScaffoldWithCredentialFooter(
        onSave = ::saveAll,
        onClear = { if (!pending) clearConfirmation = true },
        pending = pending,
        onBack = onBack,
        notice = {
            message?.let { result ->
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    AdvancedNoticeCard(
                        text = result,
                        background = if (messageSuccess) ZhituColors.Mint else ZhituColors.AmberBackground,
                        foreground = if (messageSuccess) ZhituColors.Brand else ZhituColors.Amber,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status.storageError) {
                item { AdvancedNoticeCard("本机凭据无法读取，请清空后重新输入。", ZhituColors.AmberBackground, ZhituColors.Amber) }
            }
            item {
                AdvancedCard {
                    Text("地图、地点与路线 · 高德", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(14.dp))
                    CredentialField(
                        value = amapWebKey,
                        onValueChange = { if (!pending) { amapWebKey = it; message = null } },
                        label = "高德 Web 服务 Key",
                        placeholder = "未配置",
                        supporting = "用于地点搜索、逆地理和路线规划",
                    )
                    Spacer(Modifier.height(10.dp))
                    CredentialField(
                        value = amapSdkKey,
                        onValueChange = { if (!pending) { amapSdkKey = it; message = null } },
                        label = "高德 Android SDK Key",
                        placeholder = "未配置",
                        supporting = "用于地图展示和单次定位",
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            messageRevision++
                            message = null
                            messageSuccess = false
                            if (amapWebKey.isBlank()) {
                                message = "请填写高德 Web Key。"
                            } else if (onTestAmapWebKey == null) {
                                message = "地图服务正在初始化，请完成专项授权后重试。"
                            } else {
                                pending = true
                                testingProvider = CredentialTestProvider.AMAP
                                onTestAmapWebKey(amapWebKey.trim()) { error ->
                                    testingProvider = null
                                    pending = false
                                    messageSuccess = error == null
                                    message = error ?: "高德连接测试成功"
                                }
                            }
                        },
                        enabled = !pending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (testingProvider == CredentialTestProvider.AMAP) "处理中" else "测试连接") }
                    Spacer(Modifier.height(18.dp))
                    Text("天气评估 · 彩云", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(14.dp))
                    CredentialField(
                        value = caiyunAppKey,
                        onValueChange = { if (!pending) { caiyunAppKey = it; message = null } },
                        label = "彩云 App Key",
                        placeholder = "未配置",
                    )
                    Spacer(Modifier.height(10.dp))
                    SecretField(
                        value = caiyunSecret,
                        onValueChange = { if (!pending) { caiyunSecret = it; message = null } },
                        label = "彩云 Secret",
                        configured = status.hasCaiyunSecret,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            messageRevision++
                            message = null
                            messageSuccess = false
                            val appKey = caiyunAppKey.trim()
                            val secret = caiyunSecret.trim()
                            val appKeyChanged = appKey != savedCaiyunAppKey
                            if ((appKeyChanged && secret.isEmpty()) || (secret.isNotEmpty() && appKey.isEmpty())) {
                                message = "彩云 App Key 和 Secret 必须同时填写。"
                            } else if (secret.isEmpty()) {
                                if (!status.hasCaiyunAppKey || !status.hasCaiyunSecret) {
                                    message = "请填写彩云 App Key 和 Secret。"
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
                        Text(if (testingProvider == CredentialTestProvider.CAIYUN) "处理中" else "测试连接")
                    }
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
                        onClear { error ->
                            if (error == null) {
                                amapWebKey = ""
                                amapSdkKey = ""
                                caiyunAppKey = ""
                                savedCaiyunAppKey = ""
                            }
                            complete("凭据已清空")(error)
                        }
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
    notice: @Composable () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) = androidx.compose.material3.Scaffold(
    snackbarHost = notice,
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
    visualTransformation = VisualTransformation.None,
)

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    configured: Boolean,
) {
    var editing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = if (!editing && value.isEmpty() && configured) "••••••••" else value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused },
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(if (configured) "输入新 Secret" else "未配置") },
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
fun AlarmDiagnosticsScreen(
    snapshot: PermissionSnapshot,
    confirmations: Set<XiaomiDisplayPermission>,
    appVersion: String = "unknown",
    sdkInt: Int = 0,
    onSetting: (PermissionSetting) -> Unit,
    onConfirm: (XiaomiDisplayPermission) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onNotificationRequest: () -> Unit,
    statusMessage: String? = null,
    returningToAlarm: Boolean = false,
    calendarDiagnostics: List<CalendarRefreshDiagnostic> = emptyList(),
    diagnosticEvents: List<DiagnosticEvent> = emptyList(),
    ringtoneReadability: RingtoneReadabilityCheck? = null,
    checkingRingtone: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    fun readVolume() = audioManager?.let {
        "${it.getStreamVolume(AudioManager.STREAM_ALARM)}/${it.getStreamMaxVolume(AudioManager.STREAM_ALARM)}"
    } ?: "不可用"
    var volume by remember { mutableStateOf(readVolume()) }
    DisposableEffect(lifecycleOwner, audioManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                volume = readVolume()
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    PermissionDiagnosticsContent(
        snapshot, confirmations, appVersion, sdkInt, onSetting, onConfirm,
        onRefresh = { volume = readVolume(); onRefresh() },
        onBack, onNotificationRequest, statusMessage, returningToAlarm,
        alarmVolume = volume,
        calendarDiagnostics = calendarDiagnostics,
        diagnosticEvents = diagnosticEvents,
        ringtoneReadability = ringtoneReadability,
        checkingRingtone = checkingRingtone,
    )
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
