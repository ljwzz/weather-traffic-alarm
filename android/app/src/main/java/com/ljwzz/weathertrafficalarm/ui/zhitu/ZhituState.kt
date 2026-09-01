package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
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
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * The screen only renders repository state. Scheduling writes are deliberately
 * delegated to LocalAlarmCoordinator by the feature-specific integration layer.
 */
@HiltViewModel
class ZhituViewModel @Inject constructor(
    private val coordinator: LocalAlarmCoordinator,
    private val calendar: WorkdayCalendarRepository,
    private val settingsStore: LocalSettingsStore,
    private val overrideRepository: WorkdayOverrideRepository,
    private val credentials: com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore,
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
    val dayOverrides = plans.flatMapLatest { current ->
        if (current.isEmpty()) flowOf(emptyList()) else combine(current.map { overrideRepository.observeForPlan(it.id) }) { groups -> groups.flatMap { it } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val upcomingPlans: StateFlow<List<UpcomingPlan>> = combine(coordinator.plans, coordinator.occurrences) { plans, occurrences ->
        plans.asSequence().filter { it.enabled }.mapNotNull { plan ->
            occurrences.filter { it.planId == plan.id && it.state == com.ljwzz.weathertrafficalarm.core.model.OccurrenceState.SCHEDULED }
                .minByOrNull { it.scheduledWakeAt }?.let { occurrence -> UpcomingPlan(plan, occurrence.scheduledWakeAt) }
        }.sortedBy { it.nextWakeAt }.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events = coordinator.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calendarState = calendar.state
    val settings = settingsStore.settings
    val credentialStatus = credentials.state

    fun save(draft: EditorDraft, onSuccess: () -> Unit) {
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
                schedule = schedule, sound = previous.sound.copy(uri = draft.soundUri, title = draft.ringtone),
                vibration = previous.vibration.copy(enabled = draft.vibration), snoozeMinutes = draft.snoozeMinutes,
                enabled = true, armedState = AlarmArmedState.NEEDS_PERMISSION, scheduleError = null,
            ) ?: AlarmPlan(
                id = UUID.randomUUID().toString(), revision = 0, name = draft.name.trim(), enabled = true,
                zoneId = ZoneId.systemDefault().id, defaultWakeLocalTime = draft.time,
                arrivalLocalTime = AlarmPlan.DEFAULT_ARRIVAL_TIME, preparationMinutes = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
                maxAdvanceMinutes = AlarmPlan.DEFAULT_MAX_ADVANCE_MINUTES, commuteMode = CommuteMode.DRIVING,
                schedule = schedule, armedState = AlarmArmedState.NEEDS_PERMISSION,
                sound = AlarmSound(uri = draft.soundUri, title = draft.ringtone), vibration = com.ljwzz.weathertrafficalarm.core.model.VibrationPattern(enabled = draft.vibration), snoozeMinutes = draft.snoozeMinutes,
            )
            coordinator.save(plan)
            }.onSuccess { onSuccess() }.onFailure { _error.value = it.message ?: "保存闹钟失败" }
        }
    }

    fun setEnabled(planId: String, enabled: Boolean) = safe { coordinator.setEnabled(planId, enabled) }
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
    fun clearError() { _error.value = null }
    private fun safe(block: suspend () -> Unit) = viewModelScope.launch { runCatching { block() }.onFailure { _error.value = it.message ?: "操作失败" } }
}

enum class ZhituDestination {
    HOME, PLANS, EDITOR, ROUTE, CALENDAR, SETTINGS, CREDENTIALS,
    DIAGNOSTICS, HISTORY, WEATHER, RINGING, ONBOARDING,
}

data class UpcomingPlan(val plan: AlarmPlan, val nextWakeAt: Long)

data class EditorDraft(
    val id: String? = null,
    val name: String = "本地闹钟",
    val time: String = "06:00",
    val date: String = java.time.LocalDate.now().let { if (java.time.LocalTime.now().isBefore(java.time.LocalTime.of(6, 0))) it else it.plusDays(1) }.toString(),
    val repeat: RepeatChoice = RepeatChoice.ONCE,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val ringtone: String = "系统默认铃声",
    val soundUri: String? = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI.toString(),
    val vibration: Boolean = true,
    val snoozeMinutes: Int = 10,
)

enum class RepeatChoice(val label: String) {
    ONCE("指定日期"), WEEKLY("每周重复"), WORKDAYS("工作日"),
}
