package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshDiagnostic
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshFailure
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshOutcome
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarSourceOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun PermissionSnapshot.signature(confirmations: Set<XiaomiDisplayPermission>) = AlarmPermissionSignature(
    notification = notificationRuntimeGranted && notificationsAvailable && alarmChannelAvailable,
    exactAlarm = exactAlarmAvailable,
    fullScreen = fullScreenIntentAvailable,
    xiaomiLockScreen = if (isXiaomi) XiaomiDisplayPermission.LockScreen in confirmations else null,
    xiaomiBackgroundPopup = if (isXiaomi) XiaomiDisplayPermission.BackgroundPopup in confirmations else null,
)

fun manualPermissionLabel(confirmed: Boolean) = if (confirmed) "用户已确认 · 未自动核验" else "待手动确认"

fun LocationPermissionSnapshot.statusLabel(): String = when {
    !servicesEnabled -> "定位服务已关闭"
    fineGranted -> "已允许精确位置"
    coarseGranted -> "已允许大致位置"
    else -> "未获得位置权限"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmPermissionGuide(missing: List<String>, onCheck: () -> Unit, onContinue: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("permission_guide").verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("完善响铃显示设置", color = ZhituColors.Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(
                if (missing.isEmpty()) "当前检查已完成，可继续本次启用操作。" else "显示权限未补齐时，锁屏或后台可能无法展示完整响铃页面。仍可继续启用。",
                color = ZhituColors.Muted,
            )
            Text(
                if (missing.isEmpty()) "以系统实际授权与注册结果为准。" else "待补齐：${missing.joinToString("、")}。可去检查后返回继续当前操作。",
                color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCheck, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("permission_check")) { Text("去检查") }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("permission_continue"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
                ) { Text("继续启用") }
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("permission_cancel")) { Text("取消", color = ZhituColors.Muted) }
        }
    }
}

