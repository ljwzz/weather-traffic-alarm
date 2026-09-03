package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmEvent
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(draft: EditorDraft, update: (EditorDraft) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var timeDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var soundDialog by remember { mutableStateOf(false) }
    var snoozeDialog by remember { mutableStateOf(false) }
    var arrivalDialog by remember { mutableStateOf(false) }
    var preparationDialog by remember { mutableStateOf(false) }
    var maxAdvanceDialog by remember { mutableStateOf(false) }
    val valid = draft.name.isNotBlank() && when (draft.repeat) {
        RepeatChoice.ONCE -> draft.date.isNotBlank()
        RepeatChoice.WEEKLY -> draft.weekdays.isNotEmpty()
        RepeatChoice.WORKDAYS -> true
    }
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar(if (draft.id == null) "添加闹钟" else "编辑闹钟", navigation = onCancel) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draft.id != null) TonalButton("删除", { deleteDialog = true }, Modifier.weight(1f))
                Button(
                    onClick = onSave, enabled = valid, modifier = Modifier.weight(2f).testTag("save_alarm"),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
                ) { Text("保存并注册") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FormCard {
                    Text("基础闹钟", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(draft.name, { update(draft.copy(name = it)) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth().testTag("plan_name"), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    SettingRow("响铃时间", draft.time, { timeDialog = true }, "alarm_time")
                }
            }
            item {
                FormCard {
                    Text("日期与重复", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        RepeatChoice.entries.forEachIndexed { index, choice ->
                            SegmentedButton(selected = draft.repeat == choice, onClick = { update(draft.copy(repeat = choice)) }, modifier = Modifier.testTag("repeat_${when (choice) { RepeatChoice.ONCE -> "once"; RepeatChoice.WEEKLY -> "weekly"; RepeatChoice.WORKDAYS -> "workdays" }}"), shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, RepeatChoice.entries.size)) { Text(choice.label) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (draft.repeat) {
                        RepeatChoice.ONCE -> SettingRow("响铃日期", draft.date.ifBlank { "请选择" }, { dateDialog = true }, "alarm_date")
                        RepeatChoice.WEEKLY -> WeekdaySelector(draft.weekdays) { days -> update(draft.copy(weekdays = days)) }
                        RepeatChoice.WORKDAYS -> NoticeCard("根据本地工作日日历安排。日历数据不可用时按周一至周五处理。", ZhituColors.Mint, ZhituColors.Brand)
                    }
                }
            }
            item {
                FormCard {
                    Text("铃声与贪睡", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(6.dp))
                    SettingRow("铃声", draft.ringtone, { soundDialog = true })
                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("振动", Modifier.weight(1f), color = ZhituColors.Ink)
                        Switch(draft.vibration, { update(draft.copy(vibration = it)) })
                    }
                    SettingRow("贪睡时长", "${draft.snoozeMinutes} 分钟", { snoozeDialog = true })
                }
            }
            item {
                FormCard {
                    Text("通勤与提前", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(6.dp))
                    SettingRow("期望到达时间", draft.arrivalLocalTime, { arrivalDialog = true }, "arrival_time")
                    SettingRow("准备时间", "${draft.preparationMinutes} 分钟", { preparationDialog = true }, "preparation_minutes")
                    SettingRow("最多提前", "${draft.maxAdvanceMinutes} 分钟", { maxAdvanceDialog = true }, "max_advance_minutes")
                    Text("已启用且配置通勤的计划会在后台按路线、天气和工作日重新评估下次闹钟。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { NoticeCard("保存后才会写入计划；取消或返回不会修改已有闹钟。", ZhituColors.Sky, ZhituColors.Blue) }
        }
    }
    if (timeDialog) TimePickerSheet(draft.time, { update(draft.copy(time = it)); timeDialog = false }, { timeDialog = false })
    if (dateDialog) DatePickerSheet({ date -> update(draft.copy(date = date)); dateDialog = false }, { dateDialog = false })
    if (arrivalDialog) TimePickerSheet(draft.arrivalLocalTime, { update(draft.copy(arrivalLocalTime = it)); arrivalDialog = false }, { arrivalDialog = false })
    if (soundDialog) { val uris = listOf(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, android.provider.Settings.System.DEFAULT_RINGTONE_URI, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI); AlertDialog(onDismissRequest = { soundDialog = false }, title = { Text("选择系统铃声") }, text = { Column { uris.forEach { uri -> val title = android.media.RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "系统铃声"; Row(Modifier.fillMaxWidth().clickable { android.media.RingtoneManager.getRingtone(context, uri)?.play(); update(draft.copy(ringtone = title, soundUri = uri.toString())); soundDialog = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = uri.toString() == draft.soundUri, onClick = null); Text(title) } } } }, confirmButton = {}) }
    if (snoozeDialog) AlertDialog(onDismissRequest = { snoozeDialog = false }, title = { Text("贪睡时长") }, text = { LazyColumn { items((1..30).toList()) { minutes -> Row(Modifier.fillMaxWidth().clickable { update(draft.copy(snoozeMinutes = minutes)); snoozeDialog = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = draft.snoozeMinutes == minutes, onClick = null); Text("$minutes 分钟") } } } }, confirmButton = {})
    if (preparationDialog) MinutesPickerDialog("准备时间", draft.preparationMinutes, 0..240, { update(draft.copy(preparationMinutes = it)); preparationDialog = false }, { preparationDialog = false })
    if (maxAdvanceDialog) MinutesPickerDialog("最多提前", draft.maxAdvanceMinutes, 0..180, { update(draft.copy(maxAdvanceMinutes = it)); maxAdvanceDialog = false }, { maxAdvanceDialog = false })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除闹钟？") }, text = { Text("删除后将取消本机已注册的后续提醒。") }, confirmButton = { TextButton(onClick = onDelete) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@Composable
fun SettingsScreen(settings: com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings, onSettingsChange: (com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings) -> Unit, onCalendar: () -> Unit, onRoute: () -> Unit, onNavigate: (ZhituDestination) -> Unit, onCredentials: () -> Unit, onDiagnostics: () -> Unit, onHistory: () -> Unit, onWeather: () -> Unit, onOnboarding: () -> Unit, permissionSnapshot: PermissionSnapshot, permissionConfirmations: Set<XiaomiDisplayPermission>) {
    Scaffold(
    topBar = { ZhituTopBar("设置", subtitle = "本地数据与系统能力") }, bottomBar = { ZhituNav(selected = ZhituDestination.SETTINGS, onNavigate = onNavigate) },
) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PermissionSummaryCard(permissionSnapshot, permissionConfirmations, onDiagnostics) }
        item {
            SettingsGroup("系统权限") {
                SettingRow("通知、精确闹钟与全屏提醒", "检查", onDiagnostics)
                SettingRow("位置权限", permissionSnapshot.location.statusLabel(), onDiagnostics)
                if (permissionSnapshot.isXiaomi) {
                    TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("小米锁屏显示", color = ZhituColors.Ink)
                            Text(manualPermissionLabel(XiaomiDisplayPermission.LockScreen in permissionConfirmations), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("小米后台弹出界面", color = ZhituColors.Ink)
                            Text(manualPermissionLabel(XiaomiDisplayPermission.BackgroundPopup in permissionConfirmations), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { SettingsGroup("计划") { SettingRow("工作日日历", "本地规则", onCalendar); SettingRow("常用地点", "家、公司", onRoute) } }
        item { SettingsGroup("提醒") { Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) { Text("通知摘要", Modifier.weight(1f)); Switch(settings.notificationSummary, { onSettingsChange(settings.copy(notificationSummary = it)) }) }; Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) { Text("锁屏摘要", Modifier.weight(1f)); Switch(settings.lockScreenSummary, { onSettingsChange(settings.copy(lockScreenSummary = it)) }) } } }
        item { SettingsGroup("自动提前") { Text("已启用且配置通勤的闹钟会在后台评估路线、天气和工作日，并更新下一次闹钟。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        item { BufferEditor("工作日天气缓冲", settings.workdayWeatherBuffers) { onSettingsChange(settings.copy(workdayWeatherBuffers = it)) } }
        item { BufferEditor("周末天气缓冲", settings.weekendWeatherBuffers) { onSettingsChange(settings.copy(weekendWeatherBuffers = it)) } }
        item { BufferEditor("法定休息日天气缓冲", settings.holidayWeatherBuffers) { onSettingsChange(settings.copy(holidayWeatherBuffers = it)) } }
        item { SettingsGroup("数据服务") { SettingRow("高德地图与路线", "地点、路线与路况", onRoute); SettingRow("彩云天气", "手动天气预览", onWeather); SettingRow("接口凭据", "高德与天气", onCredentials) } }
        item { SettingsGroup("可靠性") { SettingRow("权限与诊断", "查看状态", onDiagnostics); SettingRow("闹钟记录", "本机事件", onHistory) } }
        item { SettingsGroup("其他") { SettingRow("高德专项授权", if (settings.amapConsentGranted) "已同意，可重新设置" else "未同意", onOnboarding); SettingRow("首次引导", "重新查看", onOnboarding) } }
    }
}
}

@Composable
fun HistoryScreen(
    events: List<AlarmEvent>,
    decisions: List<AlarmDecision>,
    occurrences: List<AlarmOccurrence>,
    plans: List<com.ljwzz.weathertrafficalarm.core.model.AlarmPlan>,
    onBack: () -> Unit,
) {
    var days by remember { mutableStateOf(30) }
    var result by remember { mutableStateOf<HistoryResultFilter?>(null) }
    val now = System.currentTimeMillis()
    val filtered = historyItems(events, decisions, occurrences, plans)
        .filter { item -> item.timestamp >= now - days * 86_400_000L && (result == null || item.filter == result) }
    Scaffold(topBar = { ZhituTopBar("闹钟记录", navigation = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 7, 30).forEach { value -> FilterChip(selected = days == value, onClick = { days = value }, label = { Text("$value 天") }) } } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        null to "全部",
                        HistoryResultFilter.SUCCESS to "成功",
                        HistoryResultFilter.FAILED to "失败",
                        HistoryResultFilter.STALE to "已过期",
                        HistoryResultFilter.SKIPPED to "跳过",
                        HistoryResultFilter.EVENT to "闹钟事件",
                    ).forEach { (filter, label) ->
                        FilterChip(selected = result == filter, onClick = { result = filter }, label = { Text(label) })
                    }
                }
            }
            if (filtered.isEmpty()) item { EmptyProviderCard("暂无记录", "后台评估及注册、触发、停止、贪睡等本机事件会显示在这里。") }
            items(filtered, key = { it.id }) { item -> HistoryItemCard(item) }
        }
    }
}

private enum class HistoryResultFilter { SUCCESS, FAILED, STALE, SKIPPED, EVENT }

private sealed interface HistoryItem {
    val id: String
    val timestamp: Long
    val filter: HistoryResultFilter
}

private data class DecisionHistoryItem(
    val decision: AlarmDecision,
    val planName: String,
    val occurrence: AlarmOccurrence?,
    val effectiveOutcome: EvaluationOutcome,
    override val timestamp: Long,
) : HistoryItem {
    override val id: String = "decision:${decision.decisionId}"
    override val filter: HistoryResultFilter = effectiveOutcome.toHistoryFilter()
}

private data class EventHistoryItem(val event: AlarmEvent) : HistoryItem {
    override val id: String = "event:${event.id}"
    override val timestamp: Long = event.createdAt
    override val filter: HistoryResultFilter = HistoryResultFilter.EVENT
}

private fun historyItems(
    events: List<AlarmEvent>,
    decisions: List<AlarmDecision>,
    occurrences: List<AlarmOccurrence>,
    plans: List<com.ljwzz.weathertrafficalarm.core.model.AlarmPlan>,
): List<HistoryItem> {
    val planNames = plans.associate { it.id to it.name }
    val occurrencesByDecision = occurrences.filter { it.decisionId != null }.associateBy { it.decisionId!! }
    return buildList {
        decisions.forEach { decision ->
            add(
                DecisionHistoryItem(
                    decision = decision,
                    planName = planNames[decision.planId] ?: "已删除闹钟",
                    occurrence = occurrencesByDecision[decision.decisionId],
                    effectiveOutcome = decision.displayOutcome(),
                    timestamp = decision.generatedTimestamp(),
                ),
            )
        }
        events.forEach { add(EventHistoryItem(it)) }
    }.sortedByDescending(HistoryItem::timestamp)
}

@Composable
private fun HistoryItemCard(item: HistoryItem) = FormCard {
    when (item) {
        is EventHistoryItem -> {
            Text(item.event.type.name, color = ZhituColors.Brand, fontWeight = FontWeight.Medium)
            Text(item.event.message, color = ZhituColors.Ink)
            HistoryTimestamp(item.timestamp)
        }
        is DecisionHistoryItem -> {
            val decision = item.decision
            Text("自动评估 · ${item.effectiveOutcome.toLabel()}", color = item.effectiveOutcome.toColor(), fontWeight = FontWeight.Medium)
            Text("${item.planName} · ${decision.targetDate}", color = ZhituColors.Ink)
            Text(
                "计算分解：预计出发 ${decision.estimatedDepartureAt?.toDisplayTime() ?: "未提供"}；通勤 ${decision.commuteSeconds.toDurationLabel()}；准备 ${decision.preparationMinutes} 分钟；天气缓冲 ${decision.weatherBufferMinutes} 分钟",
                color = ZhituColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "基础闹钟 ${decision.defaultWakeAt?.toDisplayTime() ?: "未记录"}；建议时间 ${decision.recommendedWakeAt.toDisplayTime()}；实际提前提醒 ${decision.actualWakeAt?.toDisplayTime() ?: "未注册"}",
                color = ZhituColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            decision.applicationOutcome?.let { Text("应用结果：${it.toApplicationOutcomeLabel()}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) }
            item.occurrence?.let { occurrence ->
                Text("提醒状态：${occurrence.state.toLabel()} · ${Instant.ofEpochMilli(occurrence.scheduledWakeAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (decision.calendarSource != null || decision.fallbackReason == FallbackReason.CALENDAR_FALLBACK) {
                Text("日历：${when (decision.calendarSource) { "PLAN_OVERRIDE" -> "本日覆盖"; "HOLIDAY_CN" -> "节假日日历"; else -> "周规则兜底" }}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            decision.weatherDataSource?.let { Text("天气数据：${it.toWeatherDataSourceLabel()}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) }
            if (decision.fallbackReason != FallbackReason.NONE) {
                Text("降级：${decision.fallbackReason.toLabel()}", color = ZhituColors.Amber, style = MaterialTheme.typography.bodySmall)
            }
            decision.failureReason?.let { Text("失败原因：${it.toFailureReasonLabel()}", color = ZhituColors.Amber, style = MaterialTheme.typography.bodySmall) }
            if (decision.expiresTimestamp()?.let { it < System.currentTimeMillis() } == true) {
                Text("评估数据时效已过，历史执行结果保持不变。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (decision.attemptNumber > 0) Text("重试次数：${decision.attemptNumber}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            HistoryTimestamp(item.timestamp)
        }
    }
}

@Composable
private fun HistoryTimestamp(timestamp: Long) = Text(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
    color = ZhituColors.Muted,
    style = MaterialTheme.typography.bodySmall,
)

private fun AlarmDecision.displayOutcome(): EvaluationOutcome = evaluationOutcome

private fun AlarmDecision.generatedTimestamp(): Long = generatedAt.toInstantOrNull()?.toEpochMilli() ?: 0L
private fun AlarmDecision.expiresTimestamp(): Long? = expiresAt.toInstantOrNull()?.toEpochMilli()
private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
    ?: runCatching { java.time.LocalDateTime.parse(this).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
private fun String.toDisplayTime(): String = toInstantOrNull()?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) ?: substringAfter('T', this).take(16)
private fun Long?.toDurationLabel(): String = this?.let { "${it / 60} 分钟" } ?: "未提供"
private fun EvaluationOutcome.toHistoryFilter(): HistoryResultFilter = when (this) {
    EvaluationOutcome.SUCCESS -> HistoryResultFilter.SUCCESS
    EvaluationOutcome.FAILED -> HistoryResultFilter.FAILED
    EvaluationOutcome.STALE -> HistoryResultFilter.STALE
    EvaluationOutcome.SKIPPED -> HistoryResultFilter.SKIPPED
}
private fun EvaluationOutcome.toLabel(): String = when (this) {
    EvaluationOutcome.SUCCESS -> "成功"
    EvaluationOutcome.FAILED -> "失败"
    EvaluationOutcome.STALE -> "已过期"
    EvaluationOutcome.SKIPPED -> "跳过"
}
private fun EvaluationOutcome.toColor(): Color = when (this) {
    EvaluationOutcome.SUCCESS -> ZhituColors.Brand
    EvaluationOutcome.FAILED, EvaluationOutcome.STALE -> ZhituColors.Amber
    EvaluationOutcome.SKIPPED -> ZhituColors.Muted
}
private fun OccurrenceState.toLabel(): String = when (this) {
    OccurrenceState.REGISTERING -> "注册中"
    OccurrenceState.SCHEDULED -> "已注册"
    OccurrenceState.FAILED -> "注册失败"
    OccurrenceState.DEFAULT_REGISTERED -> "基础闹钟已注册"
    OccurrenceState.ADVANCED -> "提前闹钟已注册"
    OccurrenceState.FIRING -> "响铃中"
    OccurrenceState.SNOOZED -> "贪睡中"
    OccurrenceState.DISMISSED -> "已停止"
    OccurrenceState.MISSED -> "已错过"
    OccurrenceState.CANCELLED -> "已取消"
}
private fun FallbackReason.toLabel(): String = when (this) {
    FallbackReason.NONE -> "无"
    FallbackReason.CALENDAR_FALLBACK -> "日历 fallback"
    FallbackReason.STALE_RESPONSE -> "响应已过期"
    FallbackReason.CURRENT_TRAFFIC_FALLBACK -> "使用当前路况"
    FallbackReason.FUTURE_ROUTE_NOT_ENTITLED -> "未来路线不可用"
    FallbackReason.ROUTE_HORIZON_UNAVAILABLE -> "路线时间范围不可用"
    FallbackReason.ROUTE_PROVIDER_TIMEOUT -> "路线服务超时"
    FallbackReason.ROUTE_PROVIDER_QUOTA -> "路线服务额度不足"
    FallbackReason.ROUTE_NOT_FOUND -> "未找到路线"
    FallbackReason.WEATHER_HORIZON_UNAVAILABLE -> "天气时间范围不可用"
    FallbackReason.WEATHER_PROVIDER_TIMEOUT -> "天气服务超时"
    FallbackReason.WEATHER_PROVIDER_AUTH -> "天气凭据不可用"
    FallbackReason.WEATHER_PROVIDER_QUOTA -> "天气服务额度不足"
    FallbackReason.WEATHER_UNKNOWN_CODE -> "天气代码无法识别"
}
private fun String.toWeatherDataSourceLabel(): String = when (uppercase()) {
    "CACHE" -> "本地缓存"
    "MIXED" -> "网络与本地缓存"
    "NETWORK" -> "网络数据"
    else -> this
}
private fun String.toApplicationOutcomeLabel(): String = when (uppercase()) {
    "APPLIED" -> "已应用到提前提醒"
    "UNCHANGED" -> "沿用现有提醒"
    "CANCELLED" -> "无需提前，按基础闹钟提醒"
    "FAILED" -> "提前提醒注册失败"
    "STALE" -> "结果已过期，未应用"
    "SKIPPED" -> "本次跳过"
    else -> "未能应用"
}
private fun String.toFailureReasonLabel(): String = when (uppercase()) {
    "ROUTE_CONSENT_REQUIRED" -> "尚未完成高德专项授权"
    "COMMUTE_NOT_CONFIGURED" -> "未配置有效通勤地点"
    "MISSING_CAIYUN_CREDENTIALS" -> "未完成天气服务凭据验证"
    "EVALUATION_WINDOW_EXPIRED" -> "评估窗口已结束"
    "EVALUATION_RESULT_EXPIRED", "STALE_RESPONSE" -> "响应已过期"
    "EVALUATION_INPUTS_CHANGED" -> "计划或通勤配置已更新"
    "INVALID_TIME_WINDOW" -> "到达时间与起床时间不匹配"
    "DATE_NOT_APPLICABLE" -> "该日期不需要提醒"
    "ROUTE_INVALID_KEY", "WEATHER_INVALID_KEY" -> "服务凭据不可用"
    "ROUTE_MISSING_KEY", "WEATHER_MISSING_KEY" -> "尚未配置服务凭据"
    "ROUTE_NETWORK", "WEATHER_NETWORK" -> "网络不可用"
    "ROUTE_TIMEOUT", "WEATHER_TIMEOUT" -> "服务响应超时"
    "ROUTE_QUOTA_EXCEEDED", "WEATHER_QUOTA_EXCEEDED" -> "服务额度不足"
    "ROUTE_RATE_LIMITED", "WEATHER_RATE_LIMITED" -> "请求暂受限制，稍后重试"
    "ROUTE_ROUTE_NOT_FOUND" -> "未找到可用通勤路线"
    "WEATHER_WEATHER_HORIZON_UNAVAILABLE" -> "目标日期超出天气预报范围"
    "WEATHER_WEATHER_UNKNOWN_CODE" -> "目标时段天气数据不完整"
    else -> "后台评估失败"
}

@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    containerColor = ZhituColors.Background,
    topBar = { ZhituTopBar("天气", subtitle = "手动查看通勤天气", navigation = onBack) },
) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FormCard {
                Text(
                    "这是天气专用的手动预览；刷新不会启动自动评估，也不会改变闹钟时间。",
                    color = ZhituColors.Blue,
                )
            }
        }
        item {
            when (state) {
                WeatherUiState.Idle -> EmptyProviderCard("尚未刷新天气", "配置带坐标的起点和终点后可查看未来 24 小时的通勤天气；自动评估由后台任务处理。")
                is WeatherUiState.Loading -> FormCard {
                    Text("正在获取天气", color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
                    state.weatherRouteLabel()?.let { Text(it, color = ZhituColors.Muted) }
                }
                is WeatherUiState.Success -> FormCard {
                    Text("${state.severity.toWeatherLabel()}天气", color = ZhituColors.Brand, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("地点：${state.homeName} → ${state.workName}", color = ZhituColors.Ink)
                    Text("数据时间：${state.reportTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}", color = ZhituColors.Muted)
                    Text(state.source.toWeatherSourceLabel(), color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                is WeatherUiState.Error -> FormCard {
                    Text("无法获取天气", color = ZhituColors.Amber, fontWeight = FontWeight.Bold)
                    state.weatherRouteLabel()?.let { Text(it, color = ZhituColors.Muted) }
                    Text(state.message, color = ZhituColors.Ink)
                }
            }
        }
        item {
            Button(
                onClick = onRefresh,
                enabled = state !is WeatherUiState.Loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text(if (state is WeatherUiState.Loading) "正在刷新" else "手动刷新") }
        }
    }
}

private fun WeatherUiState.weatherRouteLabel(): String? = when (this) {
    is WeatherUiState.Loading -> listOfNotNull(homeName, workName).takeIf { it.isNotEmpty() }?.joinToString(" → ")
    is WeatherUiState.Error -> listOfNotNull(homeName, workName).takeIf { it.isNotEmpty() }?.joinToString(" → ")
    else -> null
}

private fun WeatherSeverity.toWeatherLabel(): String = when (this) {
    WeatherSeverity.FINE -> "晴好"
    WeatherSeverity.LIGHT -> "轻度"
    WeatherSeverity.MODERATE -> "中度"
    WeatherSeverity.SEVERE -> "严重"
}

private fun WeatherDataSource.toWeatherSourceLabel(): String = when (this) {
    WeatherDataSource.NETWORK -> "数据来自彩云天气"
    WeatherDataSource.CACHE -> "数据来自本地缓存"
    WeatherDataSource.MIXED -> "数据混合来自彩云天气和本地缓存"
}

@Composable
fun RingingScreen(occurrenceId: String?, alarmName: String = "本地闹钟", alarmTime: String = "--:--", snoozeMinutes: Int = 10, onDismiss: () -> Unit, onSnooze: () -> Unit) = Box(Modifier.fillMaxSize().background(ZhituColors.Navy)) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("知途 · $alarmName", color = ZhituColors.Mint); Spacer(Modifier.height(60.dp)); Text(alarmTime, color = Color.White, style = MaterialTheme.typography.displayLarge); Spacer(Modifier.height(18.dp)); Text("响铃中", color = Color.White, style = MaterialTheme.typography.headlineLarge); Text(occurrenceId?.let { "闹钟已触发" } ?: "本机闹钟", color = ZhituColors.Mint) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { Button(onDismiss, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ZhituColors.Navy), shape = RoundedCornerShape(18.dp)) { Text("停止") }; Button(onSnooze, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand), shape = RoundedCornerShape(18.dp)) { Text("贪睡 $snoozeMinutes 分钟") } }
    }
}

@Composable
fun OnboardingScreen(
    onGrantAmap: () -> Unit,
    onSkipAmap: () -> Unit,
) = Scaffold { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("知途", style = MaterialTheme.typography.displayMedium, color = ZhituColors.Ink)
            Spacer(Modifier.height(18.dp))
            Text("本地闹钟，按你选择的日期和时间响铃。", style = MaterialTheme.typography.headlineSmall, color = ZhituColors.Ink)
            Spacer(Modifier.height(18.dp))
            FormCard {
                Text("高德地图专项授权", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                Spacer(Modifier.height(8.dp))
                Text("同意后才会初始化地图、定位、地点搜索和路线服务。你可稍后在设置中重新授权。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onSkipAmap) { Text("暂不授权") }
            }
        }
        Button(onClick = onGrantAmap, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand), shape = RoundedCornerShape(16.dp)) { Text("同意并配置高德") }
    }
}

@Composable
internal fun FormCard(content: @Composable ColumnScope.() -> Unit) = Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) }

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) { Text(title, color = ZhituColors.Muted, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(6.dp)); FormCard(content) }

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit, tag: String? = null) = Row(Modifier.fillMaxWidth().height(50.dp).then(if (tag == null) Modifier else Modifier.testTag(tag)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), color = ZhituColors.Ink); Text(value, color = ZhituColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.width(8.dp)); Text("›", color = ZhituColors.Subtle, style = MaterialTheme.typography.headlineSmall) }

