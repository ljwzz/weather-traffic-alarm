package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan

/** The top-level app shell. Service integration is kept outside visual composables. */
@Composable
fun ZhituApp(
    initialDestination: ZhituDestination = ZhituDestination.HOME,
    ringingOccurrenceId: String? = null,
    viewModel: ZhituViewModel = hiltViewModel(),
) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val upcomingPlans by viewModel.upcomingPlans.collectAsStateWithLifecycle()
    val calendarState by viewModel.calendarState.collectAsStateWithLifecycle()
    val dayOverrides by viewModel.dayOverrides.collectAsStateWithLifecycle()
    val credentialStatus by viewModel.credentialStatus.collectAsStateWithLifecycle()
    val localSettings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsReady by viewModel.settingsReady.collectAsStateWithLifecycle()
    val initialPrivacyAccepted by viewModel.initialPrivacyAccepted.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(if (ringingOccurrenceId == null) initialDestination else ZhituDestination.RINGING) }
    var initialized by remember { mutableStateOf(false) }
    var editorDraft by remember { mutableStateOf(EditorDraft()) }
    val openEditor: (AlarmPlan?) -> Unit = { plan ->
        editorDraft = plan?.toEditorDraft() ?: EditorDraft()
        destination = ZhituDestination.EDITOR
    }
    LaunchedEffect(settingsReady, initialPrivacyAccepted) {
        if (settingsReady && !initialized) { initialized = true; if (initialPrivacyAccepted == false && ringingOccurrenceId == null) destination = ZhituDestination.ONBOARDING }
    }
    LaunchedEffect(error) { if (error != null) { delay(4_000); viewModel.clearError() } }
    BackHandler(enabled = destination != ZhituDestination.HOME && destination != ZhituDestination.RINGING) {
        destination = if (destination == ZhituDestination.EDITOR) ZhituDestination.PLANS else ZhituDestination.HOME
    }

    ZhituTheme {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Surface(color = ZhituColors.Background, modifier = Modifier.fillMaxSize()) {
            when (destination) {
                ZhituDestination.HOME -> HomeScreen(upcomingPlans, { destination = ZhituDestination.PLANS }, openEditor, { destination = ZhituDestination.ROUTE }, { destination = ZhituDestination.SETTINGS })
                ZhituDestination.PLANS -> PlansScreen(plans, openEditor, { destination = ZhituDestination.HOME }, viewModel::setEnabled, { destination = it })
                ZhituDestination.EDITOR -> AlarmEditorScreen(editorDraft, { editorDraft = it }, { destination = ZhituDestination.PLANS }, { viewModel.save(editorDraft) { destination = ZhituDestination.PLANS } }, { editorDraft.id?.let(viewModel::delete); destination = ZhituDestination.PLANS })
                ZhituDestination.ROUTE -> LocalRouteScreen(localSettings, viewModel::updateSettingsWithCompletion, { destination = ZhituDestination.HOME })
                ZhituDestination.CALENDAR -> LocalCalendarScreen(plans, dayOverrides, calendarState, viewModel::saveDayOverride, viewModel::refreshCalendar, { destination = ZhituDestination.SETTINGS })
                ZhituDestination.SETTINGS -> SettingsScreen(
                    settings = localSettings,
                    onSettingsChange = { updated -> viewModel.updateSettings { updated } },
                    onCalendar = { destination = ZhituDestination.CALENDAR },
                    onRoute = { destination = ZhituDestination.ROUTE },
                    onNavigate = { destination = it },
                    onCredentials = { destination = ZhituDestination.CREDENTIALS },
                    onDiagnostics = { destination = ZhituDestination.DIAGNOSTICS },
                    onHistory = { destination = ZhituDestination.HISTORY },
                    onWeather = { destination = ZhituDestination.WEATHER },
                    onOnboarding = { destination = ZhituDestination.ONBOARDING },
                )
                ZhituDestination.CREDENTIALS -> CredentialSettingsScreen(credentialStatus, viewModel::saveCredentialsWithCompletion, viewModel::clearCredentialsWithCompletion) { destination = ZhituDestination.SETTINGS }
                ZhituDestination.DIAGNOSTICS -> AlarmDiagnosticsScreen { destination = ZhituDestination.SETTINGS }
                ZhituDestination.HISTORY -> HistoryScreen(events) { destination = ZhituDestination.SETTINGS }
                ZhituDestination.WEATHER -> WeatherEmptyScreen { destination = ZhituDestination.SETTINGS }
                ZhituDestination.RINGING -> RingingScreen(occurrenceId = ringingOccurrenceId, onDismiss = { ringingOccurrenceId?.let(viewModel::dismiss); destination = ZhituDestination.HOME }, onSnooze = { ringingOccurrenceId?.let(viewModel::snooze); destination = ZhituDestination.HOME })
                ZhituDestination.ONBOARDING -> OnboardingScreen { viewModel.updateSettings { it.copy(privacyAccepted = true) }; destination = ZhituDestination.HOME }
            }
        }
        error?.let { message -> androidx.compose.material3.Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp), action = { androidx.compose.material3.TextButton(viewModel::clearError) { Text("关闭") } }) { Text(message) } }
        }
    }
}

