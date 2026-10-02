package com.ljwzz.weathertrafficalarm.core.alarm

import android.content.Context
import android.os.SystemClock
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmSchedulingGateway
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.RegistrationFailure
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.CommuteOverrideMutation
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.DayOverrideAllocation
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmEvent
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.DailyEvaluationFingerprint
import com.ljwzz.weathertrafficalarm.core.model.DayCommitCredential
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideChange
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideFailureCode
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideSaveResult
import com.ljwzz.weathertrafficalarm.core.model.DayRegistrationState
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmScheduleResolver
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole entry point for all app-owned local alarm mutations. UI, receivers and
 * recovery code never alter a plan, occurrence or AlarmManager independently.
 */
@Singleton
class LocalAlarmCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planRepository: AlarmPlanRepository,
    private val occurrenceRepository: OccurrenceRepository,
    private val decisionRepository: DecisionRepository,
    private val eventRepository: AlarmEventRepository,
    private val overrideRepository: WorkdayOverrideRepository,
    private val calendarRepository: WorkdayCalendarRepository,
    private val scheduler: AlarmSchedulingGateway,
    private val snapshotStore: NextAlarmSnapshotStore,
    private val dailyInputs: DailyEvaluationInputResolver,
    private val diagnosticLogger: RedactingEventLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    val plans: Flow<List<AlarmPlan>> = planRepository.observeAll()
    val occurrences: Flow<List<AlarmOccurrence>> = occurrenceRepository.observeAll()
    val events: Flow<List<AlarmEvent>> = eventRepository.observeAll()

    /**
     * Safely applies a completed evaluation without replacing the user's regular alarm.
     * The independent ADVANCE occurrence remains recoverable through the same snapshot
     * path as regular and snooze occurrences.
     *
     * [expectedFingerprint] is re-resolved inside this lock: a single-day write that landed
     * between the coordinator's own check and this commit changes the committed day revision or
     * any effective value, so the run is stored as stale instead of registering an instance for
     * outdated inputs.
     */
    suspend fun applyEvaluation(
        decision: AlarmDecision,
        expectedFingerprint: DailyEvaluationFingerprint? = null,
    ): ApplyEvaluationResult = mutex.withLock {
        val now = clock.millis()
        val plan = planRepository.getById(decision.planId)
        if (plan == null || !plan.enabled || plan.revision != decision.planRevision) {
            return@withLock persistEvaluationResult(decision, OUTCOME_STALE, null, EvaluationOutcome.STALE)
        }
        val targetDate = runCatching { LocalDate.parse(decision.targetDate) }.getOrNull()
            ?: return@withLock persistEvaluationResult(decision, OUTCOME_STALE, null, EvaluationOutcome.STALE)
        val inputs = dailyInputs.resolve(plan, targetDate)
        if (decision.dayRevision != inputs.committedRevision ||
            (expectedFingerprint != null && !inputs.fingerprint.matches(expectedFingerprint))
        ) {
            return@withLock persistEvaluationResult(decision, OUTCOME_STALE, null, EvaluationOutcome.STALE)
        }
        val expiresAt = parseTimestamp(decision.expiresAt)
        val recommendedAt = parseTimestamp(decision.recommendedWakeAt)
        if (expiresAt == null || expiresAt <= now || recommendedAt == null || recommendedAt <= now) {
            return@withLock persistEvaluationResult(decision, OUTCOME_STALE, null, EvaluationOutcome.STALE)
        }
        if (decision.evaluationOutcome != EvaluationOutcome.SUCCESS) {
            return@withLock persistEvaluationResult(decision, OUTCOME_UNCHANGED, null)
        }

        val planOccurrences = occurrenceRepository.getByPlanId(plan.id)
        val regular = planOccurrences.firstOrNull {
            it.kind == OccurrenceKind.REGULAR &&
                it.planRevision == plan.revision &&
                it.targetDate == decision.targetDate &&
                it.state in ARMABLE_STATES
        } ?: return@withLock persistEvaluationResult(decision, OUTCOME_STALE, null, EvaluationOutcome.STALE)

        if (recommendedAt < regular.scheduledWakeAt - plan.maxAdvanceMinutes * 60_000L) {
            return@withLock persistEvaluationResult(
                decision, OUTCOME_FAILED, null, defaultWakeAt = regular.scheduledWakeAt,
            )
        }

        val advances = planOccurrences.filter {
            it.kind == OccurrenceKind.ADVANCE &&
                it.planRevision == plan.revision &&
                it.targetDate == decision.targetDate
        }
        val advanceIds = advances.map { it.occurrenceId }.toSet()
        val hasStartedAdvance = advances.any { it.state in STARTED_ADVANCE_STATES } ||
            planOccurrences.any {
                it.kind == OccurrenceKind.SNOOZE && it.parentOccurrenceId in advanceIds &&
                    it.state in ACTIVE_STATES + TERMINAL_STATES
            }
        if (hasStartedAdvance) {
            val actual = advances.minOfOrNull { it.scheduledWakeAt }
            return@withLock persistEvaluationResult(decision, OUTCOME_UNCHANGED, actual, defaultWakeAt = regular.scheduledWakeAt)
        }

        if (recommendedAt >= regular.scheduledWakeAt) {
            advances.filter { it.state in ARMABLE_STATES }
                .forEach { cancelAdvance(plan, it, "评估结果无需提前") }
            return@withLock persistEvaluationResult(
                decision, OUTCOME_CANCELLED, null, defaultWakeAt = regular.scheduledWakeAt,
            )
        }

        val retainedEarlierAdvance = advances
            .filter { it.state in ARMABLE_STATES }
            .minByOrNull { it.scheduledWakeAt }
        if (retainedEarlierAdvance != null && retainedEarlierAdvance.scheduledWakeAt <= recommendedAt) {
            return@withLock persistEvaluationResult(
                decision, OUTCOME_UNCHANGED, retainedEarlierAdvance.scheduledWakeAt, defaultWakeAt = regular.scheduledWakeAt,
            )
        }

        val advancesToReplace = advances.filter { it.state in ARMABLE_STATES }
        val advance = AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = decision.targetDate,
            scheduledWakeAt = recommendedAt,
            state = OccurrenceState.REGISTERING,
            decisionId = decision.decisionId,
            kind = OccurrenceKind.ADVANCE,
            dayRevision = inputs.committedRevision,
        )
        val advanceSnapshot = snapshot(plan, advance).copy(defaultWakeAtMillis = regular.scheduledWakeAt)
        occurrenceRepository.save(advance)
        snapshotStore.save(advanceSnapshot)
        val registration = try {
            scheduler.schedule(advanceSnapshot)
        } catch (cancelled: CancellationException) {
            occurrenceRepository.updateState(advance.occurrenceId, OccurrenceState.FAILED.name, now)
            snapshotStore.removeOccurrence(advance.occurrenceId)
            throw cancelled
        } catch (_: Exception) {
            occurrenceRepository.updateState(advance.occurrenceId, OccurrenceState.FAILED.name, now)
            snapshotStore.removeOccurrence(advance.occurrenceId)
            return@withLock persistEvaluationResult(
                decision, OUTCOME_FAILED, null, defaultWakeAt = regular.scheduledWakeAt,
            )
        }
        when (val result = registration) {
            AlarmRegistrationResult.Registered -> {
                occurrenceRepository.updateState(advance.occurrenceId, OccurrenceState.SCHEDULED.name, now)
                snapshotStore.save(advanceSnapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED))
                advancesToReplace.forEach { cancelAdvance(plan, it, "更早评估结果替换提前闹钟") }
                eventRepository.record(plan.id, advance.occurrenceId, AlarmEventType.REGISTERED, "提前闹钟已注册")
                persistEvaluationResult(decision, OUTCOME_APPLIED, recommendedAt, defaultWakeAt = regular.scheduledWakeAt)
            }
            is AlarmRegistrationResult.Rejected -> {
                occurrenceRepository.updateState(advance.occurrenceId, OccurrenceState.FAILED.name, now)
                snapshotStore.removeOccurrence(advance.occurrenceId)
                eventRepository.record(plan.id, advance.occurrenceId, AlarmEventType.REGISTRATION_FAILED, registrationMessage(result))
                persistEvaluationResult(
                    decision, OUTCOME_FAILED, null, defaultWakeAt = regular.scheduledWakeAt,
                )
            }
        }
    }

    suspend fun save(
        plan: AlarmPlan,
        commuteOverrideMutation: CommuteOverrideMutation? = null,
    ): AlarmPlan = mutex.withLock {
        ensureOnceIsNotPast(plan)
        (commuteOverrideMutation as? CommuteOverrideMutation.Replace)?.let {
            require(it.override.planId == plan.id) { "commute override must belong to the plan being saved" }
        }
        val current = planRepository.getById(plan.id)
        if (current?.enabled == true && plan.enabled) {
            return@withLock armCandidate(
                candidate = plan.copy(revision = current.revision + 1),
                previous = current,
                commuteOverrideMutation = commuteOverrideMutation,
            )
        }
        if (current?.enabled == true && !plan.enabled) {
            cancelPlanOccurrences(current, "用户关闭闹钟")
        }
        val saved = planRepository.save(plan, commuteOverrideMutation)
        if (saved.enabled) armNext(saved.id, Instant.now()) else saved
    }

    suspend fun setEnabled(planId: String, enabled: Boolean): AlarmPlan? = mutex.withLock {
        val current = planRepository.getById(planId) ?: return null
        if (!enabled) {
            cancelPlanOccurrences(current, "用户关闭闹钟")
            return current.copy(
                enabled = false,
                armedState = AlarmArmedState.DISABLED,
                scheduleError = null,
                updatedAt = System.currentTimeMillis(),
            ).let { updated ->
                planRepository.update(updated)
                updated
            }
        }
        val desired = current.copy(enabled = true, scheduleError = null, updatedAt = System.currentTimeMillis())
        planRepository.update(desired)
        return armNext(planId, Instant.now())
    }

    suspend fun delete(planId: String) = mutex.withLock {
        val plan = planRepository.getById(planId) ?: return
        cancelPlanOccurrences(plan, "用户删除闹钟")
        planRepository.deleteById(planId)
    }

    /** Called after a verified receiver trigger once encrypted storage is available. */
    suspend fun handleTrigger(occurrenceId: String): Boolean = mutex.withLock {
        val occurrence = occurrenceRepository.getById(occurrenceId) ?: return false
        val plan = planRepository.getById(occurrence.planId) ?: return false
        val now = System.currentTimeMillis()
        if (occurrence.state == OccurrenceState.FIRING) return@withLock true
        if (!plan.enabled || occurrence.planRevision != plan.revision || occurrence.state !in ARMABLE_STATES ||
            now < occurrence.scheduledWakeAt - RECEIVER_EARLY_TOLERANCE_MILLIS ||
            now > occurrence.scheduledWakeAt + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS
        ) return@withLock false

        occurrenceRepository.updateState(occurrenceId, OccurrenceState.FIRING.name, now)
        snapshotStore.getByOccurrenceId(occurrenceId)?.let {
            snapshotStore.save(it.copy(occurrenceState = AlarmReceiver.STATE_FIRING, firedAtMillis = System.currentTimeMillis()))
        }
        eventRepository.record(plan.id, occurrenceId, AlarmEventType.TRIGGERED, "闹钟已触发")

        if (occurrence.kind == OccurrenceKind.REGULAR && plan.schedule !is AlarmSchedule.Once) {
            armNext(plan.id, afterTerminalOrFiring(occurrence, now))
        }
        true
    }

    suspend fun handleMissed(occurrenceId: String): Boolean = mutex.withLock {
        val occurrence = occurrenceRepository.getById(occurrenceId) ?: return@withLock false
        val plan = planRepository.getById(occurrence.planId) ?: return@withLock false
        if (occurrence.state == OccurrenceState.MISSED) return@withLock true
        if (occurrence.state in TERMINAL_STATES) return@withLock false
        markMissed(plan, occurrence, System.currentTimeMillis())
        true
    }

    suspend fun dismiss(occurrenceId: String): Boolean = mutex.withLock {
        val occurrence = occurrenceRepository.getById(occurrenceId) ?: return false
        if (occurrence.state == OccurrenceState.DISMISSED) return@withLock true
        // UI actions and the ringing timeout may only end the occurrence that
        // is actively sounding. In particular, do not overwrite SNOOZED after
        // its child has registered successfully.
        if (occurrence.state != OccurrenceState.FIRING) return false
        occurrenceRepository.updateState(occurrenceId, OccurrenceState.DISMISSED.name, System.currentTimeMillis())
        val snapshot = snapshotStore.getByOccurrenceId(occurrenceId)
        val receipt = snapshot?.withActionReceipt(AlarmReceiver.STATE_DISMISSED)
        scheduler.cancelOccurrence(occurrenceId)
        // cancelOccurrence removes the Direct-Boot snapshot. Restore the small
        // terminal receipt so a full-screen surface can confirm the stop after
        // its PendingIntent returns, including across Activity recreation.
        receipt?.let { snapshotStore.save(it) }
        snapshot?.let {
            context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_DISMISS, it))
        }
        eventRepository.record(occurrence.planId, occurrenceId, AlarmEventType.DISMISSED, "闹钟已停止")
        planRepository.getById(occurrence.planId)?.let { completeOneShotIfNeeded(it) }
        true
    }

    suspend fun snooze(occurrenceId: String): Boolean = mutex.withLock {
        val parent = occurrenceRepository.getById(occurrenceId) ?: return false
        val plan = planRepository.getById(parent.planId) ?: return false
        if (parent.state != OccurrenceState.FIRING || !plan.enabled) return false

        val child = createSnoozeOccurrence(plan, parent)
        val snapshot = snapshot(plan, child)
        val parentSnapshot = snapshotStore.getByOccurrenceId(parent.occurrenceId)
        occurrenceRepository.save(child)
        snapshotStore.save(snapshot)
        return when (val result = scheduler.schedule(snapshot)) {
            AlarmRegistrationResult.Registered -> {
                occurrenceRepository.updateState(parent.occurrenceId, OccurrenceState.SNOOZED.name, System.currentTimeMillis())
                occurrenceRepository.updateState(child.occurrenceId, OccurrenceState.SCHEDULED.name, System.currentTimeMillis())
                snapshotStore.save(snapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED))
                parentSnapshot?.let {
                    snapshotStore.save(it.withActionReceipt(AlarmReceiver.STATE_SNOOZED))
                    context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_SNOOZE, it))
                }
                eventRepository.record(plan.id, parent.occurrenceId, AlarmEventType.SNOOZED, "已稍后 ${plan.snoozeMinutes} 分钟")
                true
            }
            is AlarmRegistrationResult.Rejected -> {
                occurrenceRepository.updateState(child.occurrenceId, OccurrenceState.FAILED.name, System.currentTimeMillis())
                snapshotStore.removeOccurrence(child.occurrenceId)
                parentSnapshot?.let {
                    snapshotStore.save(
                        it.withActionReceipt(
                            occurrenceState = AlarmReceiver.STATE_FIRING,
                            actionError = AlarmReceiver.SNOOZE_RETRY_MESSAGE,
                        ),
                    )
                }
                eventRepository.record(plan.id, child.occurrenceId, AlarmEventType.REGISTRATION_FAILED, registrationMessage(result))
                false
            }
        }
    }

    suspend fun refreshCalendar(force: Boolean = false): Boolean {
        val changed = calendarRepository.refresh(force)
        if (!changed) return false
        mutex.withLock {
            planRepository.observeAll().first()
                .filter { it.enabled && it.schedule is AlarmSchedule.Workdays }
                .forEach { armNext(it.id, Instant.now()) }
        }
        return true
    }

    /**
     * Applies one complete single-day snapshot through the candidate protocol: the candidate
     * revision and its recoverable record are allocated first, the candidate instance is
     * registered while the previous instance is still armed, the published snapshot and commit
     * credential are written in one device-protected update, and only then does one aggregated
     * transaction commit the override, the committed revision and the occurrence transitions.
     * A failure before commit restores the existing values and effective instance. Post-commit
     * cleanup is retried from the credential without changing the successful save result.
     */
    suspend fun setDayOverride(change: DayOverrideChange): DayOverrideSaveResult = mutex.withLock {
        commitDayChange(change, clock.millis())
    }

    /**
     * Removes the single-day override so the date inherits the calendar again. The day revision
     * survives the deletion, so recreating the same values never re-uses a revision.
     */
    suspend fun clearDayOverride(planId: String, date: String, expectedDayRevision: Long): DayOverrideSaveResult =
        setDayOverride(DayOverrideChange(planId, date, expectedDayRevision, replacement = null))

    private suspend fun commitDayChange(change: DayOverrideChange, now: Long): DayOverrideSaveResult {
        val plan = planRepository.getById(change.planId)
            ?: return DayOverrideSaveResult.Failure(DayOverrideFailureCode.PLAN_NOT_FOUND, "该闹钟已删除")
        val replacement = change.replacement
            ?.takeUnless { it.isInheritingEverything }
            ?.copy(planId = change.planId, date = change.date)
        val current = overrideRepository.getState(change.planId, change.date)
        if (current.committedRevision != change.expectedDayRevision) {
            return DayOverrideSaveResult.Failure(
                DayOverrideFailureCode.CONFLICT,
                "该日期已被其他操作修改，请重新载入后再保存",
            )
        }
        if (replacement == null && current.override == null) {
            // Repeated undo of a date that already inherits everything stays a no-op.
            return DayOverrideSaveResult.Success(null, current.committedRevision, DayRegistrationState.UNCHANGED)
        }

        val occurrences = occurrenceRepository.getByPlanId(plan.id)
        // A plan that cannot be armed at all still stores the day values: only an attempted
        // registration that the platform actually refused may fail the save.
        val projected = if (plan.enabled && plan.schedule != null) {
            projectNextInstance(plan, change.date, replacement, occurrences, now)
        } else {
            null
        }
        val superseded = projected?.supersededOccurrenceIds.orEmpty()
            .mapNotNull { id -> occurrences.firstOrNull { it.occurrenceId == id } }
        val invalidated = invalidatedOccurrences(plan, change.date, occurrences, projected?.supersededOccurrenceIds.orEmpty())
        val cancelledByChange = (invalidated + superseded).distinctBy { it.occurrenceId }

        val changeId = UUID.randomUUID().toString()
        val canRegister = plan.enabled && plan.schedule != null && scheduler.canScheduleExactAlarms()
        var candidateOccurrence = projected?.takeIf { it.requiresRegistration && canRegister }?.let { candidate ->
            AlarmOccurrence(
                occurrenceId = UUID.randomUUID().toString(),
                planId = plan.id,
                planRevision = plan.revision,
                targetDate = requireNotNull(candidate.targetDate),
                scheduledWakeAt = requireNotNull(candidate.wakeAt).toEpochMilli(),
                state = OccurrenceState.REGISTERING,
                kind = OccurrenceKind.REGULAR,
            )
        }
        val allocation = overrideRepository.allocateCandidate(
            changeId = changeId,
            planId = plan.id,
            date = change.date,
            replacement = replacement,
            expectedDayRevision = change.expectedDayRevision,
            candidateOccurrenceId = candidateOccurrence?.occurrenceId,
            cancelledOccurrenceIds = cancelledByChange.map { it.occurrenceId },
            now = now,
        )
        val candidateRevision = when (allocation) {
            is DayOverrideAllocation.Conflict -> return DayOverrideSaveResult.Failure(
                DayOverrideFailureCode.CONFLICT,
                "该日期已被其他操作修改，请重新载入后再保存",
            )
            is DayOverrideAllocation.Allocated -> allocation.candidateRevision
        }
        candidateOccurrence = candidateOccurrence?.let {
            it.copy(dayRevision = if (it.targetDate == change.date) candidateRevision else overrideRepository.committedRevision(plan.id, it.targetDate))
        }

        var capabilityFailure: AlarmRegistrationResult.Rejected? = null
        if (candidateOccurrence != null) {
            val candidateSnapshot = snapshot(plan, candidateOccurrence)
            val registration = try {
                occurrenceRepository.save(candidateOccurrence)
                snapshotStore.save(candidateSnapshot)
                scheduler.schedule(candidateSnapshot)
            } catch (cancelled: CancellationException) {
                discardDayCandidate(plan, changeId, candidateOccurrence, now)
                throw cancelled
            } catch (_: Exception) {
                discardDayCandidate(plan, changeId, candidateOccurrence, now)
                return DayOverrideSaveResult.Failure(
                    DayOverrideFailureCode.REGISTRATION_FAILED,
                    "系统拒绝注册闹钟，已保留原设置",
                )
            }
            when (registration) {
                AlarmRegistrationResult.Registered -> Unit
                is AlarmRegistrationResult.Rejected -> {
                    val message = registrationMessage(registration)
                    if (registration.reason == RegistrationFailure.PLATFORM_REJECTED) {
                        discardDayCandidate(plan, changeId, candidateOccurrence, now)
                        eventRepository.record(
                            plan.id,
                            candidateOccurrence.occurrenceId,
                            AlarmEventType.REGISTRATION_FAILED,
                            message,
                        )
                        return DayOverrideSaveResult.Failure(
                            DayOverrideFailureCode.REGISTRATION_FAILED,
                            message,
                        )
                    }
                    // The device cannot arm right now (missing capability or a past trigger):
                    // keep the day values, drop the candidate and let the armed state carry the
                    // reason instead of failing the edit.
                    try {
                        withContext(NonCancellable) {
                            scheduler.cancelOccurrence(candidateOccurrence.occurrenceId)
                            snapshotStore.removeOccurrence(candidateOccurrence.occurrenceId)
                            overrideRepository.abandonRegistration(changeId, candidateOccurrence.occurrenceId, now)
                        }
                    } catch (cancelled: CancellationException) {
                        discardDayCandidate(plan, changeId, candidateOccurrence, now)
                        throw cancelled
                    } catch (_: Exception) {
                        discardDayCandidate(plan, changeId, candidateOccurrence, now)
                        return DayOverrideSaveResult.Failure(
                            DayOverrideFailureCode.STORAGE_FAILED,
                            "无法清理注册候选，已保留原设置",
                        )
                    }
                    capabilityFailure = registration
                    candidateOccurrence = null
                }
            }
        }

        val armedState = when {
            !plan.enabled -> AlarmArmedState.DISABLED
            plan.schedule == null -> AlarmArmedState.NEEDS_RULE
            projected?.wakeAt == null -> AlarmArmedState.COMPLETED
            capabilityFailure != null -> armedFailureState(capabilityFailure)
            !canRegister -> AlarmArmedState.NEEDS_PERMISSION
            else -> AlarmArmedState.SCHEDULED
        }
        val scheduleError = when {
            capabilityFailure != null -> registrationMessage(capabilityFailure)
            armedState == AlarmArmedState.NEEDS_PERMISSION -> "精确闹钟权限不可用"
            armedState == AlarmArmedState.NEEDS_RULE -> "请先选择日期或重复规则"
            else -> null
        }
        val credential = DayCommitCredential(
            changeId = changeId,
            planId = plan.id,
            date = change.date,
            dayRevision = candidateRevision,
            occurrenceId = candidateOccurrence?.occurrenceId,
            targetDate = candidateOccurrence?.targetDate,
            triggerAtMillis = candidateOccurrence?.scheduledWakeAt,
            cancelledOccurrenceIds = cancelledByChange.map { it.occurrenceId },
            revisedOccurrenceIds = projected?.revisedOccurrenceIds.orEmpty(),
            armedState = armedState,
            scheduleError = scheduleError,
        )
        var committed = false
        try {
            val result = AlarmReceiver.withDirectBootLock {
                currentCoroutineContext().ensureActive()
                // Publication decides recovery and must either commit or restore its rollback image.
                withContext(NonCancellable) {
                    try {
                        if (candidateOccurrence == null) {
                            snapshotStore.publishCommitCredential(credential)
                        } else {
                            snapshotStore.publishCandidate(
                                snapshot(plan, candidateOccurrence).copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED),
                                credential,
                            )
                        }
                        overrideRepository.markCandidatePublished(changeId)
                    } catch (cancelled: CancellationException) {
                        discardDayCandidate(plan, changeId, candidateOccurrence, now)
                        throw cancelled
                    } catch (_: Exception) {
                        discardDayCandidate(plan, changeId, candidateOccurrence, now)
                        return@withContext DayOverrideSaveResult.Failure(DayOverrideFailureCode.STORAGE_FAILED, "无法发布日期变更，已保留原设置")
                    }
                    val state = try {
                        requireNotNull(overrideRepository.commitCandidate(
                            changeId = changeId,
                            revisedOccurrenceIds = credential.revisedOccurrenceIds,
                            now = now,
                            armedState = armedState,
                            scheduleError = scheduleError,
                            candidateDayRevision = candidateOccurrence?.dayRevision,
                        )) { "日期候选记录不存在" }
                    } catch (cancelled: CancellationException) {
                        compensatePublishedCandidate(plan, changeId, candidateOccurrence, now)
                        throw cancelled
                    } catch (_: Exception) {
                        compensatePublishedCandidate(plan, changeId, candidateOccurrence, now)
                        return@withContext DayOverrideSaveResult.Failure(DayOverrideFailureCode.STORAGE_FAILED, "无法提交日期变更，已保留原设置")
                    }
                    committed = true
                    finishDayCommit(credential)
                    DayOverrideSaveResult.Success(
                        state.override,
                        state.committedRevision,
                        when {
                            armedState != AlarmArmedState.SCHEDULED -> DayRegistrationState.NOT_ARMED
                            candidateOccurrence != null -> DayRegistrationState.SCHEDULED
                            else -> DayRegistrationState.UNCHANGED
                        },
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            return result
        } catch (cancelled: CancellationException) {
            if (!committed) discardDayCandidate(plan, changeId, candidateOccurrence, now)
            throw cancelled
        }
    }

    /** Keep the credential until all post-commit effects finish; a cleanup error cannot undo a save. */
    private suspend fun finishDayCommit(credential: DayCommitCredential) {
        try {
            credential.cancelledOccurrenceIds.forEach { id ->
                scheduler.cancelOccurrence(id)
                snapshotStore.removeOccurrence(id)
                eventRepository.record(credential.planId, id, AlarmEventType.CANCELLED, "日期规则已更新")
            }
            credential.occurrenceId?.let { eventRepository.record(credential.planId, it, AlarmEventType.REGISTERED, "本地闹钟已更新") }
            snapshotStore.removeCommitCredential(credential.changeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The inactive DP snapshots and credential remain durable for the next recovery.
        }
    }

    /**
     * Instances this date change invalidates: the date's advance instances and their snooze
     * descendants, plus regular instances the new projection replaces or removes.
     */
    private suspend fun invalidatedOccurrences(
        plan: AlarmPlan,
        date: String,
        occurrences: List<AlarmOccurrence>,
        replacedRegularIds: List<String>,
    ): List<AlarmOccurrence> {
        val affected = occurrences
            .filter { it.kind == OccurrenceKind.ADVANCE && it.targetDate == date }
            .map { it.occurrenceId }
            .toMutableSet()
        affected.addAll(replacedRegularIds)
        var added: Boolean
        do {
            added = occurrences
                .filter { it.parentOccurrenceId in affected }
                .map { it.occurrenceId }
                .filter { affected.add(it) }
                .isNotEmpty()
        } while (added)
        return occurrences.filter { it.occurrenceId in affected && it.state in ACTIVE_STATES }
    }

    /** Which regular instance the changed date needs after the candidate snapshot is applied. */
    private data class ProjectedInstance(
        val wakeAt: Instant?,
        val targetDate: String?,
        val requiresRegistration: Boolean,
        val supersededOccurrenceIds: List<String>,
        val revisedOccurrenceIds: List<String>,
    )

    private suspend fun projectNextInstance(
        plan: AlarmPlan,
        date: String,
        replacement: SingleDayOverride?,
        occurrences: List<AlarmOccurrence>,
        now: Long,
    ): ProjectedInstance? {
        val overrides = overrideRepository.getForPlan(plan.id).filterNot { it.date == date } +
            listOfNotNull(replacement)
        val nextWake = AlarmScheduleResolver.next(
            plan = plan,
            after = Instant.ofEpochMilli(now),
            calendar = calendarRepository.statuses(),
            overrides = overrides,
        )
        val regulars = occurrences.filter {
            it.kind == OccurrenceKind.REGULAR &&
                it.planRevision == plan.revision &&
                it.state in ARMABLE_STATES
        }
        if (nextWake == null) {
            return ProjectedInstance(null, null, false, regulars.map { it.occurrenceId } + occurrences.filter {
                it.kind == OccurrenceKind.REGULAR && it.targetDate == date && it.state in ACTIVE_STATES
            }.map { it.occurrenceId }, emptyList())
        }
        val zone = plan.zoneIdInstance()
        val targetDate = nextWake.atZone(zone).toLocalDate().toString()
        val retained = regulars.filter {
            it.scheduledWakeAt == nextWake.toEpochMilli() && it.targetDate == targetDate
        }.minByOrNull { it.occurrenceId }?.takeIf { plan.armedState == AlarmArmedState.SCHEDULED }
        val superseded = regulars.filter { it.occurrenceId != retained?.occurrenceId }
        return ProjectedInstance(
            wakeAt = nextWake,
            targetDate = targetDate,
            requiresRegistration = retained == null,
            supersededOccurrenceIds = superseded.map { it.occurrenceId },
            revisedOccurrenceIds = if (targetDate == date) listOfNotNull(retained?.occurrenceId) else emptyList(),
        )
    }

    /** Cancels a candidate that never became effective and drops its recoverable record. */
    private suspend fun discardDayCandidate(
        plan: AlarmPlan?,
        changeId: String,
        candidateOccurrence: AlarmOccurrence?,
        now: Long,
        revokeCredential: Boolean = true,
    ) {
        // Cleanup must still run while the caller is being cancelled, otherwise a killed save
        // would leave a REGISTERING candidate behind.
        withContext(NonCancellable) {
            if (revokeCredential) snapshotStore.rollbackCandidate(changeId)
            candidateOccurrence?.let { candidate ->
                runCatching { scheduler.cancelOccurrence(candidate.occurrenceId) }
                runCatching { snapshotStore.removeOccurrence(candidate.occurrenceId) }
            }
            runCatching {
                overrideRepository.discardCandidate(
                    changeId = changeId,
                    failedOccurrenceIds = listOfNotNull(candidateOccurrence?.occurrenceId),
                    now = now,
                )
            }
            plan?.let { plan ->
                candidateOccurrence?.let {
                    runCatching {
                        eventRepository.record(
                            plan.id,
                            it.occurrenceId,
                            AlarmEventType.CANCELLED,
                            "日期变更未提交，候选实例已清理",
                        )
                    }
                }
            }
            // The previously armed instances were never cancelled, so they stay valid.
        }
    }

    /**
     * Compensation for the narrow window after the device-protected publish: the platform
     * candidate is revoked, its snapshot and credential removed, and the change record dropped.
     * The committed override and revision were never written by the failed transaction.
     */
    private suspend fun compensatePublishedCandidate(
        plan: AlarmPlan,
        changeId: String,
        candidateOccurrence: AlarmOccurrence?,
        now: Long,
    ) {
        discardDayCandidate(plan, changeId, candidateOccurrence, now)
    }

    /** Rehydrates DB state written in device-protected storage before unlock. */
    suspend fun recover() = mutex.withLock {
        val startedElapsed = SystemClock.elapsedRealtime()
        val results = mutableListOf<RecoveryResult>()
        try {
            snapshotStore.migrateLegacyCredentialProtectedSnapshotsIfUnlocked()
            val now = System.currentTimeMillis()
            recoverDayChangeCandidates(now)
            snapshotStore.observeAll().first().forEach { snapshot -> results += recoverSnapshot(snapshot, now) }
            val snapshotsByOccurrence = snapshotStore.observeAll().first().associateBy { it.occurrenceId }
            planRepository.observeAll().first()
                .filter { it.enabled && it.schedule != null }
                .forEach { plan ->
                    val active = occurrenceRepository.getByPlanId(plan.id)
                        .filter { it.planRevision == plan.revision && it.state in ACTIVE_STATES }
                    active
                        .filterNot { snapshotsByOccurrence.containsKey(it.occurrenceId) }
                        .forEach { occurrence -> results += recoverMissingSnapshot(plan, occurrence, now) }
                    deduplicatePendingAdvances(plan)
                    val hasRegular = occurrenceRepository.getByPlanId(plan.id)
                        .any {
                            it.kind == OccurrenceKind.REGULAR && it.planRevision == plan.revision &&
                                it.state in ACTIVE_STATES
                        }
                    if (!hasRegular) {
                        results += when (armNext(plan.id, Instant.ofEpochMilli(now)).armedState) {
                            AlarmArmedState.SCHEDULED -> RecoveryResult.RECOVERED
                            AlarmArmedState.COMPLETED -> RecoveryResult.SKIPPED
                            else -> RecoveryResult.FAILED
                        }
                    }
                }
            recordRecovery(results, startedElapsed)
        } catch (cancelled: CancellationException) {
            recordRecovery(listOf(RecoveryResult.CANCELLED), startedElapsed)
            throw cancelled
        } catch (failure: Exception) {
            recordRecovery(listOf(RecoveryResult.FAILED), startedElapsed)
            throw failure
        }
    }

    /**
     * Replaces an already-armed plan without first persisting the new revision.
     * A registration failure therefore leaves the previous revision and its
     * PendingIntent valid. The candidate occurrence can reference the existing
     * plan row while it is staged.
     */
    private suspend fun armCandidate(
        candidate: AlarmPlan,
        previous: AlarmPlan,
        commuteOverrideMutation: CommuteOverrideMutation?,
    ): AlarmPlan {
        if (candidate.schedule == null) {
            return preserveExistingRegistration(previous, "请先选择日期或重复规则")
        }
        if (!scheduler.canScheduleExactAlarms()) {
            return preserveExistingRegistration(previous, "精确闹钟权限不可用")
        }
        val nextWake = AlarmScheduleResolver.next(
            plan = candidate,
            after = Instant.now(),
            calendar = calendarRepository.statuses(),
            overrides = overrideRepository.getForPlan(candidate.id),
        ) ?: if (candidate.schedule is AlarmSchedule.Once) {
            throw IllegalArgumentException("指定日期时间必须晚于当前时间")
        } else {
            return preserveExistingRegistration(previous, "未找到下一次闹钟时间")
        }
        val stagedTargetDate = nextWake.atZone(candidate.zoneIdInstance()).toLocalDate().toString()
        val staged = AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = candidate.id,
            planRevision = candidate.revision,
            targetDate = stagedTargetDate,
            scheduledWakeAt = nextWake.toEpochMilli(),
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.REGULAR,
            dayRevision = overrideRepository.committedRevision(candidate.id, stagedTargetDate),
        )
        val stagedSnapshot = snapshot(candidate, staged)
        occurrenceRepository.save(staged)
        snapshotStore.save(stagedSnapshot)
        return when (val result = scheduler.schedule(stagedSnapshot)) {
            AlarmRegistrationResult.Registered -> {
                occurrenceRepository.updateState(staged.occurrenceId, OccurrenceState.SCHEDULED.name, System.currentTimeMillis())
                snapshotStore.save(stagedSnapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED))
                val committed = candidate.copy(armedState = AlarmArmedState.SCHEDULED, scheduleError = null)
                try {
                    planRepository.update(committed, commuteOverrideMutation)
                } catch (failure: Exception) {
                    discardUncommittedCandidate(staged)
                    throw failure
                }
                cancelPlanOccurrences(previous, "闹钟已更新", exceptOccurrenceId = staged.occurrenceId)
                eventRepository.record(candidate.id, staged.occurrenceId, AlarmEventType.REGISTERED, "本地闹钟已更新")
                committed
            }
            is AlarmRegistrationResult.Rejected -> {
                occurrenceRepository.updateState(staged.occurrenceId, OccurrenceState.FAILED.name, System.currentTimeMillis())
                snapshotStore.removeOccurrence(staged.occurrenceId)
                val message = registrationMessage(result)
                eventRepository.record(previous.id, staged.occurrenceId, AlarmEventType.REGISTRATION_FAILED, message)
                preserveExistingRegistration(previous, message)
            }
        }
    }

    private suspend fun armNext(planId: String, after: Instant): AlarmPlan {
        val plan = planRepository.getById(planId) ?: error("Unknown alarm plan $planId")
        if (!plan.enabled) return plan
        if (plan.schedule == null) {
            return updateArmedState(plan, AlarmArmedState.NEEDS_RULE, "请先选择日期或重复规则")
        }
        if (!scheduler.canScheduleExactAlarms()) {
            return updateArmedState(plan, AlarmArmedState.NEEDS_PERMISSION, "精确闹钟权限不可用")
        }
        val nextWake = AlarmScheduleResolver.next(
            plan = plan,
            after = after,
            calendar = calendarRepository.statuses(),
            overrides = overrideRepository.getForPlan(plan.id),
        ) ?: return updateArmedState(plan, AlarmArmedState.COMPLETED, null)

        val existingRegular = occurrenceRepository.getByPlanId(plan.id)
            .filter { it.kind == OccurrenceKind.REGULAR && it.state in ARMABLE_STATES }
            .minByOrNull { it.scheduledWakeAt }
        if (existingRegular?.scheduledWakeAt == nextWake.toEpochMilli() && existingRegular.planRevision == plan.revision) {
            return updateArmedState(plan, AlarmArmedState.SCHEDULED, null)
        }

        val newTargetDate = nextWake.atZone(plan.zoneIdInstance()).toLocalDate().toString()
        val newOccurrence = AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = newTargetDate,
            scheduledWakeAt = nextWake.toEpochMilli(),
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.REGULAR,
            dayRevision = overrideRepository.committedRevision(plan.id, newTargetDate),
        )
        val newSnapshot = snapshot(plan, newOccurrence)
        occurrenceRepository.save(newOccurrence)
        snapshotStore.save(newSnapshot)
        return when (val result = scheduler.schedule(newSnapshot)) {
            AlarmRegistrationResult.Registered -> {
                occurrenceRepository.updateState(newOccurrence.occurrenceId, OccurrenceState.SCHEDULED.name, System.currentTimeMillis())
                snapshotStore.save(newSnapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED))
                existingRegular?.let { old ->
                    scheduler.cancelOccurrence(old.occurrenceId)
                    occurrenceRepository.updateState(old.occurrenceId, OccurrenceState.CANCELLED.name, System.currentTimeMillis())
                }
                eventRepository.record(plan.id, newOccurrence.occurrenceId, AlarmEventType.REGISTERED, "本地闹钟已注册")
                updateArmedState(plan, AlarmArmedState.SCHEDULED, null)
            }
            is AlarmRegistrationResult.Rejected -> {
                occurrenceRepository.updateState(newOccurrence.occurrenceId, OccurrenceState.FAILED.name, System.currentTimeMillis())
                snapshotStore.removeOccurrence(newOccurrence.occurrenceId)
                val message = registrationMessage(result)
                eventRepository.record(plan.id, newOccurrence.occurrenceId, AlarmEventType.REGISTRATION_FAILED, message)
                updateArmedState(plan, armedFailureState(result), message)
            }
        }
    }

    /**
     * Completes or discards single-day changes interrupted between the device-protected publish
     * and the aggregated transaction. A published credential completes exactly the same commit;
     * anything else is discarded so the previously committed values and instances stay effective,
     * and an unpublished candidate never becomes an effective instance.
     */
    private suspend fun recoverDayChangeCandidates(now: Long) {
        AlarmReceiver.withDirectBootLock {
            withContext(NonCancellable) { recoverPublishedDayChanges(now) }
        }
    }

    private suspend fun recoverPublishedDayChanges(now: Long) {
        val pending = overrideRepository.pendingCandidates()
        val pendingIds = pending.map { it.changeId }.toSet()
        // Credentials without a change record belong to a commit that already finished.
        snapshotStore.commitCredentials()
            .filterNot { it.changeId in pendingIds }
            .forEach { committed -> finishDayCommit(committed) }
        pending.forEach { candidate ->
            val credential = snapshotStore.commitCredential(candidate.changeId)
            val candidateOccurrence = candidate.candidateOccurrenceId?.let { occurrenceRepository.getById(it) }
            if (credential == null) {
                candidateOccurrence?.takeIf { it.state in ACTIVE_STATES }?.let { occurrence ->
                    scheduler.cancelOccurrence(occurrence.occurrenceId)
                    snapshotStore.removeOccurrence(occurrence.occurrenceId)
                }
                overrideRepository.discardCandidate(
                    changeId = candidate.changeId,
                    failedOccurrenceIds = listOfNotNull(candidate.candidateOccurrenceId),
                    now = now,
                )
                snapshotStore.removeCommitCredential(candidate.changeId)
                return@forEach
            }
            val recoveryCredential = credential.copy(
                cancelledOccurrenceIds = (candidate.cancelledOccurrenceIds + credential.cancelledOccurrenceIds).distinct(),
            )
            val candidateRevision = candidateOccurrence?.let {
                if (it.targetDate == candidate.date) candidate.candidateRevision else overrideRepository.committedRevision(candidate.planId, it.targetDate)
            }
            val publishedSnapshot = candidateOccurrence?.let { snapshotStore.getByOccurrenceId(it.occurrenceId) }
            try {
                if (publishedSnapshot != null) {
                    snapshotStore.publishCandidate(publishedSnapshot.copy(dayRevision = requireNotNull(candidateRevision)), recoveryCredential)
                } else {
                    snapshotStore.publishCommitCredential(recoveryCredential)
                }
                requireNotNull(overrideRepository.commitCandidate(
                    changeId = candidate.changeId,
                    revisedOccurrenceIds = credential.revisedOccurrenceIds,
                    now = now,
                    armedState = credential.armedState ?: candidateOccurrence?.let { AlarmArmedState.SCHEDULED },
                    scheduleError = credential.scheduleError,
                    candidateDayRevision = candidateRevision,
                )) { "日期候选记录不存在" }
            } catch (cancelled: CancellationException) {
                discardDayCandidate(planRepository.getById(candidate.planId), candidate.changeId, candidateOccurrence, now)
                throw cancelled
            } catch (_: Exception) {
                discardDayCandidate(planRepository.getById(candidate.planId), candidate.changeId, candidateOccurrence, now)
                // The normal snapshot pass below re-registers the restored original instance.
                return@forEach
            }
            finishDayCommit(recoveryCredential)
        }
    }

    private suspend fun recoverSnapshot(snapshot: NextAlarmSnapshot, now: Long): RecoveryResult {
        val plan = planRepository.getById(snapshot.planId) ?: run {
            scheduler.cancelOccurrence(snapshot.occurrenceId)
            return RecoveryResult.RECOVERED
        }
        if (!plan.enabled || plan.revision != snapshot.planRevision || plan.armedState == AlarmArmedState.COMPLETED) {
            scheduler.cancelOccurrence(snapshot.occurrenceId)
            occurrenceRepository.getById(snapshot.occurrenceId)
                ?.takeIf { it.state !in TERMINAL_STATES }
                ?.let { occurrenceRepository.updateState(it.occurrenceId, OccurrenceState.CANCELLED.name, now) }
            return RecoveryResult.RECOVERED
        }
        val occurrence = occurrenceRepository.getById(snapshot.occurrenceId) ?: createOccurrenceFromSnapshot(plan, snapshot)
        if (occurrence.state in TERMINAL_STATES) {
            // A committed change cancels the old platform registration after the transaction; a
            // crash in between leaves this snapshot behind, so finish the cleanup here.
            scheduler.cancelOccurrence(occurrence.occurrenceId)
            snapshotStore.removeOccurrence(occurrence.occurrenceId)
            return RecoveryResult.RECOVERED
        }
        if (occurrence.kind == OccurrenceKind.ADVANCE && snapshot.dayRevision == 0L &&
            occurrence.decisionId == null && overrideRepository.committedRevision(plan.id, occurrence.targetDate) > 0L
        ) {
            // Legacy advance snapshot without a day revision cannot be verified against the
            // current date configuration, so it is dropped for the next evaluation to redo.
            cancelAdvance(plan, occurrence, "旧快照缺少日期修订，重新评估")
            snapshotStore.removeOccurrence(occurrence.occurrenceId)
            return RecoveryResult.RECOVERED
        }
        return when (snapshot.occurrenceState) {
            AlarmReceiver.STATE_SCHEDULED -> when {
                snapshot.triggerAtMillis + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS < now -> {
                    markMissed(plan, occurrence, now)
                    RecoveryResult.RECOVERED
                }
                snapshot.triggerAtMillis <= now -> {
                    val firing = snapshot.copy(occurrenceState = AlarmReceiver.STATE_FIRING, firedAtMillis = now)
                    markFiring(plan, occurrence, firing, now)
                    RecoveryResult.RECOVERED
                }
                else -> when (val result = scheduler.restore(snapshot, now)) {
                    AlarmRegistrationResult.Registered -> {
                        occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.SCHEDULED.name, now)
                        RecoveryResult.RECOVERED
                    }
                    is AlarmRegistrationResult.Rejected -> {
                        updateArmedState(plan, armedFailureState(result), registrationMessage(result))
                        RecoveryResult.FAILED
                    }
                }
            }
            AlarmReceiver.STATE_FIRING -> {
                val firedAt = snapshot.firedAtMillis ?: snapshot.triggerAtMillis
                if (firedAt + RING_TIMEOUT_MILLIS < now) {
                    markDismissed(plan, occurrence, "响铃超时结束", now)
                } else {
                    markFiring(plan, occurrence, snapshot, now)
                }
                RecoveryResult.RECOVERED
            }
            AlarmReceiver.STATE_SNOOZED -> if (occurrence.state != OccurrenceState.SNOOZED) {
                occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.SNOOZED.name, now)
                RecoveryResult.RECOVERED
            } else RecoveryResult.SKIPPED
            AlarmReceiver.STATE_DISMISSED -> {
                markDismissed(plan, occurrence, "响铃已结束", now)
                RecoveryResult.RECOVERED
            }
            AlarmReceiver.STATE_MISSED -> {
                markMissed(plan, occurrence, now)
                RecoveryResult.RECOVERED
            }
            else -> RecoveryResult.SKIPPED
        }
    }

    /**
     * Device-protected preferences can be empty after a legacy credential-encrypted
     * snapshot. Room is the post-unlock source of truth, so restore the same instance
     * rather than calculating a new occurrence ID.
     */
    private suspend fun recoverMissingSnapshot(plan: AlarmPlan, occurrence: AlarmOccurrence, now: Long): RecoveryResult {
        if (!plan.enabled || occurrence.planRevision != plan.revision || occurrence.state !in ACTIVE_STATES) return RecoveryResult.SKIPPED
        if (occurrence.kind == OccurrenceKind.REGULAR &&
            occurrence.dayRevision != overrideRepository.committedRevision(plan.id, occurrence.targetDate)
        ) {
            // The date configuration changed while the snapshot was missing. Rebuild the basic
            // instance from the current effective values instead of restoring an outdated one.
            scheduler.cancelOccurrence(occurrence.occurrenceId)
            occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.CANCELLED.name, now)
            armNext(plan.id, Instant.ofEpochMilli(now))
            return RecoveryResult.RECOVERED
        }
        return when (occurrence.state) {
            OccurrenceState.REGISTERING,
            OccurrenceState.SCHEDULED,
            OccurrenceState.DEFAULT_REGISTERED,
            OccurrenceState.ADVANCED,
            -> {
                val recovered = snapshot(plan, occurrence).copy(
                    occurrenceState = AlarmReceiver.STATE_SCHEDULED,
                    defaultWakeAtMillis = occurrence.decisionId
                        ?.let { decisionRepository.getById(it)?.defaultWakeAt }
                        ?.let(::parseTimestamp),
                )
                when {
                    occurrence.scheduledWakeAt + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS < now ->
                        markMissed(plan, occurrence, now).let { RecoveryResult.RECOVERED }
                    occurrence.scheduledWakeAt <= now ->
                        markFiring(
                            plan,
                            occurrence,
                            recovered.copy(occurrenceState = AlarmReceiver.STATE_FIRING, firedAtMillis = now),
                            now,
                        ).let { RecoveryResult.RECOVERED }
                    else -> {
                        snapshotStore.save(recovered)
                        when (val result = scheduler.restore(recovered, now)) {
                            AlarmRegistrationResult.Registered -> {
                                occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.SCHEDULED.name, now)
                                RecoveryResult.RECOVERED
                            }
                            is AlarmRegistrationResult.Rejected -> {
                                updateArmedState(plan, armedFailureState(result), registrationMessage(result))
                                RecoveryResult.FAILED
                            }
                        }
                    }
                }
            }
            OccurrenceState.FIRING -> {
                val firing = snapshot(plan, occurrence).copy(
                    occurrenceState = AlarmReceiver.STATE_FIRING,
                    firedAtMillis = occurrence.updatedAt,
                )
                if (occurrence.updatedAt + RING_TIMEOUT_MILLIS < now) {
                    markDismissed(plan, occurrence, "响铃超时结束", now)
                } else {
                    snapshotStore.save(firing)
                    AlarmReceiver.startRinging(context, firing)
                }
                RecoveryResult.RECOVERED
            }
            OccurrenceState.SNOOZED -> {
                snapshotStore.save(snapshot(plan, occurrence).copy(occurrenceState = AlarmReceiver.STATE_SNOOZED))
                RecoveryResult.RECOVERED
            }
            else -> RecoveryResult.SKIPPED
        }
    }

    private suspend fun createOccurrenceFromSnapshot(plan: AlarmPlan, snapshot: NextAlarmSnapshot): AlarmOccurrence {
        val occurrence = AlarmOccurrence(
            occurrenceId = snapshot.occurrenceId,
            planId = snapshot.planId,
            planRevision = snapshot.planRevision,
            targetDate = snapshot.targetDate
                ?: Instant.ofEpochMilli(snapshot.triggerAtMillis).atZone(ZoneId.of(plan.zoneId)).toLocalDate().toString(),
            scheduledWakeAt = snapshot.triggerAtMillis,
            state = OccurrenceState.valueOf(snapshot.occurrenceState),
            decisionId = snapshot.decisionId,
            kind = OccurrenceKind.valueOf(snapshot.occurrenceKind),
            parentOccurrenceId = snapshot.parentOccurrenceId,
            dayRevision = snapshot.dayRevision,
        )
        occurrenceRepository.save(occurrence)
        return occurrence
    }

    /**
     * A snooze child keeps the parent's logical date and day revision. A snooze that crosses
     * midnight therefore still belongs to the day it was evaluated for.
     */
    private fun createSnoozeOccurrence(plan: AlarmPlan, parent: AlarmOccurrence): AlarmOccurrence {
        val wakeAt = System.currentTimeMillis() + plan.snoozeMinutes * 60_000L
        return AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = parent.targetDate,
            scheduledWakeAt = wakeAt,
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.SNOOZE,
            parentOccurrenceId = parent.occurrenceId,
            dayRevision = parent.dayRevision,
        )
    }

    private fun snapshot(plan: AlarmPlan, occurrence: AlarmOccurrence): NextAlarmSnapshot = NextAlarmSnapshot(
        occurrenceId = occurrence.occurrenceId,
        planId = plan.id,
        planRevision = plan.revision,
        triggerAtMillis = occurrence.scheduledWakeAt,
        soundUri = plan.sound.uri,
        vibrationEnabled = plan.vibration.enabled,
        vibrationPatternMillis = plan.vibration.patternMillis.toList(),
        snoozeMinutes = plan.snoozeMinutes,
        alarmLabel = plan.name.ifBlank { "闹钟" },
        occurrenceKind = occurrence.kind.name,
        decisionId = occurrence.decisionId,
        parentOccurrenceId = occurrence.parentOccurrenceId,
        occurrenceState = occurrence.state.name,
        targetDate = occurrence.targetDate,
        dayRevision = occurrence.dayRevision,
    )

    private suspend fun cancelPlanOccurrences(
        plan: AlarmPlan,
        reason: String,
        exceptOccurrenceId: String? = null,
    ) {
        occurrenceRepository.getByPlanId(plan.id)
            .filter {
                it.occurrenceId != exceptOccurrenceId &&
                    it.state in ACTIVE_STATES
            }
            .forEach { occurrence ->
                val snapshot = snapshotStore.getByOccurrenceId(occurrence.occurrenceId)
                scheduler.cancelOccurrence(occurrence.occurrenceId)
                occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.CANCELLED.name, System.currentTimeMillis())
                eventRepository.record(plan.id, occurrence.occurrenceId, AlarmEventType.CANCELLED, reason)
                snapshot?.let {
                    context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_DISMISS, it))
                }
            }
    }

    /** A registered candidate is invalid until its plan and commute draft commit together. */
    private suspend fun discardUncommittedCandidate(candidate: AlarmOccurrence) {
        runCatching { scheduler.cancelOccurrence(candidate.occurrenceId) }
        runCatching {
            occurrenceRepository.updateState(
                candidate.occurrenceId,
                OccurrenceState.CANCELLED.name,
                System.currentTimeMillis(),
            )
        }
        runCatching { snapshotStore.removeOccurrence(candidate.occurrenceId) }
    }

    private suspend fun completeOneShotIfNeeded(plan: AlarmPlan) {
        val hasActiveFollowUp = occurrenceRepository.getByPlanId(plan.id).any {
            it.kind in setOf(OccurrenceKind.REGULAR, OccurrenceKind.ADVANCE, OccurrenceKind.SNOOZE) &&
                it.state in ACTIVE_STATES
        } || snapshotStore.observeAll().first().any {
            it.planId == plan.id &&
                it.occurrenceKind in setOf(
                    OccurrenceKind.REGULAR.name,
                    OccurrenceKind.ADVANCE.name,
                    OccurrenceKind.SNOOZE.name,
                ) &&
                it.occurrenceState in ACTIVE_SNAPSHOT_STATES
        }
        if (plan.schedule is AlarmSchedule.Once && !hasActiveFollowUp) {
            planRepository.update(plan.copy(enabled = false, armedState = AlarmArmedState.COMPLETED, scheduleError = null))
        }
    }

    private suspend fun cancelAdvance(plan: AlarmPlan, occurrence: AlarmOccurrence, reason: String) {
        scheduler.cancelOccurrence(occurrence.occurrenceId)
        occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.CANCELLED.name, System.currentTimeMillis())
        eventRepository.record(plan.id, occurrence.occurrenceId, AlarmEventType.CANCELLED, reason)
    }

    /** A crash after registering a replacement can leave two valid advance snapshots. */
    private suspend fun deduplicatePendingAdvances(plan: AlarmPlan) {
        occurrenceRepository.getByPlanId(plan.id)
            .filter {
                it.kind == OccurrenceKind.ADVANCE && it.planRevision == plan.revision &&
                    it.state in ARMABLE_STATES
            }
            .groupBy { it.targetDate }
            .values
            .forEach { advances ->
                advances.sortedBy { it.scheduledWakeAt }.drop(1)
                    .forEach { cancelAdvance(plan, it, "恢复时清理重复提前闹钟") }
            }
    }

    private suspend fun persistEvaluationResult(
        decision: AlarmDecision,
        outcome: String,
        actualWakeAt: Long?,
        evaluationOutcome: EvaluationOutcome = decision.evaluationOutcome,
        defaultWakeAt: Long? = null,
    ): ApplyEvaluationResult {
        decisionRepository.save(
            decision.copy(
                evaluationOutcome = evaluationOutcome,
                applicationOutcome = outcome,
                defaultWakeAt = decision.defaultWakeAt ?: defaultWakeAt?.let { Instant.ofEpochMilli(it).toString() },
                actualWakeAt = actualWakeAt?.let { Instant.ofEpochMilli(it).toString() },
            ),
        )
        return ApplyEvaluationResult(outcome, actualWakeAt)
    }

    private fun parseTimestamp(value: String): Long? =
        value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private suspend fun scheduleAfterTerminalRegular(plan: AlarmPlan, occurrence: AlarmOccurrence) {
        if (occurrence.kind == OccurrenceKind.REGULAR && plan.schedule !is AlarmSchedule.Once) {
            armNext(plan.id, afterTerminalOrFiring(occurrence, System.currentTimeMillis()))
        } else {
            completeOneShotIfNeeded(plan)
        }
    }

    private suspend fun markFiring(
        plan: AlarmPlan,
        occurrence: AlarmOccurrence,
        snapshot: NextAlarmSnapshot,
        now: Long,
    ) {
        if (occurrence.state == OccurrenceState.FIRING) {
            AlarmReceiver.startRinging(context, snapshot)
            return
        }
        if (occurrence.state !in ARMABLE_STATES) return
        occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.FIRING.name, now)
        snapshotStore.save(snapshot.copy(occurrenceState = AlarmReceiver.STATE_FIRING, firedAtMillis = now))
        eventRepository.record(plan.id, occurrence.occurrenceId, AlarmEventType.TRIGGERED, "闹钟已触发")
        if (occurrence.kind == OccurrenceKind.REGULAR && plan.schedule !is AlarmSchedule.Once) {
            armNext(plan.id, afterTerminalOrFiring(occurrence, now))
        }
        AlarmReceiver.startRinging(context, snapshot)
    }

    private suspend fun markDismissed(
        plan: AlarmPlan,
        occurrence: AlarmOccurrence,
        message: String,
        now: Long,
    ) {
        if (occurrence.state == OccurrenceState.DISMISSED) return
        if (occurrence.state in TERMINAL_STATES) return
        occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.DISMISSED.name, now)
        scheduler.cancelOccurrence(occurrence.occurrenceId)
        eventRepository.record(plan.id, occurrence.occurrenceId, AlarmEventType.DISMISSED, message)
        scheduleAfterTerminalRegular(plan, occurrence)
    }

    private suspend fun markMissed(plan: AlarmPlan, occurrence: AlarmOccurrence, now: Long) {
        if (occurrence.state == OccurrenceState.MISSED || occurrence.state in TERMINAL_STATES) return
        occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.MISSED.name, now)
        scheduler.cancelOccurrence(occurrence.occurrenceId)
        eventRepository.record(plan.id, occurrence.occurrenceId, AlarmEventType.MISSED, "超过响铃宽限期")
        scheduleAfterTerminalRegular(plan, occurrence)
    }

    private fun recordRecovery(results: List<RecoveryResult>, startedElapsed: Long) {
        val resultCode = when {
            results.any { it == RecoveryResult.FAILED } -> DiagnosticResultCode.FAILED
            results.any { it == RecoveryResult.CANCELLED } -> DiagnosticResultCode.CANCELLED
            results.any { it == RecoveryResult.RECOVERED } -> DiagnosticResultCode.SUCCESS
            else -> DiagnosticResultCode.SKIPPED
        }
        diagnosticLogger?.record(
            eventType = DiagnosticEventType.ALARM_RECOVERY,
            resultCode = resultCode,
            durationMs = SystemClock.elapsedRealtime() - startedElapsed,
        )
    }

    private fun afterTerminalOrFiring(occurrence: AlarmOccurrence, now: Long): Instant =
        Instant.ofEpochMilli(maxOf(now, occurrence.scheduledWakeAt + 1))

    private suspend fun ensureOnceIsNotPast(plan: AlarmPlan) {
        if (plan.schedule is AlarmSchedule.Once && AlarmScheduleResolver.next(plan, Instant.now()) == null) {
            throw IllegalArgumentException("指定日期时间必须晚于当前时间")
        }
    }

    private suspend fun preserveExistingRegistration(previous: AlarmPlan, error: String): AlarmPlan {
        val retained = previous.copy(scheduleError = error, updatedAt = System.currentTimeMillis())
        planRepository.update(retained)
        return retained
    }

    private suspend fun updateArmedState(
        plan: AlarmPlan,
        state: AlarmArmedState,
        error: String?,
    ): AlarmPlan {
        val updated = plan.copy(
        armedState = state,
        scheduleError = error,
        updatedAt = System.currentTimeMillis(),
        )
        planRepository.update(updated)
        return updated
    }

    private fun armedFailureState(result: AlarmRegistrationResult.Rejected): AlarmArmedState = when (result.reason) {
        RegistrationFailure.EXACT_ALARM_PERMISSION,
        RegistrationFailure.NOTIFICATIONS_DISABLED,
        -> AlarmArmedState.NEEDS_PERMISSION
        else -> AlarmArmedState.FAILED
    }

    private fun registrationMessage(result: AlarmRegistrationResult.Rejected): String = result.detail ?: when (result.reason) {
        RegistrationFailure.PAST_TRIGGER -> "闹钟时间已过"
        RegistrationFailure.EXACT_ALARM_PERMISSION -> "精确闹钟权限不可用"
        RegistrationFailure.NOTIFICATIONS_DISABLED -> "通知权限不可用"
        RegistrationFailure.PLATFORM_REJECTED -> "系统拒绝注册闹钟"
    }

    private fun NextAlarmSnapshot.withActionReceipt(
        occurrenceState: String,
        actionError: String? = null,
    ): NextAlarmSnapshot = copy(
        occurrenceState = occurrenceState,
        actionRevision = actionRevision + 1,
        actionError = actionError,
    )

    private companion object {
        enum class RecoveryResult {
            RECOVERED,
            SKIPPED,
            FAILED,
            CANCELLED,
        }

        const val RECEIVER_EARLY_TOLERANCE_MILLIS = 60_000L
        const val RING_TIMEOUT_MILLIS = 10 * 60_000L
        val ARMABLE_STATES = setOf(
            OccurrenceState.REGISTERING,
            OccurrenceState.SCHEDULED,
            OccurrenceState.DEFAULT_REGISTERED,
            OccurrenceState.ADVANCED,
        )
        val ACTIVE_STATES = ARMABLE_STATES + setOf(OccurrenceState.FIRING, OccurrenceState.SNOOZED)
        val TERMINAL_STATES = setOf(
            OccurrenceState.DISMISSED,
            OccurrenceState.MISSED,
            OccurrenceState.CANCELLED,
            OccurrenceState.FAILED,
        )
        val ACTIVE_SNAPSHOT_STATES = setOf(
            AlarmReceiver.STATE_SCHEDULED,
            AlarmReceiver.STATE_FIRING,
            AlarmReceiver.STATE_SNOOZED,
        )
        val STARTED_ADVANCE_STATES = setOf(
            OccurrenceState.FIRING,
            OccurrenceState.SNOOZED,
            OccurrenceState.DISMISSED,
            OccurrenceState.MISSED,
        )

        const val OUTCOME_APPLIED = "APPLIED"
        const val OUTCOME_UNCHANGED = "UNCHANGED"
        const val OUTCOME_CANCELLED = "CANCELLED"
        const val OUTCOME_STALE = "STALE"
        const val OUTCOME_FAILED = "FAILED"
    }
}
