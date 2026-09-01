package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarUiState
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Per-plan calendar editor. Changes are held in Compose state until Save calls
 * the coordinator through [onSave]; a null status restores automatic rules.
 */
@Composable
fun LocalCalendarScreen(
    plans: List<AlarmPlan>,
    overrides: List<WorkdayOverride>,
    calendarState: CalendarUiState,
    onSave: (planId: String, date: String, status: DayStatus?, wake: String?, onComplete: (String?) -> Unit) -> Unit,
    onRefresh: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedPlanId by remember { mutableStateOf(plans.firstOrNull()?.id) }
    var draftStatus by remember { mutableStateOf<DayStatus?>(null) }
    var draftWake by remember { mutableStateOf<String?>(null) }
    var timeDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val activePlan = plans.firstOrNull { it.id == selectedPlanId } ?: plans.firstOrNull()
    val activePlanId = activePlan?.id
    val existing = overrides.firstOrNull { it.planId == activePlanId && it.date == selectedDate.toString() }

    LaunchedEffect(Unit) { onRefresh(false) }
    LaunchedEffect(activePlanId, selectedDate, overrides) {
        draftStatus = existing?.status
        draftWake = existing?.wakeLocalTime
        feedback = null
    }

    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("工作日日历", "识别日期类型，只改动指定的一天", onBack) },
        bottomBar = {
            Button(
                onClick = {
                    val plan = activePlan ?: return@Button
                    onSave(plan.id, selectedDate.toString(), draftStatus, draftWake) { failure ->
                        if (failure == null) onBack() else feedback = failure
                    }
                },
                enabled = activePlan != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text(if (draftStatus == null) "恢复自动规则" else "保存单日覆盖") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { CalendarSourceCard(calendarState, onRefresh) }
            item {
                if (plans.isEmpty()) LocalInfoCard("尚未创建闹钟", "先创建闹钟后，才能设置某个计划在指定日期的覆盖规则。")
                else {
                    Text("选择闹钟", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(plans, key = AlarmPlan::id) { plan ->
                            FilterChip(
                                selected = plan.id == activePlanId,
                                onClick = { selectedPlanId = plan.id },
                                label = { Text(plan.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
            item {
                LocalMonthGrid(
                    month = month,
                    selected = selectedDate,
                    officialDays = calendarState.days,
                    overrides = overrides.filter { it.planId == activePlanId },
                    onPrevious = { month = month.minusMonths(1) },
                    onNext = { month = month.plusMonths(1) },
                    onDate = { date -> selectedDate = date },
                )
            }
            if (activePlan != null) {
                item {
                    val automatic = calendarState.days[selectedDate.toString()]
                        ?: fallbackStatus(selectedDate)
                    val source = if (calendarState.days.containsKey(selectedDate.toString())) "年度日历" else "星期回退"
                    LocalCard {
                        Text("${activePlan.name} · ${selectedDate.format(DateTimeFormatter.ofPattern("M月d日"))}", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (draftStatus == null) "自动：${statusLabel(automatic)} · $source" else "手动：${statusLabel(draftStatus!!)}",
                            color = ZhituColors.Muted,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OverrideChoice("自动", draftStatus == null) { draftStatus = null; draftWake = null }
                            OverrideChoice("工作", draftStatus == DayStatus.WORKDAY) { draftStatus = DayStatus.WORKDAY }
                            OverrideChoice("休息", draftStatus == DayStatus.HOLIDAY) { draftStatus = DayStatus.HOLIDAY; draftWake = null }
                        }
                        if (draftStatus == DayStatus.WORKDAY) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { timeDialog = true }) {
                                Text("本日响铃时间", color = ZhituColors.Ink, modifier = Modifier.weight(1f))
                                Text(draftWake ?: "沿用 ${activePlan.defaultWakeLocalTime}", color = ZhituColors.Muted)
                                Text(" ›", color = ZhituColors.Subtle)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("单日覆盖只影响当前计划和所选日期；未保存前离开不会写入。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                }
            }
            feedback?.let { message -> item { LocalInfoCard("无法保存", message, ZhituColors.AmberBackground, ZhituColors.Amber) } }
        }
    }
    if (timeDialog && activePlan != null) LocalTimePicker(
        initial = draftWake ?: activePlan.defaultWakeLocalTime,
        onSave = { draftWake = it; timeDialog = false },
        onDismiss = { timeDialog = false },
    )
}

/** Local-only place and commute-mode editor. It persists as one settings draft. */
@Composable
fun LocalRouteScreen(
    settings: LocalSettings,
    onSave: (LocalSettings, onComplete: (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var favorites by remember(settings) { mutableStateOf(settings.favorites) }
    var originId by remember(settings) { mutableStateOf(settings.originId) }
    var destinationId by remember(settings) { mutableStateOf(settings.destinationId) }
    var mode by remember(settings) { mutableStateOf(settings.commuteMode) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun clearForm() { editingId = null; name = ""; address = "" }
    fun storeFavorite() {
        val trimmedName = name.trim(); val trimmedAddress = address.trim()
        if (trimmedName.isEmpty() || trimmedAddress.isEmpty()) { feedback = "地点名称和地点文字不能为空。"; return }
        val id = editingId ?: UUID.randomUUID().toString()
        favorites = favorites.filterNot { it.id == id } + FavoritePlace(id, trimmedName, trimmedAddress)
        clearForm()
    }
    fun deleteFavorite(id: String) {
        favorites = favorites.filterNot { it.id == id }
        if (originId == id) originId = null
        if (destinationId == id) destinationId = null
        if (editingId == id) clearForm()
    }

    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("地点与出行方式", "本地文字地点，不请求地图或定位", onBack) },
        bottomBar = {
            Button(
                onClick = {
                    val updated = settings.copy(favorites = favorites, originId = originId, destinationId = destinationId, commuteMode = mode)
                    onSave(updated) { failure -> if (failure == null) onBack() else feedback = failure }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text("保存地点与方式") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { LocalMapPlaceholder() }
            item {
                LocalCard {
                    Text("出行方式", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        commuteModes.forEach { item ->
                            FilterChip(selected = mode == item.first, onClick = { mode = item.first }, label = { Text(item.second) })
                        }
                    }
                }
            }
            item {
                LocalCard {
                    Text("起点与终点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("未选择地点不会影响基础闹钟。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    FavoriteSelector("起点", originId, favorites) { id -> originId = id; if (destinationId == id) destinationId = null }
                    FavoriteSelector("终点", destinationId, favorites) { id -> destinationId = id; if (originId == id) originId = null }
                }
            }
            item {
                LocalCard {
                    Text(if (editingId == null) "添加常用地点" else "编辑常用地点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("地点名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(address, { address = it }, label = { Text("地点文字") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (editingId != null) TextButton(::clearForm) { Text("取消编辑") }
                        TextButton(::storeFavorite) { Text(if (editingId == null) "添加地点" else "保存修改") }
                    }
                }
            }
            item {
                LocalCard {
                    Text("常用地点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    if (favorites.isEmpty()) {
                        Spacer(Modifier.height(8.dp)); Text("暂无常用地点。", color = ZhituColors.Muted)
                    } else favorites.forEach { favorite ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editingId = favorite.id; name = favorite.name; address = favorite.address }) {
                                Text(favorite.name, color = ZhituColors.Ink)
                                Text(favorite.address, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton({ deleteFavorite(favorite.id) }) { Text("删除") }
                        }
                    }
                }
            }
            feedback?.let { message -> item { LocalInfoCard("无法保存", message, ZhituColors.AmberBackground, ZhituColors.Amber) } }
        }
    }
}

private val commuteModes = listOf(
    CommuteMode.DRIVING to "驾车", CommuteMode.TRANSIT to "公交", CommuteMode.BICYCLING to "骑行",
    CommuteMode.ELECTRIC_BICYCLE to "电动车", CommuteMode.WALKING to "步行",
)

@Composable private fun CalendarSourceCard(state: CalendarUiState, refresh: (Boolean) -> Unit) = LocalCard(background = if (state.error == null) ZhituColors.Mint else ZhituColors.AmberBackground) {
    Text(if (state.days.isEmpty()) "日历来源 · 星期回退" else "日历来源 · 本地缓存", color = if (state.error == null) ZhituColors.Brand else ZhituColors.Amber, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(state.error ?: state.fetchedAt?.let { "已加载年度日历" } ?: "首次进入正在检查本地日历。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    TextButton({ refresh(true) }, enabled = !state.loading) { Text(if (state.loading) "正在刷新" else "刷新日历") }
}

@Composable private fun LocalMonthGrid(month: YearMonth, selected: LocalDate, officialDays: Map<String, DayStatus>, overrides: List<WorkdayOverride>, onPrevious: () -> Unit, onNext: () -> Unit, onDate: (LocalDate) -> Unit) = LocalCard {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { TextButton(onPrevious) { Text("‹") }; Text(month.format(DateTimeFormatter.ofPattern("yyyy年 M月")), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = ZhituColors.Ink); TextButton(onNext) { Text("›") } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("一","二","三","四","五","六","日").forEach { Text(it, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } }
    val first = month.atDay(1); val start = first.minusDays((first.dayOfWeek.value - 1).toLong()); val overridesByDate = overrides.associateBy(WorkdayOverride::date)
    repeat(6) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { repeat(7) { column -> val date = start.plusDays((row * 7 + column).toLong()); val inMonth = date.month == month.month; val override = overridesByDate[date.toString()]; val status = override?.status ?: officialDays[date.toString()] ?: fallbackStatus(date); val selectedDay = date == selected; val background = when { selectedDay -> ZhituColors.Brand; override != null -> ZhituColors.Mint; status == DayStatus.HOLIDAY -> ZhituColors.Sky; else -> Color.Transparent }; Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background).then(if (inMonth) Modifier.clickable { onDate(date) } else Modifier), contentAlignment = Alignment.Center) { Text(date.dayOfMonth.toString(), color = if (selectedDay) Color.White else if (inMonth) ZhituColors.Ink else ZhituColors.Subtle); if (override != null) Text("•", color = ZhituColors.Brand, modifier = Modifier.align(Alignment.BottomCenter), style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } } } }
}

@Composable private fun FavoriteSelector(label: String, selectedId: String?, favorites: List<FavoritePlace>, onSelected: (String?) -> Unit) { Column { Text(label, color = ZhituColors.Ink); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) { item { FilterChip(selected = selectedId == null, onClick = { onSelected(null) }, label = { Text("未选择") }) }; items(favorites, key = FavoritePlace::id) { place -> FilterChip(selected = selectedId == place.id, onClick = { onSelected(place.id) }, label = { Text(place.name) }) } } } }
@Composable private fun OverrideChoice(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
@Composable private fun LocalMapPlaceholder() = Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)).background(ZhituColors.Mint), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("地图暂未接入", fontWeight = FontWeight.Bold, color = ZhituColors.Ink); Text("可保存地点文字；不会请求定位、路线或距离。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } }
@Composable private fun LocalCard(background: Color = ZhituColors.Surface, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) = Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = background)) { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) }
@Composable private fun LocalInfoCard(title: String, body: String, background: Color = ZhituColors.Surface, color: Color = ZhituColors.Ink) = LocalCard(background) { Text(title, fontWeight = FontWeight.Bold, color = color); Spacer(Modifier.height(6.dp)); Text(body, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
private fun fallbackStatus(date: LocalDate) = if (date.dayOfWeek.value <= 5) DayStatus.WORKDAY else DayStatus.HOLIDAY
private fun statusLabel(status: DayStatus) = if (status == DayStatus.WORKDAY) "工作日" else "休息日"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalTimePicker(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pieces = initial.split(":")
    val state = rememberTimePickerState(
        initialHour = pieces.getOrNull(0)?.toIntOrNull() ?: 6,
        initialMinute = pieces.getOrNull(1)?.toIntOrNull() ?: 0,
        is24Hour = true,
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave("%02d:%02d".format(state.hour, state.minute)) }) {
                Text("确定")
            }
        },
        title = { Text("选择时间") },
    ) {
        TimePicker(state)
    }
}