private fun AlarmPlan.toEditorDraft() = EditorDraft(
    id = id,
    name = name,
    time = defaultWakeLocalTime,
    ringtone = sound.title,
    soundUri = sound.uri,
    vibration = vibration.enabled,
    snoozeMinutes = snoozeMinutes,
    date = (schedule as? com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Once)?.date.orEmpty(),
    repeat = when (schedule) {
        is com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Weekly -> RepeatChoice.WEEKLY
        com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Workdays -> RepeatChoice.WORKDAYS
        else -> RepeatChoice.ONCE
    },
    weekdays = (schedule as? com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Weekly)?.days ?: setOf(1, 2, 3, 4, 5),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    plans: List<UpcomingPlan>,
    onPlans: () -> Unit,
    onAdd: (AlarmPlan?) -> Unit,
    onRoute: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar(title = "知途", subtitle = "本地闹钟与出行准备") },
        bottomBar = { ZhituNav(selected = ZhituDestination.HOME, onNavigate = { target -> when (target) { ZhituDestination.PLANS -> onPlans(); ZhituDestination.ROUTE -> onRoute(); ZhituDestination.SETTINGS -> onSettings(); else -> Unit } }) },
        floatingActionButton = { FloatingActionButton(containerColor = ZhituColors.Brand, onClick = { onAdd(null) }) { Text("＋", color = androidx.compose.ui.graphics.Color.White) } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { EmptyProviderCard("天气暂未接入", "天气图与预报将在服务接入后显示；当前不使用模拟数据。") }
            item { SectionTitle("最近的有效闹钟", action = "全部闹钟", onAction = onPlans) }
            if (plans.isEmpty()) item { HomeAlarmHero(onAdd) }
            else items(plans.take(3), key = { it.plan.id }) { item -> HomePlanCard(item, { onAdd(item.plan) }) }
            item { SectionTitle("通勤信息") }
            item { EmptyProviderCard(title = "地图暂未接入", description = "可编辑地点文字和出行方式，但不显示模拟路线或距离。", onClick = onRoute) }
            item { SafetyNotice("闹钟由本机注册；是否已注册以计划状态为准。") }
        }
    }
}

@Composable
private fun HomeAlarmHero(onAdd: (AlarmPlan?) -> Unit) = Card(
    shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ZhituColors.Navy),
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("还没有本地闹钟", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
        Spacer(Modifier.height(8.dp))
        Text("添加日期、时间和重复规则后，本机将注册下一次提醒。", textAlign = TextAlign.Center, color = ZhituColors.Mint, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        TonalButton("添加闹钟", { onAdd(null) })
    }
}

@Composable
private fun HomePlanCard(item: UpcomingPlan, onClick: () -> Unit) = Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ZhituColors.Navy),
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.plan.name, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            StatusBadge(planStateLabel(item.plan), bright = true)
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(java.time.Instant.ofEpochMilli(item.nextWakeAt).atZone(java.time.ZoneId.of(item.plan.zoneId)).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.width(14.dp))
            Text(scheduleLabel(item.plan), color = ZhituColors.Mint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Divider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = .16f))
        Spacer(Modifier.height(12.dp))
        Text("下次 ${java.time.Instant.ofEpochMilli(item.nextWakeAt).atZone(java.time.ZoneId.of(item.plan.zoneId)).format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))}", color = ZhituColors.Mint, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlansScreen(plans: List<AlarmPlan>, onEdit: (AlarmPlan?) -> Unit, onBack: () -> Unit, onEnabled: (String, Boolean) -> Unit, onNavigate: (ZhituDestination) -> Unit) {
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("闹钟", navigation = onBack) },
        bottomBar = { ZhituNav(selected = ZhituDestination.PLANS, onNavigate = onNavigate) },
        floatingActionButton = { FloatingActionButton(containerColor = ZhituColors.Brand, onClick = { onEdit(null) }) { Text("＋", color = androidx.compose.ui.graphics.Color.White) } },
    ) { padding ->
        if (plans.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) { HomeAlarmHero(onEdit) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("本机闹钟", style = MaterialTheme.typography.titleLarge, color = ZhituColors.Ink) }
            items(plans, key = { it.id }) { plan -> PlanRow(plan, { onEdit(plan) }, { onEnabled(plan.id, it) }) }
        }
    }
}

@Composable
private fun PlanRow(plan: AlarmPlan, onClick: () -> Unit, onEnabled: (Boolean) -> Unit) = Card(
    modifier = Modifier.fillMaxWidth().testTag("alarm_${plan.id}").clickable(onClick = onClick), shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = ZhituColors.Surface),
) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(plan.defaultWakeLocalTime, style = MaterialTheme.typography.displaySmall, color = ZhituColors.Ink)
            Text(plan.name, fontWeight = FontWeight.Medium, color = ZhituColors.Ink)
            Text(scheduleLabel(plan), style = MaterialTheme.typography.bodySmall, color = ZhituColors.Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            StatusBadge(planStateLabel(plan))
            Spacer(Modifier.height(10.dp))
            Switch(checked = plan.enabled, onCheckedChange = onEnabled)
        }
    }
}

private fun scheduleLabel(plan: AlarmPlan): String = when (val schedule = plan.schedule) {
    is com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Once -> "${schedule.date} · 单次"
    is com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Weekly -> "每周 ${schedule.days.sorted().joinToString("") { listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] }}"
    com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule.Workdays -> "工作日"
    null -> "请选择日期或重复规则"
}
private fun planStateLabel(plan: AlarmPlan): String = when (plan.armedState) {
    com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState.SCHEDULED -> "已注册"
    com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState.NEEDS_PERMISSION -> "待授权"
    com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState.FAILED -> "注册失败"
    com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState.COMPLETED -> "已完成"
    com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState.NEEDS_RULE -> "需设置日期"
    com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState.DISABLED -> "已停用"
}
