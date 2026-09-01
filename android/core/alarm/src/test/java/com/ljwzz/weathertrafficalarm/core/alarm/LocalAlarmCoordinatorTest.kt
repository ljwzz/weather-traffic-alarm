package com.ljwzz.weathertrafficalarm.core.alarm

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmSchedulingGateway
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.RegistrationFailure
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var events: AlarmEventRepository
    private lateinit var snapshots: NextAlarmSnapshotStore
    private lateinit var gateway: FakeGateway
    private lateinit var coordinator: LocalAlarmCoordinator

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao())
        occurrences = OccurrenceRepository(db.alarmOccurrenceDao())
        events = AlarmEventRepository(db.alarmEventDao())
        snapshots = NextAlarmSnapshotStore(context)
        snapshots.clear()
        gateway = FakeGateway()
        coordinator = LocalAlarmCoordinator(
            context = context,
            planRepository = plans,
            occurrenceRepository = occurrences,
            eventRepository = events,
            overrideRepository = WorkdayOverrideRepository(db.workdayOverrideDao()),
            calendarRepository = WorkdayCalendarRepository(context),
            scheduler = gateway,
            snapshotStore = snapshots,
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

        coordinator.recover()

        assertTrue(plans.getById(once.id)!!.enabled)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(child.occurrenceId)!!.state)
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

    private class FakeGateway : AlarmSchedulingGateway {
        var result: AlarmRegistrationResult = AlarmRegistrationResult.Registered
        val cancelled = mutableListOf<String>()
        val restored = mutableListOf<String>()
        var scheduleDelayMillis: Long = 0L
        var activeSchedules: Int = 0
        var maxConcurrentSchedules: Int = 0

        override suspend fun schedule(snapshot: NextAlarmSnapshot): AlarmRegistrationResult {
            activeSchedules += 1
            maxConcurrentSchedules = maxOf(maxConcurrentSchedules, activeSchedules)
            try {
                if (scheduleDelayMillis > 0) delay(scheduleDelayMillis)
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
        }
        override fun canScheduleExactAlarms(): Boolean = true
    }
}
