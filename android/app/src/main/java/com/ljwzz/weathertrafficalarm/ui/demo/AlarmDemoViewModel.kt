package com.ljwzz.weathertrafficalarm.ui.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.ExactAlarmScheduler
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class AlarmPlanRow(
    val plan: AlarmPlan,
    val nextTriggerAtMillis: Long?,
)

@HiltViewModel
class AlarmDemoViewModel @Inject constructor(
    private val repository: AlarmPlanRepository,
    private val scheduler: ExactAlarmScheduler,
    private val snapshotStore: NextAlarmSnapshotStore,
) : ViewModel() {

    private val snapshots: StateFlow<Map<String, NextAlarmSnapshot>> =
        snapshotStore.observeAll()
            .map { list -> list.associateBy { it.planId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val rows: StateFlow<List<AlarmPlanRow>> = combine(
        repository.observeAll(),
        snapshots,
    ) { plans, snapMap ->
        plans.map { plan ->
            AlarmPlanRow(plan = plan, nextTriggerAtMillis = snapMap[plan.id]?.triggerAtMillis)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val errorMessage = MutableStateFlow<String?>(null)

    fun addPlan(name: String, wakeTime: LocalTime, enabled: Boolean) {
        viewModelScope.launch {
            val plan = AlarmPlan(
                id = UUID.randomUUID().toString(),
                revision = 0,
                name = name.ifBlank { "通勤闹钟" },
                enabled = enabled,
                zoneId = ZoneId.systemDefault().id,
                defaultWakeLocalTime = wakeTime.toString(),
                arrivalLocalTime = wakeTime.toString(),
                preparationMinutes = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
                maxAdvanceMinutes = AlarmPlan.DEFAULT_MAX_ADVANCE_MINUTES,
                commuteMode = CommuteMode.DRIVING,
                origin = HOME,
                destination = WORK,
                sound = AlarmSound(),
            )
            repository.save(plan)
            if (enabled) schedule(plan)
        }
    }

    fun setEnabled(plan: AlarmPlan, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                repository.enable(plan.id)
                repository.getById(plan.id)?.let { schedule(it) }
            } else {
                scheduler.cancelForPlan(plan.id)
                repository.disable(plan.id)
            }
        }
    }

    fun delete(plan: AlarmPlan) {
        viewModelScope.launch {
            scheduler.cancelForPlan(plan.id)
            repository.deleteById(plan.id)
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

    private suspend fun schedule(plan: AlarmPlan) {
        runCatching { scheduler.scheduleDefault(plan) }
            .onFailure { e -> errorMessage.value = "注册系统闹钟失败:${e.message}" }
    }

    companion object {
        private val HOME = PlaceRef(name = "家", displayAddress = "家", longitudeGcj02 = 0.0, latitudeGcj02 = 0.0, adcode = "0", citycode = "0")
        private val WORK = PlaceRef(name = "公司", displayAddress = "公司", longitudeGcj02 = 0.0, latitudeGcj02 = 0.0, adcode = "0", citycode = "0")
    }
}
