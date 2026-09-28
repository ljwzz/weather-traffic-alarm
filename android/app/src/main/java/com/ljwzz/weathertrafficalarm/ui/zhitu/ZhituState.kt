package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunCredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunConnectionTestResult
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverride
import com.ljwzz.weathertrafficalarm.core.data.repository.CommuteOverrideMutation
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationWorkScheduler
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationTaskState
import com.ljwzz.weathertrafficalarm.evaluation.userMessage
import com.ljwzz.weathertrafficalarm.core.map.AmapSdkController
import com.ljwzz.weathertrafficalarm.core.map.AmapSdkInitialization
import com.ljwzz.weathertrafficalarm.core.map.MapLocationResult
import com.ljwzz.weathertrafficalarm.core.map.isAmapNativeRendererSupported
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WeatherTimeWindow
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebProvider
import com.ljwzz.weathertrafficalarm.core.network.caiyun.CaiyunCredentials
import com.ljwzz.weathertrafficalarm.core.network.caiyun.CaiyunWeatherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/**
 * The screen only renders repository state. Scheduling writes are deliberately
 * delegated to LocalAlarmCoordinator by the feature-specific integration layer.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ZhituViewModel @Inject constructor(
    private val coordinator: LocalAlarmCoordinator,
    private val decisionRepository: DecisionRepository,
    private val evaluationWorkScheduler: EvaluationWorkScheduler,
    private val calendar: WorkdayCalendarRepository,
    private val settingsStore: LocalSettingsStore,
    private val overrideRepository: WorkdayOverrideRepository,
    private val credentials: com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore,
    private val planCommuteOverrideRepository: PlanCommuteOverrideRepository,
    private val effectiveCommuteResolver: EffectiveCommuteResolver,
    private val amapSdk: AmapSdkController,
    private val amapProvider: AmapWebProvider,
    private val weatherProvider: WeatherProvider,
    private val caiyunWeatherProvider: CaiyunWeatherProvider,
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _settingsReady = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = _settingsReady
    private val _initialPrivacyAccepted = MutableStateFlow<Boolean?>(null)
    val initialPrivacyAccepted: StateFlow<Boolean?> = _initialPrivacyAccepted

    init {
        viewModelScope.launch {
            val stored = runCatching { settingsStore.loadInitial() }
                .onFailure { _error.value = "本地设置读取失败，请重新检查" }
                .getOrDefault(LocalSettings())
            _initialPrivacyAccepted.value = stored.privacyAccepted
            _settingsReady.value = true
        }
    }
    val plans: StateFlow<List<AlarmPlan>> = coordinator.plans
        .map { it.sortedBy { plan -> plan.defaultWakeLocalTime } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val occurrences = coordinator.occurrences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val decisions: StateFlow<List<AlarmDecision>> = decisionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val evaluationTaskStates: StateFlow<Map<String, EvaluationTaskState>> = evaluationWorkScheduler.observeTaskStates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val evaluationSchedulingError: StateFlow<String?> = evaluationWorkScheduler.schedulingError
    val dayOverrides = plans.flatMapLatest { current ->
        if (current.isEmpty()) flowOf(emptyList()) else combine(current.map { overrideRepository.observeForPlan(it.id) }) { groups -> groups.flatMap { it } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val upcomingPlans: StateFlow<List<UpcomingPlan>> = combine(coordinator.plans, coordinator.occurrences) { plans, occurrences ->
        plans.asSequence().filter { it.enabled }.mapNotNull { plan ->
            occurrences.filter { it.planId == plan.id && it.state == com.ljwzz.weathertrafficalarm.core.model.OccurrenceState.SCHEDULED }
                .minByOrNull { it.scheduledWakeAt }?.let { occurrence -> UpcomingPlan(plan, occurrence) }
        }.sortedBy { it.nextWakeAt }.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events = coordinator.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calendarState = calendar.state
    val settings = settingsStore.settings
    val credentialStatus = credentials.state
    private val _mapStatus = MutableStateFlow<MapStatus>(MapStatus.NotInitialized)
    val mapStatus: StateFlow<MapStatus> = _mapStatus
    private val _routeState = MutableStateFlow(RouteUiState())
    val routeState: StateFlow<RouteUiState> = _routeState
    private val _placePickerState = MutableStateFlow(PlacePickerUiState())
    val placePickerState: StateFlow<PlacePickerUiState> = _placePickerState
    private val _planCommuteEditor = MutableStateFlow(PlanCommuteEditorState())
    val planCommuteEditor: StateFlow<PlanCommuteEditorState> = _planCommuteEditor
    private val _weatherState = MutableStateFlow<WeatherUiState>(WeatherUiState.Idle)
    val weatherState: StateFlow<WeatherUiState> = _weatherState
    private val _homeRefreshing = MutableStateFlow(false)
    private var homeRefreshJob: Job? = null
    private val homeRefreshGate = HomeRefreshGate()
    private var routeRefreshJob: Job? = null
    private var routeRefreshInputKey: String? = null
    private var routeRefreshForce = false
    private var weatherRefreshJob: Job? = null
    private var weatherRefreshInputKey: String? = null
    private var routeGeneration = 0L
    private var weatherGeneration = 0L
    private val homeInputClock = MutableStateFlow(Instant.now())
    private val homeInputs = combine(settings, credentialStatus, homeInputClock) { currentSettings, currentCredentials, now ->
        homePreviewInputs(currentSettings, currentCredentials, now)
    }
    private var observedHomeInputs: HomePreviewInputs? = null
    private val homePreviewSnapshot = combine(
        homeInputs,
        credentialStatus,
        settings,
        weatherState,
        routeState,
    ) { inputs, currentCredentials, currentSettings, currentWeather, currentRoute ->
        HomePreviewSnapshot(inputs, currentCredentials, currentSettings, currentWeather, currentRoute)
    }
    internal val homeUiState: StateFlow<HomeUiState> = combine(homePreviewSnapshot, _homeRefreshing) { snapshot, refreshing ->
        HomeUiState(
            weather = homeWeatherCard(snapshot.credentials, snapshot.inputs, snapshot.weather),
            route = homeRouteCard(snapshot.credentials, snapshot.settings, snapshot.inputs, snapshot.route),
            isRefreshing = refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
    init {
        viewModelScope.launch {
            homeInputs.collect { next ->
                val previous = observedHomeInputs
                observedHomeInputs = next
                if (previous == null) return@collect
                if (previous.routeKey != next.routeKey && _routeState.value.inputKey != next.routeKey) {
                    ++routeGeneration
                    routeRefreshJob?.cancel()
                    _routeState.value = RouteUiState(inputKey = next.routeKey)
                }
                val weatherInputKey = when (val state = _weatherState.value) {
                    is WeatherUiState.Loading -> state.inputKey
                    is WeatherUiState.Success -> state.inputKey
                    is WeatherUiState.Error -> state.inputKey
                    WeatherUiState.Idle -> null
                }
                if (previous.weatherKey != next.weatherKey && weatherInputKey != next.weatherKey) {
                    ++weatherGeneration
                    weatherRefreshJob?.cancel()
                    _weatherState.value = WeatherUiState.Idle
                }
            }
        }
    }
    val planCommuteOverrides: StateFlow<Map<String, PlanCommuteOverride>> = plans.flatMapLatest { current ->
        if (current.isEmpty()) flowOf(emptyMap()) else combine(current.map { plan ->
            planCommuteOverrideRepository.observeByPlanId(plan.id).map { plan.id to it }
        }) { pairs -> pairs.mapNotNull { (id, override) -> override?.let { id to it } }.toMap() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val evaluablePlanIds: StateFlow<Set<String>> = combine(plans, settings, planCommuteOverrides) { currentPlans, currentSettings, overrides ->
        val globalCommuteConfigured = effectiveCommuteResolver.resolveGlobal(currentSettings) != null
        currentPlans.asSequence()
            .filter { it.enabled && (overrides.containsKey(it.id) || globalCommuteConfigured) }
            .map(AlarmPlan::id)
            .toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun save(draft: EditorDraft, onSuccess: () -> Unit) {
        saveWithCompletion(draft) { success -> if (success) onSuccess() }
    }

    fun saveWithCompletion(draft: EditorDraft, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            runCatching {
            val schedule = when (draft.repeat) {
                RepeatChoice.ONCE -> AlarmSchedule.Once(draft.date)
                RepeatChoice.WEEKLY -> AlarmSchedule.Weekly(draft.weekdays)
                RepeatChoice.WORKDAYS -> AlarmSchedule.Workdays
            }
            val previous = draft.id?.let { id -> plans.value.firstOrNull { it.id == id } }
            val plan = previous?.copy(
                name = draft.name.trim(), defaultWakeLocalTime = draft.time,
                arrivalLocalTime = draft.arrivalLocalTime,
                preparationMinutes = draft.preparationMinutes,
                maxAdvanceMinutes = draft.maxAdvanceMinutes,
                schedule = schedule, sound = previous.sound.copy(uri = draft.soundUri, title = draft.ringtone),
                vibration = previous.vibration.copy(enabled = draft.vibration), snoozeMinutes = draft.snoozeMinutes,
                enabled = true, armedState = AlarmArmedState.NEEDS_PERMISSION, scheduleError = null,
            ) ?: AlarmPlan(
                id = draft.planId, revision = 0, name = draft.name.trim(), enabled = true,
                zoneId = ZoneId.systemDefault().id, defaultWakeLocalTime = draft.time,
                arrivalLocalTime = draft.arrivalLocalTime, preparationMinutes = draft.preparationMinutes,
                maxAdvanceMinutes = draft.maxAdvanceMinutes, commuteMode = CommuteMode.DRIVING,
                schedule = schedule, armedState = AlarmArmedState.NEEDS_PERMISSION,
                sound = AlarmSound(uri = draft.soundUri, title = draft.ringtone), vibration = com.ljwzz.weathertrafficalarm.core.model.VibrationPattern(enabled = draft.vibration), snoozeMinutes = draft.snoozeMinutes,
            )
            val commuteMutation = draft.commute?.let { commute ->
                commute.toOverride(plan.id)?.let(CommuteOverrideMutation::Replace) ?: CommuteOverrideMutation.Reset
            }
            val saved = coordinator.save(plan, commuteMutation)
            if (commuteMutation != null && saved.revision <= plan.revision) {
                error(saved.scheduleError ?: "闹钟未更新，请检查后重试")
            }
            }.onSuccess { onComplete(true) }.onFailure {
                _error.value = it.message ?: "保存闹钟失败"
                onComplete(false)
            }
        }
    }

    fun setEnabled(planId: String, enabled: Boolean) = safe { coordinator.setEnabled(planId, enabled) }
    fun setEnabledWithCompletion(planId: String, enabled: Boolean, onComplete: (Boolean) -> Unit) = viewModelScope.launch {
        runCatching { coordinator.setEnabled(planId, enabled) }
            .onSuccess { onComplete(true) }
            .onFailure { _error.value = it.message ?: "更新闹钟失败"; onComplete(false) }
    }
    fun delete(planId: String) = safe { coordinator.delete(planId) }
    fun dismiss(occurrenceId: String) = safe { coordinator.dismiss(occurrenceId) }
    fun snooze(occurrenceId: String) = safe { coordinator.snooze(occurrenceId) }
    fun recover() = viewModelScope.launch { coordinator.recover() }
    fun refreshCalendar(force: Boolean = false) = viewModelScope.launch { coordinator.refreshCalendar(force) }
    fun setDayOverride(planId: String, date: String, status: DayStatus, wakeLocalTime: String? = null) = viewModelScope.launch {
        coordinator.setDayOverride(WorkdayOverride(planId = planId, date = date, status = status, wakeLocalTime = wakeLocalTime))
    }
    fun clearDayOverride(planId: String, date: String) = viewModelScope.launch { coordinator.clearDayOverride(planId, date) }
    fun saveDayOverride(planId: String, date: String, status: DayStatus?, wake: String?, onComplete: (String?) -> Unit) = viewModelScope.launch {
        runCatching { if (status == null) coordinator.clearDayOverride(planId, date) else coordinator.setDayOverride(WorkdayOverride(planId, date, status, wake)) }.onSuccess { onComplete(null) }.onFailure { onComplete(it.message ?: "日历保存失败") }
    }
    fun updateSettings(transform: (LocalSettings) -> LocalSettings) = safe { settingsStore.update(transform) }
    fun updateSettingsWithCompletion(settings: LocalSettings, onComplete: (String?) -> Unit) = viewModelScope.launch { runCatching { settingsStore.update { settings } }.onSuccess { onComplete(null) }.onFailure { onComplete(it.message ?: "设置保存失败") } }
    fun saveCredentials(input: CredentialInput) = safe { credentials.save(input) }
    fun clearCredentials() = safe { credentials.clear() }
    fun saveCredentialsWithCompletion(input: CredentialInput, onComplete: (String?) -> Unit) = viewModelScope.launch {
        runCatching { credentials.save(input) }.onSuccess { onComplete(null) }.onFailure { onComplete(it.message ?: "凭据保存失败") }
    }
    fun clearCredentialsWithCompletion(onComplete: (String?) -> Unit) = viewModelScope.launch {
        runCatching { credentials.clear() }.onSuccess { onComplete(null) }.onFailure { onComplete(it.message ?: "凭据清空失败") }
    }
    fun setAmapConsent(granted: Boolean, version: Int = AMAP_CONSENT_VERSION) = safe {
        settingsStore.update { it.copy(amapConsentPromptedVersion = version, amapConsentGranted = granted) }
        _mapStatus.value = MapStatus.NotInitialized
    }

    fun initializeAmap(context: Context) = viewModelScope.launch {
        val serviceCredentials = credentials.credentialsForServiceUse()
        val initialization = amapSdk.initialize(context, serviceCredentials?.amapSdkKey.orEmpty(), settings.value.amapConsentGranted)
        _mapStatus.value = when (initialization) {
            AmapSdkInitialization.Ready -> if (isAmapNativeRendererSupported()) MapStatus.Ready else MapStatus.RendererUnavailable
            AmapSdkInitialization.ConsentRequired -> MapStatus.ConsentRequired
            AmapSdkInitialization.MissingApiKey -> MapStatus.MissingAndroidKey
            AmapSdkInitialization.Failed -> MapStatus.Failed
        }
        if (initialization == AmapSdkInitialization.Ready) refreshRoute()
    }

    fun setRouteMode(mode: CommuteMode) = viewModelScope.launch {
        settingsStore.update { it.copy(commuteMode = mode) }
        refreshRoute()
    }

    /** Refreshes the global route, or the persisted effective commute for [planId]. */
    fun refreshRoute(planId: String? = null, forceRefresh: Boolean = false) {
        startRouteRefresh(planId, forceRefresh)
    }

    private fun startRouteRefresh(planId: String? = null, forceRefresh: Boolean = false): Job {
        val inputs = homePreviewInputs(settings.value, credentialStatus.value)
        val inputKey = if (planId == null) inputs.routeKey else null
        val requestKey = planId?.let { "plan:$it" } ?: inputKey
        val active = routeRefreshJob
        if (active?.isActive == true && routeRefreshInputKey == requestKey && (!forceRefresh || routeRefreshForce)) return active
        active?.cancel()
        val generation = ++routeGeneration
        routeRefreshInputKey = requestKey
        routeRefreshForce = forceRefresh
        return viewModelScope.launch { refreshRouteInternal(planId, forceRefresh, inputKey, generation) }
            .also { routeRefreshJob = it }
    }

    private suspend fun refreshRouteInternal(
        planId: String?,
        forceRefresh: Boolean,
        inputKey: String?,
        generation: Long,
    ) {
        val current = settings.value
        if (!current.amapConsentGranted) {
            _routeState.value = RouteUiState(message = "请先完成高德地图专项授权", inputKey = inputKey)
            return
        }
        if (!credentialStatus.value.hasAmapWebKey) {
            _routeState.value = RouteUiState(message = "请先配置高德 Web Key", inputKey = inputKey)
            return
        }
        val commute = if (planId == null) effectiveCommuteResolver.resolveGlobal(current)
        else effectiveCommuteResolver.resolveForPlan(planId, current)
        if (commute == null) {
            _routeState.value = RouteUiState(message = "请选择带坐标的起点和终点", inputKey = inputKey)
            return
        }
        _routeState.update { state ->
            if (state.inputKey == inputKey) state.copy(loading = true, message = null, refreshError = null)
            else RouteUiState(loading = true, inputKey = inputKey)
        }
        estimateRoute(commute.origin, commute.destination, commute.commuteMode, forceRefresh).onSuccess { estimate ->
            if (generation != routeGeneration || (planId == null && inputKey != homePreviewInputs(settings.value, credentialStatus.value).routeKey)) return@onSuccess
            _routeState.value = RouteUiState(
                alternatives = estimate.alternatives.take(3),
                selectedRouteId = estimate.alternatives.firstOrNull()?.id,
                inputKey = inputKey,
                fetchedAtEpochMillis = estimate.fetchedAtEpochMillis,
                source = estimate.source,
                noRoute = estimate.alternatives.isEmpty(),
            )
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            if (generation != routeGeneration || (planId == null && inputKey != homePreviewInputs(settings.value, credentialStatus.value).routeKey)) return@onFailure
            val previous = _routeState.value
            val now = System.currentTimeMillis()
            _routeState.value = if (
                previous.inputKey == inputKey &&
                previous.alternatives.isNotEmpty() &&
                HomePreviewPolicy.isFresh(previous.fetchedAtEpochMillis, HomePreviewPolicy.ROUTE_TTL_MILLIS, now)
            ) previous.copy(loading = false, refreshError = providerMessage(failure))
            else RouteUiState(message = providerMessage(failure), inputKey = inputKey)
        }
    }

    private suspend fun estimateRoute(origin: PlaceRef, destination: PlaceRef, mode: CommuteMode, forceRefresh: Boolean = false) = runCatching {
        val (routeOrigin, routeDestination) = resolveRoutePlaces(origin, destination, mode)
        amapProvider.estimate(
            RouteRequest(
                origin = GeoPoint(routeOrigin.longitudeGcj02, routeOrigin.latitudeGcj02),
                destination = GeoPoint(routeDestination.longitudeGcj02, routeDestination.latitudeGcj02),
                mode = mode,
                originCity = routeOrigin.citycode.takeIf(String::isNotBlank),
                destinationCity = routeDestination.citycode.takeIf(String::isNotBlank),
                departureAt = java.time.LocalDateTime.now(),
                forceRefresh = forceRefresh,
            ),
        )
    }

    private suspend fun resolveRoutePlaces(
        origin: PlaceRef,
        destination: PlaceRef,
        mode: CommuteMode,
    ): Pair<PlaceRef, PlaceRef> {
        if (mode != CommuteMode.TRANSIT) return origin to destination
        return when (
            val resolution = resolveTransitCityCodes(origin, destination) { point ->
                amapProvider.reverseGeocode(point)
            }
        ) {
            is TransitCityCodeResolution.Ready -> resolution.origin to resolution.destination
            TransitCityCodeResolution.Unavailable -> throw TransitCityCodeUnavailable
        }
    }

    fun selectRoute(routeId: String) { _routeState.update { it.copy(selectedRouteId = routeId) } }
    fun setTrafficEnabled(enabled: Boolean) { _routeState.update { it.copy(trafficEnabled = enabled) } }

    fun beginPlaceSelection() { _placePickerState.value = PlacePickerUiState() }
    fun updatePlaceQuery(query: String) {
        _placePickerState.update { it.copy(query = query, loading = query.isNotBlank(), message = null, candidates = emptyList(), selected = null) }
        viewModelScope.launch {
            delay(300)
            if (query != _placePickerState.value.query || query.isBlank()) return@launch
            if (!settings.value.amapConsentGranted) {
                _placePickerState.update { it.copy(loading = false, message = "请先完成高德地图专项授权") }
                return@launch
            }
            runCatching {
                (amapProvider.inputTips(query) + amapProvider.search(query)).distinctBy { it.poiId ?: "${it.longitudeGcj02},${it.latitudeGcj02}" }
            }.onSuccess { matches ->
                if (query == _placePickerState.value.query) _placePickerState.value = PlacePickerUiState(query = query, candidates = matches, selected = matches.firstOrNull())
            }.onFailure { failure ->
                if (query == _placePickerState.value.query) _placePickerState.value = PlacePickerUiState(query = query, message = providerMessage(failure))
            }
        }
    }

    fun selectPlace(place: PlaceRef) { _placePickerState.update { it.copy(selected = place) } }
    fun selectMapPoint(point: GeoPoint) = viewModelScope.launch {
        _placePickerState.update { it.copy(loading = true, message = null) }
        runCatching { amapProvider.reverseGeocode(point) }
            .onSuccess { place -> _placePickerState.update { it.copy(loading = false, candidates = listOf(place), selected = place) } }
            .onFailure { failure -> _placePickerState.update { it.copy(loading = false, message = providerMessage(failure)) } }
    }

    fun locateCurrentPlace(context: Context, onComplete: () -> Unit = {}) = viewModelScope.launch {
        _placePickerState.update { it.copy(loading = true, message = null) }
        try {
            when (val location = amapSdk.locateOnce(context)) {
                is MapLocationResult.Success -> selectMapPoint(location.point).join()
                MapLocationResult.PermissionDenied -> _placePickerState.update { it.copy(loading = false, message = "未获得位置权限") }
                MapLocationResult.Timeout -> _placePickerState.update { it.copy(loading = false, message = "定位超时") }
                MapLocationResult.InitializationRequired -> _placePickerState.update { it.copy(loading = false, message = "地图尚未初始化") }
                MapLocationResult.Unavailable -> _placePickerState.update { it.copy(loading = false, message = "当前位置不可用") }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _placePickerState.update { it.copy(loading = false, message = "当前位置不可用，请检查定位权限和服务后重试") }
        } finally {
            onComplete()
        }
    }

    fun startPlanCommuteEditor(planId: String) = startDraftCommuteEditor(EditorDraft(id = planId))

    private var commuteEditorJob: Job? = null
    private var commutePreviewJob: Job? = null
    fun startDraftCommuteEditor(draft: EditorDraft) {
        commuteEditorJob?.cancel()
        commutePreviewJob?.cancel()
        _planCommuteEditor.value = PlanCommuteEditorState(planId = draft.planId, loading = true)
        commuteEditorJob = viewModelScope.launch {
        runCatching {
        val current = settings.value
        val override = draft.id?.let { planCommuteOverrideRepository.getByPlanId(it) }
        val effective = effectiveCommuteResolver.resolveGlobal(current)
        _planCommuteEditor.value = PlanCommuteEditorState(
            planId = draft.planId,
            origin = draft.commute?.origin ?: override?.origin ?: effective?.origin,
            destination = draft.commute?.destination ?: override?.destination ?: effective?.destination,
            mode = draft.commute?.mode ?: override?.commuteMode ?: effective?.commuteMode ?: current.commuteMode,
            useGlobal = draft.commute?.useGlobal ?: (override == null),
        )
        refreshPlanCommutePreview()
        }.onFailure {
            if (it is CancellationException) throw it
            _planCommuteEditor.update { state -> state.copy(loading = false, loadError = "读取计划通勤失败，请返回重试") }
        }
        }
    }

    fun setPlanCommuteUseGlobal(useGlobal: Boolean) {
        _planCommuteEditor.update { it.copy(useGlobal = useGlobal) }
        if (useGlobal) refreshPlanCommutePreview()
    }

    fun setPlanCommuteMode(mode: CommuteMode) {
        _planCommuteEditor.update { it.copy(mode = mode, useGlobal = false) }
        refreshPlanCommutePreview()
    }

    fun setPlanCommutePlace(target: PlaceSelectionTarget, place: PlaceRef) {
        _planCommuteEditor.update {
            when (target) {
                PlaceSelectionTarget.PLAN_ORIGIN -> it.copy(origin = place, useGlobal = false)
                PlaceSelectionTarget.PLAN_DESTINATION -> it.copy(destination = place, useGlobal = false)
                else -> it
            }
        }
        refreshPlanCommutePreview()
    }

    /** Shows the draft route while editing, or the resolver-selected commute in global mode. */
    fun refreshPlanCommutePreview() {
        commutePreviewJob?.cancel()
        commutePreviewJob = viewModelScope.launch {
        val editor = _planCommuteEditor.value
        if (editor.planId == null || editor.loading || editor.loadError != null) return@launch
        if (!settings.value.amapConsentGranted) {
            _planCommuteEditor.update { it.copy(route = RouteUiState(message = "请先完成高德地图专项授权")) }
            return@launch
        }
        val effective = if (editor.useGlobal) effectiveCommuteResolver.resolveGlobal(settings.value) else null
        val draft = if (editor.useGlobal) null else editor.origin?.let { origin -> editor.destination?.let { destination -> PlanCommuteDraft(origin, destination, editor.mode) } }
        if (effective == null && draft == null) {
            _planCommuteEditor.update { it.copy(route = RouteUiState(message = "请选择计划专属起点和终点")) }
            return@launch
        }
        val origin = draft?.origin ?: effective!!.origin
        val destination = draft?.destination ?: effective!!.destination
        val mode = draft?.mode ?: effective!!.commuteMode
        _planCommuteEditor.update { it.copy(route = it.route.copy(loading = true, message = null)) }
        estimateRoute(origin, destination, mode).onSuccess { estimate ->
            _planCommuteEditor.update { it.copy(route = RouteUiState(estimate.alternatives.take(3), estimate.alternatives.firstOrNull()?.id)) }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            _planCommuteEditor.update { it.copy(route = RouteUiState(message = providerMessage(failure))) }
        }
        }
    }

    fun selectPlanCommuteRoute(routeId: String) { _planCommuteEditor.update { it.copy(route = it.route.copy(selectedRouteId = routeId)) } }
    fun setPlanCommuteTraffic(enabled: Boolean) { _planCommuteEditor.update { it.copy(route = it.route.copy(trafficEnabled = enabled)) } }

    fun savePlanCommute() = viewModelScope.launch {
        val editor = _planCommuteEditor.value
        val planId = editor.planId ?: return@launch
        runCatching {
            if (editor.useGlobal) planCommuteOverrideRepository.deleteByPlanId(planId)
            else {
                val origin = editor.origin ?: error("请选择计划专属起点")
                val destination = editor.destination ?: error("请选择计划专属终点")
                planCommuteOverrideRepository.save(PlanCommuteOverride(planId, origin, destination, editor.mode, System.currentTimeMillis()))
            }
        }.onSuccess {
            startPlanCommuteEditor(planId)
        }.onFailure { _error.value = it.message ?: "保存计划通勤失败" }
    }

    fun testAmapWebKey(onComplete: (String?) -> Unit) = viewModelScope.launch {
        if (!settings.value.amapConsentGranted) { onComplete("请先完成高德地图专项授权"); return@launch }
        runCatching { amapProvider.inputTips("北京") }.onSuccess { onComplete(null) }.onFailure { onComplete(providerMessage(it)) }
    }

    /** Tests a candidate without persisting it; the store changes only after a successful request. */
    fun testCaiyun(candidate: CaiyunCredentialInput?, onComplete: (String?) -> Unit) = viewModelScope.launch {
        val location = weatherLocations().firstOrNull()
        if (location == null) {
            onComplete("请先配置带坐标的起点或终点")
            return@launch
        }
        val requestedAt = Instant.now()
        val weatherLocation = WeatherLocation(location.role, location.point)
        val test = if (candidate == null) {
            runCatching { caiyunWeatherProvider.testConnection(weatherLocation, requestedAt) }
        } else {
            val credentials = CaiyunCredentials(candidate.appKey, candidate.secret)
            runCatching { caiyunWeatherProvider.testConnection(credentials, weatherLocation, requestedAt) }
        }
        val connectionFailure = test.exceptionOrNull()
        if (connectionFailure != null) {
            if (candidate == null && runCatching { credentials.recordCaiyunTestFailure() }.isFailure) {
                onComplete("凭据保存失败")
            } else {
                onComplete(weatherProviderMessage(connectionFailure))
            }
            return@launch
        }
        val persistence = runCatching {
            if (candidate != null) credentials.saveVerifiedCaiyun(candidate)
            else credentials.recordStoredCaiyunTestSuccess()
        }
        if (persistence.isFailure) {
            onComplete("凭据保存失败")
        } else {
            onComplete(null)
        }
    }

    /** Manual preview reads configured places and settings only; it does not evaluate or schedule alarms. */
    fun refreshWeather() {
        startWeatherRefresh()
    }

    private fun startWeatherRefresh(): Job {
        homeInputClock.value = Instant.now()
        val inputs = homePreviewInputs(settings.value, credentialStatus.value)
        val inputKey = inputs.weatherKey
        val active = weatherRefreshJob
        if (active?.isActive == true && weatherRefreshInputKey == inputKey) return active
        active?.cancel()
        val generation = ++weatherGeneration
        weatherRefreshInputKey = inputKey
        return viewModelScope.launch { refreshWeatherInternal(inputKey, generation) }
            .also { weatherRefreshJob = it }
    }

    private suspend fun refreshWeatherInternal(inputKey: String?, generation: Long) {
        val locations = weatherLocations()
        if (locations.size != 2) {
            _weatherState.value = WeatherUiState.Error("请先配置带坐标的起点和终点", inputKey = inputKey)
            return
        }
        if (locations[0].point == locations[1].point) {
            _weatherState.value = WeatherUiState.Error("起点和终点不能使用相同坐标", locations[0].name, locations[1].name, inputKey)
            return
        }
        if (!credentialStatus.value.hasCaiyunAppKey || !credentialStatus.value.hasCaiyunSecret) {
            _weatherState.value = WeatherUiState.Error("请先配置彩云 App Key 和 Secret", locations[0].name, locations[1].name, inputKey)
            return
        }
        if (credentialStatus.value.caiyunTestResult != CaiyunConnectionTestResult.PASSED) {
            _weatherState.value = WeatherUiState.Error("请先完成彩云凭据连接测试", locations[0].name, locations[1].name, inputKey)
            return
        }
        val currentSettings = settings.value
        val requestedAt = Instant.now()
        val (start, end) = weatherWindow(requestedAt)
        val previousWeather = _weatherState.value
        _weatherState.value = WeatherUiState.Loading(locations[0].name, locations[1].name, inputKey)
        runCatching {
            weatherProvider.evaluate(
                WeatherRequest(
                    home = WeatherLocation(WeatherLocationRole.HOME, locations[0].point),
                    work = WeatherLocation(WeatherLocationRole.WORK, locations[1].point),
                    window = WeatherTimeWindow(start, end),
                    weatherBufferProfile = currentSettings.workdayWeatherBuffers.toWeatherBufferProfile(),
                    requestedAt = requestedAt,
                ),
            )
        }.onSuccess { evaluation ->
            if (generation != weatherGeneration || inputKey != homePreviewInputs(settings.value, credentialStatus.value).weatherKey) return@onSuccess
            val reportTime = evaluation.providerReportTime
            val source = evaluation.source
            if (!evaluation.isUsableForScheduling || reportTime == null || source == null) {
                _weatherState.value = WeatherUiState.Error(
                    weatherUnavailableMessage(evaluation.fallbackReason),
                    locations[0].name,
                    locations[1].name,
                    inputKey,
                )
            } else {
                _weatherState.value = WeatherUiState.Success(
                    homeName = locations[0].name,
                    workName = locations[1].name,
                    severity = evaluation.severity,
                    reportTime = reportTime,
                    source = source,
                    inputKey = inputKey,
                    fetchedAtEpochMillis = minOf(System.currentTimeMillis(), reportTime.toEpochMilli()),
                    windowEnd = end,
                )
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            if (generation != weatherGeneration || inputKey != homePreviewInputs(settings.value, credentialStatus.value).weatherKey) return@onFailure
            val previous = previousWeather
            val now = System.currentTimeMillis()
            _weatherState.value = if (
                previous is WeatherUiState.Success &&
                previous.inputKey == inputKey &&
                HomePreviewPolicy.isFresh(previous.fetchedAtEpochMillis, HomePreviewPolicy.WEATHER_TTL_MILLIS, now) &&
                previous.windowEnd.isAfter(Instant.now().atZone(ZoneId.systemDefault()))
            ) previous.copy(refreshError = weatherProviderMessage(failure))
            else WeatherUiState.Error(weatherProviderMessage(failure), locations[0].name, locations[1].name, inputKey)
        }
    }

    /**
     * The home refresh is intentionally isolated from alarm evaluation: it only updates
     * the two preview providers. An active gesture shares this job rather than creating
     * duplicate provider work.
     */
    fun refreshHomePreviews(forceRefresh: Boolean = false) {
        homeInputClock.value = Instant.now()
        val requestedInputs = homePreviewInputs(settings.value, credentialStatus.value)
        val start = homeRefreshGate.begin(requestedInputs, forceRefresh)
        if (!start.accepted) return
        if (start.cancelCurrent) homeRefreshJob?.cancel()
        homeRefreshJob = viewModelScope.launch {
            _homeRefreshing.value = true
            try {
                val inputs = homePreviewInputs(settings.value, credentialStatus.value)
                val now = System.currentTimeMillis()
                val routeIsFresh = routeState.value.inputKey == inputs.routeKey &&
                    HomePreviewPolicy.isFresh(routeState.value.fetchedAtEpochMillis, HomePreviewPolicy.ROUTE_TTL_MILLIS, now)
                val weather = weatherState.value
                val weatherIsFresh = weather is WeatherUiState.Success &&
                    weather.inputKey == inputs.weatherKey &&
                    HomePreviewPolicy.isFresh(weather.fetchedAtEpochMillis, HomePreviewPolicy.WEATHER_TTL_MILLIS, now) &&
                    weather.windowEnd.isAfter(Instant.now().atZone(ZoneId.systemDefault()))
                coroutineScope {
                    listOfNotNull(
                        (if (forceRefresh || !routeIsFresh) startRouteRefresh(forceRefresh = forceRefresh) else null),
                        (if (forceRefresh || !weatherIsFresh) startWeatherRefresh() else null),
                    ).map { job -> async { job.join() } }.awaitAll()
                }
            } finally {
                if (homeRefreshGate.finish(start.token)) {
                    _homeRefreshing.value = false
                }
            }
        }
    }

    fun refreshHomePreviewsOnForeground() = refreshHomePreviews()

    fun clearError() { _error.value = null }
    fun showError(message: String) { _error.value = message }
    private val _evaluatingPlanIds = MutableStateFlow<Set<String>>(emptySet())
    val evaluatingPlanIds: StateFlow<Set<String>> = _evaluatingPlanIds

    fun evaluateNow(planId: String) { reevaluatePlan(planId) { _error.value = it } }

    fun reevaluatePlan(planId: String, onResult: (String) -> Unit) {
        if (planId in _evaluatingPlanIds.value) return
        _evaluatingPlanIds.update { it + planId }
        viewModelScope.launch {
            try {
                onResult(evaluationWorkScheduler.evaluateNow(planId).userMessage())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onResult("无法加入评估队列，请重试")
            } finally {
                _evaluatingPlanIds.update { it - planId }
            }
        }
    }
    private fun safe(block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onFailure { _error.value = it.message ?: "操作失败" } }

    private fun providerMessage(failure: Throwable): String = when (failure) {
        TransitCityCodeUnavailable -> TRANSIT_CITY_CODE_UNAVAILABLE_MESSAGE
        is ProviderError -> when (failure.category) {
            ProviderError.Category.MISSING_KEY -> "请先配置高德 Web Key"
            ProviderError.Category.INVALID_KEY -> "高德 Web Key 无效或未授权"
            ProviderError.Category.QUOTA_EXCEEDED -> "高德服务配额不足"
            ProviderError.Category.RATE_LIMITED -> "请求过于频繁，请稍后重试"
            ProviderError.Category.ROUTE_NOT_FOUND -> "未找到可用路线"
            ProviderError.Category.NETWORK -> "网络不可用，请检查连接"
            ProviderError.Category.TIMEOUT -> "服务响应超时，请稍后重试"
            else -> "高德服务暂不可用"
        }
        else -> "请求失败，请稍后重试"
    }

    private fun weatherLocations(): List<WeatherConfiguredLocation> {
        val current = settings.value
        return listOfNotNull(
            current.favorites.firstOrNull { it.id == current.originId }?.placeRef?.let { WeatherConfiguredLocation(WeatherLocationRole.HOME, it.name, GeoPoint(it.longitudeGcj02, it.latitudeGcj02)) },
            current.favorites.firstOrNull { it.id == current.destinationId }?.placeRef?.let { WeatherConfiguredLocation(WeatherLocationRole.WORK, it.name, GeoPoint(it.longitudeGcj02, it.latitudeGcj02)) },
        )
    }

    private fun com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers.toWeatherBufferProfile() =
        WeatherBufferProfile(lightMinutes, moderateMinutes, severeMinutes)

    private fun weatherProviderMessage(failure: Throwable): String = when (failure) {
        is ProviderError -> when (failure.category) {
            ProviderError.Category.MISSING_KEY -> "请先配置彩云 App Key 和 Secret"
            ProviderError.Category.INVALID_KEY -> "彩云凭据无效或未授权"
            ProviderError.Category.QUOTA_EXCEEDED, ProviderError.Category.RATE_LIMITED -> "彩云服务额度或频率限制"
            ProviderError.Category.NETWORK -> "网络不可用，请检查连接"
            ProviderError.Category.INVALID_REQUEST -> "天气请求参数无效"
            ProviderError.Category.MALFORMED_RESPONSE -> "天气数据格式无效"
            else -> "彩云天气服务暂不可用"
        }
        else -> "天气请求失败，请稍后重试"
    }

    private fun weatherUnavailableMessage(reason: FallbackReason): String = when (reason) {
        FallbackReason.WEATHER_HORIZON_UNAVAILABLE -> "所选时间范围超出彩云小时预报范围"
        FallbackReason.WEATHER_UNKNOWN_CODE -> "天气数据包含未识别天气代码，无法使用"
        FallbackReason.WEATHER_PROVIDER_TIMEOUT -> "服务响应超时，请稍后重试"
        FallbackReason.WEATHER_PROVIDER_AUTH -> "彩云凭据无效或未授权"
        FallbackReason.WEATHER_PROVIDER_QUOTA -> "彩云服务额度或频率限制"
        else -> "天气数据当前不可用"
    }

    private companion object { const val AMAP_CONSENT_VERSION = 1 }
}

internal sealed interface TransitCityCodeResolution {
    data class Ready(val origin: PlaceRef, val destination: PlaceRef) : TransitCityCodeResolution
    data object Unavailable : TransitCityCodeResolution
}

/** Resolves only the city codes needed by AMap transit routes, preserving selected POI details. */
internal suspend fun resolveTransitCityCodes(
    origin: PlaceRef,
    destination: PlaceRef,
    reverseGeocode: suspend (GeoPoint) -> PlaceRef,
): TransitCityCodeResolution {
    val reverseResults = mutableMapOf<GeoPoint, PlaceRef>()

    suspend fun resolve(place: PlaceRef): PlaceRef {
        if (place.citycode.isNotBlank()) return place
        val point = GeoPoint(place.longitudeGcj02, place.latitudeGcj02)
        val reverse = reverseResults[point] ?: reverseGeocode(point).also { reverseResults[point] = it }
        return place.copy(
            adcode = place.adcode.ifBlank { reverse.adcode },
            citycode = reverse.citycode,
        )
    }

    val resolvedOrigin = resolve(origin)
    val resolvedDestination = resolve(destination)
    return if (resolvedOrigin.citycode.isBlank() || resolvedDestination.citycode.isBlank()) {
        TransitCityCodeResolution.Unavailable
    } else {
        TransitCityCodeResolution.Ready(resolvedOrigin, resolvedDestination)
    }
}

private object TransitCityCodeUnavailable : IllegalStateException(TRANSIT_CITY_CODE_UNAVAILABLE_MESSAGE)

private const val TRANSIT_CITY_CODE_UNAVAILABLE_MESSAGE = "无法确定起点或终点所在城市，请重新选择地点或稍后重试"

enum class ZhituDestination {
    HOME, PLANS, EDITOR, ROUTE, CALENDAR, SETTINGS, CREDENTIALS,
    DIAGNOSTICS, HISTORY, WEATHER, WEATHER_BUFFERS, RINGING, ONBOARDING, PLACE_PICKER, PLAN_COMMUTE, DECISION_DETAIL,
}

data class UpcomingPlan(val plan: AlarmPlan, val occurrence: AlarmOccurrence) {
    val nextWakeAt: Long get() = occurrence.scheduledWakeAt
}

sealed interface MapStatus {
    data object NotInitialized : MapStatus
    data object Ready : MapStatus
    data object RendererUnavailable : MapStatus
    data object ConsentRequired : MapStatus
    data object MissingAndroidKey : MapStatus
    data object Failed : MapStatus
}

data class RouteUiState(
    val alternatives: List<RouteAlternative> = emptyList(),
    val selectedRouteId: String? = null,
    val trafficEnabled: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val inputKey: String? = null,
    val fetchedAtEpochMillis: Long? = null,
    val source: com.ljwzz.weathertrafficalarm.core.model.RouteDataSource? = null,
    val noRoute: Boolean = false,
    val refreshError: String? = null,
)

private data class WeatherConfiguredLocation(
    val role: WeatherLocationRole,
    val name: String,
    val point: GeoPoint,
)

sealed interface WeatherUiState {
    data object Idle : WeatherUiState
    data class Loading(
        val homeName: String? = null,
        val workName: String? = null,
        val inputKey: String? = null,
    ) : WeatherUiState
    data class Success(
        val homeName: String,
        val workName: String,
        val severity: WeatherSeverity,
        val reportTime: Instant,
        val source: WeatherDataSource,
        val inputKey: String? = null,
        val fetchedAtEpochMillis: Long = System.currentTimeMillis(),
        val windowEnd: java.time.ZonedDateTime = Instant.now().atZone(ZoneId.systemDefault()).plusHours(23),
        val refreshError: String? = null,
    ) : WeatherUiState
    data class Error(
        val message: String,
        val homeName: String? = null,
        val workName: String? = null,
        val inputKey: String? = null,
    ) : WeatherUiState
}

data class PlacePickerUiState(
    val query: String = "",
    val candidates: List<PlaceRef> = emptyList(),
    val selected: PlaceRef? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

private data class PlanCommuteDraft(
    val origin: PlaceRef,
    val destination: PlaceRef,
    val mode: CommuteMode,
)

data class PlanCommuteEditorState(
    val planId: String? = null,
    val origin: PlaceRef? = null,
    val destination: PlaceRef? = null,
    val mode: CommuteMode = CommuteMode.DRIVING,
    val useGlobal: Boolean = true,
    val route: RouteUiState = RouteUiState(),
    val loading: Boolean = false,
    val loadError: String? = null,
)

data class EditorDraft(
    val id: String? = null,
    val zoneId: String = ZoneId.systemDefault().id,
    val name: String = "本地闹钟",
    val time: String = "06:00",
    val date: String = java.time.LocalDate.now().let { if (java.time.LocalTime.now().isBefore(java.time.LocalTime.of(6, 0))) it else it.plusDays(1) }.toString(),
    val repeat: RepeatChoice = RepeatChoice.ONCE,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val ringtone: String = "系统默认铃声",
    val soundUri: String? = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI.toString(),
    val vibration: Boolean = true,
    val snoozeMinutes: Int = 10,
    val arrivalLocalTime: String = AlarmPlan.DEFAULT_ARRIVAL_TIME,
    val preparationMinutes: Int = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
    val maxAdvanceMinutes: Int = AlarmPlan.DEFAULT_MAX_ADVANCE_MINUTES,
    val newPlanId: String = UUID.randomUUID().toString(),
    val commute: EditorCommuteDraft? = null,
) {
    val planId: String get() = id ?: newPlanId

    fun withCommute(editor: PlanCommuteEditorState): EditorDraft {
        require(editor.planId == planId) { "通勤配置与当前闹钟不匹配" }
        require(!editor.loading && editor.loadError == null) { "请等待通勤配置读取完成" }
        val updated = EditorCommuteDraft(editor.useGlobal, editor.origin, editor.destination, editor.mode)
        updated.toOverride(planId)
        return copy(commute = updated)
    }
}

/** Session draft only. Null on EditorDraft means the stored override has not been edited. */
data class EditorCommuteDraft(
    val useGlobal: Boolean = true,
    val origin: PlaceRef? = null,
    val destination: PlaceRef? = null,
    val mode: CommuteMode = CommuteMode.DRIVING,
) {
    fun toOverride(planId: String): PlanCommuteOverride? {
        if (useGlobal) return null
        val from = requireNotNull(origin) { "请选择计划专属起点" }
        val to = requireNotNull(destination) { "请选择计划专属终点" }
        require(from != to) { "起点与终点不能相同" }
        return PlanCommuteOverride(planId, from, to, mode, System.currentTimeMillis())
    }
}

enum class RepeatChoice(val label: String) {
    ONCE("指定日期"), WEEKLY("每周重复"), WORKDAYS("法定工作日"),
}
