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
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
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
    /** Test-only seam; production always reloads the encrypted credential store. */
    internal var credentialStatusReaderForTest: (suspend () -> CredentialStatus)? = null

    /** Active runs retain their plan, revision and target identity for decision-detail matching. */
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
            val hasCommute = commuteResolver.resolveForPlan(planId, localSettings) != null
            val now = clock.instant()
            validateManualEvaluation(plan, localSettings, credentialStatus, hasCommute, now)?.let { rejection ->
                return@withLock EvaluateNowResult.Rejected(rejection)
            }

            val target = occurrences.getByPlanId(planId)
                .asSequence()
                .filter { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED }
                .filter { it.planRevision == plan.revision }
                .filter { it.scheduledWakeAt > now.toEpochMilli() }
                .sortedBy { it.scheduledWakeAt }
                .mapNotNull { runCatching { LocalDate.parse(it.targetDate) }.getOrNull() }
                .firstOrNull()
                ?: return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.NO_UPCOMING_OCCURRENCE)
            val defaultWake = target.atTime(LocalTime.parse(plan.defaultWakeLocalTime))
                .atZone(plan.zoneIdInstance()).toInstant()
            val expiry = minOf(now.plus(Duration.ofHours(2)), defaultWake)
            if (!expiry.isAfter(now)) {
                return@withLock EvaluateNowResult.Rejected(EvaluateNowRejection.EVALUATION_WINDOW_EXPIRED)
            }

            val run = EvaluationWorkRun(target, now, expiry, 0, "manual", plan.revision, plan.zoneId)
            val name = uniqueWorkName(plan, run)
            val existing = manager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS)
            existing.firstOrNull { !it.state.isFinished }?.let { work ->
                return@withLock EvaluateNowResult.AlreadyQueued(plan.id, target.toString(), plan.revision, work.id.toString())
            }
            val workId = enqueue(plan, run, ExistingWorkPolicy.KEEP)
            start()
            EvaluateNowResult.Enqueued(plan.id, target.toString(), plan.revision, workId)
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
            enqueue(
                plan,
                EvaluationWorkRun(evaluationDate.plusDays(1), at, EvaluationWorkPolicy.deadline(evaluationDate, zone), 0, "night", plan.revision, plan.zoneId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                terminalDeduplication = true,
            )
        }
    }

    suspend fun enqueueRetry(plan: AlarmPlan, run: EvaluationWorkRun, at: Instant, decisionId: String) {
        enqueue(
            plan,
            run.copy(notBefore = at, attempt = run.attempt + 1, decisionId = decisionId),
            ExistingWorkPolicy.KEEP,
            terminalDeduplication = true,
        )
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
        plan: AlarmPlan,
        run: EvaluationWorkRun,
        policy: ExistingWorkPolicy,
        terminalDeduplication: Boolean = false,
    ): String {
        val name = uniqueWorkName(plan, run)
        if (terminalDeduplication && manager.getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS).isNotEmpty()) return ""
        val request = OneTimeWorkRequestBuilder<EvaluationWorker>()
            .setInputData(workDataOf(PLAN_ID to plan.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Duration.between(clock.instant(), run.notBefore).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(planTag(plan.id))
            .addTag("evaluation-${run.origin}:${plan.id}")
            .addTag(ALL_WORK_TAG)
            .apply { run.tags().forEach(::addTag) }
            .build()
        manager.enqueueUniqueWork(name, policy, request)
            .result.get(30, TimeUnit.SECONDS)
        _schedulingError.value = null
        return request.id.toString()
    }

    private fun uniqueWorkName(plan: AlarmPlan, run: EvaluationWorkRun): String =
        "evaluation:${plan.id}:${run.revision}:${run.targetDate}:${run.origin}:${run.attempt}"

    companion object {
        const val PLAN_ID = "planId"
        const val ALL_WORK_TAG = "automatic-evaluation"
        fun planTag(planId: String) = "evaluation-plan:$planId"
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

internal fun validateManualEvaluation(
    plan: AlarmPlan,
    settings: LocalSettings,
    credentials: CredentialStatus,
    hasCommute: Boolean,
    now: Instant = Instant.now(),
): EvaluateNowRejection? = when {
    !plan.enabled -> EvaluateNowRejection.PLAN_DISABLED
    runCatching { LocalTime.parse(plan.defaultWakeLocalTime); LocalTime.parse(plan.arrivalLocalTime); ZoneId.of(plan.zoneId) }.isFailure ->
        EvaluateNowRejection.INVALID_TIME
    !plan.hasManualEvaluationScheduleAt(now) -> EvaluateNowRejection.INVALID_SCHEDULE
    !hasCommute -> EvaluateNowRejection.COMMUTE_NOT_CONFIGURED
    !settings.amapConsentGranted -> EvaluateNowRejection.AMAP_CONSENT_REQUIRED
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
) {
    fun tags(): Set<String> = buildSet {
        addAll(setOf(
        "target:$targetDate", "not-before:${notBefore.toEpochMilli()}", "deadline:${deadline.toEpochMilli()}",
        "attempt:$attempt", "origin:$origin", "revision:$revision", "zone:$zoneId",
        ))
        decisionId?.let { add("decision:$it") }
    }

    companion object {
        fun fromTags(tags: Set<String>): EvaluationWorkRun? = runCatching {
            fun tag(prefix: String) = tags.single { it.startsWith("$prefix:") }.substringAfter(':')
            fun optionalTag(prefix: String) = tags.singleOrNull { it.startsWith("$prefix:") }?.substringAfter(':')
            EvaluationWorkRun(LocalDate.parse(tag("target")), Instant.ofEpochMilli(tag("not-before").toLong()),
                Instant.ofEpochMilli(tag("deadline").toLong()), tag("attempt").toInt(), tag("origin"), tag("revision").toLong(), tag("zone"), optionalTag("decision"))
                .also { require(it.attempt in 0..3 && it.origin in setOf("night", "manual")) }
        }.getOrNull()
    }
}
