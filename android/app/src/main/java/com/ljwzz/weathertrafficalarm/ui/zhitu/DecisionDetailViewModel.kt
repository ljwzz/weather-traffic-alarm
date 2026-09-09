package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionDetailLookup
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionDetailRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationTaskState
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every load is a local read of the same anchor; no evaluation or alarm write dependency. */
@HiltViewModel
class DecisionDetailViewModel @Inject constructor(
    private val repository: DecisionDetailRepository,
    decisions: DecisionRepository,
    occurrences: OccurrenceRepository,
    plans: AlarmPlanRepository,
    scheduler: EvaluationWorkScheduler,
) : ViewModel() {
    private val _lookup = MutableStateFlow<DecisionDetailLookup?>(null)
    val lookup: StateFlow<DecisionDetailLookup?> = _lookup
    private val _readError = MutableStateFlow<String?>(null)
    val readError: StateFlow<String?> = _readError
    val taskRuns = scheduler.observeTaskStateRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var anchor: Pair<String?, String?>? = null
    private var readJob: Job? = null

    init {
        viewModelScope.launch {
            combine(decisions.observeAll(), occurrences.observeAll(), plans.observeAll()) { _, _, _ -> Unit }
                .collect { refresh() }
        }
    }

    fun open(decisionId: String?, occurrenceId: String?) {
        val requested = decisionId to occurrenceId
        if (anchor != requested) _lookup.value = null
        anchor = requested
        refresh()
    }

    fun refresh() {
        val requested = anchor ?: return
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _readError.value = null
            try {
                val (decisionId, occurrenceId) = requested
                val found = when {
                    !occurrenceId.isNullOrBlank() && decisionId == null -> repository.resolveOccurrence(occurrenceId)
                    !decisionId.isNullOrBlank() && occurrenceId == null -> repository.resolveDecision(decisionId)
                    else -> { _readError.value = "详情入口缺少唯一记录标识。"; return@launch }
                }
                if (anchor == requested) _lookup.value = found
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (anchor == requested) {
                    _lookup.value = null
                    _readError.value = "本地决策记录读取失败，请重试。"
                }
            }
        }
    }
}

internal fun DecisionDetailLookup?.historicalDecision(): AlarmDecision? = when (this) {
    is DecisionDetailLookup.DecisionFound -> decision
    is DecisionDetailLookup.OccurrenceFound -> decision
    else -> null
}

internal fun DecisionDetailLookup?.currentPlan(): AlarmPlan? = when (this) {
    is DecisionDetailLookup.DecisionFound -> plan
    is DecisionDetailLookup.OccurrenceFound -> plan
    else -> null
}

/** Do not render the previous lookup during the composition before a new anchor is loaded. */
internal fun DecisionDetailLookup.matchesDetailAnchor(decisionId: String?, occurrenceId: String?): Boolean = when (this) {
    is DecisionDetailLookup.DecisionFound -> occurrenceId == null && decision.decisionId == decisionId
    is DecisionDetailLookup.DecisionMissing -> occurrenceId == null && this.decisionId == decisionId
    is DecisionDetailLookup.OccurrenceFound -> decisionId == null && occurrence.occurrenceId == occurrenceId
    is DecisionDetailLookup.OccurrenceUnavailable -> decisionId == null && this.occurrenceId == occurrenceId
}

/** Only an actual unfinished retry explicitly tagged with this decision can appear here. */
internal fun decisionRetryLabel(decision: AlarmDecision, tasks: List<EvaluationTaskState>): String? {
    val task = tasks.filter {
        it.decisionId == decision.decisionId && it.planId == decision.planId &&
            it.planRevision == decision.planRevision && it.targetDate == decision.targetDate &&
            it.phase == "RETRYING" && it.nextAttemptAt != null
    }.singleOrNull() ?: return null
    val zone = decision.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
    return "已安排重试：${Instant.ofEpochMilli(task.nextAttemptAt!!).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}（${zone.id}）"
}
