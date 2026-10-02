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
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
    private val dailyInputs: DailyEvaluationInputResolver,
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
    /** Test-only seam; production always reloads the encrypted credential store. */
    internal var credentialStatusReaderForTest: (suspend () -> CredentialStatus)? = null

    /** Active runs retain their plan, revision, target date and day generation identity. */
    fun observeTaskStateRuns(): kotlinx.coroutines.flow.Flow<List<EvaluationTaskState>> =
        manager.getWorkInfosByTagFlow(ALL_WORK_TAG).map { works ->
            works.filter { !it.state.isFinished }.mapNotNull { info ->
                val planId = info.tags.firstOrNull { it.startsWith("evaluation-plan:") }?.substringAfter(':')
                    ?: return@mapNotNull null
                val run = EvaluationWorkRun.fromTags(info.tags) ?: return@mapNotNull null
                val running = info.state == WorkInfo.State.RUNNING
                EvaluationTaskState(
                    planId = planId,
                    phase = when {
                        running -> "RUNNING"
                        run.attempt > 0 -> "RETRYING"
                        else -> "WAITING"
                    },
                    // A running retry has no scheduled successor yet. Do not derive one from
                    // its attempt count; a successor exists only after WorkManager persists it.
                    nextAttemptAt = if (running) null else run.notBefore.toEpochMilli(),
                    attemptNumber = run.attempt,
                    targetDate = run.targetDate.toString(),
                    planRevision = run.revision,
                    dayRevision = run.dayRevision,
                    origin = run.origin,
                    decisionId = run.decisionId,
                    workId = info.id.toString(),
                )
            }.sortedWith(
                compareBy<EvaluationTaskState> { it.phase != "RUNNING" }
                    .thenBy { it.nextAttemptAt ?: Long.MAX_VALUE },
            )
        }

    /** Per-plan home summary; decision details must use [observeTaskStateRuns]. */
    fun observeTaskStates(): kotlinx.coroutines.flow.Flow<Map<String, EvaluationTaskState>> =
        observeTaskStateRuns().map { states ->
            states.filter { it.planId != null }.groupBy { requireNotNull(it.planId) }.mapValues { (_, states) ->
                states.sortedWith(
                    compareBy<EvaluationTaskState> { it.phase != "RUNNING" }
                        .thenBy { it.nextAttemptAt ?: Long.MAX_VALUE },
                ).first()
            }
        }

    /** Shared configuration of one plan; a change resets that plan's nightly work. */
    private data class PlanScheduleState(
        val configKey: List<Any?>,
        val dayRevisions: Map<String, Long>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked || !started.compareAndSet(false, true)) return
        scope.launch {
            try {
                // Await the persisted values, rather than enqueuing from the StateFlow's empty defaults.
                settings.loadInitial()
                var previous: Map<String, PlanScheduleState>? = null
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
                        combine(commuteOverrides.observeByPlanId(plan.id), dayOverrides.observeDayStates(plan.id)) { commute, dayStates ->
                            plan to PlanScheduleState(
                                configKey = shared + listOf(plan.revision, plan.enabled, plan.zoneId, commute),
                                // Committed revisions survive deleting the override, so undo and
                                // undo-then-recreate both register as a change of this date.
                                dayRevisions = dayStates.associate { it.date to it.committedRevision },
                            )
                        }
                    }) { it.toList() }
                }.collect { entries ->
                    mutation.withLock {
                        val current = entries.associate { it.first.id to it.second }
                        val old = previous
                        old?.keys?.minus(current.keys)?.forEach { cancel(it) }
                        entries.forEach { (plan, state) ->
                            val before = old?.get(plan.id)
                            when {
                                !plan.enabled -> cancel(plan.id)
                                before == null -> ensureNightly(plan)
                                before.configKey != state.configKey -> {
                                    cancel(plan.id, nightOnly = true)
                                    ensureNightly(plan, replace = true)
                                }
                                else -> {
                                    val changedDates = (before.dayRevisions.keys + state.dayRevisions.keys)
                                        .filter { before.dayRevisions[it] != state.dayRevisions[it] }
                                        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                                        .toSet()
                                    if (changedDates.isNotEmpty()) {
                                        // Only this date's old generation is cancelled; other dates keep
                                        // their effective work.
                                        cancelNightlyForDates(plan.id, changedDates)
                                        ensureNightly(plan, replace = true, changedDates = changedDates)
                                    }
                                }
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

    /**
     * Explicit foreground action. It only queues work for the plan's *current* valid
     * configuration. It never rewrites a persisted decision that is currently displayed.
     */
    suspend fun evaluateNow(planId: String): EvaluateNowResult = withContext(Dispatchers.IO) {
        mutation.withLock {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
                return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.USER_LOCKED)
            }
            val plan = plans.getById(planId)
                ?: return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.PLAN_NOT_FOUND)
            if (!plan.enabled) return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.PLAN_DISABLED)

            // Read persistent state here instead of using StateFlow defaults from process start.
            val localSettings = settings.loadInitial()
            val credentialStatus = credentialStatusReaderForTest?.invoke() ?: credentials.maskedValues()
            val now = clock.instant()
            // The target date comes from the effective regular instance, so a day-level wake or
            // commute decides whether this plan can be evaluated now.
            val regular = occurrences.getByPlanId(planId)
                .asSequence()
                .filter { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED }
                .filter { it.planRevision == plan.revision }
                .filter { it.scheduledWakeAt > now.toEpochMilli() }
                .minByOrNull { it.scheduledWakeAt }
                ?: return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.NO_UPCOMING_OCCURRENCE)
            val target = runCatching { LocalDate.parse(regular.targetDate) }.getOrNull()
                ?: return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.NO_UPCOMING_OCCURRENCE)
            val resolved = dailyInputs.resolve(plan, target)
            validateManualEvaluation(
                plan = plan,
                inputs = resolved,
                credentials = credentialStatus,
                now = now,
            )?.let { rejection -> return@withLock EvaluateNowResult.Rejected(rejection) }

            // The instance's own wake time is the effective day wake; a plan-level default must
            // not expire a window that the configured day still allows.
            val expiry = minOf(now.plus(Duration.ofHours(2)), Instant.ofEpochMilli(regular.scheduledWakeAt))
            if (!expiry.isAfter(now)) {
                return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.EVALUATION_WINDOW_EXPIRED)
            }

            val run = EvaluationWorkRun(
                targetDate = target,
                notBefore = now,
                deadline = expiry,
                attempt = 0,
                origin = "manual",
                revision = plan.revision,
                zoneId = plan.zoneId,
                dayRevision = resolved.committedRevision,
            )
            val name = uniqueWorkName(plan, run)
            val existing = manager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS)
            existing.firstOrNull { !it.state.isFinished }?.let { work ->
                return@withLock EvaluateNowResult.AlreadyQueued(plan.id, target.toString(), plan.revision, work.id.toString())
            }
            val workId = enqueue(plan, run, ExistingWorkPolicy.KEEP, skipExisting = false)
            start()
            EvaluateNowResult.Enqueued(plan.id, target.toString(), plan.revision, workId)
        }
    }

    /**
     * Arranges the nightly evaluation windows. [changedDates] restricts the replacement to the
     * dates whose committed revision changed, so a single-day edit never restarts another date's
     * effective work.
     */
    suspend fun ensureNightly(
        plan: AlarmPlan,
        replace: Boolean = false,
        changedDates: Set<LocalDate> = emptySet(),
    ) {
        if (!plan.enabled) return
        val now = clock.instant()
        val jitter = Math.floorMod(plan.id.hashCode(), 16)
        val zone = plan.zoneIdInstance()
        val localTime = now.atZone(zone).toLocalTime()
        val immediate = replace && !localTime.isBefore(LocalTime.of(19, 0)) && localTime.isBefore(LocalTime.of(23, 30))
        val starts = mutableSetOf(
            if (immediate) now else EvaluationWorkPolicy.nextNight(now, zone, jitter),
            EvaluationWorkPolicy.nextNight(now, zone, jitter, futureOnly = true),
        )
        // Future day-only commutes need a persisted window even when no rolling work exists.
        val targetDates = changedDates + dayOverrides.getForPlan(plan.id).map { LocalDate.parse(it.date) } +
            listOfNotNull((plan.schedule as? AlarmSchedule.Once)?.date?.let(LocalDate::parse))
        targetDates.forEach { target ->
            val evaluationDate = target.minusDays(1)
            val deadline = EvaluationWorkPolicy.deadline(evaluationDate, zone)
            if (!now.isBefore(deadline)) return@forEach
            val at = if (replace && evaluationDate == now.atZone(zone).toLocalDate() && immediate) now
                else maxOf(now, evaluationDate.atTime(19, 0).atZone(zone).plusMinutes(jitter.toLong()).toInstant())
            if (starts.none { it.atZone(zone).toLocalDate() == evaluationDate }) starts.add(at)
        }
        starts.forEach { at ->
            val evaluationDate = at.atZone(zone).toLocalDate()
            val target = evaluationDate.plusDays(1)
            // Resolve the target date first: only this date's effective commute decides whether
            // an evaluation is possible, and a plan-level commute is no longer required.
            val resolved = dailyInputs.resolve(plan, target)
            if (resolved.effectiveCommute == null) return@forEach
            val run = EvaluationWorkRun(
                targetDate = target,
                notBefore = at,
                deadline = EvaluationWorkPolicy.deadline(evaluationDate, zone),
                attempt = 0,
                origin = "night",
                revision = plan.revision,
                zoneId = plan.zoneId,
                dayRevision = resolved.committedRevision,
            )
            val replacesThisDate = (replace && changedDates.isEmpty()) || target in changedDates
            enqueue(
                plan,
                run,
                if (replacesThisDate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                skipExisting = !replacesThisDate,
            )
        }
    }

    suspend fun enqueueRetry(plan: AlarmPlan, run: EvaluationWorkRun, at: Instant, decisionId: String) {
        enqueue(
            plan,
            run.copy(notBefore = at, attempt = run.attempt + 1, decisionId = decisionId),
            ExistingWorkPolicy.KEEP,
            skipExisting = true,
        )
    }

    fun recover(): kotlinx.coroutines.Job {
        start()
        return scope.launch {
            mutation.withLock {
                plans.observeAll().first().filter { it.enabled }.forEach {
                    ensureNightly(it)
                }
            }
        }
    }

    private fun cancel(planId: String, nightOnly: Boolean = false) {
        manager.cancelAllWorkByTag(if (nightOnly) "evaluation-night:$planId" else planTag(planId)).result.get(30, TimeUnit.SECONDS)
    }

    private fun cancelNightlyForDates(planId: String, dates: Set<LocalDate>) {
        dates.forEach { date ->
            manager.cancelAllWorkByTag(nightDateTag(planId, date)).result.get(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Enqueues one run. [skipExisting] keeps an unfinished run of the same generation and does not
     * repeat one that already succeeded, while a genuinely changed configuration replaces it.
     * Cancelled or failed history alone never blocks a required re-run.
     */
    private fun enqueue(
        plan: AlarmPlan,
        run: EvaluationWorkRun,
        policy: ExistingWorkPolicy,
        skipExisting: Boolean,
    ): String {
        val name = uniqueWorkName(plan, run)
        if (skipExisting) {
            val known = manager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS)
            val alreadyEffective = known.any { !it.state.isFinished || it.state == WorkInfo.State.SUCCEEDED }
            if (alreadyEffective) return ""
        }
        val request = OneTimeWorkRequestBuilder<EvaluationWorker>()
            .setInputData(workDataOf(PLAN_ID to plan.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Duration.between(clock.instant(), run.notBefore).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(planTag(plan.id))
            .addTag("evaluation-${run.origin}:${plan.id}")
            .addTag(nightDateTag(plan.id, run.targetDate))
            .addTag(ALL_WORK_TAG)
            .apply { run.tags().forEach(::addTag) }
            .build()
        manager.enqueueUniqueWork(name, policy, request)
            .result.get(30, TimeUnit.SECONDS)
        _schedulingError.value = null
        return request.id.toString()
    }

    private fun uniqueWorkName(plan: AlarmPlan, run: EvaluationWorkRun): String =
        "evaluation:${plan.id}:${run.revision}:${run.targetDate}:${run.dayRevision}:${run.origin}:${run.attempt}"

    companion object {
        const val PLAN_ID = "planId"
        const val ALL_WORK_TAG = "automatic-evaluation"
        fun planTag(planId: String) = "evaluation-plan:$planId"
        fun nightDateTag(planId: String, date: LocalDate) = "evaluation-night-date:$planId:$date"
    }
}

data class EvaluationTaskState(
    val phase: String,
    val planId: String? = null,
    /** Null unless WorkManager has actually persisted a future retry. */
    val nextAttemptAt: Long? = null,
    val attemptNumber: Int = 0,
    val targetDate: String? = null,
    val planRevision: Long? = null,
    /** Committed day revision this run belongs to; null for legacy work without the tag. */
    val dayRevision: Long? = null,
    val origin: String? = null,
    /** Decision that requested this retry; null for initial work and legacy WorkManager rows. */
    val decisionId: String? = null,
    val workId: String? = null,
)

sealed interface EvaluateNowResult {
    data class Enqueued(val planId: String, val targetDate: String, val revision: Long, val workId: String) : EvaluateNowResult
    data class AlreadyQueued(val planId: String, val targetDate: String, val revision: Long, val workId: String) : EvaluateNowResult
    data class Rejected(val reason: EvaluateNowRejection) : EvaluateNowResult
}

enum class EvaluateNowRejection {
    USER_LOCKED,
    PLAN_NOT_FOUND,
    PLAN_DISABLED,
    INVALID_TIME,
    COMMUTE_NOT_CONFIGURED,
    AMAP_CONSENT_REQUIRED,
    CREDENTIAL_STORAGE_ERROR,
    AMAP_WEB_KEY_MISSING,
    CAIYUN_CREDENTIALS_MISSING,
    INVALID_SCHEDULE,
    NO_UPCOMING_OCCURRENCE,
    EVALUATION_WINDOW_EXPIRED,
}

/** Stable UI text for the explicit action; no Provider request has started for a rejection. */
fun EvaluateNowResult.userMessage(): String = when (this) {
    is EvaluateNowResult.Enqueued -> "已加入评估队列"
    is EvaluateNowResult.AlreadyQueued -> "本次评估已在队列中"
    is EvaluateNowResult.Rejected -> reason.userMessage()
}

fun EvaluateNowRejection.userMessage(): String = when (this) {
    EvaluateNowRejection.USER_LOCKED -> "请解锁设备后重新评估"
    EvaluateNowRejection.PLAN_NOT_FOUND -> "该闹钟已删除"
    EvaluateNowRejection.PLAN_DISABLED -> "该闹钟已停用"
    EvaluateNowRejection.INVALID_TIME -> "当前闹钟时间无效，请编辑后重试"
    EvaluateNowRejection.COMMUTE_NOT_CONFIGURED -> "请先配置通勤地点"
    EvaluateNowRejection.AMAP_CONSENT_REQUIRED -> "请先完成高德地图专项授权"
    EvaluateNowRejection.CREDENTIAL_STORAGE_ERROR -> "无法读取服务凭据，请重新配置"
    EvaluateNowRejection.AMAP_WEB_KEY_MISSING -> "请先配置高德 Web Key"
    EvaluateNowRejection.CAIYUN_CREDENTIALS_MISSING -> "请先配置彩云 App Key 和 Secret"
    EvaluateNowRejection.INVALID_SCHEDULE -> "当前闹钟规则无效，请编辑后重试"
    EvaluateNowRejection.NO_UPCOMING_OCCURRENCE -> "当前计划没有可用的下一次基础提醒"
    EvaluateNowRejection.EVALUATION_WINDOW_EXPIRED -> "本次评估窗口已结束"
}

/**
 * Manual evaluation checks the resolved day rather than the plan defaults: the effective commute
 * of the target date must be complete, and its times must parse.
 */
internal fun validateManualEvaluation(
    plan: AlarmPlan,
    inputs: com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputs,
    credentials: CredentialStatus,
    now: Instant = Instant.now(),
): EvaluateNowRejection? = when {
    !plan.enabled -> EvaluateNowRejection.PLAN_DISABLED
    runCatching {
        ZoneId.of(plan.zoneId)
        requireNotNull(inputs.effective.wakeLocalTime)
        requireNotNull(inputs.effective.arrivalLocalTimeValue)
    }.isFailure -> EvaluateNowRejection.INVALID_TIME
    !plan.hasManualEvaluationScheduleAt(now) -> EvaluateNowRejection.INVALID_SCHEDULE
    inputs.effectiveCommute == null -> EvaluateNowRejection.COMMUTE_NOT_CONFIGURED
    !inputs.settings.amapConsentGranted -> EvaluateNowRejection.AMAP_CONSENT_REQUIRED
    credentials.storageError -> EvaluateNowRejection.CREDENTIAL_STORAGE_ERROR
    !credentials.hasAmapWebKey -> EvaluateNowRejection.AMAP_WEB_KEY_MISSING
    !credentials.hasCaiyunAppKey || !credentials.hasCaiyunSecret -> EvaluateNowRejection.CAIYUN_CREDENTIALS_MISSING
    else -> null
}

private fun AlarmPlan.hasManualEvaluationScheduleAt(now: Instant): Boolean = when (val schedule = schedule) {
    null -> false
    is AlarmSchedule.Once -> runCatching { LocalDate.parse(schedule.date) }
        .getOrNull()
        ?.let { !it.isBefore(now.atZone(zoneIdInstance()).toLocalDate()) }
        ?: false
    is AlarmSchedule.Weekly -> schedule.days.isNotEmpty() && schedule.days.all { it in 1..7 }
    AlarmSchedule.Workdays -> true
}

data class EvaluationWorkRun(
    val targetDate: LocalDate,
    val notBefore: Instant,
    val deadline: Instant,
    val attempt: Int,
    val origin: String,
    val revision: Long,
    val zoneId: String,
    val decisionId: String? = null,
    /** Committed day revision of [targetDate]; 0 for legacy work without the tag. */
    val dayRevision: Long = 0,
) {
    fun tags(): Set<String> = buildSet {
        addAll(setOf(
        "target:$targetDate", "not-before:${notBefore.toEpochMilli()}", "deadline:${deadline.toEpochMilli()}",
        "attempt:$attempt", "origin:$origin", "revision:$revision", "zone:$zoneId",
        "day-revision:$dayRevision",
        ))
        decisionId?.let { add("decision:$it") }
    }

    companion object {
        fun fromTags(tags: Set<String>): EvaluationWorkRun? = runCatching {
            fun tag(prefix: String) = tags.single { it.startsWith("$prefix:") }.substringAfter(':')
            fun optionalTag(prefix: String) = tags.singleOrNull { it.startsWith("$prefix:") }?.substringAfter(':')
            EvaluationWorkRun(
                LocalDate.parse(tag("target")),
                Instant.ofEpochMilli(tag("not-before").toLong()),
                Instant.ofEpochMilli(tag("deadline").toLong()),
                tag("attempt").toInt(),
                tag("origin"),
                tag("revision").toLong(),
                tag("zone"),
                optionalTag("decision"),
                // Work enqueued before this fix has no day-revision tag; it is treated as the
                // legacy generation and replaced rather than matched to revision 0.
                optionalTag("day-revision")?.toLong() ?: LEGACY_DAY_REVISION,
            ).also { require(it.attempt in 0..3 && it.origin in setOf("night", "manual")) }
        }.getOrNull()

        /** Marker for legacy work rows that predate day-revision aware identities. */
        const val LEGACY_DAY_REVISION = -1L
    }
}
