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
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarUiState
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmEvent
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.DayStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(
    draft: EditorDraft,
    calendarState: CalendarUiState,
    calendarOverrides: Map<String, DayStatus>,
    update: (EditorDraft) -> Unit,
    onCalendarPreviewRefresh: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    commuteSummary: String = "使用全局通勤",
    onOpenCommuteOverride: () -> Unit = {},
) {
    val context = LocalContext.current
    var timeDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var soundDialog by remember { mutableStateOf(false) }
    var snoozeDialog by remember { mutableStateOf(false) }
    var arrivalDialog by remember { mutableStateOf(false) }
    var preparationDialog by remember { mutableStateOf(false) }
    var maxAdvanceDialog by remember { mutableStateOf(false) }
    var commuteAdvanceExpanded by remember(draft.id) { mutableStateOf(false) }
    LaunchedEffect(draft.repeat) {
        if (draft.repeat == RepeatChoice.WORKDAYS) onCalendarPreviewRefresh()
    }
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
                    if (draft.repeat != RepeatChoice.WORKDAYS) Spacer(Modifier.height(12.dp))
                    when (draft.repeat) {
                        RepeatChoice.ONCE -> SettingRow("响铃日期", draft.date.ifBlank { "请选择" }, { dateDialog = true }, "alarm_date")
                        RepeatChoice.WEEKLY -> WeekdaySelector(draft.weekdays) { days -> update(draft.copy(weekdays = days)) }
                        RepeatChoice.WORKDAYS -> Unit
                    }
                }
            }
            if (draft.repeat == RepeatChoice.WORKDAYS) {
                item(key = "workday_preview") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkdayPreviewCard(
                            calendarState = calendarState,
                            today = LocalDate.now(ZoneId.of(draft.zoneId)),
                            overrides = calendarOverrides,
                        )
                        val calendarStatus = when {
                            calendarState.loading -> "正在刷新日历。"
                            !calendarState.loaded -> "正在读取本地日历。"
                            calendarState.error != null -> "日历刷新失败。"
                            else -> ""
                        }
                        if (calendarStatus.isNotEmpty() || calendarState.days.isEmpty()) {
                            val source = if (calendarState.days.isEmpty()) {
                                "日历数据不可用，使用星期规则。"
                            } else {
                                "使用已有年度日历缓存。"
                            }
                            Column(Modifier.fillMaxWidth().testTag("workday_preview_status")) {
                                NoticeCard(
                                    calendarStatus + source,
                                    if (calendarState.days.isEmpty()) ZhituColors.AmberBackground else ZhituColors.Mint,
                                    if (calendarState.days.isEmpty()) ZhituColors.Amber else ZhituColors.Brand,
                                )
                            }
                        }
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
            item(key = "commute_advance") {
                FormCard {
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("alarm_editor_commute_advance")
                            .clickable { commuteAdvanceExpanded = !commuteAdvanceExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("通勤与提前提醒", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                            Text(
                                commuteSummary,
                                modifier = Modifier.testTag("alarm_editor_commute_summary"),
                                color = ZhituColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            if (commuteAdvanceExpanded) "收起" else "设置",
                            color = ZhituColors.Brand,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (commuteAdvanceExpanded) {
                        Spacer(Modifier.height(6.dp))
                        SettingRow("期望到达时间", draft.arrivalLocalTime, { arrivalDialog = true }, "arrival_time")
                        SettingRow("准备时间", "${draft.preparationMinutes} 分钟", { preparationDialog = true }, "preparation_minutes")
                        SettingRow("最多提前", "${draft.maxAdvanceMinutes} 分钟", { maxAdvanceDialog = true }, "max_advance_minutes")
                        SettingRow("计划通勤覆盖", commuteSummary, onOpenCommuteOverride, "open_plan_commute_override")
                        Text("有效通勤会参与提前提醒。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
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
fun HistoryScreen(
    events: List<AlarmEvent>,
    decisions: List<AlarmDecision>,
    onDecision: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    var days by remember { mutableStateOf(30) }
    var result by remember { mutableStateOf<HistoryResultFilter?>(null) }
    val now = System.currentTimeMillis()
    val filtered = historyItems(events, decisions)
        .filter { item -> item.timestamp >= now - days * 86_400_000L && (result == null || item.filter == result) }
    Scaffold(topBar = { ZhituTopBar("闹钟记录", navigation = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("history_content"), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            items(filtered, key = { it.id }) { item -> HistoryItemCard(item, onDecision) }
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
    val effectiveOutcome: EvaluationOutcome,
    override val timestamp: Long,
) : HistoryItem {
    override val id: String = "decision:${decision.decisionId}"
    override val filter: HistoryResultFilter = if (decision.applicationOutcome == "FAILED") HistoryResultFilter.FAILED else effectiveOutcome.toHistoryFilter()
}

private data class EventHistoryItem(val event: AlarmEvent) : HistoryItem {
    override val id: String = "event:${event.id}"
    override val timestamp: Long = event.createdAt
    override val filter: HistoryResultFilter = HistoryResultFilter.EVENT
}

private fun historyItems(
    events: List<AlarmEvent>,
    decisions: List<AlarmDecision>,
): List<HistoryItem> {
    return buildList {
        decisions.forEach { decision ->
            add(
                DecisionHistoryItem(
                    decision = decision,
                    effectiveOutcome = decision.displayOutcome(),
                    timestamp = decision.generatedTimestamp(),
                ),
            )
        }
        events.forEach { add(EventHistoryItem(it)) }
    }.sortedByDescending(HistoryItem::timestamp)
}

@Composable
private fun HistoryItemCard(item: HistoryItem, onDecision: (String) -> Unit) = FormCard {
    when (item) {
        is EventHistoryItem -> {
            Text(item.event.type.name, color = ZhituColors.Brand, fontWeight = FontWeight.Medium)
            Text(item.event.message, color = ZhituColors.Ink)
            HistoryTimestamp(item.timestamp)
        }
        is DecisionHistoryItem -> {
            val decision = item.decision
            val summary = decision.toDecisionDetailUi()
            Text(summary.title, color = if (summary.evaluationTone == DecisionDetailTone.WARNING) ZhituColors.Amber else item.effectiveOutcome.toColor(), fontWeight = FontWeight.Medium)
            Text("${summary.planName} · ${summary.targetDate}", color = ZhituColors.Ink)
            Text(summary.applicationLabel, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text("基础 ${summary.baseWake} · 建议 ${summary.recommendedWake}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text("评估于 ${summary.evaluatedAt}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onDecision(decision.decisionId) }, modifier = Modifier.testTag("history_decision_${decision.decisionId}")) { Text("查看本次评估") }
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
private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private fun EvaluationOutcome.toHistoryFilter(): HistoryResultFilter = when (this) {
    EvaluationOutcome.SUCCESS -> HistoryResultFilter.SUCCESS
    EvaluationOutcome.FAILED -> HistoryResultFilter.FAILED
    EvaluationOutcome.STALE -> HistoryResultFilter.STALE
    EvaluationOutcome.SKIPPED -> HistoryResultFilter.SKIPPED
}
private fun EvaluationOutcome.toColor(): Color = when (this) {
    EvaluationOutcome.SUCCESS -> ZhituColors.Brand
    EvaluationOutcome.FAILED, EvaluationOutcome.STALE -> ZhituColors.Amber
    EvaluationOutcome.SKIPPED -> ZhituColors.Muted
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
private fun SettingRow(title: String, value: String, onClick: () -> Unit, tag: String? = null) = Row(Modifier.fillMaxWidth().height(50.dp).then(if (tag == null) Modifier else Modifier.testTag(tag)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), color = ZhituColors.Ink); Text(value, color = ZhituColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.width(8.dp)); Text("›", color = ZhituColors.Subtle, style = MaterialTheme.typography.headlineSmall) }

@Composable
fun EmptyProviderCard(title: String, description: String, onClick: (() -> Unit)? = null) = Card(modifier = Modifier.fillMaxWidth().let { if (onClick == null) it else it.clickable(onClick = onClick) }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ZhituColors.Surface), border = BorderStroke(1.dp, ZhituColors.Line)) { Column(Modifier.padding(20.dp)) { Text(title, fontWeight = FontWeight.Bold, color = ZhituColors.Ink); Spacer(Modifier.height(6.dp)); Text(description, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) } }

@Composable
private fun NoticeCard(text: String, background: Color, foreground: Color) = Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = background)) { Text(text, Modifier.padding(16.dp), color = foreground, style = MaterialTheme.typography.bodySmall) }

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
