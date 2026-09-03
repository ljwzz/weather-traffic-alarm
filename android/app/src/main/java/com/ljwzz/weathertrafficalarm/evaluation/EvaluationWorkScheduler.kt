package com.ljwzz.weathertrafficalarm.evaluation

import android.content.Context
import android.os.UserManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Work Data contains only a plan ID. No addresses, provider responses or keys leave repositories. */
@Singleton
class EvaluationWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plans: AlarmPlanRepository,
    private val occurrences: OccurrenceRepository,
    private val settings: LocalSettingsStore,
    private val commuteOverrides: PlanCommuteOverrideRepository,
    private val dayOverrides: WorkdayOverrideRepository,
    private val calendar: WorkdayCalendarRepository,
    private val credentials: CredentialStore,
    private val commuteResolver: EffectiveCommuteResolver,
    private val clock: Clock,
) {
    private val _schedulingError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val schedulingError: kotlinx.coroutines.flow.StateFlow<String?> = _schedulingError
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        _schedulingError.value = "后台评估安排失败，请重新检查"
    })
    private val started = AtomicBoolean(false)
    private val mutation = Mutex()
    private val manager get() = WorkManager.getInstance(context)

    fun observeTaskStates(): kotlinx.coroutines.flow.Flow<Map<String, EvaluationTaskState>> =
        manager.getWorkInfosByTagFlow(ALL_WORK_TAG).map { works ->
            works.filter { !it.state.isFinished }.mapNotNull { info ->
                val planId = info.tags.firstOrNull { it.startsWith("evaluation-plan:") }?.substringAfter(':')
                    ?: return@mapNotNull null
                val run = EvaluationWorkRun.fromTags(info.tags) ?: return@mapNotNull null
                planId to EvaluationTaskState(
                    phase = when {
                        info.state == WorkInfo.State.RUNNING -> "RUNNING"
                        run.attempt > 0 -> "RETRYING"
                        else -> "WAITING"
                    },
                    nextAttemptAt = run.notBefore.toEpochMilli(), attemptNumber = run.attempt,
                )
            }.groupBy({ it.first }, { it.second }).mapValues { (_, states) ->
                states.sortedWith(compareBy<EvaluationTaskState> { it.phase != "RUNNING" }.thenBy { it.nextAttemptAt }).first()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked || !started.compareAndSet(false, true)) return
        scope.launch {
            try {
                // Await the persisted values, rather than enqueuing from the StateFlow's empty defaults.
                settings.loadInitial()
                var previous: Map<String, List<Any?>>? = null
                combine(
                    plans.observeAll(), settings.settings,
                    calendar.state.map { it.days }.distinctUntilChanged(),
                    credentials.state,
                ) { currentPlans, config, days, credentialState ->
                    currentPlans to listOf(
                        config.originId, config.destinationId, config.favorites, config.commuteMode,
                        config.workdayWeatherBuffers, config.weekendWeatherBuffers, config.holidayWeatherBuffers,
                        config.amapConsentGranted, config.amapConsentPromptedVersion, days,
                        credentialState,
                    )
                }.flatMapLatest { (currentPlans, shared) ->
                    if (currentPlans.isEmpty()) flowOf(emptyList()) else combine(currentPlans.map { plan ->
                        combine(commuteOverrides.observeByPlanId(plan.id), dayOverrides.observeForPlan(plan.id)) { commute, overrides ->
                            plan to (shared + listOf(plan.revision, plan.enabled, plan.zoneId, commute, overrides))
                        }
                    }) { it.toList() }
                }.collect { entries ->
                    mutation.withLock {
                        val current = entries.associate { it.first.id to it.second }
                        val old = previous
                        old?.keys?.minus(current.keys)?.forEach { cancel(it) }
                        entries.forEach { (plan, key) ->
                            if (!plan.enabled) {
                                cancel(plan.id)
                            } else if (old == null || old[plan.id] != key) {
                                val changed = old != null
                                // A queued manual refresh reads current inputs at execution. Do not
                                // discard that action while an earlier Room emission catches up.
                                if (changed) cancel(plan.id, nightOnly = true)
                                ensureNightly(plan, replace = changed)
                            }
                        }
                        previous = current
                    }
                }
            } finally {
                started.set(false)
            }
        }
    }

    /** Explicit foreground action. It evaluates the next regular occurrence, including a later date. */
    fun evaluateNow(planId: String) {
        start()
        scope.launch {
            mutation.withLock {
                val plan = plans.getById(planId)?.takeIf { it.enabled } ?: return@withLock
                val now = clock.instant()
                val target = occurrences.getByPlanId(planId)
                    .filter { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED && it.scheduledWakeAt > now.toEpochMilli() }
                    .minByOrNull { it.scheduledWakeAt }?.targetDate?.let(LocalDate::parse)
                    ?: now.atZone(plan.zoneIdInstance()).toLocalDate().plusDays(1)
                val expiry = minOf(now.plus(Duration.ofHours(2)), target.atTime(java.time.LocalTime.parse(plan.defaultWakeLocalTime)).atZone(plan.zoneIdInstance()).toInstant())
                if (!expiry.isAfter(now)) return@withLock
                enqueue(plan, target, now, expiry, 0, "manual", replace = true, terminalDeduplication = false)
            }
        }
    }

    /** Invoked on startup, recovery and at the start of each Worker, before network I/O. */
    suspend fun ensureNightly(plan: AlarmPlan, replace: Boolean = false) {
        if (!plan.enabled) return
        if (commuteResolver.resolveForPlan(plan.id, settings.loadInitial()) == null) return
        val now = clock.instant()
        val jitter = Math.floorMod(plan.id.hashCode(), 16)
        val zone = plan.zoneIdInstance()
        val localTime = now.atZone(zone).toLocalTime()
        val immediate = replace && !localTime.isBefore(java.time.LocalTime.of(19, 0)) && localTime.isBefore(java.time.LocalTime.of(23, 30))
        val starts = setOf(
            if (immediate) now else EvaluationWorkPolicy.nextNight(now, zone, jitter),
            EvaluationWorkPolicy.nextNight(now, zone, jitter, futureOnly = true),
        )
        starts.forEach { at ->
            val evaluationDate = at.atZone(zone).toLocalDate()
            enqueue(plan, evaluationDate.plusDays(1), at, EvaluationWorkPolicy.deadline(evaluationDate, zone), 0, "night", replace, true)
        }
    }

    suspend fun enqueueRetry(plan: AlarmPlan, run: EvaluationWorkRun, at: Instant) {
        enqueue(plan, run.targetDate, at, run.deadline, run.attempt + 1, run.origin, false, true)
    }

    fun recover(): kotlinx.coroutines.Job {
        start()
        return scope.launch {
            mutation.withLock {
                plans.observeAll().first().filter { it.enabled }.forEach {
                    cancel(it.id)
                    ensureNightly(it, replace = true)
                }
            }
        }
    }

    private fun cancel(planId: String, nightOnly: Boolean = false) {
        manager.cancelAllWorkByTag(if (nightOnly) "evaluation-night:$planId" else planTag(planId)).result.get(30, TimeUnit.SECONDS)
    }

    private fun enqueue(
        plan: AlarmPlan, targetDate: LocalDate, at: Instant, deadline: Instant, attempt: Int,
        origin: String, replace: Boolean, terminalDeduplication: Boolean,
    ) {
        val name = "evaluation:${plan.id}:${plan.revision}:$targetDate:$origin:$attempt"
        if (!replace && terminalDeduplication && manager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS).isNotEmpty()) return
        val run = EvaluationWorkRun(targetDate, at, deadline, attempt, origin, plan.revision, plan.zoneId)
        val request = OneTimeWorkRequestBuilder<EvaluationWorker>()
            .setInputData(workDataOf(PLAN_ID to plan.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Duration.between(clock.instant(), at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(planTag(plan.id))
            .addTag("evaluation-$origin:${plan.id}")
            .addTag(ALL_WORK_TAG)
            .apply { run.tags().forEach(::addTag) }
            .build()
        manager.enqueueUniqueWork(name, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
            .result.get(30, TimeUnit.SECONDS)
        _schedulingError.value = null
    }

    companion object {
        const val PLAN_ID = "planId"
        const val ALL_WORK_TAG = "automatic-evaluation"
        fun planTag(planId: String) = "evaluation-plan:$planId"
    }
}

data class EvaluationTaskState(val phase: String, val nextAttemptAt: Long, val attemptNumber: Int)

data class EvaluationWorkRun(
    val targetDate: LocalDate,
    val notBefore: Instant,
    val deadline: Instant,
    val attempt: Int,
    val origin: String,
    val revision: Long,
    val zoneId: String,
) {
    fun tags(): Set<String> = setOf(
        "target:$targetDate", "not-before:${notBefore.toEpochMilli()}", "deadline:${deadline.toEpochMilli()}",
        "attempt:$attempt", "origin:$origin", "revision:$revision", "zone:$zoneId",
    )

    companion object {
        fun fromTags(tags: Set<String>): EvaluationWorkRun? = runCatching {
            fun tag(prefix: String) = tags.single { it.startsWith("$prefix:") }.substringAfter(':')
            EvaluationWorkRun(LocalDate.parse(tag("target")), Instant.ofEpochMilli(tag("not-before").toLong()),
                Instant.ofEpochMilli(tag("deadline").toLong()), tag("attempt").toInt(), tag("origin"), tag("revision").toLong(), tag("zone"))
                .also { require(it.attempt in 0..3 && it.origin in setOf("night", "manual")) }
        }.getOrNull()
    }
}
