package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.map.AmapMapUiState
import java.util.UUID

/** The top-level app shell. Service integration is kept outside visual composables. */
@Composable
fun ZhituApp(
    initialDestination: ZhituDestination = ZhituDestination.HOME,
    ringingOccurrenceId: String? = null,
    viewModel: ZhituViewModel = hiltViewModel(),
    permissionViewModel: AlarmPermissionViewModel = viewModel(),
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
    val routeState by viewModel.routeState.collectAsStateWithLifecycle()
    val placePickerState by viewModel.placePickerState.collectAsStateWithLifecycle()
    val mapStatus by viewModel.mapStatus.collectAsStateWithLifecycle()
    val planCommuteEditor by viewModel.planCommuteEditor.collectAsStateWithLifecycle()
    val weatherState by viewModel.weatherState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!permissionViewModel.navigationInitialized || permissionViewModel.entryOccurrenceId != ringingOccurrenceId || permissionViewModel.entryDestination != initialDestination) {
        permissionViewModel.destination = if (ringingOccurrenceId == null) initialDestination else ZhituDestination.RINGING
        permissionViewModel.navigationInitialized = true
        permissionViewModel.entryOccurrenceId = ringingOccurrenceId
        permissionViewModel.entryDestination = initialDestination
        permissionViewModel.cancel()
    }
    var destination by permissionViewModel::destination
    var initialized by permissionViewModel::initialized
    var editorDraft by permissionViewModel::editorDraft
    val permissionAccess = remember(context) { PermissionAccess(context) }
    var permissionSnapshot by remember { mutableStateOf(permissionAccess.read()) }
    var settingsMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun refreshPermissions() { permissionSnapshot = permissionAccess.read() }
    fun openPermissionSettings(setting: PermissionSetting) {
        settingsMessage = when (permissionAccess.openSettings(setting)) {
            SettingsLaunchResult.Opened -> null
            is SettingsLaunchResult.FallbackOpened -> "专项设置入口不可用，已打开备用系统页面。请查找对应权限；若未找到，可返回继续。"
            is SettingsLaunchResult.Unavailable -> "系统设置入口不可用或未找到。请手动打开系统设置中的本应用权限页；仍可返回继续。"
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        settingsMessage = if (granted) null else "通知权限未开启，可再次点按“去设置”手动开启；仍可返回继续。"
    }
    val requestNotification: () -> Unit = {
        refreshPermissions()
        if (!permissionSnapshot.notificationRuntimeGranted && !notificationRequested) {
            notificationRequested = true
            runCatching { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { openPermissionSettings(PermissionSetting.Notifications) }
        } else openPermissionSettings(PermissionSetting.Notifications)
    }
    fun returnFromDiagnostics() {
        refreshPermissions()
        if (permissionViewModel.flow.phase == AlarmEnablePhase.Checking) {
            destination = if (permissionViewModel.flow.pending is AlarmEnableAction.Save) ZhituDestination.EDITOR else ZhituDestination.PLANS
            permissionViewModel.returnFromCheck()
        } else destination = ZhituDestination.SETTINGS
    }
    DisposableEffect(lifecycleOwner, permissionAccess) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermissions() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(permissionViewModel.flow.phase) {
        when (permissionViewModel.flow.phase) {
            AlarmEnablePhase.Ready -> when (val action = permissionViewModel.takeAction()) {
                is AlarmEnableAction.Save -> viewModel.saveWithCompletion(action.draft, permissionViewModel::complete)
                is AlarmEnableAction.Enable -> viewModel.setEnabledWithCompletion(action.planId, true, permissionViewModel::complete)
                null -> Unit
            }
            AlarmEnablePhase.Finished -> { destination = ZhituDestination.PLANS; permissionViewModel.finish() }
            else -> Unit
        }
    }
    var placeTarget by remember { mutableStateOf(PlaceSelectionTarget.ORIGIN) }
    val openEditor: (AlarmPlan?) -> Unit = { plan ->
        editorDraft = plan?.toEditorDraft() ?: EditorDraft()
        destination = ZhituDestination.EDITOR
    }
    LaunchedEffect(settingsReady, initialPrivacyAccepted, localSettings.amapConsentPromptedVersion) {
        if (settingsReady && !initialized) {
            initialized = true
            if ((initialPrivacyAccepted == false || localSettings.amapConsentPromptedVersion == null) && ringingOccurrenceId == null) destination = ZhituDestination.ONBOARDING
        }
    }
    LaunchedEffect(error) { if (error != null) { delay(4_000); viewModel.clearError() } }
    LaunchedEffect(localSettings.amapConsentGranted, credentialStatus.hasAmapSdkKey) { viewModel.initializeAmap(context) }
    LaunchedEffect(localSettings.originId, localSettings.destinationId, localSettings.commuteMode, localSettings.amapConsentGranted) { viewModel.refreshRoute() }
    BackHandler(enabled = destination != ZhituDestination.HOME && destination != ZhituDestination.RINGING) {
        if (destination == ZhituDestination.DIAGNOSTICS) returnFromDiagnostics()
        else {
            permissionViewModel.cancel()
            destination = if (destination == ZhituDestination.EDITOR) ZhituDestination.PLANS else ZhituDestination.HOME
        }
    }

    ZhituTheme {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Surface(color = ZhituColors.Background, modifier = Modifier.fillMaxSize()) {
            when (destination) {
                ZhituDestination.HOME -> HomeScreen(
                    plans = upcomingPlans,
                    onPlans = { destination = ZhituDestination.PLANS },
                    onAdd = openEditor,
                    onRoute = { destination = ZhituDestination.ROUTE },
                    onWeather = { destination = ZhituDestination.WEATHER },
                    onSettings = { destination = ZhituDestination.SETTINGS },
                )
                ZhituDestination.PLANS -> PlansScreen(plans, openEditor, { destination = ZhituDestination.HOME }, { planId, enabled ->
                    if (enabled) {
                        refreshPermissions()
                        permissionViewModel.start(AlarmEnableAction.Enable(planId), permissionSnapshot.signature(permissionViewModel.confirmations))
                    } else viewModel.setEnabled(planId, false)
                }, { destination = it })
                ZhituDestination.EDITOR -> AlarmEditorScreen(editorDraft, { editorDraft = it }, { permissionViewModel.cancel(); destination = ZhituDestination.PLANS }, {
                    refreshPermissions()
                    permissionViewModel.start(AlarmEnableAction.Save(editorDraft), permissionSnapshot.signature(permissionViewModel.confirmations))
                }, { editorDraft.id?.let(viewModel::delete); destination = ZhituDestination.PLANS })
                ZhituDestination.ROUTE -> LocalRouteScreen(
                    settings = localSettings,
                    routeState = routeState,
                    mapStatus = mapStatus,
                    onSave = viewModel::updateSettingsWithCompletion,
                    onBack = { destination = ZhituDestination.HOME },
                    onModeChange = viewModel::setRouteMode,
                    onRefresh = viewModel::refreshRoute,
                    onSelectRoute = viewModel::selectRoute,
                    onTrafficChange = viewModel::setTrafficEnabled,
                    onPickPlace = { target -> placeTarget = target; viewModel.beginPlaceSelection(); destination = ZhituDestination.PLACE_PICKER },
                    onConfigurePlan = { plans.firstOrNull()?.let { viewModel.startPlanCommuteEditor(it.id) }; destination = ZhituDestination.PLAN_COMMUTE },
                )
                ZhituDestination.PLACE_PICKER -> PlacePickerScreen(
                    target = placeTarget,
                    query = placePickerState.query,
                    candidates = placePickerState.candidates.map { place -> PlaceCandidateUi(place.poiId ?: "${place.longitudeGcj02},${place.latitudeGcj02}", place.name, place.displayAddress, placeRef = place) },
                    loading = placePickerState.loading,
                    message = placePickerState.message,
                    mapStatus = mapStatus,
                    mapState = AmapMapUiState(selectedPoint = placePickerState.selected?.let { GeoPoint(it.longitudeGcj02, it.latitudeGcj02) }),
                    onQueryChanged = viewModel::updatePlaceQuery,
                    onUseCurrentLocation = { onComplete -> viewModel.locateCurrentPlace(context, onComplete) },
                    onLocationPermissionDenied = { viewModel.showError("未获得位置权限") },
                    onMapClick = viewModel::selectMapPoint,
                    onConfirm = { candidate ->
                        val place = candidate.placeRef ?: return@PlacePickerScreen
                        when (placeTarget) {
                            PlaceSelectionTarget.PLAN_ORIGIN, PlaceSelectionTarget.PLAN_DESTINATION -> viewModel.setPlanCommutePlace(placeTarget, place)
                            else -> {
                                val favorite = FavoritePlace(UUID.randomUUID().toString(), candidate.name, candidate.address, place)
                                viewModel.updateSettings { current ->
                                    val updatedFavorites = current.favorites.filterNot { it.name == favorite.name && it.address == favorite.address } + favorite
                                    when (placeTarget) {
                                        PlaceSelectionTarget.ORIGIN -> current.copy(favorites = updatedFavorites, originId = favorite.id)
                                        PlaceSelectionTarget.DESTINATION -> current.copy(favorites = updatedFavorites, destinationId = favorite.id)
                                        PlaceSelectionTarget.FAVORITE -> current.copy(favorites = updatedFavorites)
                                        else -> current
                                    }
                                }
                            }
                        }
                        destination = if (placeTarget == PlaceSelectionTarget.PLAN_ORIGIN || placeTarget == PlaceSelectionTarget.PLAN_DESTINATION) ZhituDestination.PLAN_COMMUTE else ZhituDestination.ROUTE
                    },
                    onBack = { destination = if (placeTarget == PlaceSelectionTarget.PLAN_ORIGIN || placeTarget == PlaceSelectionTarget.PLAN_DESTINATION) ZhituDestination.PLAN_COMMUTE else ZhituDestination.ROUTE },
                )
                ZhituDestination.PLAN_COMMUTE -> PlanCommuteScreen(
                    plans = plans,
                    editor = planCommuteEditor,
                    mapStatus = mapStatus,
                    onBack = { destination = ZhituDestination.ROUTE },
                    onSelectPlan = viewModel::startPlanCommuteEditor,
                    onUseGlobal = viewModel::setPlanCommuteUseGlobal,
                    onModeChange = viewModel::setPlanCommuteMode,
                    onPickPlace = { target -> placeTarget = target; viewModel.beginPlaceSelection(); destination = ZhituDestination.PLACE_PICKER },
                    onRefresh = viewModel::refreshPlanCommutePreview,
                    onSelectRoute = viewModel::selectPlanCommuteRoute,
                    onTrafficChange = viewModel::setPlanCommuteTraffic,
                    onSave = viewModel::savePlanCommute,
                )
                ZhituDestination.CALENDAR -> LocalCalendarScreen(plans, dayOverrides, calendarState, viewModel::saveDayOverride, viewModel::refreshCalendar, { destination = ZhituDestination.SETTINGS })
                ZhituDestination.SETTINGS -> SettingsScreen(
                    permissionSnapshot = permissionSnapshot,
                    permissionConfirmations = permissionViewModel.confirmations,
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
                ZhituDestination.CREDENTIALS -> CredentialSettingsScreen(
                    status = credentialStatus,
                    onSave = { input, onComplete ->
                        viewModel.saveCredentialsWithCompletion(input) { failure ->
                            if (failure == null) viewModel.initializeAmap(context)
                            onComplete(failure)
                        }
                    },
                    onClear = viewModel::clearCredentialsWithCompletion,
                    onTestAmapWebKey = viewModel::testAmapWebKey,
                    onTestCaiyun = viewModel::testCaiyun,
                    onBack = { destination = ZhituDestination.SETTINGS },
                )
                ZhituDestination.DIAGNOSTICS -> AlarmDiagnosticsScreen(
                    snapshot = permissionSnapshot,
                    confirmations = permissionViewModel.confirmations,
                    onSetting = ::openPermissionSettings,
                    onConfirm = { permissionViewModel.confirm(it); refreshPermissions() },
                    onRefresh = ::refreshPermissions,
                    onBack = ::returnFromDiagnostics,
                    onNotificationRequest = requestNotification,
                    statusMessage = settingsMessage,
                    returningToAlarm = permissionViewModel.flow.phase == AlarmEnablePhase.Checking,
                )
                ZhituDestination.HISTORY -> HistoryScreen(events) { destination = ZhituDestination.SETTINGS }
                ZhituDestination.WEATHER -> WeatherScreen(
                    state = weatherState,
                    onRefresh = viewModel::refreshWeather,
                    onBack = { destination = ZhituDestination.SETTINGS },
                )
                ZhituDestination.RINGING -> RingingScreen(occurrenceId = ringingOccurrenceId, onDismiss = { ringingOccurrenceId?.let(viewModel::dismiss); destination = ZhituDestination.HOME }, onSnooze = { ringingOccurrenceId?.let(viewModel::snooze); destination = ZhituDestination.HOME })
                ZhituDestination.ONBOARDING -> OnboardingScreen(
                    onGrantAmap = { viewModel.setAmapConsent(true); viewModel.updateSettings { it.copy(privacyAccepted = true) }; destination = ZhituDestination.CREDENTIALS },
                    onSkipAmap = { viewModel.setAmapConsent(false); viewModel.updateSettings { it.copy(privacyAccepted = true) }; destination = ZhituDestination.HOME },
                )
            }
        }
        error?.let { message -> androidx.compose.material3.Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp), action = { androidx.compose.material3.TextButton(viewModel::clearError) { Text("关闭") } }) { Text(message) } }
        if (permissionViewModel.flow.phase == AlarmEnablePhase.Guide) {
            AlarmPermissionGuide(
                missing = permissionSnapshot.signature(permissionViewModel.confirmations).missing,
                onCheck = { permissionViewModel.check(); destination = ZhituDestination.DIAGNOSTICS },
                onContinue = { refreshPermissions(); permissionViewModel.continueWith(permissionSnapshot.signature(permissionViewModel.confirmations)) },
                onCancel = permissionViewModel::cancel,
            )
        }
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
    onWeather: () -> Unit,
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
            item { EmptyProviderCard("彩云天气", "手动刷新通勤天气预览。", onClick = onWeather) }
            item { SectionTitle("最近的有效闹钟", action = "全部闹钟", onAction = onPlans) }
            if (plans.isEmpty()) item { HomeAlarmHero(onAdd) }
            else items(plans.take(3), key = { it.plan.id }) { item -> HomePlanCard(item, { onAdd(item.plan) }) }
            item { SectionTitle("通勤信息") }
            item { EmptyProviderCard(title = "通勤路线", description = "配置起终点、出行方式与高德地图路线。", onClick = onRoute) }
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
        HorizontalDivider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = .16f))
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
