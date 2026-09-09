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
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityCheck
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityResult
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEvent
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
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

fun PermissionSnapshot.notificationStatusLabel(): String = when {
    !notificationRuntimeGranted -> "未补齐（通知运行时权限）"
    !notificationsAvailable -> "未补齐（应用通知）"
    !alarmChannelAvailable -> "未补齐（闹钟通知渠道）"
    else -> "已开启"
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
    appVersion: String = "unknown",
    sdkInt: Int = 0,
    onSetting: (PermissionSetting) -> Unit,
    onConfirm: (XiaomiDisplayPermission) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onNotificationRequest: () -> Unit,
    statusMessage: String? = null,
    returningToAlarm: Boolean = false,
    alarmVolume: String? = null,
    calendarDiagnostics: List<CalendarRefreshDiagnostic> = emptyList(),
    diagnosticEvents: List<DiagnosticEvent> = emptyList(),
    ringtoneReadability: RingtoneReadabilityCheck? = null,
    checkingRingtone: Boolean = false,
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
            item {
                PermissionCard {
                    Text("应用与系统", color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
                    Text("应用 $appVersion · Android API $sdkInt", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            statusMessage?.let { message -> item { PermissionCard(background = ZhituColors.AmberBackground) { Text(message, color = ZhituColors.Amber) } } }
            item {
                PermissionCard {
                    Text("通用 Android", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    PermissionItem("通知权限", "响铃通知与操作入口", snapshot.notificationStatusLabel(), "notifications", onNotificationRequest)
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
            item {
                RingtoneReadabilityCard(ringtoneReadability, checkingRingtone)
            }
            item {
                Text("最近本地记录", color = ZhituColors.Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            if (diagnosticEvents.isEmpty()) {
                item {
                    PermissionCard {
                        Text("尚无本地诊断记录", color = ZhituColors.Ink, fontWeight = FontWeight.Medium)
                        Text("重新检查后会显示本机能力和铃声检查结果。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                diagnosticEvents.sortedByDescending(DiagnosticEvent::timestamp).take(20).forEachIndexed { index, event ->
                    item(key = "diagnostic_event_${event.timestamp}_${event.eventType}_$index") {
                        DiagnosticEventCard(event)
                    }
                }
            }
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
private fun RingtoneReadabilityCard(check: RingtoneReadabilityCheck?, checking: Boolean) = PermissionCard {
    Text("铃声可读性", color = ZhituColors.Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    val status = when {
        checking -> "正在检查"
        check == null -> "等待检查"
        check.result == RingtoneReadabilityResult.READABLE -> "已选铃声可读取"
        check.result == RingtoneReadabilityResult.DEFAULT_FALLBACK -> "备用铃声可读取"
        check.result == RingtoneReadabilityResult.UNREADABLE -> "铃声不可读取"
        check.result == RingtoneReadabilityResult.NOT_CONFIGURED -> "暂无计划铃声"
        else -> "检查不可用"
    }
    Text(status, color = if (check?.result == RingtoneReadabilityResult.UNREADABLE) ZhituColors.Amber else ZhituColors.Brand, modifier = Modifier.testTag("ringtone_readability"))
    val detail = when {
        checking -> "正在读取已保存的计划铃声。"
        check == null -> "打开页面或点按重新检查后读取已保存的计划铃声。"
        check.configuredSoundCount == 0 -> "没有可检查的已保存计划铃声。"
        check.result == RingtoneReadabilityResult.DEFAULT_FALLBACK -> "${check.configuredSoundCount} 个计划铃声中 ${check.fallbackCount} 个首选铃声无法读取，可尝试默认回退。"
        check.result == RingtoneReadabilityResult.UNREADABLE -> "${check.configuredSoundCount} 个计划铃声中 ${check.unreadableCount} 个不可读取。"
        else -> "已检查 ${check.configuredSoundCount} 个计划铃声。"
    }
    Text(detail, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
    Text("仅验证来源可读取，不播放铃声，也不改变闹钟。", color = ZhituColors.Muted, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent) = PermissionCard(
    modifier = Modifier.testTag("diagnostic_event_${event.timestamp}_${event.eventType}"),
) {
    Text(
        "${formatCalendarDiagnosticTime(event.timestamp)} · ${event.eventType.displayName()}",
        color = ZhituColors.Ink,
        fontWeight = FontWeight.Medium,
    )
    val duration = event.durationMs?.let { " · ${formatCalendarDuration(it)}" }.orEmpty()
    Text(
        "${event.resultCode.displayName()}$duration",
        color = if (event.resultCode == DiagnosticResultCode.SUCCESS) ZhituColors.Brand else ZhituColors.Amber,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun DiagnosticEventType.displayName(): String = when (this) {
    DiagnosticEventType.CALENDAR_REFRESH -> "日历刷新"
    DiagnosticEventType.EVALUATION -> "评估"
    DiagnosticEventType.ALARM_REGISTRATION -> "闹钟注册"
    DiagnosticEventType.ALARM_TRIGGER -> "闹钟响铃"
    DiagnosticEventType.ALARM_DISMISS -> "停止响铃"
    DiagnosticEventType.ALARM_SNOOZE -> "贪睡"
    DiagnosticEventType.ALARM_MISSED -> "错过响铃"
    DiagnosticEventType.ALARM_CANCEL -> "取消闹钟"
    DiagnosticEventType.ALARM_RECOVERY -> "闹钟恢复"
    DiagnosticEventType.ALARM_PLAYBACK -> "铃声播放"
    DiagnosticEventType.RINGTONE_CHECK -> "铃声检查"
}

private fun DiagnosticResultCode.displayName(): String = when (this) {
    DiagnosticResultCode.SUCCESS -> "成功"
    DiagnosticResultCode.FAILED -> "失败"
    DiagnosticResultCode.CANCELLED -> "已取消"
    DiagnosticResultCode.SKIPPED -> "已跳过"
    DiagnosticResultCode.CACHE_HIT -> "命中缓存"
    DiagnosticResultCode.NETWORK -> "网络失败"
    DiagnosticResultCode.TIMEOUT -> "超时"
    DiagnosticResultCode.HTTP -> "服务响应失败"
    DiagnosticResultCode.VALIDATION -> "校验失败"
    DiagnosticResultCode.STORAGE -> "本地存储失败"
    DiagnosticResultCode.RATE_LIMITED -> "请求受限"
    DiagnosticResultCode.UNKNOWN -> "未知错误"
    DiagnosticResultCode.STALE -> "数据过期"
    DiagnosticResultCode.NEEDS_PERMISSION -> "需要权限"
    DiagnosticResultCode.NOT_FOUND -> "未找到"
    DiagnosticResultCode.UNREADABLE -> "不可读取"
    DiagnosticResultCode.DEFAULT_FALLBACK -> "使用默认回退"
    DiagnosticResultCode.MISSED -> "已错过"
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
    val summary = snapshot.alarmReliabilitySummary(confirmations)
    PermissionCard(background = ZhituColors.Mint, modifier = Modifier.testTag("setting-reliability-summary")) {
        Text("闹钟可靠性", color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
        Text(summary.label, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
        Text(
            "系统能力与人工确认会在诊断中分别显示。",
            color = ZhituColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onDiagnostics, modifier = Modifier.testTag("setting-open-diagnostics")) { Text("查看并检查") }
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
private fun PermissionCard(
    background: Color = Color.White,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
