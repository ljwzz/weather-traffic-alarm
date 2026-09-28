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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.map.AmapMapUiState
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationTaskState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The top-level app shell. Service integration is kept outside visual composables. */
@Composable
fun ZhituApp(
    initialDestination: ZhituDestination = ZhituDestination.HOME,
    ringingOccurrenceId: String? = null,
    initialDecisionId: String? = null,
    initialDecisionOccurrenceId: String? = null,
    onDecisionDetailVisibilityChanged: (Boolean) -> Unit = {},
    onExternalDecisionBack: () -> Unit = {},
    viewModel: ZhituViewModel = hiltViewModel(),
    diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel(),
    detailViewModel: DecisionDetailViewModel = hiltViewModel(),
    permissionViewModel: AlarmPermissionViewModel = viewModel(),
) {
    val detailLookup by detailViewModel.lookup.collectAsStateWithLifecycle()
    val detailReadError by detailViewModel.readError.collectAsStateWithLifecycle()
    val detailTaskRuns by detailViewModel.taskRuns.collectAsStateWithLifecycle()
    val evaluatingPlanIds by viewModel.evaluatingPlanIds.collectAsStateWithLifecycle()
    var reevaluateFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val upcomingPlans by viewModel.upcomingPlans.collectAsStateWithLifecycle()
    val calendarState by viewModel.calendarState.collectAsStateWithLifecycle()
    val dayOverrides by viewModel.dayOverrides.collectAsStateWithLifecycle()
    val credentialStatus by viewModel.credentialStatus.collectAsStateWithLifecycle()
    val localSettings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsReady by viewModel.settingsReady.collectAsStateWithLifecycle()
    val initialPrivacyAccepted by viewModel.initialPrivacyAccepted.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val decisions by viewModel.decisions.collectAsStateWithLifecycle()
    val occurrences by viewModel.occurrences.collectAsStateWithLifecycle()
    val evaluablePlanIds by viewModel.evaluablePlanIds.collectAsStateWithLifecycle()
    val evaluationTaskStates by viewModel.evaluationTaskStates.collectAsStateWithLifecycle()
    val evaluationSchedulingError by viewModel.evaluationSchedulingError.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val routeState by viewModel.routeState.collectAsStateWithLifecycle()
    val placePickerState by viewModel.placePickerState.collectAsStateWithLifecycle()
    val mapStatus by viewModel.mapStatus.collectAsStateWithLifecycle()
    val planCommuteEditor by viewModel.planCommuteEditor.collectAsStateWithLifecycle()
    val planCommuteOverrides by viewModel.planCommuteOverrides.collectAsStateWithLifecycle()
    val weatherState by viewModel.weatherState.collectAsStateWithLifecycle()
    val homeUiState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val diagnosticEvents by diagnosticsViewModel.events.collectAsStateWithLifecycle()
    val ringtoneReadability by diagnosticsViewModel.ringtoneReadability.collectAsStateWithLifecycle()
    val checkingRingtone by diagnosticsViewModel.checkingRingtone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var detailDecisionId by rememberSaveable { mutableStateOf(initialDecisionId) }
    var detailOccurrenceId by rememberSaveable { mutableStateOf(initialDecisionOccurrenceId) }
    var detailReturn by rememberSaveable { mutableStateOf(ZhituDestination.HOME) }
    var detailExternal by rememberSaveable { mutableStateOf(initialDestination == ZhituDestination.DECISION_DETAIL || initialDecisionId != null || initialDecisionOccurrenceId != null) }
    var detailRecoveryReturn by rememberSaveable { mutableStateOf(false) }
    var suppressHomePreviewRefresh by rememberSaveable { mutableStateOf(detailExternal) }
    if (!permissionViewModel.navigationInitialized || permissionViewModel.entryOccurrenceId != ringingOccurrenceId || permissionViewModel.entryDestination != initialDestination) {
        permissionViewModel.destination = when {
            initialDecisionId != null || initialDecisionOccurrenceId != null -> ZhituDestination.DECISION_DETAIL
            ringingOccurrenceId != null -> ZhituDestination.RINGING
            else -> initialDestination
        }
        permissionViewModel.navigationInitialized = true
        permissionViewModel.entryOccurrenceId = ringingOccurrenceId
        permissionViewModel.entryDestination = initialDestination
        permissionViewModel.cancel()
    }
    var destination by permissionViewModel::destination
    val externalDetailKey = initialDecisionId?.let { "decision:$it" } ?: initialDecisionOccurrenceId?.let { "occurrence:$it" }
        ?: if (initialDestination == ZhituDestination.DECISION_DETAIL) "invalid-detail" else null
    var consumedDetailKey by rememberSaveable { mutableStateOf(externalDetailKey) }
    if (externalDetailKey != null && consumedDetailKey != externalDetailKey) {
        consumedDetailKey = externalDetailKey
        detailDecisionId = initialDecisionId
        detailOccurrenceId = initialDecisionOccurrenceId
        detailExternal = true
        detailRecoveryReturn = false
        suppressHomePreviewRefresh = true
        reevaluateFeedback = null
        destination = ZhituDestination.DECISION_DETAIL
    }
    val currentDestination by rememberUpdatedState(destination)
    val currentSuppressHomeRefresh by rememberUpdatedState(suppressHomePreviewRefresh)
    androidx.compose.runtime.SideEffect {
        onDecisionDetailVisibilityChanged(destination == ZhituDestination.DECISION_DETAIL || detailRecoveryReturn)
    }
    fun openDecision(id: String) {
        detailReturn = destination
        reevaluateFeedback = null
        detailDecisionId = id
        detailOccurrenceId = null
        detailExternal = false
        detailRecoveryReturn = false
        suppressHomePreviewRefresh = true
        destination = ZhituDestination.DECISION_DETAIL
    }
    fun returnFromDecision() {
        if (detailExternal) onExternalDecisionBack() else destination = detailReturn
    }
    fun returnFromRecovery(fallback: ZhituDestination) {
        if (detailRecoveryReturn) {
            detailRecoveryReturn = false
            destination = ZhituDestination.DECISION_DETAIL
        } else destination = fallback
    }

    LaunchedEffect(destination, detailDecisionId, detailOccurrenceId) {
        if (destination == ZhituDestination.DECISION_DETAIL) detailViewModel.open(detailDecisionId, detailOccurrenceId)
    }
    fun navigatePrimary(target: ZhituDestination) {
        suppressHomePreviewRefresh = false
        detailRecoveryReturn = false
        destination = target
        if (target == ZhituDestination.ROUTE) viewModel.initializeAmap(context)
    }
    fun openRecovery(target: ZhituDestination) {
        detailRecoveryReturn = true
        destination = target
    }
    var initialized by permissionViewModel::initialized
    var editorDraft by permissionViewModel::editorDraft
    val permissionAccess = remember(context) { PermissionAccess(context) }
    var permissionSnapshot by remember { mutableStateOf(permissionAccess.read()) }
    var settingsMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun refreshPermissions() { permissionSnapshot = permissionAccess.read() }
    fun refreshDiagnostics() {
        refreshPermissions()
        diagnosticsViewModel.refreshRingtoneReadability(plans.map(AlarmPlan::sound))
    }
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
        } else returnFromRecovery(ZhituDestination.SETTINGS)
    }
    DisposableEffect(lifecycleOwner, permissionAccess) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
                if (currentDestination == ZhituDestination.DECISION_DETAIL) detailViewModel.refresh()
                if (currentDestination == ZhituDestination.HOME && !currentSuppressHomeRefresh) viewModel.refreshHomePreviewsOnForeground()
            }
        }
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
            AlarmEnablePhase.Finished -> { returnFromRecovery(ZhituDestination.PLANS); permissionViewModel.finish() }
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
            if ((initialPrivacyAccepted == false || localSettings.amapConsentPromptedVersion == null) && ringingOccurrenceId == null && !detailExternal) destination = ZhituDestination.ONBOARDING
        }
    }
    LaunchedEffect(error) { if (error != null) { delay(4_000); viewModel.clearError() } }
    LaunchedEffect(localSettings.amapConsentGranted, credentialStatus.hasAmapSdkKey, credentialStatus.amapSdkVersion) {
        if (destination != ZhituDestination.DECISION_DETAIL && !suppressHomePreviewRefresh) viewModel.initializeAmap(context)
    }
    LaunchedEffect(destination, plans.map { plan -> plan.id to plan.sound }) {
        if (destination == ZhituDestination.DIAGNOSTICS) refreshDiagnostics()
    }
    LaunchedEffect(
        destination,
        localSettings.originId,
        localSettings.destinationId,
        localSettings.favorites,
        localSettings.commuteMode,
        localSettings.amapConsentGranted,
        credentialStatus.loaded,
        credentialStatus.storageError,
        credentialStatus.hasAmapWebKey,
        credentialStatus.hasCaiyunAppKey,
        credentialStatus.hasCaiyunSecret,
        credentialStatus.amapWebVersion,
        credentialStatus.caiyunVersion,
        credentialStatus.caiyunTestResult,
    ) {
        if (destination == ZhituDestination.HOME) {
            if (suppressHomePreviewRefresh) suppressHomePreviewRefresh = false
            else viewModel.refreshHomePreviews()
        }
    }
    BackHandler(enabled = destination != ZhituDestination.HOME && destination != ZhituDestination.RINGING) {
        when {
            destination == ZhituDestination.DECISION_DETAIL -> returnFromDecision()
            detailRecoveryReturn && destination in setOf(ZhituDestination.CREDENTIALS, ZhituDestination.ROUTE, ZhituDestination.ONBOARDING, ZhituDestination.EDITOR, ZhituDestination.HISTORY) -> returnFromRecovery(detailReturn)
            else -> when (destination) {
            ZhituDestination.DIAGNOSTICS -> returnFromDiagnostics()
            ZhituDestination.PLAN_COMMUTE -> destination = ZhituDestination.EDITOR
            ZhituDestination.PLACE_PICKER -> destination = if (placeTarget == PlaceSelectionTarget.PLAN_ORIGIN || placeTarget == PlaceSelectionTarget.PLAN_DESTINATION) ZhituDestination.PLAN_COMMUTE else ZhituDestination.ROUTE
            ZhituDestination.CALENDAR, ZhituDestination.CREDENTIALS, ZhituDestination.HISTORY, ZhituDestination.WEATHER, ZhituDestination.WEATHER_BUFFERS -> destination = ZhituDestination.SETTINGS
            else -> {
            permissionViewModel.cancel()
            destination = if (destination == ZhituDestination.EDITOR) ZhituDestination.PLANS else ZhituDestination.HOME
            }
        }
        }
    }

    ZhituTheme {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Surface(color = ZhituColors.Background, modifier = Modifier.fillMaxSize()) {
            when (destination) {
                ZhituDestination.DECISION_DETAIL -> {
                    val anchoredLookup = detailLookup?.takeIf { it.matchesDetailAnchor(detailDecisionId, detailOccurrenceId) }
                    val decision = anchoredLookup.historicalDecision()
                    DecisionDetailScreen(
                        detail = if (detailReadError != null) DecisionDetailUi(false, unavailableMessage = detailReadError)
                            else anchoredLookup?.toDecisionDetailUi() ?: DecisionDetailUi(false, unavailableMessage = "正在读取本地决策记录…"),
                        onBack = ::returnFromDecision,
                        onReevaluate = { decision?.let { historical -> viewModel.reevaluatePlan(historical.planId) { reevaluateFeedback = it } } },
                        reevaluateInProgress = decision?.planId in evaluatingPlanIds,
                        reevaluateFeedback = reevaluateFeedback,
                        onCredentials = { openRecovery(ZhituDestination.CREDENTIALS) },
                        onOnboarding = { openRecovery(ZhituDestination.ONBOARDING) },
                        onCommute = {
                            anchoredLookup.currentPlan()?.let { plan ->
                                editorDraft = plan.toEditorDraft()
                                openRecovery(ZhituDestination.EDITOR)
                            } ?: run { reevaluateFeedback = "该计划已删除，无法编辑通勤配置。" }
                        },
                        onDiagnostics = { openRecovery(ZhituDestination.DIAGNOSTICS) },
                        onRefresh = detailViewModel::refresh,
                        nextRetryLabel = decision?.let { decisionRetryLabel(it, detailTaskRuns) },
                        currentPlanMessage = anchoredLookup?.toDecisionDetailUi()?.currentPlanMessage,
                        onHistory = { openRecovery(ZhituDestination.HISTORY) },
                    )
                }
                ZhituDestination.HOME -> HomeScreen(
                    plans = upcomingPlans,
                    decisions = decisions,
                    evaluationTaskStates = evaluationTaskStates,
                    evaluablePlanIds = evaluablePlanIds,
                    schedulingError = evaluationSchedulingError,
                    homeUiState = homeUiState,
                    mapStatus = mapStatus,
                    onPlans = { navigatePrimary(ZhituDestination.PLANS) },
                    onAdd = openEditor,
                    onEvaluate = viewModel::evaluateNow,
                    onDecision = ::openDecision,
                    onRoute = { navigatePrimary(ZhituDestination.ROUTE) },
                    onWeather = { destination = ZhituDestination.WEATHER },
                    onCredentials = { destination = ZhituDestination.CREDENTIALS },
                    onAmapConsent = { destination = ZhituDestination.ONBOARDING },
                    onRefreshPreviews = { suppressHomePreviewRefresh = false; viewModel.refreshHomePreviews(forceRefresh = true) },
                    onSettings = { navigatePrimary(ZhituDestination.SETTINGS) },
                )
                ZhituDestination.PLANS -> PlansScreen(plans, openEditor, { destination = ZhituDestination.HOME }, { planId, enabled ->
                    if (enabled) {
                        refreshPermissions()
                        permissionViewModel.start(AlarmEnableAction.Enable(planId), permissionSnapshot.signature(permissionViewModel.confirmations))
                    } else viewModel.setEnabled(planId, false)
                }, ::navigatePrimary, decisions = decisions, onDecision = ::openDecision)
                ZhituDestination.EDITOR -> AlarmEditorScreen(
                    draft = editorDraft,
                    commuteSummary = if (editorDraft.commute?.useGlobal ?: (planCommuteOverrides[editorDraft.id] == null)) "使用全局通勤" else "使用本计划通勤覆盖",
                    onOpenCommuteOverride = {
                        viewModel.startDraftCommuteEditor(editorDraft)
                        destination = ZhituDestination.PLAN_COMMUTE
                    },
                    calendarState = calendarState,
                    calendarOverrides = dayOverrides
                        .filter { it.planId == editorDraft.id }
                        .associate { it.date to it.status },
                    update = { editorDraft = it },
                    onCalendarPreviewRefresh = { viewModel.refreshCalendar() },
                    onCancel = { permissionViewModel.cancel(); returnFromRecovery(ZhituDestination.PLANS) },
                    onSave = {
                        refreshPermissions()
                        permissionViewModel.start(AlarmEnableAction.Save(editorDraft), permissionSnapshot.signature(permissionViewModel.confirmations))
                    },
                    onDelete = { editorDraft.id?.let(viewModel::delete); returnFromRecovery(ZhituDestination.PLANS) },
                )
                ZhituDestination.ROUTE -> LocalRouteScreen(
                    settings = localSettings,
                    routeState = routeState,
                    mapStatus = mapStatus,
                    onSave = viewModel::updateSettingsWithCompletion,
                    onBack = { returnFromRecovery(ZhituDestination.HOME) },
                    onModeChange = viewModel::setRouteMode,
                    onRefresh = viewModel::refreshRoute,
                    onSelectRoute = viewModel::selectRoute,
                    onTrafficChange = viewModel::setTrafficEnabled,
                    onPickPlace = { target -> placeTarget = target; viewModel.beginPlaceSelection(); destination = ZhituDestination.PLACE_PICKER },
                    onConfigurePlan = { destination = ZhituDestination.PLANS },
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
                    planName = editorDraft.name.ifBlank { "闹钟" },
                    editor = planCommuteEditor,
                    mapStatus = mapStatus,
                    onBack = { destination = ZhituDestination.EDITOR },
                    onUseGlobal = viewModel::setPlanCommuteUseGlobal,
                    onModeChange = viewModel::setPlanCommuteMode,
                    onPickPlace = { target -> placeTarget = target; viewModel.beginPlaceSelection(); destination = ZhituDestination.PLACE_PICKER },
                    onRefresh = viewModel::refreshPlanCommutePreview,
                    onSelectRoute = viewModel::selectPlanCommuteRoute,
                    onTrafficChange = viewModel::setPlanCommuteTraffic,
                    onSave = {
                        runCatching { editorDraft.withCommute(planCommuteEditor) }
                            .onSuccess { editorDraft = it; destination = ZhituDestination.EDITOR }
                            .onFailure { viewModel.showError(it.message ?: "请检查通勤配置") }
                    },
                )
                ZhituDestination.CALENDAR -> LocalCalendarScreen(plans, dayOverrides, calendarState, viewModel::saveDayOverride, viewModel::refreshCalendar, { destination = ZhituDestination.SETTINGS })
                ZhituDestination.SETTINGS -> SettingsScreen(
                    permissionSnapshot = permissionSnapshot,
                    permissionConfirmations = permissionViewModel.confirmations,
                    onCalendar = { destination = ZhituDestination.CALENDAR },
                    onRoute = { navigatePrimary(ZhituDestination.ROUTE) },
                    onNavigate = ::navigatePrimary,
                    onCredentials = { destination = ZhituDestination.CREDENTIALS },
                    onDiagnostics = { destination = ZhituDestination.DIAGNOSTICS },
                    onHistory = { destination = ZhituDestination.HISTORY },
                    onWeather = { destination = ZhituDestination.WEATHER },
                    onOnboarding = { destination = ZhituDestination.ONBOARDING },
                )
                ZhituDestination.WEATHER_BUFFERS -> WeatherBuffersScreen(
                    settings = localSettings,
                    onWeatherBufferChange = { kind, buffers ->
                        viewModel.updateSettings { current ->
                            when (kind) {
                                WeatherBufferKind.Workday -> current.copy(workdayWeatherBuffers = buffers)
                                WeatherBufferKind.Weekend -> current.copy(weekendWeatherBuffers = buffers)
                                WeatherBufferKind.LegalRest -> current.copy(holidayWeatherBuffers = buffers)
                            }
                        }
                    },
                    onBack = { destination = ZhituDestination.SETTINGS },
                )
                ZhituDestination.CREDENTIALS -> CredentialSettingsScreen(
                    status = credentialStatus,
                    onLoadKeys = viewModel::credentialEditorKeys,
                    onSave = { input, onComplete ->
                        viewModel.saveCredentialsWithCompletion(input) { failure ->
                            if (failure == null) viewModel.initializeAmap(context)
                            onComplete(failure)
                        }
                    },
                    onClear = viewModel::clearCredentialsWithCompletion,
                    onTestAmapWebKey = viewModel::testAmapWebKey,
                    onTestCaiyun = viewModel::testCaiyun,
                    onBack = { returnFromRecovery(ZhituDestination.SETTINGS) },
                )
                ZhituDestination.DIAGNOSTICS -> AlarmDiagnosticsScreen(
                    snapshot = permissionSnapshot,
                    confirmations = permissionViewModel.confirmations,
                    appVersion = diagnosticsViewModel.appVersion,
                    sdkInt = diagnosticsViewModel.sdkInt,
                    calendarDiagnostics = calendarState.diagnostics,
                    diagnosticEvents = diagnosticEvents,
                    ringtoneReadability = ringtoneReadability,
                    checkingRingtone = checkingRingtone,
                    onSetting = ::openPermissionSettings,
                    onConfirm = { permissionViewModel.confirm(it); refreshPermissions() },
                    onRefresh = ::refreshDiagnostics,
                    onBack = ::returnFromDiagnostics,
                    onNotificationRequest = requestNotification,
                    statusMessage = settingsMessage,
                    returningToAlarm = permissionViewModel.flow.phase == AlarmEnablePhase.Checking,
                )
                ZhituDestination.HISTORY -> HistoryScreen(events, decisions, onDecision = ::openDecision, onBack = { returnFromRecovery(ZhituDestination.SETTINGS) })
                ZhituDestination.WEATHER -> WeatherScreen(
                    state = weatherState,
                    onRefresh = viewModel::refreshWeather,
                    onBack = { destination = ZhituDestination.SETTINGS },
                )
                ZhituDestination.RINGING -> {
                    LaunchedEffect(ringingOccurrenceId) {
                        ringingOccurrenceId?.let { id ->
                            context.startActivity(com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory(context).createShowAlarmIntent(id))
                        }
                        destination = ZhituDestination.PLANS
                    }
                }
                ZhituDestination.ONBOARDING -> OnboardingScreen(
                    onGrantAmap = { viewModel.setAmapConsent(true); viewModel.updateSettings { it.copy(privacyAccepted = true) }; returnFromRecovery(ZhituDestination.CREDENTIALS) },
                    onSkipAmap = { viewModel.setAmapConsent(false); viewModel.updateSettings { it.copy(privacyAccepted = true) }; returnFromRecovery(ZhituDestination.HOME) },
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

internal fun AlarmPlan.toEditorDraft() = EditorDraft(
    id = id,
    zoneId = zoneId,
    name = name,
    time = defaultWakeLocalTime,
    ringtone = sound.title,
    soundUri = sound.uri,
    vibration = vibration.enabled,
    snoozeMinutes = snoozeMinutes,
    arrivalLocalTime = arrivalLocalTime,
    preparationMinutes = preparationMinutes,
    maxAdvanceMinutes = maxAdvanceMinutes,
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
internal fun HomeScreen(
    plans: List<UpcomingPlan>,
    decisions: List<AlarmDecision>,
    evaluationTaskStates: Map<String, EvaluationTaskState>,
    evaluablePlanIds: Set<String>,
    schedulingError: String?,
    homeUiState: HomeUiState,
    mapStatus: MapStatus,
    onPlans: () -> Unit,
    onAdd: (AlarmPlan?) -> Unit,
    onEvaluate: (String) -> Unit,
    onRoute: () -> Unit,
    onWeather: () -> Unit,
    onCredentials: () -> Unit,
    onAmapConsent: () -> Unit,
    onRefreshPreviews: () -> Unit,
    onSettings: () -> Unit,
    onDecision: (String) -> Unit = {},
) {
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar(title = "知途", subtitle = "本地闹钟与出行准备") },
        bottomBar = { ZhituNav(selected = ZhituDestination.HOME, onNavigate = { target -> when (target) { ZhituDestination.PLANS -> onPlans(); ZhituDestination.ROUTE -> onRoute(); ZhituDestination.SETTINGS -> onSettings(); else -> Unit } }) },
        floatingActionButton = { FloatingActionButton(modifier = Modifier.testTag("home_add_alarm"), containerColor = ZhituColors.Brand, onClick = { onAdd(null) }) { Text("＋", color = androidx.compose.ui.graphics.Color.White) } },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = homeUiState.isRefreshing,
            onRefresh = onRefreshPreviews,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("home_pull_refresh"),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("home_content"),
                contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRefreshPreviews, enabled = !homeUiState.isRefreshing, modifier = Modifier.testTag("home_refresh")) {
                        Text(if (homeUiState.isRefreshing) "正在刷新通勤预览" else "刷新通勤预览")
                    }
                }
            }
            item {
                HomeWeatherCard(
                    state = homeUiState.weather,
                    onDetail = onWeather,
                    onCredentials = onCredentials,
                    onRoute = onRoute,
                    onRetry = onRefreshPreviews,
                )
            }
            item { HomeLatestEvaluationCard(decisions.maxByOrNull { it.generatedAt.homeInstant()?.toEpochMilli() ?: Long.MIN_VALUE }, schedulingError, onDecision) }
            item { SectionTitle("最近的有效闹钟", action = "全部闹钟", onAction = onPlans) }
            if (plans.isEmpty()) item { HomeAlarmHero(onAdd) }
            else items(plans.take(3), key = { it.plan.id }) { item ->
                HomePlanCard(
                    item = item,
                    decision = decisions.firstOrNull { it.decisionId == item.occurrence.decisionId }
                        ?: decisions.filter { it.planId == item.plan.id }.maxByOrNull { it.generatedAt.homeInstant()?.toEpochMilli() ?: Long.MIN_VALUE },
                    taskState = evaluationTaskStates[item.plan.id],
                    canEvaluate = item.plan.id in evaluablePlanIds,
                    onClick = { onAdd(item.plan) },
                    onEvaluate = { onEvaluate(item.plan.id) },
                    onDecision = onDecision,
                )
            }
            item { SectionTitle("通勤信息") }
            item {
                HomeRouteCard(
                    state = homeUiState.route,
                    mapStatus = mapStatus,
                    onDetail = onRoute,
                    onCredentials = onCredentials,
                    onConsent = onAmapConsent,
                    onRetry = onRefreshPreviews,
                )
            }
            item { SafetyNotice("闹钟由本机注册；是否已注册以计划状态为准。") }
            }
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
private fun HomeLatestEvaluationCard(decision: AlarmDecision?, schedulingError: String?, onDecision: (String) -> Unit) = FormCard {
    Text("最近自动评估", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
    Spacer(Modifier.height(6.dp))
    if (decision == null) {
        Text("尚无真实评估结果。已启用且配置通勤的闹钟将在后台评估路线、天气和工作日。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
    } else {
        val summary = decision.toDecisionDetailUi()
        Text("${summary.planName.ifBlank { "闹钟" }} · ${summary.targetDate}", color = ZhituColors.Ink)
        Text(summary.title, color = ZhituColors.Brand, style = MaterialTheme.typography.bodyMedium)
        Text(summary.applicationLabel, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
        Text("基础 ${summary.baseWake} · 建议 ${summary.recommendedWake}", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onDecision(decision.decisionId) }, modifier = Modifier.testTag("home_decision_${decision.decisionId}")) { Text("查看本次评估") }
    }
    schedulingError?.let { Text(it, color = ZhituColors.Amber, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun HomePlanCard(
    item: UpcomingPlan,
    decision: AlarmDecision?,
    taskState: EvaluationTaskState?,
    canEvaluate: Boolean,
    onClick: () -> Unit,
    onEvaluate: () -> Unit,
    onDecision: (String) -> Unit,
) = Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = ZhituColors.Navy),
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.plan.name.ifBlank { "闹钟" }, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
        decision?.let {
            val summary = it.toDecisionDetailUi()
            Text("${summary.targetDate} · ${summary.title}", color = ZhituColors.Mint, style = MaterialTheme.typography.labelSmall)
            Text(summary.applicationLabel, color = ZhituColors.Mint, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onDecision(it.decisionId) }, modifier = Modifier.testTag("plan_decision_${item.plan.id}")) { Text("查看提前摘要", color = ZhituColors.Mint) }
        }
        taskState?.let { Text(it.homeTaskLabel(), color = ZhituColors.Mint, style = MaterialTheme.typography.labelSmall) }
        if (canEvaluate) {
            TextButton(onClick = onEvaluate, modifier = Modifier.testTag("evaluate_${item.plan.id}")) { Text("立即评估") }
        }
    }
}

private fun EvaluationTaskState.homeTaskLabel(): String = when (phase) {
    "RUNNING" -> "正在评估"
    "RETRYING" -> nextAttemptAt?.let { "第 $attemptNumber 次重试 · ${it.homeTimestamp()}" }
        ?: "正在进行第 $attemptNumber 次重试"
    else -> nextAttemptAt?.let { "下次评估 · ${it.homeTimestamp()}" } ?: "评估等待系统安排"
}

private fun String?.homeInstant(): Instant? = this?.let { value ->
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
}
private fun String?.homeTime(): String? = homeInstant()?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: this?.substringAfter('T')?.take(5)
private fun Long.homeTimestamp(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlansScreen(plans: List<AlarmPlan>, onEdit: (AlarmPlan?) -> Unit, onBack: () -> Unit, onEnabled: (String, Boolean) -> Unit, onNavigate: (ZhituDestination) -> Unit, decisions: List<AlarmDecision>, onDecision: (String) -> Unit) {
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("闹钟", navigation = onBack) },
        bottomBar = { ZhituNav(selected = ZhituDestination.PLANS, onNavigate = onNavigate) },
        floatingActionButton = { FloatingActionButton(containerColor = ZhituColors.Brand, onClick = { onEdit(null) }) { Text("＋", color = androidx.compose.ui.graphics.Color.White) } },
    ) { padding ->
        if (plans.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) { HomeAlarmHero(onEdit) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("本机闹钟", style = MaterialTheme.typography.titleLarge, color = ZhituColors.Ink) }
            items(plans, key = { it.id }) { plan ->
                Column {
                    PlanRow(plan, { onEdit(plan) }, { onEnabled(plan.id, it) })
                    decisions.filter { it.planId == plan.id }.maxByOrNull { it.generatedAt.homeInstant()?.toEpochMilli() ?: Long.MIN_VALUE }?.let { decision ->
                        TextButton(onClick = { onDecision(decision.decisionId) }, modifier = Modifier.testTag("plans_decision_${plan.id}")) {
                            Text("${decision.targetDate} · ${decision.toDecisionDetailUi().title} · 查看详情")
                        }
                    }
                }
            }
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
            Text(plan.name.ifBlank { "闹钟" }, fontWeight = FontWeight.Medium, color = ZhituColors.Ink)
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