@Composable
fun PermissionDiagnosticsContent(
    snapshot: PermissionSnapshot,
    confirmations: Set<XiaomiDisplayPermission>,
    onSetting: (PermissionSetting) -> Unit,
    onConfirm: (XiaomiDisplayPermission) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onNotificationRequest: () -> Unit,
    statusMessage: String? = null,
    returningToAlarm: Boolean = false,
    alarmVolume: String? = null,
    calendarDiagnostics: List<CalendarRefreshDiagnostic> = emptyList(),
) {
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("可靠性诊断", subtitle = "从系统设置返回后自动重新检查", navigation = onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("permission_diagnostics"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                PermissionCard(background = ZhituColors.Mint) {
                    Text(if (snapshot.isXiaomi) "小米 · 系统能力检查" else "通用 Android · 系统能力检查", color = ZhituColors.Brand, fontWeight = FontWeight.Medium)
                    Text("标准权限读取当前系统状态；计划是否注册成功，以闹钟列表结果为准。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            statusMessage?.let { message -> item { PermissionCard(background = ZhituColors.AmberBackground) { Text(message, color = ZhituColors.Amber) } } }
            item {
                PermissionCard {
                    Text("通用 Android", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    PermissionItem("通知权限", "响铃通知与操作入口", if (snapshot.notificationsAvailable && snapshot.alarmChannelAvailable) "已开启" else "未补齐（应用通知或闹钟通知渠道）", "notifications", onNotificationRequest)
                    PermissionItem("精确闹钟", "按设定时间触发本地闹钟", if (snapshot.exactAlarmAvailable) "已开启" else "未补齐", "exact_alarm", { onSetting(PermissionSetting.ExactAlarm) })
                    PermissionItem("全屏提醒", "锁屏与后台响铃页面", if (snapshot.fullScreenIntentAvailable) "已开启" else "未补齐", "full_screen", { onSetting(PermissionSetting.FullScreenIntent) })
                    PermissionItem("位置权限", "仅在点击“使用当前位置”时请求", snapshot.location.statusLabel(), "location", { onSetting(PermissionSetting.ApplicationDetails) })
                }
            }
            if (snapshot.isXiaomi) item {
                PermissionCard {
                    Text("小米系统显示", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    XiaomiDisplayPermission.entries.forEach { permission ->
                        val tag = if (permission == XiaomiDisplayPermission.LockScreen) "xiaomi_lock" else "xiaomi_background"
                        val title = if (permission == XiaomiDisplayPermission.LockScreen) "锁屏显示" else "后台弹出界面"
                        val purpose = if (permission == XiaomiDisplayPermission.LockScreen) "允许锁屏时展示响铃页面" else "允许后台触发时展示响铃页面"
                        PermissionItem(title, purpose, manualPermissionLabel(permission in confirmations), tag, { onSetting(PermissionSetting.XiaomiDisplayPermissions) })
                        TonalButton(
                            if (permission in confirmations) "重新确认" else "我已手动确认",
                            { onConfirm(permission) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirm_$tag"),
                        )
                    }
                    Text("请在系统应用权限页查找“锁屏显示”和“后台弹出界面”。具体名称与入口以当前系统为准；未找到时可保留待确认并继续。用户确认不等于系统检测。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                PermissionCard {
                    Text("按场景使用权限", fontWeight = FontWeight.Bold)
                    Text("位置仅在点击“使用当前位置”后请求；可改用搜索或地图选点。全屏提醒用于锁屏与后台；解锁使用手机时可能只显示横幅。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            alarmVolume?.let { volume -> item {
                PermissionCard { PermissionItem("闹钟音量", "使用系统闹钟音量", volume, "alarm_volume", { onSetting(PermissionSetting.AlarmVolume) }) }
            } }
            if (calendarDiagnostics.isNotEmpty()) {
                item {
                    Text("最近日历刷新", color = ZhituColors.Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                }
                calendarDiagnostics.takeLast(5).asReversed().forEachIndexed { index, diagnostic ->
                    item(key = "calendar_refresh_${diagnostic.startedAt}_$index") {
                        CalendarRefreshDiagnosticCard(diagnostic)
                    }
                }
            }
            item { TonalButton("重新检查", onRefresh, Modifier.fillMaxWidth().testTag("permissions_refresh")) }
            if (returningToAlarm) item { TonalButton("返回继续启用", onBack, Modifier.fillMaxWidth().testTag("permissions_return")) }
        }
    }
}

@Composable
fun CalendarRefreshDiagnosticCard(diagnostic: CalendarRefreshDiagnostic) = PermissionCard {
    CalendarRefreshDiagnosticDetails(diagnostic, includeTitle = true)
}

@Composable
fun ColumnScope.CalendarRefreshDiagnosticDetails(
    diagnostic: CalendarRefreshDiagnostic,
    includeTitle: Boolean,
) {
    val success = diagnostic.outcome == CalendarRefreshOutcome.SUCCESS
    val statusColor = if (success) ZhituColors.Brand else ZhituColors.Amber
    if (includeTitle) {
        Text(
            "${formatCalendarDiagnosticTime(diagnostic.startedAt)} · 日历刷新",
            color = ZhituColors.Ink,
            fontWeight = FontWeight.Medium,
        )
    }
    Text(
        "${diagnostic.outcome.calendarOutcomeLabel(diagnostic.failure)} · ${formatCalendarDuration(diagnostic.durationMillis)} · ${diagnostic.attempts.distinctBy { it.sourceHost }.size} 个来源",
        color = statusColor,
        style = MaterialTheme.typography.bodySmall,
    )
    val years = buildList {
        if (diagnostic.cacheHitYears.isNotEmpty()) add("使用缓存 ${diagnostic.cacheHitYears.joinToString("、")}")
        if (diagnostic.refreshedYears.isNotEmpty()) add("刷新 ${diagnostic.refreshedYears.joinToString("、")}")
    }
    if (years.isNotEmpty()) {
        Text(years.joinToString(" · "), color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (!success) {
        Text("连续失败 ${diagnostic.consecutiveFailures} 次", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    diagnostic.attempts.forEach { attempt ->
        val attemptStatus = attempt.outcome.calendarAttemptLabel(attempt.failure)
        val failureCount = if (attempt.dailyFailures > 0) " · 今日失败 ${attempt.dailyFailures} 次" else ""
        Text(
            "${attempt.year} · ${attempt.sourceHost} · $attemptStatus · ${formatCalendarDuration(attempt.durationMillis)}$failureCount",
            color = ZhituColors.Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal fun formatCalendarDiagnosticTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

internal fun formatCalendarDuration(durationMillis: Long): String = when {
    durationMillis < 1_000L -> "${durationMillis.coerceAtLeast(0L)} 毫秒"
    else -> "${durationMillis / 1_000}.${(durationMillis % 1_000) / 100} 秒"
}

private fun CalendarRefreshOutcome.calendarOutcomeLabel(failure: CalendarRefreshFailure?): String = when (this) {
    CalendarRefreshOutcome.SUCCESS -> "刷新成功"
    CalendarRefreshOutcome.FAILED -> failure.calendarFailureLabel()
}

private fun CalendarSourceOutcome.calendarAttemptLabel(failure: CalendarRefreshFailure?): String = when (this) {
    CalendarSourceOutcome.SUCCESS -> "成功"
    CalendarSourceOutcome.SKIPPED_LIMIT -> "今日失败已达上限"
    CalendarSourceOutcome.FAILED -> failure.calendarFailureLabel()
}

private fun CalendarRefreshFailure?.calendarFailureLabel(): String = when (this) {
    CalendarRefreshFailure.NETWORK -> "网络失败"
    CalendarRefreshFailure.TIMEOUT -> "请求超时"
    CalendarRefreshFailure.HTTP -> "服务响应失败"
    CalendarRefreshFailure.VALIDATION -> "数据校验失败"
    CalendarRefreshFailure.STORAGE -> "本地存储失败"
    CalendarRefreshFailure.RATE_LIMITED -> "请求受限"
    CalendarRefreshFailure.CANCELLED -> "已取消"
    CalendarRefreshFailure.UNKNOWN -> "未知错误"
    null -> "刷新失败"
}

@Composable
fun PermissionSummaryCard(snapshot: PermissionSnapshot, confirmations: Set<XiaomiDisplayPermission>, onDiagnostics: () -> Unit) {
    val missing = snapshot.signature(confirmations).missing
    PermissionCard(background = ZhituColors.Mint) {
        Text(if (missing.isEmpty()) "响铃显示检查已完成" else "显示设置待补齐", color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
        Text(if (missing.isEmpty()) "以系统实际授权与注册结果为准。" else "${missing.size} 项设置待补齐；启用时会提示，仍可继续。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDiagnostics, modifier = Modifier.testTag("open_permissions")) { Text("检查系统权限") }
    }
}

@Composable
private fun PermissionItem(title: String, purpose: String, status: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = ZhituColors.Ink)
            Text(purpose, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text(status, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("status_$tag"))
        }
        TextButton(onClick = onClick, modifier = Modifier.testTag("settings_$tag")) { Text("去设置") }
    }
}

@Composable
private fun PermissionCard(background: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
