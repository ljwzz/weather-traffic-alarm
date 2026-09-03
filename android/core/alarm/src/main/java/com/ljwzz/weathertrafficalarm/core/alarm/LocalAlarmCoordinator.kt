package com.ljwzz.weathertrafficalarm.core.alarm

import android.content.Context
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmSchedulingGateway
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.RegistrationFailure
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
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
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmScheduleResolver
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
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
) {
    private val mutex = Mutex()
    val plans: Flow<List<AlarmPlan>> = planRepository.observeAll()
    val occurrences: Flow<List<AlarmOccurrence>> = occurrenceRepository.observeAll()
    val events: Flow<List<AlarmEvent>> = eventRepository.observeAll()

    /**
     * Safely applies a completed evaluation without replacing the user's regular alarm.
     * The independent ADVANCE occurrence remains recoverable through the same snapshot
     * path as regular and snooze occurrences.
     */
    suspend fun applyEvaluation(decision: AlarmDecision): ApplyEvaluationResult = mutex.withLock {
        val now = System.currentTimeMillis()
        val plan = planRepository.getById(decision.planId)
        if (plan == null || !plan.enabled || plan.revision != decision.planRevision) {
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
                decision, OUTCOME_FAILED, null, EvaluationOutcome.FAILED, regular.scheduledWakeAt,
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
                decision, OUTCOME_FAILED, null, EvaluationOutcome.FAILED, regular.scheduledWakeAt,
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
                    decision, OUTCOME_FAILED, null, EvaluationOutcome.FAILED, regular.scheduledWakeAt,
                )
            }
        }
    }

    suspend fun save(plan: AlarmPlan): AlarmPlan = mutex.withLock {
        ensureOnceIsNotPast(plan)
        val current = planRepository.getById(plan.id)
        if (current?.enabled == true && plan.enabled) {
            return@withLock armCandidate(
                candidate = plan.copy(revision = current.revision + 1),
                previous = current,
            )
        }
        if (current?.enabled == true && !plan.enabled) {
            cancelPlanOccurrences(current, "用户关闭闹钟")
        }
        val saved = planRepository.save(plan)
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
        if (occurrence.state !in setOf(OccurrenceState.FIRING, OccurrenceState.SNOOZED, OccurrenceState.SCHEDULED)) return false
        occurrenceRepository.updateState(occurrenceId, OccurrenceState.DISMISSED.name, System.currentTimeMillis())
        val snapshot = snapshotStore.getByOccurrenceId(occurrenceId)
        snapshot?.let {
            snapshotStore.save(it.copy(occurrenceState = AlarmReceiver.STATE_DISMISSED))
        }
        scheduler.cancelOccurrence(occurrenceId)
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
        occurrenceRepository.save(child)
        snapshotStore.save(snapshot)
        return when (val result = scheduler.schedule(snapshot)) {
            AlarmRegistrationResult.Registered -> {
                occurrenceRepository.updateState(parent.occurrenceId, OccurrenceState.SNOOZED.name, System.currentTimeMillis())
                occurrenceRepository.updateState(child.occurrenceId, OccurrenceState.SCHEDULED.name, System.currentTimeMillis())
                snapshotStore.save(snapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED))
                snapshotStore.getByOccurrenceId(parent.occurrenceId)?.let {
                    snapshotStore.save(it.copy(occurrenceState = AlarmReceiver.STATE_SNOOZED))
                    context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_SNOOZE, it))
                }
                eventRepository.record(plan.id, parent.occurrenceId, AlarmEventType.SNOOZED, "已稍后 ${plan.snoozeMinutes} 分钟")
                true
            }
            is AlarmRegistrationResult.Rejected -> {
                occurrenceRepository.updateState(child.occurrenceId, OccurrenceState.FAILED.name, System.currentTimeMillis())
                snapshotStore.removeOccurrence(child.occurrenceId)
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

    suspend fun setDayOverride(override: WorkdayOverride) = mutex.withLock {
        overrideRepository.save(override)
        planRepository.getById(override.planId)?.takeIf { it.enabled }?.let { plan ->
            cancelEvaluationOccurrencesForDate(plan, override.date, "日期规则已更新")
            armNext(plan.id, Instant.now())
        }
    }

    suspend fun clearDayOverride(planId: String, date: String) = mutex.withLock {
        overrideRepository.delete(planId, date)
        planRepository.getById(planId)?.takeIf { it.enabled }?.let { plan ->
            cancelEvaluationOccurrencesForDate(plan, date, "日期规则已更新")
            armNext(plan.id, Instant.now())
        }
    }

    /** Rehydrates DB state written in device-protected storage before unlock. */
    suspend fun recover() = mutex.withLock {
        snapshotStore.migrateLegacyCredentialProtectedSnapshotsIfUnlocked()
        val now = System.currentTimeMillis()
        snapshotStore.observeAll().first().forEach { snapshot -> recoverSnapshot(snapshot, now) }
        val snapshotsByOccurrence = snapshotStore.observeAll().first().associateBy { it.occurrenceId }
        planRepository.observeAll().first()
            .filter { it.enabled && it.schedule != null }
            .forEach { plan ->
                val active = occurrenceRepository.getByPlanId(plan.id)
                    .filter { it.planRevision == plan.revision && it.state in ACTIVE_STATES }
                active
                    .filterNot { snapshotsByOccurrence.containsKey(it.occurrenceId) }
                    .forEach { occurrence -> recoverMissingSnapshot(plan, occurrence, now) }
                deduplicatePendingAdvances(plan)
                val hasRegular = occurrenceRepository.getByPlanId(plan.id)
                    .any {
                        it.kind == OccurrenceKind.REGULAR && it.planRevision == plan.revision &&
                            it.state in ACTIVE_STATES
                    }
                if (!hasRegular) armNext(plan.id, Instant.ofEpochMilli(now))
            }
    }

    /**
     * Replaces an already-armed plan without first persisting the new revision.
     * A registration failure therefore leaves the previous revision and its
     * PendingIntent valid. The candidate occurrence can reference the existing
     * plan row while it is staged.
     */
    private suspend fun armCandidate(candidate: AlarmPlan, previous: AlarmPlan): AlarmPlan {
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
        val staged = AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = candidate.id,
            planRevision = candidate.revision,
            targetDate = nextWake.atZone(candidate.zoneIdInstance()).toLocalDate().toString(),
            scheduledWakeAt = nextWake.toEpochMilli(),
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.REGULAR,
        )
        val stagedSnapshot = snapshot(candidate, staged)
        occurrenceRepository.save(staged)
        snapshotStore.save(stagedSnapshot)
        return when (val result = scheduler.schedule(stagedSnapshot)) {
            AlarmRegistrationResult.Registered -> {
                occurrenceRepository.updateState(staged.occurrenceId, OccurrenceState.SCHEDULED.name, System.currentTimeMillis())
                snapshotStore.save(stagedSnapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED))
                planRepository.update(candidate.copy(armedState = AlarmArmedState.SCHEDULED, scheduleError = null))
                cancelPlanOccurrences(previous, "闹钟已更新", exceptOccurrenceId = staged.occurrenceId)
                eventRepository.record(candidate.id, staged.occurrenceId, AlarmEventType.REGISTERED, "本地闹钟已更新")
                candidate.copy(armedState = AlarmArmedState.SCHEDULED, scheduleError = null)
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

        val newOccurrence = AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = nextWake.atZone(plan.zoneIdInstance()).toLocalDate().toString(),
            scheduledWakeAt = nextWake.toEpochMilli(),
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.REGULAR,
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

    private suspend fun recoverSnapshot(snapshot: NextAlarmSnapshot, now: Long) {
        val plan = planRepository.getById(snapshot.planId) ?: run {
            scheduler.cancelOccurrence(snapshot.occurrenceId)
            return
        }
        if (!plan.enabled || plan.revision != snapshot.planRevision || plan.armedState == AlarmArmedState.COMPLETED) {
            scheduler.cancelOccurrence(snapshot.occurrenceId)
            occurrenceRepository.getById(snapshot.occurrenceId)
                ?.takeIf { it.state !in TERMINAL_STATES }
                ?.let { occurrenceRepository.updateState(it.occurrenceId, OccurrenceState.CANCELLED.name, now) }
            return
        }
        val occurrence = occurrenceRepository.getById(snapshot.occurrenceId) ?: createOccurrenceFromSnapshot(plan, snapshot)
        if (occurrence.state in TERMINAL_STATES) {
            snapshotStore.removeOccurrence(occurrence.occurrenceId)
            return
        }
        when (snapshot.occurrenceState) {
            AlarmReceiver.STATE_SCHEDULED -> when {
                snapshot.triggerAtMillis + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS < now -> {
                    markMissed(plan, occurrence, now)
                }
                snapshot.triggerAtMillis <= now -> {
                    val firing = snapshot.copy(occurrenceState = AlarmReceiver.STATE_FIRING, firedAtMillis = now)
                    markFiring(plan, occurrence, firing, now)
                }
                else -> when (val result = scheduler.restore(snapshot, now)) {
                    AlarmRegistrationResult.Registered -> occurrenceRepository.updateState(
                        occurrence.occurrenceId,
                        OccurrenceState.SCHEDULED.name,
                        now,
                    )
                    is AlarmRegistrationResult.Rejected -> updateArmedState(plan, armedFailureState(result), registrationMessage(result))
                }
            }
            AlarmReceiver.STATE_FIRING -> {
                val firedAt = snapshot.firedAtMillis ?: snapshot.triggerAtMillis
                if (firedAt + RING_TIMEOUT_MILLIS < now) {
                    markDismissed(plan, occurrence, "响铃超时结束", now)
                } else {
                    markFiring(plan, occurrence, snapshot, now)
                }
            }
            AlarmReceiver.STATE_SNOOZED -> if (occurrence.state != OccurrenceState.SNOOZED) {
                occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.SNOOZED.name, now)
            }
            AlarmReceiver.STATE_DISMISSED -> {
                markDismissed(plan, occurrence, "响铃已结束", now)
            }
            AlarmReceiver.STATE_MISSED -> {
                markMissed(plan, occurrence, now)
            }
        }
    }

    /**
     * Device-protected preferences can be empty after a legacy credential-encrypted
     * snapshot. Room is the post-unlock source of truth, so restore the same instance
     * rather than calculating a new occurrence ID.
     */
    private suspend fun recoverMissingSnapshot(plan: AlarmPlan, occurrence: AlarmOccurrence, now: Long) {
        if (!plan.enabled || occurrence.planRevision != plan.revision || occurrence.state !in ACTIVE_STATES) return
        when (occurrence.state) {
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
                        markMissed(plan, occurrence, now)
                    occurrence.scheduledWakeAt <= now ->
                        markFiring(
                            plan,
                            occurrence,
                            recovered.copy(occurrenceState = AlarmReceiver.STATE_FIRING, firedAtMillis = now),
                            now,
                        )
                    else -> {
                        snapshotStore.save(recovered)
                        when (val result = scheduler.restore(recovered, now)) {
                            AlarmRegistrationResult.Registered -> occurrenceRepository.updateState(
                                occurrence.occurrenceId,
                                OccurrenceState.SCHEDULED.name,
                                now,
                            )
                            is AlarmRegistrationResult.Rejected ->
                                updateArmedState(plan, armedFailureState(result), registrationMessage(result))
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
            }
            OccurrenceState.SNOOZED -> snapshotStore.save(
                snapshot(plan, occurrence).copy(occurrenceState = AlarmReceiver.STATE_SNOOZED),
            )
            else -> Unit
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
        )
        occurrenceRepository.save(occurrence)
        return occurrence
    }

    private fun createSnoozeOccurrence(plan: AlarmPlan, parent: AlarmOccurrence): AlarmOccurrence {
        val wakeAt = System.currentTimeMillis() + plan.snoozeMinutes * 60_000L
        return AlarmOccurrence(
            occurrenceId = UUID.randomUUID().toString(),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = Instant.ofEpochMilli(wakeAt).atZone(plan.zoneIdInstance()).toLocalDate().toString(),
            scheduledWakeAt = wakeAt,
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.SNOOZE,
            parentOccurrenceId = parent.occurrenceId,
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
        alarmLabel = plan.name,
        occurrenceKind = occurrence.kind.name,
        decisionId = occurrence.decisionId,
        parentOccurrenceId = occurrence.parentOccurrenceId,
        occurrenceState = occurrence.state.name,
        targetDate = occurrence.targetDate,
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

    /** Cancels an invalidated advance and every still-active snooze descendant. */
    private suspend fun cancelEvaluationOccurrencesForDate(plan: AlarmPlan, targetDate: String, reason: String) {
        val all = occurrenceRepository.getByPlanId(plan.id)
        val affectedIds = all
            .filter { it.kind == OccurrenceKind.ADVANCE && it.targetDate == targetDate }
            .map { it.occurrenceId }
            .toMutableSet()
        var added: Boolean
        do {
            added = all.filter { it.parentOccurrenceId in affectedIds }
                .map { it.occurrenceId }
                .filter { affectedIds.add(it) }
                .isNotEmpty()
        } while (added)
        all.filter { it.occurrenceId in affectedIds && it.state in ACTIVE_STATES }
            .forEach { occurrence ->
                val snapshot = snapshotStore.getByOccurrenceId(occurrence.occurrenceId)
                scheduler.cancelOccurrence(occurrence.occurrenceId)
                occurrenceRepository.updateState(occurrence.occurrenceId, OccurrenceState.CANCELLED.name, System.currentTimeMillis())
                eventRepository.record(plan.id, occurrence.occurrenceId, AlarmEventType.CANCELLED, reason)
                snapshot?.takeIf { occurrence.state == OccurrenceState.FIRING }?.let {
                    context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_DISMISS, it))
                }
            }
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

    private companion object {
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
