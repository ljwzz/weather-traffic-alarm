package com.ljwzz.weathertrafficalarm.core.alarm

import android.content.Context
import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.*
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.*
import com.ljwzz.weathertrafficalarm.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayOverrideCommitRegressionTest {
    private val now = Instant.parse("2099-10-01T12:00:00Z")
    private val target = "2099-10-02"
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var days: WorkdayOverrideRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var snapshots: NextAlarmSnapshotStore
    private lateinit var gateway: Gateway
    private lateinit var coordinator: LocalAlarmCoordinator

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao(), db.planCommuteWriteDao())
        days = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        occurrences = OccurrenceRepository(db.alarmOccurrenceDao())
        snapshots = NextAlarmSnapshotStore(context)
        snapshots.clear()
        gateway = Gateway()
        coordinator = coordinatorFor(snapshots)
    }

    private fun coordinatorFor(store: NextAlarmSnapshotStore): LocalAlarmCoordinator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calendar = WorkdayCalendarRepository(context)
        val resolver = DailyEvaluationInputResolver(plans, LocalSettingsStore(context),
            EffectiveCommuteResolver(PlanCommuteOverrideRepository(db.planCommuteOverrideDao())), days, calendar)
        return LocalAlarmCoordinator(context, plans, occurrences,
            DecisionRepository(db.alarmDecisionDao()), AlarmEventRepository(db.alarmEventDao()),
            days, calendar, gateway, store, resolver, clock = Clock.fixed(now, ZoneId.of("UTC")))
    }
    @After fun cleanup() = runBlocking { snapshots.clear(); db.close() }

    private suspend fun seed(schedule: AlarmSchedule = AlarmSchedule.Once(target),
        armed: AlarmArmedState = AlarmArmedState.SCHEDULED): AlarmPlan {
        val plan = AlarmPlan(id = "review", revision = 1, name = "review", enabled = true,
            zoneId = "UTC", defaultWakeLocalTime = "06:30", arrivalLocalTime = "09:00",
            preparationMinutes = 30, maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING,
            schedule = schedule, armedState = armed)
        db.alarmPlanDao().upsert(plan.toEntity())
        return plan
    }
    private suspend fun regular(plan: AlarmPlan, revision: Long = 0): AlarmOccurrence {
        val at = LocalDate.parse(target).atTime(6, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val occurrence = AlarmOccurrence("old-regular", plan.id, plan.revision, target, at,
            OccurrenceState.SCHEDULED, dayRevision = revision)
        occurrences.save(occurrence)
        snapshots.save(NextAlarmSnapshot(occurrence.occurrenceId, plan.id, plan.revision, at,
            null, true, 5, targetDate = target, dayRevision = revision))
        return occurrence
    }
    private suspend fun change(plan: AlarmPlan, date: String = target, wake: String? = "07:30",
        status: DayStatus? = null): DayOverrideSaveResult = coordinator.setDayOverride(
        DayOverrideChange(plan.id, date, days.committedRevision(plan.id, date),
            SingleDayOverride(plan.id, date, status = status, wakeLocalTime = wake, preparationMinutes = 45)))

    @Test fun reviewCapabilityRejectionStillPersistsTheDraft() = runBlocking {
        val plan = seed()
        regular(plan)
        gateway.result = AlarmRegistrationResult.Rejected(RegistrationFailure.NOTIFICATIONS_DISABLED)
        val result = change(plan)
        val state = days.getState(plan.id, target)
        assertEquals("07:30", state.override?.wakeLocalTime,
            "result=$result; committedRevision=${state.committedRevision}; capability rejection must commit the date")
    }

    @Test fun reviewHolidayWithoutNextInstanceCancelsTheRegular() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val result = change(plan, wake = null, status = DayStatus.HOLIDAY)
        val row = occurrences.getById(old.occurrenceId)
        val snapshot = snapshots.getByOccurrenceId(old.occurrenceId)
        val gate = snapshot?.let { AlarmReceiver.triggerHandling(it, it.triggerAtMillis) }
        assertEquals(OccurrenceState.CANCELLED, row?.state,
            "result=$result; planState=${plans.getById(plan.id)?.armedState}; old trigger gate=$gate")
    }

    @Test fun reviewOtherDateEditKeepsTheNextRegularRevision() = runBlocking {
        val plan = seed(AlarmSchedule.Weekly(setOf(1, 2, 3, 4, 5, 6, 7)))
        val old = regular(plan)
        val result = change(plan, date = "2099-10-05", wake = null)
        val row = occurrences.getById(old.occurrenceId)
        assertEquals(old.dayRevision, row?.dayRevision,
            "editing another date changed the next instance; result=$result; target revision=${days.committedRevision(plan.id, target)}")
    }

    @Test fun reviewReplacementSnapshotUsesItsOwnTargetDateRevision() = runBlocking {
        val plan = seed(AlarmSchedule.Weekly(setOf(1, 2, 3, 4, 5, 6, 7)))
        regular(plan)
        change(plan, wake = null, status = DayStatus.HOLIDAY)
        val next = occurrences.getByPlanId(plan.id).single { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED }
        val snapshot = requireNotNull(snapshots.getByOccurrenceId(next.occurrenceId))
        assertEquals(days.committedRevision(plan.id, next.targetDate), snapshot.dayRevision,
            "next target=${next.targetDate}; snapshot=${snapshot.dayRevision}; occurrence=${next.dayRevision}")
    }

    @Test fun reviewSameDateCandidateCarriesItsCommittedRevision() = runBlocking {
        val plan = seed()
        regular(plan)
        change(plan)
        val next = occurrences.getByPlanId(plan.id).single {
            it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
        }
        val committed = days.committedRevision(plan.id, next.targetDate)
        assertEquals(committed, next.dayRevision,
            "registered candidate target=${next.targetDate}; committed=$committed; snapshot=${snapshots.getByOccurrenceId(next.occurrenceId)?.dayRevision}")
    }

    @Test fun reviewCleanupFailureCannotReportAnUncommittedEdit() = runBlocking {
        val plan = seed()
        regular(plan)
        gateway.cancelFailure = IllegalStateException("injected cancellation cleanup failure")
        val result = runCatching { change(plan) }
        val state = days.getState(plan.id, target)
        assertTrue(result.isSuccess,
            "save throws after committed=${state.committedRevision}, wake=${state.override?.wakeLocalTime}; ViewModel reports STORAGE_FAILED")
    }

    @Test fun reviewCommittedInterruptionLeavesOnlyOneBootEligibleSnapshot() = runBlocking {
        val plan = seed()
        regular(plan)
        gateway.cancelFailure = IllegalStateException("interrupt before old registration cleanup")
        runCatching { change(plan) }
        val committed = days.getState(plan.id, target)
        val bootEligible = snapshots.observeAll().first().filter {
            it.occurrenceState == AlarmReceiver.STATE_SCHEDULED &&
                AlarmReceiver.triggerHandling(it, it.triggerAtMillis) == AlarmReceiver.AlarmHandling.TRIGGERED
        }
        assertEquals(1, bootEligible.size,
            "Room revision=${committed.committedRevision}; Direct Boot restores ${bootEligible.map { it.occurrenceId to it.dayRevision }}")
    }

    @Test fun reviewRegisteredCandidateUpdatesThePlanArmedState() = runBlocking {
        val plan = seed(armed = AlarmArmedState.NEEDS_PERMISSION)
        val result = change(plan)
        assertEquals(AlarmArmedState.SCHEDULED, plans.getById(plan.id)?.armedState,
            "registered candidate result=$result; plan state must reflect the actual registration")
    }

    @Test fun reviewAdvanceCarriesItsEvaluatedDayRevision() = runBlocking {
        val plan = seed()
        val state = requireNotNull(days.commitCurrent(plan.id, target,
            SingleDayOverride(plan.id, target, preparationMinutes = 15), now.toEpochMilli()))
        val regular = regular(plan, state.committedRevision)
        val decision = AlarmDecision(decisionId = "review-decision", planId = plan.id,
            planRevision = plan.revision, targetDate = target, workdayStatus = null,
            estimatedDepartureAt = null, commuteSeconds = null, weatherSeverity = 0,
            weatherBufferMinutes = 0, recommendedWakeAt = Instant.ofEpochMilli(regular.scheduledWakeAt - 1_800_000).toString(),
            routeProvider = null, routeProviderReportTime = null, weatherProvider = null,
            weatherProviderReportTime = null, weatherWindowStart = null, weatherWindowEnd = null,
            fallbackReason = FallbackReason.NONE, insufficientAdvance = false,
            generatedAt = now.toString(), expiresAt = Instant.ofEpochMilli(regular.scheduledWakeAt).toString(),
            evaluationOutcome = EvaluationOutcome.SUCCESS, dayRevision = state.committedRevision)
        coordinator.applyEvaluation(decision)
        val advance = occurrences.getByPlanId(plan.id).single { it.kind == OccurrenceKind.ADVANCE }
        assertEquals(state.committedRevision, advance.dayRevision,
            "advance day revision must match the decision instead of defaulting to zero")
    }

    @Test fun capabilityRejectionsAllCommitWithoutAnActiveCandidate() = runBlocking {
        val plan = seed()
        regular(plan)
        for (reason in listOf(RegistrationFailure.NOTIFICATIONS_DISABLED,
            RegistrationFailure.EXACT_ALARM_PERMISSION, RegistrationFailure.PAST_TRIGGER)) {
            gateway.result = AlarmRegistrationResult.Rejected(reason)
            val result = change(plan) as DayOverrideSaveResult.Success
            assertEquals(DayRegistrationState.NOT_ARMED, result.registration)
            assertEquals("07:30", result.stored?.wakeLocalTime)
            assertTrue(days.pendingCandidates().isEmpty())
            assertTrue(snapshots.commitCredentials().isEmpty())
            assertFalse(occurrences.getByPlanId(plan.id).any { it.state == OccurrenceState.REGISTERING })
        }
    }

    @Test fun aFailedTransactionRestoresTheOriginalDpSnapshot() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val original = snapshots.getByOccurrenceId(old.occurrenceId)
        db.useWriterConnection { it.executeSQL("CREATE TRIGGER fail_day_commit BEFORE INSERT ON workday_overrides " +
            "BEGIN SELECT RAISE(ABORT, 'injected commit failure'); END") }
        val result = change(plan)
        assertEquals(DayOverrideFailureCode.STORAGE_FAILED, (result as DayOverrideSaveResult.Failure).code)
        assertEquals(original, snapshots.getByOccurrenceId(old.occurrenceId))
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(old.occurrenceId)?.state)
        assertEquals(0L, days.committedRevision(plan.id, target))
        assertTrue(snapshots.commitCredentials().isEmpty())
        assertTrue(days.pendingCandidates().isEmpty())
        assertEquals(listOf(old.occurrenceId), snapshots.observeAll().first().map { it.occurrenceId })
    }

    @Test fun postCommitCleanupFailureIsRetriedDuringRecovery() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        gateway.cancelFailure = IllegalStateException("injected cleanup failure")
        val result = change(plan) as DayOverrideSaveResult.Success
        assertEquals(1L, result.dayRevision)
        assertEquals(1, snapshots.commitCredentials().size)
        val tombstone = requireNotNull(snapshots.getByOccurrenceId(old.occurrenceId))
        assertEquals(AlarmReceiver.AlarmHandling.IGNORED, AlarmReceiver.triggerHandling(tombstone, tombstone.triggerAtMillis))
        gateway.cancelFailure = null
        coordinator.recover()
        assertTrue(snapshots.commitCredentials().isEmpty())
        assertNull(snapshots.getByOccurrenceId(old.occurrenceId))
        assertEquals(1L, days.committedRevision(plan.id, target))
        assertEquals(1, snapshots.observeAll().first().count { it.occurrenceState == AlarmReceiver.STATE_SCHEDULED })
    }

    @Test fun cancellingAfterCommitFinishesCleanupAndPreservesTheCommit() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.beforeCancel = {
            if (it == old.occurrenceId) { committed.complete(Unit); release.await() }
        }
        val save = launch { change(plan) }
        withTimeout(10_000) { committed.await() }
        assertEquals(1L, days.committedRevision(plan.id, target))
        save.cancel()
        release.complete(Unit)
        withTimeout(10_000) { save.join() }
        assertTrue(save.isCancelled)
        assertEquals("07:30", days.getState(plan.id, target).override?.wakeLocalTime)
        assertTrue(snapshots.commitCredentials().isEmpty())
        assertNull(snapshots.getByOccurrenceId(old.occurrenceId))
    }

    @Test fun recoveryCompletesTheRevisionOfARetainedSameDayRegular() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val allocation = days.allocateCandidate("retained-day", plan.id, target,
            SingleDayOverride(plan.id, target, preparationMinutes = 45), 0, null, emptyList(), now.toEpochMilli()) as DayOverrideAllocation.Allocated
        snapshots.publishCommitCredential(DayCommitCredential(allocation.changeId, plan.id, target,
            allocation.candidateRevision, revisedOccurrenceIds = listOf(old.occurrenceId), armedState = AlarmArmedState.SCHEDULED))
        coordinator.recover()
        assertEquals(allocation.candidateRevision, occurrences.getById(old.occurrenceId)?.dayRevision)
        assertEquals(allocation.candidateRevision, snapshots.getByOccurrenceId(old.occurrenceId)?.dayRevision)
        assertEquals(45, days.getState(plan.id, target).override?.preparationMinutes)
        assertTrue(snapshots.commitCredentials().isEmpty())
    }

    @Test fun restoringAHolidayArmsTheOncePlanAgain() = runBlocking {
        val plan = seed()
        regular(plan)
        change(plan, wake = null, status = DayStatus.HOLIDAY)
        assertEquals(AlarmArmedState.COMPLETED, plans.getById(plan.id)?.armedState)
        val result = coordinator.clearDayOverride(plan.id, target, days.committedRevision(plan.id, target)) as DayOverrideSaveResult.Success
        assertEquals(DayRegistrationState.SCHEDULED, result.registration)
        assertEquals(AlarmArmedState.SCHEDULED, plans.getById(plan.id)?.armedState)
        assertEquals(1, occurrences.getByPlanId(plan.id).count { it.state == OccurrenceState.SCHEDULED })
    }

    @Test fun retryingAnOlderCleanupCannotRewindTheRetainedRevision() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val advance = old.copy(occurrenceId = "advance", kind = OccurrenceKind.ADVANCE,
            scheduledWakeAt = old.scheduledWakeAt - 1_800_000)
        occurrences.save(advance)
        snapshots.save(requireNotNull(snapshots.getByOccurrenceId(old.occurrenceId)).copy(
            occurrenceId = advance.occurrenceId, occurrenceKind = OccurrenceKind.ADVANCE.name,
            triggerAtMillis = advance.scheduledWakeAt))
        gateway.cancelFailure = IllegalStateException("first cleanup interrupted")
        val first = change(plan, wake = null) as DayOverrideSaveResult.Success
        assertEquals(1L, first.dayRevision)
        assertEquals(DayRegistrationState.UNCHANGED, first.registration)
        assertEquals(1, snapshots.commitCredentials().size)
        gateway.cancelFailure = null
        assertEquals(2L, (change(plan, wake = null) as DayOverrideSaveResult.Success).dayRevision)
        coordinator.recover()
        assertEquals(2L, occurrences.getById(old.occurrenceId)?.dayRevision)
        assertEquals(2L, snapshots.getByOccurrenceId(old.occurrenceId)?.dayRevision)
        assertTrue(snapshots.commitCredentials().isEmpty())
    }

    @Test fun aFailedRecoveryTransactionRestoresAndRegistersTheOriginalInstance() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val original = requireNotNull(snapshots.getByOccurrenceId(old.occurrenceId))
        val candidate = old.copy(occurrenceId = "candidate", state = OccurrenceState.REGISTERING,
            scheduledWakeAt = old.scheduledWakeAt + 3_600_000, dayRevision = 1)
        occurrences.save(candidate)
        val allocation = days.allocateCandidate("recover-failure", plan.id, target,
            SingleDayOverride(plan.id, target, wakeLocalTime = "07:30"), 0, candidate.occurrenceId,
            listOf(old.occurrenceId), now.toEpochMilli()) as DayOverrideAllocation.Allocated
        snapshots.publishCandidate(original.copy(occurrenceId = candidate.occurrenceId,
            triggerAtMillis = candidate.scheduledWakeAt, dayRevision = allocation.candidateRevision),
            DayCommitCredential(allocation.changeId, plan.id, target, allocation.candidateRevision,
                occurrenceId = candidate.occurrenceId, cancelledOccurrenceIds = listOf(old.occurrenceId),
                armedState = AlarmArmedState.SCHEDULED))
        db.useWriterConnection { it.executeSQL("CREATE TRIGGER fail_recovery BEFORE INSERT ON workday_overrides " +
            "BEGIN SELECT RAISE(ABORT, 'injected recovery transaction failure'); END") }
        coordinator.recover()
        assertEquals(0L, days.committedRevision(plan.id, target))
        assertEquals(original, snapshots.getByOccurrenceId(old.occurrenceId))
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(old.occurrenceId)?.state)
        assertEquals(OccurrenceState.FAILED, occurrences.getById(candidate.occurrenceId)?.state)
        assertTrue(old.occurrenceId in gateway.restored)
        assertTrue(days.pendingCandidates().isEmpty())
        assertTrue(snapshots.commitCredentials().isEmpty())
    }

    @Test fun removingTheOnlyWorkdayCancelsTheSnoozeDescendants() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        occurrences.save(old.copy(state = OccurrenceState.SNOOZED))
        val original = requireNotNull(snapshots.getByOccurrenceId(old.occurrenceId))
        snapshots.save(original.copy(occurrenceState = AlarmReceiver.STATE_SNOOZED))
        val child = old.copy(occurrenceId = "snooze-child", kind = OccurrenceKind.SNOOZE,
            parentOccurrenceId = old.occurrenceId, scheduledWakeAt = old.scheduledWakeAt + 300_000)
        occurrences.save(child)
        snapshots.save(original.copy(occurrenceId = child.occurrenceId,
            occurrenceKind = OccurrenceKind.SNOOZE.name, parentOccurrenceId = old.occurrenceId,
            triggerAtMillis = child.scheduledWakeAt))
        change(plan, wake = null, status = DayStatus.HOLIDAY)
        assertEquals(OccurrenceState.CANCELLED, occurrences.getById(old.occurrenceId)?.state)
        assertEquals(OccurrenceState.CANCELLED, occurrences.getById(child.occurrenceId)?.state)
        assertTrue(snapshots.observeAll().first().isEmpty())
    }

    @Test fun cancellingDuringPublicationCompletesTheCommitBeforeReleasingTheDpLock() = runBlocking {
        val plan = seed()
        val old = regular(plan)
        val published = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : NextAlarmSnapshotStore(ApplicationProvider.getApplicationContext<Context>()) {
            override suspend fun publishCandidate(snapshot: NextAlarmSnapshot, credential: DayCommitCredential) {
                super.publishCandidate(snapshot, credential)
                published.complete(Unit)
                release.await()
            }
        }
        val saver = coordinatorFor(store)
        val save = launch {
            saver.setDayOverride(DayOverrideChange(plan.id, target, 0,
                SingleDayOverride(plan.id, target, wakeLocalTime = "07:30")))
        }
        withTimeout(10_000) { published.await() }
        assertEquals(0L, days.committedRevision(plan.id, target))
        assertEquals("CANCELLED", snapshots.getByOccurrenceId(old.occurrenceId)?.occurrenceState)
        val receiver = async {
            AlarmReceiver.withDirectBootLock {
                days.committedRevision(plan.id, target) to snapshots.observeAll().first()
                    .filter { it.occurrenceState == AlarmReceiver.STATE_SCHEDULED }
            }
        }
        yield()
        assertFalse(receiver.isCompleted)
        save.cancel()
        release.complete(Unit)
        val (revision, eligible) = withTimeout(10_000) { receiver.await() }
        withTimeout(10_000) { save.join() }
        assertTrue(save.isCancelled)
        assertEquals(1L, revision)
        assertEquals(1, eligible.size)
        assertEquals(1L, eligible.single().dayRevision)
        assertNull(snapshots.getByOccurrenceId(old.occurrenceId))
        assertTrue(snapshots.commitCredentials().isEmpty())
    }

    private class Gateway : AlarmSchedulingGateway {
        var result: AlarmRegistrationResult = AlarmRegistrationResult.Registered
        var cancelFailure: Exception? = null
        var beforeCancel: (suspend (String) -> Unit)? = null
        val restored = mutableListOf<String>()
        override fun canScheduleExactAlarms() = true
        override suspend fun schedule(snapshot: NextAlarmSnapshot) = result
        override suspend fun restore(snapshot: NextAlarmSnapshot, nowMillis: Long): AlarmRegistrationResult {
            restored.add(snapshot.occurrenceId)
            return result
        }
        override suspend fun cancelOccurrence(occurrenceId: String) {
            beforeCancel?.invoke(occurrenceId)
            cancelFailure?.let { throw it }
        }
    }
}
