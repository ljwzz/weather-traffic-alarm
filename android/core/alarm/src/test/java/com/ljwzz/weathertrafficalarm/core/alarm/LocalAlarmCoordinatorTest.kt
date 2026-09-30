package com.ljwzz.weathertrafficalarm.core.alarm

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import com.ljwzz.weathertrafficalarm.core.data.repository.DayOverrideAllocation
import com.ljwzz.weathertrafficalarm.core.model.DayCommitCredential
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideFailureCode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmSchedulingGateway
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.RegistrationFailure
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.CommuteOverrideMutation
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverride
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideChange
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideSaveResult
import com.ljwzz.weathertrafficalarm.core.model.DayRegistrationState
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAlarmCoordinatorTest {

    private val FIXED_NOW = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
    private val TOMORROW = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var commuteOverrides: PlanCommuteOverrideRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var decisions: DecisionRepository
    private lateinit var events: AlarmEventRepository
    private lateinit var snapshots: NextAlarmSnapshotStore
    private lateinit var gateway: FakeGateway
    private lateinit var dailyInputs: DailyEvaluationInputResolver
    private lateinit var coordinator: LocalAlarmCoordinator

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao(), db.planCommuteWriteDao())
        commuteOverrides = PlanCommuteOverrideRepository(db.planCommuteOverrideDao())
        occurrences = OccurrenceRepository(db.alarmOccurrenceDao())
        decisions = DecisionRepository(db.alarmDecisionDao())
        events = AlarmEventRepository(db.alarmEventDao())
        snapshots = NextAlarmSnapshotStore(context)
        snapshots.clear()
        gateway = FakeGateway()
        val settingsStore = LocalSettingsStore(context)
        val overrideRepository = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val calendarRepository = WorkdayCalendarRepository(context)
        dailyInputs = DailyEvaluationInputResolver(
            plans = plans,
            settings = settingsStore,
            commutes = EffectiveCommuteResolver(commuteOverrides),
            overrides = overrideRepository,
            calendar = calendarRepository,
        )
        coordinator = LocalAlarmCoordinator(
            context = context,
            planRepository = plans,
            occurrenceRepository = occurrences,
            decisionRepository = decisions,
            eventRepository = events,
            overrideRepository = overrideRepository,
            calendarRepository = calendarRepository,
            scheduler = gateway,
            snapshotStore = snapshots,
            dailyInputs = dailyInputs,
        )
    }

    @After
    fun tearDown() = runBlocking {
        snapshots.clear()
        db.close()
    }

    @Test
    fun `editing an armed plan publishes scheduled snapshot and cancels every old active occurrence`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val oldRegular = occurrence(existing, "old-regular", OccurrenceState.SCHEDULED)
        val oldSnooze = occurrence(existing, "old-snooze", OccurrenceState.SCHEDULED, OccurrenceKind.SNOOZE)
        val oldFiring = occurrence(existing, "old-firing", OccurrenceState.FIRING)
        listOf(oldRegular, oldSnooze, oldFiring).forEach { occurrences.save(it) }

        val saved = coordinator.save(existing.copy(name = "Edited"))

        assertEquals(AlarmArmedState.SCHEDULED, saved.armedState)
        assertEquals(2L, saved.revision)
        assertContains(gateway.cancelled, "old-regular")
        assertContains(gateway.cancelled, "old-snooze")
        assertContains(gateway.cancelled, "old-firing")
        val newOccurrence = occurrences.getByPlanId(existing.id).single { it.planRevision == 2L }
        assertEquals(OccurrenceState.SCHEDULED, newOccurrence.state)
        assertEquals(AlarmReceiver.STATE_SCHEDULED, snapshots.getByOccurrenceId(newOccurrence.occurrenceId)!!.occurrenceState)
    }

    @Test
    fun `failed edit keeps prior scheduled plan and pending instance`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val active = occurrence(existing, "existing", OccurrenceState.SCHEDULED)
        occurrences.save(active)
        gateway.result = AlarmRegistrationResult.Rejected(RegistrationFailure.PLATFORM_REJECTED, "platform failure")

        val returned = coordinator.save(existing.copy(name = "Unsaved edit"))

        val stored = plans.getById(existing.id)!!
        assertEquals(existing.revision, stored.revision)
        assertEquals(AlarmArmedState.SCHEDULED, stored.armedState)
        assertEquals("platform failure", stored.scheduleError)
        assertEquals(existing.name, returned.name)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(active.occurrenceId)!!.state)
        assertFalse(gateway.cancelled.contains(active.occurrenceId))
    }

    @Test
    fun `failed armed edit keeps prior commute override`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val active = occurrence(existing, "existing", OccurrenceState.SCHEDULED)
        occurrences.save(active)
        val previousOverride = commuteOverride(existing.id, "Old home", "Old office")
        commuteOverrides.save(previousOverride)
        gateway.result = AlarmRegistrationResult.Rejected(RegistrationFailure.PLATFORM_REJECTED, "platform failure")

        coordinator.save(
            existing.copy(name = "Unsaved edit"),
            CommuteOverrideMutation.Replace(commuteOverride(existing.id, "New home", "New office")),
        )

        assertEquals(existing.name, plans.getById(existing.id)!!.name)
        assertEquals(previousOverride, commuteOverrides.getByPlanId(existing.id))
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(active.occurrenceId)!!.state)
    }

    @Test
    fun `new plan persists its replacement override under the plan id`() = runBlocking {
        val proposed = plan(id = "stable-draft-id")
        val override = commuteOverride(proposed.id, "Home", "Office")

        val saved = coordinator.save(proposed, CommuteOverrideMutation.Replace(override))

        assertEquals(proposed.id, saved.id)
        assertEquals(override, commuteOverrides.getByPlanId(proposed.id))
    }

    @Test
    fun `armed edit without a commute mutation retains its existing override`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        occurrences.save(occurrence(existing, "existing", OccurrenceState.SCHEDULED))
        val existingOverride = commuteOverride(existing.id, "Home", "Office")
        commuteOverrides.save(existingOverride)

        coordinator.save(existing.copy(name = "Other alarm fields"))

        assertEquals(existingOverride, commuteOverrides.getByPlanId(existing.id))
    }

    @Test
    fun `armed edit resets its override with the plan commit`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        occurrences.save(occurrence(existing, "existing", OccurrenceState.SCHEDULED))
        commuteOverrides.save(commuteOverride(existing.id, "Home", "Office"))

        coordinator.save(existing.copy(name = "Global commute"), CommuteOverrideMutation.Reset)

        assertEquals("Global commute", plans.getById(existing.id)!!.name)
        assertEquals(null, commuteOverrides.getByPlanId(existing.id))
    }

    @Test
    fun `saving disabled plan cancels pending snooze and firing instances`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val active = listOf(
            occurrence(existing, "regular", OccurrenceState.SCHEDULED),
            occurrence(existing, "snooze", OccurrenceState.SNOOZED, OccurrenceKind.SNOOZE),
            occurrence(existing, "firing", OccurrenceState.FIRING),
        )
        active.forEach { occurrences.save(it) }

        coordinator.save(existing.copy(enabled = false))

        active.forEach {
            assertEquals(OccurrenceState.CANCELLED, occurrences.getById(it.occurrenceId)!!.state)
            assertContains(gateway.cancelled, it.occurrenceId)
        }
    }

    @Test
    fun `miss handling is idempotent and reschedules recurring plan after now`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val late = occurrence(existing, "late", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() - 60_000L)
        occurrences.save(late)

        assertTrue(coordinator.handleMissed(late.occurrenceId))
        assertTrue(coordinator.handleMissed(late.occurrenceId))

        assertEquals(OccurrenceState.MISSED, occurrences.getById(late.occurrenceId)!!.state)
        assertEquals(1, events.observeAll().first().count { it.type == AlarmEventType.MISSED })
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED })
    }

    @Test
    fun `evaluation arms independent advance and keeps regular baseline`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 7_200_000L)
        occurrences.save(regular)

        val result = coordinator.applyEvaluation(decision(existing, regular, "decision-1", regular.scheduledWakeAt - 600_000L))

        assertEquals("APPLIED", result.outcome)
        assertEquals(regular.scheduledWakeAt - 600_000L, result.actualWakeAt)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        val advance = occurrences.getByPlanId(existing.id).single { it.kind == OccurrenceKind.ADVANCE }
        assertEquals("decision-1", advance.decisionId)
        assertEquals(regular.scheduledWakeAt - 600_000L, advance.scheduledWakeAt)
        assertEquals("decision-1", snapshots.getByOccurrenceId(advance.occurrenceId)?.decisionId)
        assertEquals(regular.scheduledWakeAt, snapshots.getByOccurrenceId(advance.occurrenceId)?.defaultWakeAtMillis)
        assertEquals("APPLIED", decisions.getById("decision-1")?.applicationOutcome)
        assertEquals(Instant.ofEpochMilli(regular.scheduledWakeAt).toString(), decisions.getById("decision-1")?.defaultWakeAt)
        assertEquals(Instant.ofEpochMilli(advance.scheduledWakeAt).toString(), decisions.getById("decision-1")?.actualWakeAt)
    }

    @Test
    fun `evaluation rejects an expired result without changing scheduled instances`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 3_600_000L)
        occurrences.save(regular)

        val result = coordinator.applyEvaluation(
            decision(existing, regular, "expired", regular.scheduledWakeAt - 600_000L).copy(
                expiresAt = (System.currentTimeMillis() - 1).toString(),
            ),
        )

        assertEquals("STALE", result.outcome)
        assertEquals(null, result.actualWakeAt)
        assertEquals(listOf(regular.occurrenceId), occurrences.getByPlanId(existing.id).map { it.occurrenceId })
        assertEquals(EvaluationOutcome.STALE, decisions.getById("expired")?.evaluationOutcome)
    }

    @Test
    fun `later evaluation retains earlier advance and earlier result replaces it`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 3_600_000L)
        occurrences.save(regular)
        val firstAt = regular.scheduledWakeAt - 1_200_000L
        coordinator.applyEvaluation(decision(existing, regular, "first", firstAt))

        val retained = coordinator.applyEvaluation(decision(existing, regular, "later", regular.scheduledWakeAt - 600_000L))
        assertEquals("UNCHANGED", retained.outcome)
        assertEquals(firstAt, retained.actualWakeAt)
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.ADVANCE && it.state == OccurrenceState.SCHEDULED })

        val earlierAt = regular.scheduledWakeAt - 1_800_000L
        val replaced = coordinator.applyEvaluation(decision(existing, regular, "earlier", earlierAt))
        assertEquals("APPLIED", replaced.outcome)
        assertEquals(OccurrenceState.CANCELLED, occurrences.getByPlanId(existing.id).single { it.decisionId == "first" }.state)
        val advance = occurrences.getByPlanId(existing.id).single { it.decisionId == "earlier" }
        assertEquals(OccurrenceState.SCHEDULED, advance.state)
        assertEquals(earlierAt, advance.scheduledWakeAt)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
    }

    @Test
    fun `failed earlier registration keeps existing advance armed`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 3_600_000L)
        occurrences.save(regular)
        val firstAt = regular.scheduledWakeAt - 600_000L
        coordinator.applyEvaluation(decision(existing, regular, "first", firstAt))
        gateway.result = AlarmRegistrationResult.Rejected(RegistrationFailure.PLATFORM_REJECTED)

        val result = coordinator.applyEvaluation(decision(existing, regular, "earlier", firstAt - 600_000L))

        assertEquals("FAILED", result.outcome)
        assertEquals(EvaluationOutcome.SUCCESS, decisions.getById("earlier")?.evaluationOutcome)
        assertEquals("FAILED", decisions.getById("earlier")?.applicationOutcome)
        val original = occurrences.getByPlanId(existing.id).single { it.decisionId == "first" }
        assertEquals(OccurrenceState.SCHEDULED, original.state)
        assertFalse(gateway.cancelled.contains(original.occurrenceId))
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.ADVANCE && it.state == OccurrenceState.SCHEDULED })
    }

    @Test
    fun `same decision can replace its pending advance when reevaluated earlier`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 3_600_000L)
        occurrences.save(regular)
        coordinator.applyEvaluation(decision(existing, regular, "same", regular.scheduledWakeAt - 600_000L))

        val result = coordinator.applyEvaluation(decision(existing, regular, "same", regular.scheduledWakeAt - 1_200_000L))

        assertEquals("APPLIED", result.outcome)
        val advances = occurrences.getByPlanId(existing.id).filter { it.decisionId == "same" }
        assertEquals(OccurrenceState.CANCELLED, advances.single { it.scheduledWakeAt == regular.scheduledWakeAt - 600_000L }.state)
        assertEquals(OccurrenceState.SCHEDULED, advances.single { it.scheduledWakeAt == regular.scheduledWakeAt - 1_200_000L }.state)
    }

    @Test
    fun `excessive advance is rejected without mutating the regular alarm`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 7_200_000L)
        occurrences.save(regular)

        val result = coordinator.applyEvaluation(
            decision(existing, regular, "too-early", regular.scheduledWakeAt - (existing.maxAdvanceMinutes + 1) * 60_000L),
        )

        assertEquals("FAILED", result.outcome)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(existing.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    @Test
    fun `non-advance result cancels only pending advance and leaves regular armed`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 3_600_000L)
        occurrences.save(regular)
        coordinator.applyEvaluation(decision(existing, regular, "first", regular.scheduledWakeAt - 600_000L))

        val result = coordinator.applyEvaluation(decision(existing, regular, "baseline", regular.scheduledWakeAt))

        assertEquals("CANCELLED", result.outcome)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertEquals(OccurrenceState.CANCELLED, occurrences.getByPlanId(existing.id).single { it.kind == OccurrenceKind.ADVANCE }.state)
    }

    @Test
    fun `advance snooze chain prevents a second advance and once plan remains enabled`() = runBlocking {
        val targetDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)
        val existing = plan(schedule = AlarmSchedule.Once(targetDate.toString())).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED, wakeAt = System.currentTimeMillis() + 3_600_000L)
        occurrences.save(regular)
        coordinator.applyEvaluation(decision(existing, regular, "first", regular.scheduledWakeAt - 600_000L))
        val advance = occurrences.getByPlanId(existing.id).single { it.kind == OccurrenceKind.ADVANCE }
        occurrences.updateState(advance.occurrenceId, OccurrenceState.FIRING.name, System.currentTimeMillis())
        snapshots.save(snapshot(existing, advance.occurrenceId, advance.scheduledWakeAt, AlarmReceiver.STATE_FIRING, OccurrenceKind.ADVANCE))

        assertTrue(coordinator.snooze(advance.occurrenceId))
        val result = coordinator.applyEvaluation(decision(existing, regular, "second", regular.scheduledWakeAt - 1_200_000L))

        assertEquals("UNCHANGED", result.outcome)
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.ADVANCE })
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(plans.getById(existing.id)!!.enabled)
    }

    @Test
    fun `day override changes cancel advance and snooze descendants`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED)
        val advance = occurrence(existing, "advance", OccurrenceState.SCHEDULED, OccurrenceKind.ADVANCE)
        val snooze = occurrence(existing, "snooze", OccurrenceState.SCHEDULED, OccurrenceKind.SNOOZE)
            .copy(parentOccurrenceId = advance.occurrenceId)
        listOf(regular, advance, snooze).forEach { occurrences.save(it) }

        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        coordinator.setDayOverride(
            dayChange(existing.id, regular.targetDate, overrides, SingleDayOverride(existing.id, regular.targetDate, DayStatus.HOLIDAY)),
        )

        assertEquals(OccurrenceState.CANCELLED, occurrences.getById(advance.occurrenceId)?.state)
        assertEquals(OccurrenceState.CANCELLED, occurrences.getById(snooze.occurrenceId)?.state)
        assertContains(gateway.cancelled, advance.occurrenceId)
        assertContains(gateway.cancelled, snooze.occurrenceId)

        val replacement = occurrence(existing, "replacement", OccurrenceState.SCHEDULED, OccurrenceKind.ADVANCE)
        occurrences.save(replacement)
        coordinator.clearDayOverride(existing.id, regular.targetDate, overrides.committedRevision(existing.id, regular.targetDate))
        assertEquals(OccurrenceState.CANCELLED, occurrences.getById(replacement.occurrenceId)?.state)
    }

    @Test
    fun `saving a day override bumps only that plan and date revision`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val target = LocalDate.now(ZoneId.of(existing.zoneId)).plusDays(1)
        val other = target.plusDays(1)
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())

        val first = coordinator.setDayOverride(
            dayChange(existing.id, target.toString(), overrides, SingleDayOverride(existing.id, target.toString(), DayStatus.WORKDAY, arrivalLocalTime = "08:45")),
        )
        val second = coordinator.setDayOverride(
            DayOverrideChange(
                planId = existing.id,
                date = target.toString(),
                expectedDayRevision = (first as DayOverrideSaveResult.Success).dayRevision,
                replacement = SingleDayOverride(
                    existing.id,
                    target.toString(),
                    DayStatus.WORKDAY,
                    arrivalLocalTime = "08:45",
                    preparationMinutes = 15,
                ),
            ),
        )
        coordinator.setDayOverride(
            DayOverrideChange(
                planId = existing.id,
                date = other.toString(),
                expectedDayRevision = overrides.committedRevision(existing.id, other.toString()),
                replacement = SingleDayOverride(existing.id, other.toString(), DayStatus.HOLIDAY),
            ),
        )

        assertEquals(1L, (first as DayOverrideSaveResult.Success).dayRevision)
        assertEquals(2L, (second as DayOverrideSaveResult.Success).dayRevision)
        assertEquals("08:45", overrides.getForPlanDate(existing.id, target.toString())?.arrivalLocalTime)
        assertEquals(15, overrides.getForPlanDate(existing.id, target.toString())?.preparationMinutes)
        assertEquals(1L, overrides.getForPlanDate(existing.id, other.toString())?.dayRevision)
        // The neighbouring date and the plan revision are untouched.
        assertEquals(DayStatus.HOLIDAY, overrides.getForPlanDate(existing.id, other.toString())?.status)
        assertEquals(1L, plans.getById(existing.id)!!.revision)
    }

    @Test
    fun `repeated save and undo stay idempotent for the target date`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val target = LocalDate.now(ZoneId.of(existing.zoneId)).plusDays(1).toString()
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())

        val saved = coordinator.setDayOverride(
            dayChange(existing.id, target, overrides, SingleDayOverride(existing.id, target, DayStatus.WORKDAY)),
        ) as DayOverrideSaveResult.Success
        val removed = coordinator.clearDayOverride(existing.id, target, saved.dayRevision) as DayOverrideSaveResult.Success

        assertEquals(2L, removed.dayRevision)
        assertNull(overrides.getForPlanDate(existing.id, target))
        // A second undo of an already inheriting date succeeds without consuming a revision.
        val repeated = coordinator.clearDayOverride(existing.id, target, removed.dayRevision) as DayOverrideSaveResult.Success
        assertEquals(2L, repeated.dayRevision)
        assertNull(overrides.getForPlanDate(existing.id, target))
    }

    @Test
    fun `a disabled plan keeps its day override without arming an instance`() = runBlocking {
        val existing = plan().copy(revision = 1, enabled = false, armedState = AlarmArmedState.DISABLED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val target = LocalDate.now(ZoneId.of(existing.zoneId)).plusDays(1).toString()
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())

        val change = dayChange(existing.id, target, overrides, SingleDayOverride(existing.id, target, DayStatus.WORKDAY, wakeLocalTime = "07:00"))
        val result = coordinator.setDayOverride(change) as DayOverrideSaveResult.Success

        assertEquals("07:00", overrides.getForPlanDate(existing.id, target)?.wakeLocalTime)
        assertEquals(DayRegistrationState.NOT_ARMED, result.registration)
        assertTrue(occurrences.getByPlanId(existing.id).isEmpty())
    }

    @Test
    fun `dismiss restores a terminal direct boot receipt after cancellation`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val firing = occurrence(existing, "firing", OccurrenceState.FIRING)
        occurrences.save(firing)
        snapshots.save(
            snapshot(existing, firing.occurrenceId, firing.scheduledWakeAt, AlarmReceiver.STATE_FIRING)
                .copy(actionRevision = 2, actionError = "old error"),
        )
        gateway.onCancel = { snapshots.removeOccurrence(it) }

        assertTrue(coordinator.dismiss(firing.occurrenceId))

        val receipt = requireNotNull(snapshots.getByOccurrenceId(firing.occurrenceId))
        assertEquals(AlarmReceiver.STATE_DISMISSED, receipt.occurrenceState)
        assertEquals(3L, receipt.actionRevision)
        assertEquals(null, receipt.actionError)
        assertEquals(OccurrenceState.DISMISSED, occurrences.getById(firing.occurrenceId)?.state)
    }

    @Test
    fun `failed snooze keeps the parent firing and records a retry receipt`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val firing = occurrence(existing, "firing", OccurrenceState.FIRING)
        occurrences.save(firing)
        snapshots.save(
            snapshot(existing, firing.occurrenceId, firing.scheduledWakeAt, AlarmReceiver.STATE_FIRING)
                .copy(actionRevision = 4),
        )
        gateway.result = AlarmRegistrationResult.Rejected(RegistrationFailure.PLATFORM_REJECTED)

        assertFalse(coordinator.snooze(firing.occurrenceId))

        val parent = requireNotNull(snapshots.getByOccurrenceId(firing.occurrenceId))
        assertEquals(AlarmReceiver.STATE_FIRING, parent.occurrenceState)
        assertEquals(5L, parent.actionRevision)
        assertEquals("贪睡未能注册，请重试或停止闹钟", parent.actionError)
        assertEquals(OccurrenceState.FIRING, occurrences.getById(firing.occurrenceId)?.state)
        assertTrue(snapshots.observeAll().first().none { it.parentOccurrenceId == firing.occurrenceId })
        assertEquals(
            1,
            occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.SNOOZE && it.state == OccurrenceState.FAILED },
        )

        gateway.result = AlarmRegistrationResult.Registered
        assertTrue(coordinator.snooze(firing.occurrenceId))
        val retriedParent = requireNotNull(snapshots.getByOccurrenceId(firing.occurrenceId))
        assertEquals(AlarmReceiver.STATE_SNOOZED, retriedParent.occurrenceState)
        assertEquals(6L, retriedParent.actionRevision)
        assertEquals(null, retriedParent.actionError)
        val registeredChildren = snapshots.observeAll().first().filter {
            it.parentOccurrenceId == firing.occurrenceId
        }
        assertEquals(1, registeredChildren.size)
        assertEquals(AlarmReceiver.STATE_SCHEDULED, registeredChildren.single().occurrenceState)
        assertEquals(null, registeredChildren.single().actionError)
    }

    @Test
    fun `concurrent snoozes create one child and one successful parent receipt`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val firing = occurrence(existing, "firing", OccurrenceState.FIRING)
        occurrences.save(firing)
        snapshots.save(snapshot(existing, firing.occurrenceId, firing.scheduledWakeAt, AlarmReceiver.STATE_FIRING))
        gateway.scheduleDelayMillis = 25L

        coroutineScope {
            val results = awaitAll(
                async { coordinator.snooze(firing.occurrenceId) },
                async { coordinator.snooze(firing.occurrenceId) },
            )
            assertEquals(1, results.count { it })
        }

        val parent = requireNotNull(snapshots.getByOccurrenceId(firing.occurrenceId))
        assertEquals(AlarmReceiver.STATE_SNOOZED, parent.occurrenceState)
        assertEquals(1L, parent.actionRevision)
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.SNOOZE && it.state == OccurrenceState.SCHEDULED })
        assertFalse(coordinator.dismiss(firing.occurrenceId))
        assertEquals(OccurrenceState.SNOOZED, occurrences.getById(firing.occurrenceId)?.state)
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.SNOOZE && it.state == OccurrenceState.SCHEDULED })
    }

    @Test
    fun `dismiss wins before snooze and prevents a child occurrence`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val firing = occurrence(existing, "firing", OccurrenceState.FIRING)
        occurrences.save(firing)
        snapshots.save(snapshot(existing, firing.occurrenceId, firing.scheduledWakeAt, AlarmReceiver.STATE_FIRING))

        assertTrue(coordinator.dismiss(firing.occurrenceId))
        assertFalse(coordinator.snooze(firing.occurrenceId))

        assertEquals(OccurrenceState.DISMISSED, occurrences.getById(firing.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(existing.id).none { it.kind == OccurrenceKind.SNOOZE })
    }

    @Test
    fun `past once plan is rejected before any persistent write`() = runBlocking {
        val past = plan(id = "past", schedule = AlarmSchedule.Once(LocalDate.now().minusDays(1).toString()))

        assertFailsWith<IllegalArgumentException> { coordinator.save(past) }
        assertEquals(null, plans.getById(past.id))
    }

    @Test
    fun `recover rejects stale revision snapshot without reviving alarm`() = runBlocking {
        val current = plan().copy(revision = 2, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(current.toEntity())
        val stale = snapshot(current.copy(revision = 1), "stale", System.currentTimeMillis() + 60_000L)
        snapshots.save(stale)

        coordinator.recover()

        assertContains(gateway.cancelled, "stale")
        assertEquals(null, occurrences.getById("stale"))
    }

    @Test
    fun `recover rebuilds missing device protected snapshot for scheduled room occurrence`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val scheduled = occurrence(existing, "room-only", OccurrenceState.SCHEDULED)
        occurrences.save(scheduled)

        coordinator.recover()

        assertContains(gateway.restored, scheduled.occurrenceId)
        assertEquals(
            AlarmReceiver.STATE_SCHEDULED,
            snapshots.getByOccurrenceId(scheduled.occurrenceId)!!.occurrenceState,
        )
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(scheduled.occurrenceId)!!.state)
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.REGULAR })
    }

    @Test
    fun `recover restores an independent advance without replacing regular or its decision link`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED)
        val advance = occurrence(
            existing,
            "advance",
            OccurrenceState.SCHEDULED,
            kind = OccurrenceKind.ADVANCE,
            wakeAt = regular.scheduledWakeAt - 600_000L,
        ).copy(decisionId = "decision-1")
        occurrences.save(regular)
        occurrences.save(advance)
        decisions.save(
            decision(existing, regular, "decision-1", advance.scheduledWakeAt).copy(
                defaultWakeAt = Instant.ofEpochMilli(regular.scheduledWakeAt).toString(),
            ),
        )

        coordinator.recover()

        assertContains(gateway.restored, regular.occurrenceId)
        assertContains(gateway.restored, advance.occurrenceId)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        val restored = requireNotNull(snapshots.getByOccurrenceId(advance.occurrenceId))
        assertEquals(OccurrenceKind.ADVANCE.name, restored.occurrenceKind)
        assertEquals("decision-1", restored.decisionId)
        assertEquals(regular.scheduledWakeAt, restored.defaultWakeAtMillis)
    }

    @Test
    fun `recover preserves advance target date when its trigger crosses midnight`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val zone = ZoneId.of(existing.zoneId)
        val targetDate = LocalDate.now(zone).plusDays(2)
        val triggerAt = targetDate.atStartOfDay(zone).minusMinutes(30).toInstant().toEpochMilli()
        val crossMidnight = NextAlarmSnapshot(
            occurrenceId = "cross-midnight",
            planId = existing.id,
            planRevision = existing.revision,
            triggerAtMillis = triggerAt,
            soundUri = null,
            vibrationEnabled = true,
            snoozeMinutes = existing.snoozeMinutes,
            occurrenceKind = OccurrenceKind.ADVANCE.name,
            decisionId = "decision-1",
            targetDate = targetDate.toString(),
            defaultWakeAtMillis = triggerAt + 60 * 60_000L,
        )
        snapshots.save(crossMidnight)

        coordinator.recover()

        val restored = requireNotNull(occurrences.getById(crossMidnight.occurrenceId))
        assertEquals(targetDate.toString(), restored.targetDate)
        assertEquals(OccurrenceKind.ADVANCE, restored.kind)
        assertEquals(crossMidnight.defaultWakeAtMillis, snapshots.getByOccurrenceId(crossMidnight.occurrenceId)?.defaultWakeAtMillis)
    }

    @Test
    fun `recover arms a missing regular when an advance remains pending`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val advance = occurrence(existing, "advance", OccurrenceState.SCHEDULED, OccurrenceKind.ADVANCE)
        occurrences.save(advance)

        coordinator.recover()

        assertContains(gateway.restored, advance.occurrenceId)
        assertEquals(1, occurrences.getByPlanId(existing.id).count {
            it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
        })
    }

    @Test
    fun `recover retains only the earliest pending advance for one target date`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED)
        val earliest = occurrence(
            existing, "earliest", OccurrenceState.SCHEDULED, OccurrenceKind.ADVANCE,
            wakeAt = regular.scheduledWakeAt - 1_200_000L,
        )
        val later = occurrence(
            existing, "later", OccurrenceState.SCHEDULED, OccurrenceKind.ADVANCE,
            wakeAt = regular.scheduledWakeAt - 600_000L,
        )
        listOf(regular, earliest, later).forEach { occurrences.save(it) }

        coordinator.recover()

        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(earliest.occurrenceId)?.state)
        assertEquals(OccurrenceState.CANCELLED, occurrences.getById(later.occurrenceId)?.state)
        assertContains(gateway.cancelled, later.occurrenceId)
    }

    @Test
    fun `recover marks room-only occurrence missed after late window then schedules next recurrence`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val expired = occurrence(
            existing,
            "expired-room-only",
            OccurrenceState.SCHEDULED,
            wakeAt = System.currentTimeMillis() - AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS - 1,
        )
        occurrences.save(expired)

        coordinator.recover()

        assertEquals(OccurrenceState.MISSED, occurrences.getById(expired.occurrenceId)!!.state)
        assertEquals(1, events.observeAll().first().count { it.type == AlarmEventType.MISSED })
        assertEquals(1, occurrences.getByPlanId(existing.id).count { it.state == OccurrenceState.SCHEDULED })
    }

    @Test
    fun `recover keeps once plan enabled when only device protected snooze child is pending`() = runBlocking {
        val once = plan(schedule = AlarmSchedule.Once(LocalDate.now().plusDays(1).toString())).copy(
            revision = 1,
            armedState = AlarmArmedState.SCHEDULED,
        )
        db.alarmPlanDao().upsert(once.toEntity())
        val parent = occurrence(once, "parent", OccurrenceState.FIRING)
        val child = occurrence(once, "child", OccurrenceState.SCHEDULED, OccurrenceKind.SNOOZE)
        occurrences.save(parent)
        snapshots.save(snapshot(once, parent.occurrenceId, parent.scheduledWakeAt, AlarmReceiver.STATE_DISMISSED))
        snapshots.save(snapshot(once, child.occurrenceId, child.scheduledWakeAt, AlarmReceiver.STATE_SCHEDULED, OccurrenceKind.SNOOZE, parent.occurrenceId))
        gateway.onCancel = { snapshots.removeOccurrence(it) }

        coordinator.recover()

        assertTrue(plans.getById(once.id)!!.enabled)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(child.occurrenceId)!!.state)
        assertEquals(null, snapshots.getByOccurrenceId(parent.occurrenceId))
    }

    @Test
    fun `concurrent saves serialize system registration`() = runBlocking {
        gateway.scheduleDelayMillis = 25L
        val proposed = plan(id = "concurrent")

        coroutineScope {
            awaitAll(
                async { coordinator.save(proposed) },
                async { coordinator.save(proposed.copy(name = "Second")) },
            )
        }

        assertEquals(1, gateway.maxConcurrentSchedules)
        assertEquals(2L, plans.getById(proposed.id)!!.revision)
    }

    /** Builds a change against the revision that is committed right now (test seeding). */
    private suspend fun dayChange(
        planId: String,
        date: String,
        overrides: WorkdayOverrideRepository,
        replacement: SingleDayOverride?,
    ) = DayOverrideChange(planId, date, overrides.committedRevision(planId, date), replacement)

    // --- N004 fixes: candidate commit protocol, failure compensation and final validation ----

    /** F3: a platform rejection must keep the stored day and the already armed instance. */
    @Test
    fun `rejected day registration keeps the stored values and the previous instance`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, wakeLocalTime = "06:30", preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        val regular = occurrence(
            existing,
            "regular",
            OccurrenceState.SCHEDULED,
            wakeAt = LocalDate.parse(TOMORROW).atTime(6, 30).atZone(ZoneId.of(existing.zoneId)).toInstant().toEpochMilli(),
        )
        occurrences.save(regular)
        gateway.result = AlarmRegistrationResult.Rejected(RegistrationFailure.PLATFORM_REJECTED)

        val result = coordinator.setDayOverride(
            DayOverrideChange(
                planId = existing.id,
                date = TOMORROW,
                expectedDayRevision = stored.committedRevision,
                replacement = SingleDayOverride(existing.id, TOMORROW, wakeLocalTime = "07:30", preparationMinutes = 45),
            ),
        )

        assertEquals(
            DayOverrideFailureCode.REGISTRATION_FAILED,
            (result as? DayOverrideSaveResult.Failure)?.code,
            "a rejected registration must be reported as a failure",
        )
        val after = requireNotNull(overrides.getForPlanDate(existing.id, TOMORROW))
        assertEquals("06:30", after.wakeLocalTime)
        assertEquals(15, after.preparationMinutes)
        assertEquals(stored.committedRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertEquals(
            0,
            occurrences.getByPlanId(existing.id).count { it.state == OccurrenceState.REGISTERING },
            "the unpublished candidate must not stay registerable",
        )
        assertEquals(0, snapshots.observeAll().first().count { it.occurrenceId != regular.occurrenceId })
    }

    /** A registration exception travels the same path as a rejection. */
    @Test
    fun `registration exception keeps the stored values and removes the candidate`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        gateway.failure = IllegalStateException("platform exploded")

        val result = coordinator.setDayOverride(
            DayOverrideChange(
                planId = existing.id,
                date = TOMORROW,
                expectedDayRevision = stored.committedRevision,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 45),
            ),
        )

        assertEquals(DayOverrideFailureCode.REGISTRATION_FAILED, (result as? DayOverrideSaveResult.Failure)?.code)
        assertEquals(15, overrides.getForPlanDate(existing.id, TOMORROW)?.preparationMinutes)
        assertEquals(stored.committedRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertEquals(0, occurrences.getByPlanId(existing.id).count { it.state == OccurrenceState.REGISTERING })
    }

    /** Cancelling the coroutine mid-registration still cleans the candidate. */
    @Test
    fun `cancelling a day save leaves the stored values untouched`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        gateway.scheduleDelayMillis = 30_000L

        val job = launch {
            coordinator.setDayOverride(
                DayOverrideChange(
                    planId = existing.id,
                    date = TOMORROW,
                    expectedDayRevision = stored.committedRevision,
                    replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 45),
                ),
            )
        }
        withTimeout(10_000) {
            while (occurrences.getByPlanId(existing.id).none { it.state == OccurrenceState.REGISTERING }) delay(20)
        }
        job.cancelAndJoin()

        assertEquals(15, overrides.getForPlanDate(existing.id, TOMORROW)?.preparationMinutes)
        assertEquals(stored.committedRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertEquals(0, occurrences.getByPlanId(existing.id).count { it.state == OccurrenceState.REGISTERING })
    }

    /** A failure of the aggregated transaction compensates the already published candidate. */
    @Test
    fun `a failed aggregated commit keeps the stored values and clears the candidate`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        db.useWriterConnection { connection ->
            connection.executeSQL(
                "CREATE TRIGGER fail_day_override_commit BEFORE INSERT ON workday_overrides " +
                    "BEGIN SELECT RAISE(ABORT, 'forced commit failure'); END",
            )
        }

        val result = coordinator.setDayOverride(
            DayOverrideChange(
                planId = existing.id,
                date = TOMORROW,
                expectedDayRevision = stored.committedRevision,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 45),
            ),
        )

        assertEquals(DayOverrideFailureCode.STORAGE_FAILED, (result as? DayOverrideSaveResult.Failure)?.code)
        assertEquals(15, overrides.getForPlanDate(existing.id, TOMORROW)?.preparationMinutes)
        assertEquals(stored.committedRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertTrue(overrides.pendingCandidates().isEmpty())
        assertEquals(0, occurrences.getByPlanId(existing.id).count { it.state == OccurrenceState.REGISTERING })
        assertEquals(0, snapshots.observeAll().first().count { it.occurrenceId.startsWith("candidate") })
    }

    /** An interrupted change without a published credential is discarded, never promoted. */
    @Test
    fun `recovery discards an unpublished candidate and keeps the previous values`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        // Simulate a process kill after allocation but before the device-protected publish.
        val candidate = AlarmOccurrence(
            occurrenceId = "candidate",
            planId = existing.id,
            planRevision = existing.revision,
            targetDate = TOMORROW,
            scheduledWakeAt = System.currentTimeMillis() + 600_000L,
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.REGULAR,
        )
        occurrences.save(candidate)
        snapshots.save(snapshot(existing, candidate.occurrenceId, candidate.scheduledWakeAt, kind = OccurrenceKind.REGULAR))
        val allocation = overrides.allocateCandidate(
            changeId = "interrupted",
            planId = existing.id,
            date = TOMORROW,
            replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 45),
            expectedDayRevision = stored.committedRevision,
            candidateOccurrenceId = candidate.occurrenceId,
            cancelledOccurrenceIds = emptyList(),
            now = FIXED_NOW,
        )
        assertTrue(allocation is DayOverrideAllocation.Allocated)

        coordinator.recover()

        assertEquals(15, overrides.getForPlanDate(existing.id, TOMORROW)?.preparationMinutes)
        assertEquals(stored.committedRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertTrue(overrides.pendingCandidates().isEmpty())
        assertEquals(OccurrenceState.FAILED, occurrences.getById(candidate.occurrenceId)?.state)
    }

    /** A published credential means the same commit is completed, never restarted from scratch. */
    @Test
    fun `recovery completes a published candidate exactly once`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        val candidate = AlarmOccurrence(
            occurrenceId = "candidate-published",
            planId = existing.id,
            planRevision = existing.revision,
            targetDate = TOMORROW,
            scheduledWakeAt = System.currentTimeMillis() + 600_000L,
            state = OccurrenceState.REGISTERING,
            kind = OccurrenceKind.REGULAR,
        )
        occurrences.save(candidate)
        val allocation = overrides.allocateCandidate(
            changeId = "published",
            planId = existing.id,
            date = TOMORROW,
            replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 45),
            expectedDayRevision = stored.committedRevision,
            candidateOccurrenceId = candidate.occurrenceId,
            cancelledOccurrenceIds = emptyList(),
            now = FIXED_NOW,
        ) as DayOverrideAllocation.Allocated
        val candidateSnapshot = snapshot(existing, candidate.occurrenceId, candidate.scheduledWakeAt, kind = OccurrenceKind.REGULAR)
        snapshots.publishCandidate(
            candidateSnapshot.copy(occurrenceState = AlarmReceiver.STATE_SCHEDULED),
            DayCommitCredential(
                changeId = allocation.changeId,
                planId = existing.id,
                date = TOMORROW,
                dayRevision = allocation.candidateRevision,
                occurrenceId = candidate.occurrenceId,
                targetDate = TOMORROW,
                triggerAtMillis = candidate.scheduledWakeAt,
            ),
        )

        coordinator.recover()

        assertEquals(45, overrides.getForPlanDate(existing.id, TOMORROW)?.preparationMinutes)
        assertEquals(allocation.candidateRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertTrue(overrides.pendingCandidates().isEmpty())
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(candidate.occurrenceId)?.state)
    }

    /** A failure while publishing the device-protected snapshot keeps the stored values. */
    @Test
    fun `a failed device protected publish keeps the stored values and clears the candidate`() = runBlocking {
        val existing = plan(schedule = AlarmSchedule.Once(TOMORROW)).copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = TOMORROW,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 15),
                now = FIXED_NOW,
            ),
        )
        val failing = LocalAlarmCoordinator(
            context = context,
            planRepository = plans,
            occurrenceRepository = occurrences,
            decisionRepository = decisions,
            eventRepository = events,
            overrideRepository = overrides,
            calendarRepository = WorkdayCalendarRepository(context),
            scheduler = gateway,
            snapshotStore = object : NextAlarmSnapshotStore(context) {
                override suspend fun publishCandidate(
                    snapshot: NextAlarmSnapshot,
                    credential: DayCommitCredential,
                ): Unit = throw IllegalStateException("device protected storage unavailable")
            },
            dailyInputs = dailyInputs,
        )

        val result = failing.setDayOverride(
            DayOverrideChange(
                planId = existing.id,
                date = TOMORROW,
                expectedDayRevision = stored.committedRevision,
                replacement = SingleDayOverride(existing.id, TOMORROW, preparationMinutes = 45),
            ),
        )

        assertEquals(DayOverrideFailureCode.STORAGE_FAILED, (result as? DayOverrideSaveResult.Failure)?.code)
        assertEquals(15, overrides.getForPlanDate(existing.id, TOMORROW)?.preparationMinutes)
        assertEquals(stored.committedRevision, overrides.committedRevision(existing.id, TOMORROW))
        assertTrue(overrides.pendingCandidates().isEmpty())
        assertEquals(0, occurrences.getByPlanId(existing.id).count { it.state == OccurrenceState.REGISTERING })
        assertTrue(gateway.cancelled.isNotEmpty(), "the platform candidate must be revoked")
    }

    /** The final check runs inside the commit lock: a stale day revision is stored as STALE. */
    @Test
    fun `applying an evaluation whose day revision changed is stale and registers nothing`() = runBlocking {
        val existing = plan().copy(revision = 1, armedState = AlarmArmedState.SCHEDULED)
        db.alarmPlanDao().upsert(existing.toEntity())
        val overrides = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val regular = occurrence(existing, "regular", OccurrenceState.SCHEDULED)
        occurrences.save(regular)
        val decision = decision(existing, regular, "decision", regular.scheduledWakeAt - 1_200_000L)
        requireNotNull(
            overrides.commitCurrent(
                planId = existing.id,
                date = regular.targetDate,
                replacement = SingleDayOverride(existing.id, regular.targetDate, preparationMinutes = 60),
                now = FIXED_NOW,
            ),
        )

        val result = coordinator.applyEvaluation(decision, null)

        assertEquals("STALE", result.outcome)
        assertEquals(EvaluationOutcome.STALE, decisions.getById("decision")?.evaluationOutcome)
        assertEquals(0, occurrences.getByPlanId(existing.id).count { it.kind == OccurrenceKind.ADVANCE })
    }

    private fun plan(
        id: String = "plan",
        schedule: AlarmSchedule = AlarmSchedule.Weekly(setOf(1, 2, 3, 4, 5, 6, 7)),
    ): AlarmPlan = AlarmPlan(
        id = id,
        revision = 0,
        name = "Alarm",
        enabled = true,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "06:30",
        arrivalLocalTime = "09:00",
        preparationMinutes = 30,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
        schedule = schedule,
    )

    private fun commuteOverride(planId: String, originName: String, destinationName: String) = PlanCommuteOverride(
        planId = planId,
        origin = place(originName, 116.39, 39.90),
        destination = place(destinationName, 116.40, 39.91),
        commuteMode = CommuteMode.DRIVING,
        updatedAt = 1L,
    )

    private fun place(name: String, longitude: Double, latitude: Double) = PlaceRef(
        name = name,
        displayAddress = "$name address",
        longitudeGcj02 = longitude,
        latitudeGcj02 = latitude,
        adcode = "110000",
        citycode = "010",
    )

    private fun occurrence(
        plan: AlarmPlan,
        id: String,
        state: OccurrenceState,
        kind: OccurrenceKind = OccurrenceKind.REGULAR,
        wakeAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1_000L,
    ) = AlarmOccurrence(
        occurrenceId = id,
        planId = plan.id,
        planRevision = plan.revision,
        targetDate = LocalDate.now(ZoneId.of(plan.zoneId)).plusDays(1).toString(),
        scheduledWakeAt = wakeAt,
        state = state,
        kind = kind,
    )

    private fun snapshot(
        plan: AlarmPlan,
        occurrenceId: String,
        wakeAt: Long,
        state: String = AlarmReceiver.STATE_SCHEDULED,
        kind: OccurrenceKind = OccurrenceKind.REGULAR,
        parentId: String? = null,
    ) = NextAlarmSnapshot(
        occurrenceId = occurrenceId,
        planId = plan.id,
        planRevision = plan.revision,
        triggerAtMillis = wakeAt,
        soundUri = null,
        vibrationEnabled = true,
        snoozeMinutes = plan.snoozeMinutes,
        alarmLabel = plan.name,
        occurrenceState = state,
        occurrenceKind = kind.name,
        parentOccurrenceId = parentId,
    )

    private fun decision(
        plan: AlarmPlan,
        regular: AlarmOccurrence,
        id: String,
        recommendedAt: Long,
    ) = AlarmDecision(
        decisionId = id,
        planId = plan.id,
        planRevision = plan.revision,
        targetDate = regular.targetDate,
        workdayStatus = null,
        estimatedDepartureAt = null,
        commuteSeconds = null,
        weatherSeverity = 0,
        weatherBufferMinutes = 0,
        recommendedWakeAt = Instant.ofEpochMilli(recommendedAt).toString(),
        routeProvider = null,
        routeProviderReportTime = null,
        weatherProvider = null,
        weatherProviderReportTime = null,
        weatherWindowStart = null,
        weatherWindowEnd = null,
        fallbackReason = FallbackReason.NONE,
        insufficientAdvance = false,
        generatedAt = Instant.now().toString(),
        expiresAt = (System.currentTimeMillis() + 1_800_000L).toString(),
        evaluationOutcome = EvaluationOutcome.SUCCESS,
    )

    private class FakeGateway : AlarmSchedulingGateway {
        var result: AlarmRegistrationResult = AlarmRegistrationResult.Registered
        var failure: Exception? = null
        val cancelled = mutableListOf<String>()
        val restored = mutableListOf<String>()
        var scheduleDelayMillis: Long = 0L
        var activeSchedules: Int = 0
        var maxConcurrentSchedules: Int = 0
        var onCancel: suspend (String) -> Unit = {}

        override suspend fun schedule(snapshot: NextAlarmSnapshot): AlarmRegistrationResult {
            activeSchedules += 1
            maxConcurrentSchedules = maxOf(maxConcurrentSchedules, activeSchedules)
            try {
                if (scheduleDelayMillis > 0) delay(scheduleDelayMillis)
                failure?.let { throw it }
                return result
            } finally {
                activeSchedules -= 1
            }
        }
        override suspend fun restore(snapshot: NextAlarmSnapshot, nowMillis: Long): AlarmRegistrationResult {
            restored += snapshot.occurrenceId
            return result
        }
        override suspend fun cancelOccurrence(occurrenceId: String) {
            cancelled += occurrenceId
            onCancel(occurrenceId)
        }
        override fun canScheduleExactAlarms(): Boolean = true
    }
}
