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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(draft: EditorDraft, update: (EditorDraft) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var timeDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var soundDialog by remember { mutableStateOf(false) }
    var snoozeDialog by remember { mutableStateOf(false) }
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
            item { NoticeCard("保存后才会写入计划；取消或返回不会修改已有闹钟。", ZhituColors.Sky, ZhituColors.Blue) }
        }
    }
    if (timeDialog) TimePickerSheet(draft.time, { update(draft.copy(time = it)); timeDialog = false }, { timeDialog = false })
    if (dateDialog) DatePickerSheet({ date -> update(draft.copy(date = date)); dateDialog = false }, { dateDialog = false })
    if (soundDialog) { val uris = listOf(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, android.provider.Settings.System.DEFAULT_RINGTONE_URI, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI); AlertDialog(onDismissRequest = { soundDialog = false }, title = { Text("选择系统铃声") }, text = { Column { uris.forEach { uri -> val title = android.media.RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "系统铃声"; Row(Modifier.fillMaxWidth().clickable { android.media.RingtoneManager.getRingtone(context, uri)?.play(); update(draft.copy(ringtone = title, soundUri = uri.toString())); soundDialog = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = uri.toString() == draft.soundUri, onClick = null); Text(title) } } } }, confirmButton = {}) }
    if (snoozeDialog) AlertDialog(onDismissRequest = { snoozeDialog = false }, title = { Text("贪睡时长") }, text = { LazyColumn { items((1..30).toList()) { minutes -> Row(Modifier.fillMaxWidth().clickable { update(draft.copy(snoozeMinutes = minutes)); snoozeDialog = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = draft.snoozeMinutes == minutes, onClick = null); Text("$minutes 分钟") } } } }, confirmButton = {})
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除闹钟？") }, text = { Text("删除后将取消本机已注册的后续提醒。") }, confirmButton = { TextButton(onClick = onDelete) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@Composable
fun SettingsScreen(settings: com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings, onSettingsChange: (com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings) -> Unit, onCalendar: () -> Unit, onRoute: () -> Unit, onNavigate: (ZhituDestination) -> Unit, onCredentials: () -> Unit, onDiagnostics: () -> Unit, onHistory: () -> Unit, onWeather: () -> Unit, onOnboarding: () -> Unit) {
    Scaffold(
    topBar = { ZhituTopBar("设置", subtitle = "本地数据与系统能力") }, bottomBar = { ZhituNav(selected = ZhituDestination.SETTINGS, onNavigate = onNavigate) },
) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SettingsGroup("计划") { SettingRow("工作日日历", "本地规则", onCalendar); SettingRow("常用地点", "家、公司", onRoute) } }
        item { SettingsGroup("提醒") { Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) { Text("通知摘要", Modifier.weight(1f)); Switch(settings.notificationSummary, { onSettingsChange(settings.copy(notificationSummary = it)) }) }; Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) { Text("锁屏摘要", Modifier.weight(1f)); Switch(settings.lockScreenSummary, { onSettingsChange(settings.copy(lockScreenSummary = it)) }) } } }
        item { BufferEditor("工作日天气缓冲", settings.workdayWeatherBuffers) { onSettingsChange(settings.copy(workdayWeatherBuffers = it)) } }
        item { BufferEditor("周末天气缓冲", settings.weekendWeatherBuffers) { onSettingsChange(settings.copy(weekendWeatherBuffers = it)) } }
        item { BufferEditor("法定休息日天气缓冲", settings.holidayWeatherBuffers) { onSettingsChange(settings.copy(holidayWeatherBuffers = it)) } }
        item { SettingsGroup("数据服务") { SettingRow("高德地图与路线", "地点、路线与路况", onRoute); SettingRow("接口凭据", "高德与天气", onCredentials) } }
        item { SettingsGroup("可靠性") { SettingRow("权限与诊断", "查看状态", onDiagnostics); SettingRow("闹钟记录", "本机事件", onHistory) } }
        item { SettingsGroup("其他") { SettingRow("高德专项授权", if (settings.amapConsentGranted) "已同意，可重新设置" else "未同意", onOnboarding); SettingRow("首次引导", "重新查看", onOnboarding) } }
    }
}
}

@Composable
fun HistoryScreen(events: List<com.ljwzz.weathertrafficalarm.core.model.AlarmEvent>, onBack: () -> Unit) {
    var days by remember { mutableStateOf(30) }
    var result by remember { mutableStateOf<com.ljwzz.weathertrafficalarm.core.model.AlarmEventType?>(null) }
    val filtered = events.filter { event -> event.createdAt >= System.currentTimeMillis() - days * 86_400_000L && (result == null || event.type == result) }.sortedByDescending { it.createdAt }
    Scaffold(topBar = { ZhituTopBar("闹钟记录", navigation = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 7, 30).forEach { value -> FilterChip(selected = days == value, onClick = { days = value }, label = { Text("$value 天") }) } } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = result == null, onClick = { result = null }, label = { Text("全部") }); FilterChip(selected = result == com.ljwzz.weathertrafficalarm.core.model.AlarmEventType.REGISTRATION_FAILED, onClick = { result = com.ljwzz.weathertrafficalarm.core.model.AlarmEventType.REGISTRATION_FAILED }, label = { Text("异常") }); FilterChip(selected = result == com.ljwzz.weathertrafficalarm.core.model.AlarmEventType.DISMISSED, onClick = { result = com.ljwzz.weathertrafficalarm.core.model.AlarmEventType.DISMISSED }, label = { Text("已停止") }) } }
            if (filtered.isEmpty()) item { EmptyProviderCard("暂无记录", "注册、触发、停止、贪睡和异常事件会显示在这里。") }
            items(filtered, key = { it.id }) { event -> FormCard { Text(event.type.name, color = ZhituColors.Brand, fontWeight = FontWeight.Medium); Text(event.message, color = ZhituColors.Ink); Text(java.time.Instant.ofEpochMilli(event.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
fun WeatherEmptyScreen(onBack: () -> Unit) = Scaffold(topBar = { ZhituTopBar("天气", navigation = onBack) }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { EmptyProviderCard("天气服务暂未接入", "此处不展示模拟天气或提前计算。基础闹钟可独立运行。") }
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
private fun FormCard(content: @Composable ColumnScope.() -> Unit) = Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) }

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
