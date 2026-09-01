package com.ljwzz.weathertrafficalarm.core.alarm.scheduler

import android.app.AlarmManager
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmReceiver
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.AlarmAction
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExactAlarmSchedulerTest {

    private lateinit var scheduler: ExactAlarmScheduler
    private lateinit var snapshotStore: NextAlarmSnapshotStore
    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingIntentFactory: PendingIntentFactory

    private val origin = PlaceRef("home", "Home", "123 Main St", 116.397428, 39.90923, "110000", "010")
    private val destination = PlaceRef("office", "Office", "456 Work Ave", 116.407428, 39.91923, "110000", "010")

    private val testPlan = AlarmPlan(
        id = "plan-test-1",
        revision = 1,
        name = "Test Plan",
        enabled = true,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "06:30",
        arrivalLocalTime = "09:00",
        preparationMinutes = 30,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
        origin = origin,
        destination = destination,
        waypoints = emptyList(),
        routePolicy = RoutePolicy.LEAST_TRAFFIC,
        weatherRuleVersion = "v1",
        sound = AlarmSound(),
        vibration = VibrationPattern(),
        snoozeMinutes = 10,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        snapshotStore = NextAlarmSnapshotStore(context)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        pendingIntentFactory = PendingIntentFactory(context)
        scheduler = ExactAlarmScheduler(
            context = context,
            alarmManager = alarmManager,
            pendingIntentFactory = pendingIntentFactory,
            snapshotStore = snapshotStore,
        )
    }

    @Test
    fun scheduleDisabledPlanReturnsNull() = runTest {
        val disabledPlan = testPlan.copy(enabled = false)
        val result = scheduler.scheduleDefault(disabledPlan)
        assertTrue(result == null)
    }

    @Test
    fun scheduleEnabledPlanCreatesOccurrence() = runTest {
        val result = scheduler.scheduleDefault(testPlan)
        assertNotNull(result)
        assertTrue(result!!.planId == testPlan.id)
    }

    @Test
    fun cancelForPlanCancelsTheRegisteredAlarmAndItsToken() = runTest {
        val occurrence = requireNotNull(scheduler.scheduleDefault(testPlan))
        val token = requireNotNull(
            pendingIntentFactory.findPendingIntent(
                occurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )
        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.any { it.operation == token })

        scheduler.cancelForPlan(testPlan.id)

        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.none { it.operation == token })
        assertTrue(Shadows.shadowOf(token).isCanceled)
        assertNull(
            pendingIntentFactory.findPendingIntent(
                occurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )
        val snapshot = snapshotStore.get(testPlan.id)
        assertNull(snapshot)
    }

    @Test
    fun cancelForPlanDoesNotCancelAnotherPlanAlarm() = runTest {
        val firstOccurrence = requireNotNull(scheduler.scheduleDefault(testPlan))
        val otherPlan = testPlan.copy(id = "plan-test-2")
        val secondOccurrence = requireNotNull(scheduler.scheduleDefault(otherPlan))
        val secondToken = requireNotNull(
            pendingIntentFactory.findPendingIntent(
                secondOccurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )

        scheduler.cancelForPlan(testPlan.id)

        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.any { it.operation == secondToken })
        assertNotNull(snapshotStore.get(otherPlan.id))
        assertNull(
            pendingIntentFactory.findPendingIntent(
                firstOccurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )
    }

    @Test
    fun cancelForPlanIsIdempotentAfterAScheduledAlarm() = runTest {
        val occurrence = requireNotNull(scheduler.scheduleDefault(testPlan))
        val token = requireNotNull(
            pendingIntentFactory.findPendingIntent(
                occurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )

        scheduler.cancelForPlan(testPlan.id)
        scheduler.cancelForPlan(testPlan.id)

        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isEmpty())
        assertTrue(Shadows.shadowOf(token).isCanceled)
        assertNull(snapshotStore.get(testPlan.id))
    }

    @Test
    fun cancelForPlanRemovesSnapshotWhenTheSystemTokenIsAlreadyMissing() = runTest {
        val occurrence = requireNotNull(scheduler.scheduleDefault(testPlan))
        val token = requireNotNull(
            pendingIntentFactory.findPendingIntent(
                occurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )
        alarmManager.cancel(token)
        token.cancel()
        assertNull(
            pendingIntentFactory.findPendingIntent(
                occurrence.occurrenceId,
                AlarmAction.ALARM,
                AlarmReceiver::class.java,
            ),
        )

        scheduler.cancelForPlan(testPlan.id)

        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isEmpty())
        assertNull(snapshotStore.get(testPlan.id))
    }
}