@Composable
fun EmptyProviderCard(title: String, description: String, onClick: (() -> Unit)? = null) = Card(modifier = Modifier.fillMaxWidth().let { if (onClick == null) it else it.clickable(onClick = onClick) }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ZhituColors.Surface), border = BorderStroke(1.dp, ZhituColors.Line)) { Column(Modifier.padding(20.dp)) { Text(title, fontWeight = FontWeight.Bold, color = ZhituColors.Ink); Spacer(Modifier.height(6.dp)); Text(description, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) } }

@Composable
private fun NoticeCard(text: String, background: Color, foreground: Color) = Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = background)) { Text(text, Modifier.padding(16.dp), color = foreground, style = MaterialTheme.typography.bodySmall) }

@Composable
private fun BufferEditor(title: String, current: com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers, onSave: (com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers) -> Unit) {
    var light by remember(current) { mutableStateOf(current.lightMinutes.toString()) }
    var moderate by remember(current) { mutableStateOf(current.moderateMinutes.toString()) }
    var severe by remember(current) { mutableStateOf(current.severeMinutes.toString()) }
    SettingsGroup(title) {
        OutlinedTextField(light, { light = it.filter(Char::isDigit) }, label = { Text("小雨分钟") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(moderate, { moderate = it.filter(Char::isDigit) }, label = { Text("中雨分钟") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(severe, { severe = it.filter(Char::isDigit) }, label = { Text("恶劣天气分钟") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onSave(com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers(light.toIntOrNull()?.coerceIn(0, 60) ?: 0, moderate.toIntOrNull()?.coerceIn(0, 60) ?: 0, severe.toIntOrNull()?.coerceIn(0, 60) ?: 0)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand)) { Text("保存缓冲") }
    }
}

@Composable
fun TonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = Button(onClick, modifier, colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Mint, contentColor = ZhituColors.Brand), shape = RoundedCornerShape(16.dp)) { Text(label) }

@Composable
private fun WeekdaySelector(days: Set<Int>, onChange: (Set<Int>) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label -> val day = index + 1; FilterChip(selected = day in days, onClick = { onChange(if (day in days) days - day else days + day) }, label = { Text(label) }) } } }

@Composable
private fun MinutesPickerDialog(
    title: String,
    selected: Int,
    choices: IntRange,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
        LazyColumn {
            items(choices.toList()) { minutes ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSave(minutes) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = minutes == selected, onClick = null)
                    Text("$minutes 分钟")
                }
            }
        }
    },
    confirmButton = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val values = initial.split(":").map { it.toIntOrNull() ?: 0 }
    val state = rememberTimePickerState(values[0], values[1], true)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave("%02d:%02d".format(state.hour, state.minute)) }) { Text("确定") } },
        title = { Text("选择响铃时间") },
    ) { TimePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onSave(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()) } }) { Text("确定") } },
    ) { DatePicker(state = state) }
}

@Composable
private fun CalendarMonth(selected: LocalDate, previous: () -> Unit, next: () -> Unit, select: (LocalDate) -> Unit) {
    val first = selected.withDayOfMonth(1)
    val leading = first.dayOfWeek.value - 1
    val count = first.lengthOfMonth()
    FormCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = previous) { Text("‹") }
            Text(first.format(DateTimeFormatter.ofPattern("yyyy年M月")), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, color = ZhituColors.Ink)
            TextButton(onClick = next) { Text("›") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, color = ZhituColors.Subtle, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center) } }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(7) { day ->
                    val current = week * 7 + day - leading + 1
                    if (current !in 1..count) Spacer(Modifier.width(36.dp).height(36.dp))
                    else {
                        val date = first.withDayOfMonth(current)
                        val chosen = date == selected
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(if (chosen) ZhituColors.Brand else Color.Transparent).clickable { select(date) }, contentAlignment = Alignment.Center) { Text("$current", color = if (chosen) Color.White else ZhituColors.Ink) }
                    }
                }
            }
        }
    }
}
