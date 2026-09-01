package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
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
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import com.ljwzz.weathertrafficalarm.core.map.AmapMap
import com.ljwzz.weathertrafficalarm.core.map.AmapMapUiState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

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

/**
 * Global commute configuration. Network and map effects deliberately remain in
 * the owner so this composable can keep rendering while a provider is loading
 * or unavailable.
 */
@Composable
fun LocalRouteScreen(
    settings: LocalSettings,
    routeState: RouteUiState,
    mapStatus: MapStatus,
    onSave: (LocalSettings, onComplete: (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
    onModeChange: (CommuteMode) -> Unit,
    onRefresh: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onTrafficChange: (Boolean) -> Unit,
    onPickPlace: ((PlaceSelectionTarget) -> Unit)? = null,
    onConfigurePlan: (() -> Unit)? = null,
) {
    var favorites by remember(settings) { mutableStateOf(settings.favorites) }
    var originId by remember(settings) { mutableStateOf(settings.originId) }
    var destinationId by remember(settings) { mutableStateOf(settings.destinationId) }
    var mode by remember(settings) { mutableStateOf(settings.commuteMode) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun deleteFavorite(id: String) {
        favorites = favorites.filterNot { it.id == id }
        if (originId == id) originId = null
        if (destinationId == id) destinationId = null
    }

    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("通勤路线", "高德地图与实时路况", onBack) },
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
            item { RouteMapPanel(routeMapState(settings, routeState), mapStatus) }
            item {
                LocalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("出行方式", fontWeight = FontWeight.Bold, color = ZhituColors.Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefresh) { Text("刷新") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        commuteModes.forEach { item ->
                            FilterChip(selected = mode == item.first, onClick = { mode = item.first; onModeChange(item.first) }, label = { Text(item.second) })
                        }
                    }
                }
            }
            item {
                LocalCard {
                    Text("起点与终点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("可搜索 POI、使用当前位置或在地图上点选。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    PlaceSummaryRow("起点", favorites.firstOrNull { it.id == originId }?.name ?: "请选择", onClick = { onPickPlace?.invoke(PlaceSelectionTarget.ORIGIN) })
                    PlaceSummaryRow("终点", favorites.firstOrNull { it.id == destinationId }?.name ?: "请选择", onClick = { onPickPlace?.invoke(PlaceSelectionTarget.DESTINATION) })
                }
            }
            item {
                LocalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("候选路线", fontWeight = FontWeight.Bold, color = ZhituColors.Ink, modifier = Modifier.weight(1f))
                        FilterChip(selected = routeState.trafficEnabled, onClick = { onTrafficChange(!routeState.trafficEnabled) }, label = { Text("实时路况") })
                    }
                    Spacer(Modifier.height(6.dp))
                    when {
                        routeState.loading -> Text("正在查询路线…", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        routeState.alternatives.isNotEmpty() -> routeState.alternatives.forEach { alternative -> RouteAlternativeRow(alternative, routeState.selectedRouteId == alternative.id) { onSelectRoute(alternative.id) }
                        }
                        else -> RouteEmptyState(routeState.message ?: "选择起点和终点后显示最多三条路线、距离、预计时间和实时路况。")
                    }
                }
            }
            item {
                LocalCard {
                    Text("常用地点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                    if (favorites.isEmpty()) {
                        Spacer(Modifier.height(8.dp)); Text("暂无常用地点，请选择起点或终点添加。", color = ZhituColors.Muted)
                    } else favorites.forEach { favorite ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onPickPlace?.invoke(PlaceSelectionTarget.FAVORITE) }) {
                                Text(favorite.name, color = ZhituColors.Ink)
                                Text(favorite.address, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton({ deleteFavorite(favorite.id) }) { Text("删除") }
                        }
                    }
                }
            }
            onConfigurePlan?.let { configure ->
                item { LocalCard { Text("已保存计划", fontWeight = FontWeight.Bold, color = ZhituColors.Ink); Spacer(Modifier.height(6.dp)); Text("默认使用全局通勤；可为单个计划设置专属起点、终点和方式。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall); TextButton(onClick = configure) { Text("配置专属通勤") } } }
            }
            feedback?.let { message -> item { LocalInfoCard("无法保存", message, ZhituColors.AmberBackground, ZhituColors.Amber) } }
        }
    }
}

/** Which value receives a confirmed place from [PlacePickerScreen]. */
enum class PlaceSelectionTarget { ORIGIN, DESTINATION, FAVORITE, PLAN_ORIGIN, PLAN_DESTINATION }

/** UI representation intentionally keeps provider DTOs out of Compose state. */
data class PlaceCandidateUi(
    val id: String,
    val name: String,
    val address: String,
    val subtitle: String = "",
    val placeRef: PlaceRef? = null,
)

/**
 * Shared place chooser for the global route and per-plan route editor. The
 * owner debounces [onQueryChanged], maps provider results, and handles map
 * clicks/location permission; this screen only exposes the resulting states.
 */
@Composable
fun PlacePickerScreen(
    target: PlaceSelectionTarget,
    query: String,
    candidates: List<PlaceCandidateUi>,
    loading: Boolean,
    message: String?,
    mapStatus: MapStatus,
    mapState: AmapMapUiState,
    onQueryChanged: (String) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onLocationPermissionDenied: () -> Unit,
    onMapClick: (GeoPoint) -> Unit,
    onConfirm: (PlaceCandidateUi) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(candidates) { mutableStateOf<PlaceCandidateUi?>(candidates.firstOrNull()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) onUseCurrentLocation()
        else onLocationPermissionDenied()
    }
    fun requestCurrentLocation() {
        val permitted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (permitted) onUseCurrentLocation()
        else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val title = when (target) {
        PlaceSelectionTarget.ORIGIN -> "选择起点"
        PlaceSelectionTarget.DESTINATION -> "选择终点"
        PlaceSelectionTarget.FAVORITE -> "添加常用地点"
        PlaceSelectionTarget.PLAN_ORIGIN -> "选择计划起点"
        PlaceSelectionTarget.PLAN_DESTINATION -> "选择计划终点"
    }
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar(title, "搜索、定位或地图点选", onBack) },
        bottomBar = {
            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text("确认地点") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索地点") },
                    singleLine = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = ::requestCurrentLocation, label = { Text("使用当前位置") })
                }
            }
            item {
                Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp))) {
                    if (mapStatus == MapStatus.Ready) AmapMap(state = mapState, modifier = Modifier.fillMaxSize(), onMapClick = onMapClick)
                    else RouteMapPanel(mapState, mapStatus)
                }
            }
            item { Text("在地图上点击即可选点并进行逆地理编码。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
            message?.let { item { LocalInfoCard("地点服务", it, ZhituColors.AmberBackground, ZhituColors.Amber) } }
            when {
                loading -> item { LocalInfoCard("正在搜索", "正在获取地点建议。") }
                candidates.isEmpty() && query.isNotBlank() -> item { LocalInfoCard("未找到地点", "请修改关键词，或在地图上点选位置。") }
                candidates.isEmpty() -> item { LocalInfoCard("输入地点名称", "可显示输入提示和 POI 搜索结果。") }
                else -> items(candidates, key = PlaceCandidateUi::id) { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selected = candidate },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = if (selected?.id == candidate.id) ZhituColors.Mint else ZhituColors.Surface),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(candidate.name, color = ZhituColors.Ink, fontWeight = FontWeight.Medium)
                            Text(candidate.address, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            if (candidate.subtitle.isNotBlank()) Text(candidate.subtitle, color = ZhituColors.Subtle, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** A plan-specific draft; it never writes global preferences. */
@Composable
fun PlanCommuteScreen(
    plans: List<AlarmPlan>,
    editor: PlanCommuteEditorState,
    mapStatus: MapStatus,
    onBack: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onUseGlobal: (Boolean) -> Unit,
    onModeChange: (CommuteMode) -> Unit,
    onPickPlace: (PlaceSelectionTarget) -> Unit,
    onRefresh: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onTrafficChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val selectedPlan = plans.firstOrNull { it.id == editor.planId }
    val mapState = AmapMapUiState(
        markers = listOfNotNull(editor.origin?.toMarker("plan-origin", "计划起点"), editor.destination?.toMarker("plan-destination", "计划终点")),
        routes = editor.route.alternatives,
        selectedRouteId = editor.route.selectedRouteId,
        trafficEnabled = editor.route.trafficEnabled,
    )
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("计划通勤", "专属配置优先于全局通勤", onBack) },
        bottomBar = {
            Button(onClick = onSave, enabled = selectedPlan != null, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand)) { Text("保存计划通勤") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (plans.isEmpty()) item { LocalInfoCard("暂无计划", "创建并保存闹钟后，可为它配置专属通勤。") }
            else {
                item {
                    Text("选择计划", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        items(plans, key = AlarmPlan::id) { plan -> FilterChip(selected = editor.planId == plan.id, onClick = { onSelectPlan(plan.id) }, label = { Text(plan.name) }) }
                    }
                }
                item {
                    LocalCard {
                        Text(selectedPlan?.name.orEmpty(), fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                        Spacer(Modifier.height(10.dp))
                        FilterChip(selected = editor.useGlobal, onClick = { onUseGlobal(true) }, label = { Text("使用全局通勤") })
                        Spacer(Modifier.height(8.dp))
                        FilterChip(selected = !editor.useGlobal, onClick = { onUseGlobal(false) }, label = { Text("使用专属通勤") })
                    }
                }
                if (!editor.useGlobal) {
                    item {
                        LocalCard {
                            Text("专属起点与终点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                            PlaceSummaryRow("起点", editor.origin?.name ?: "请选择", onClick = { onPickPlace(PlaceSelectionTarget.PLAN_ORIGIN) })
                            PlaceSummaryRow("终点", editor.destination?.name ?: "请选择", onClick = { onPickPlace(PlaceSelectionTarget.PLAN_DESTINATION) })
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                commuteModes.forEach { (mode, label) -> FilterChip(selected = editor.mode == mode, onClick = { onModeChange(mode) }, label = { Text(label) }) }
                            }
                        }
                    }
                }
                item { RouteMapPanel(mapState, mapStatus) }
                item {
                    LocalCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (editor.useGlobal) "全局有效路线" else "专属路线预览", fontWeight = FontWeight.Bold, color = ZhituColors.Ink, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRefresh) { Text("刷新") }
                        }
                        FilterChip(selected = editor.route.trafficEnabled, onClick = { onTrafficChange(!editor.route.trafficEnabled) }, label = { Text("实时路况") })
                        when {
                            editor.route.loading -> Text("正在查询路线…", color = ZhituColors.Muted)
                            editor.route.alternatives.isNotEmpty() -> editor.route.alternatives.forEach { route -> RouteAlternativeRow(route, editor.route.selectedRouteId == route.id) { onSelectRoute(route.id) } }
                            else -> RouteEmptyState(editor.route.message ?: "请选择地点后查询路线。")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceSummaryRow(label: String, value: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, color = ZhituColors.Ink, modifier = Modifier.width(52.dp))
    Text(value, color = ZhituColors.Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text("›", color = ZhituColors.Subtle, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
}

@Composable
private fun RouteMapPanel(state: AmapMapUiState, mapStatus: MapStatus) = Box(
    Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)).background(ZhituColors.Mint),
    contentAlignment = Alignment.Center,
) {
    if (mapStatus == MapStatus.Ready) AmapMap(state, Modifier.fillMaxSize())
    else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
        Text("地图未就绪", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(mapStatus.label(), color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RouteEmptyState(message: String) = Card(
    colors = CardDefaults.cardColors(containerColor = ZhituColors.Sky),
    shape = RoundedCornerShape(16.dp),
) { Text(message, Modifier.padding(12.dp), color = ZhituColors.Blue, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }

private fun routeMapState(settings: LocalSettings, routeState: RouteUiState): AmapMapUiState {
    val origin = settings.favorites.firstOrNull { it.id == settings.originId }?.placeRef
    val destination = settings.favorites.firstOrNull { it.id == settings.destinationId }?.placeRef
    return AmapMapUiState(
        markers = listOfNotNull(origin?.toMarker("origin", "起点"), destination?.toMarker("destination", "终点")),
        routes = routeState.alternatives,
        selectedRouteId = routeState.selectedRouteId,
        trafficEnabled = routeState.trafficEnabled,
    )
}

private fun PlaceRef.toMarker(id: String, title: String) = com.ljwzz.weathertrafficalarm.core.map.MapMarker(id, GeoPoint(longitudeGcj02, latitudeGcj02), title)
private fun MapStatus.label(): String = when (this) {
    MapStatus.NotInitialized -> "正在初始化高德地图。"
    MapStatus.ConsentRequired -> "请先完成高德地图专项授权。"
    MapStatus.MissingAndroidKey -> "请在凭据页配置高德 Android Key。"
    MapStatus.RendererUnavailable -> "当前模拟器图形环境不兼容高德原生地图；路线结果仍可使用，请在真机验收地图。"
    MapStatus.Failed -> "地图初始化失败，请检查 Key 与网络。"
    MapStatus.Ready -> ""
}

@Composable
private fun RouteAlternativeRow(route: RouteAlternative, selected: Boolean, onClick: () -> Unit) = Card(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = if (selected) ZhituColors.Mint else ZhituColors.Surface),
) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "已选" else "备选", color = ZhituColors.Brand, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(12.dp))
        Text("${route.durationSeconds / 60} 分钟 · ${formatDistance(route.distanceMeters)}", color = ZhituColors.Ink)
    }
}

private fun formatDistance(meters: Long): String = if (meters >= 1_000) "%.1f km".format(meters / 1_000.0) else "$meters m"

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
