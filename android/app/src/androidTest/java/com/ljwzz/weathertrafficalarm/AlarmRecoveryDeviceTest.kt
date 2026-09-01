package com.ljwzz.weathertrafficalarm

import android.app.AlarmManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmRingingService
import com.ljwzz.weathertrafficalarm.core.model.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Two explicit host-controlled phases; normal connected tests skip this reboot scenario. */
@RunWith(AndroidJUnit4::class)
class AlarmRecoveryDeviceTest {
    @Test fun alarmSurvivesDeviceRestart() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue("Requires prepare → emulator reboot → verify", phase != null)
        val context = instrumentation.targetContext
        val deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        when (phase) {
            "prepare" -> {
                deps.plans().getById(PLAN_ID)?.let { deps.coordinator().delete(it.id) }
                val at = Instant.now().plusSeconds(60).atZone(ZoneId.systemDefault())
                val plan = AlarmPlan(
                    id = PLAN_ID, revision = 0, name = "重启恢复验证", enabled = true,
                    zoneId = at.zone.id, defaultWakeLocalTime = at.toLocalTime().withNano(0).toString(),
                    arrivalLocalTime = "09:00", preparationMinutes = 30, maxAdvanceMinutes = 60,
                    commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Once(at.toLocalDate().toString()),
                )
                assertEquals(AlarmArmedState.SCHEDULED, deps.coordinator().save(plan).armedState)
                instrumentation.sendStatus(0, Bundle().apply { putLong("scheduledAt", at.toInstant().toEpochMilli()) })
            }
            "verify" -> {
                // Deliberately do not call coordinator.recover() or launch MainActivity:
                // the boot/unlock receivers must have restored the actual system alarm.
                val plan = requireNotNull(deps.plans().getById(PLAN_ID))
                val pending = deps.occurrences().getByPlanId(plan.id).single { it.state == OccurrenceState.SCHEDULED }
                assertTrue(pending.scheduledWakeAt > System.currentTimeMillis())
                assertEquals(pending.scheduledWakeAt, context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime)
                withTimeout(150_000) {
                    while (deps.occurrences().getById(pending.occurrenceId)?.state != OccurrenceState.FIRING) delay(150)
                }
                withTimeout(10_000) {
                    while (AlarmRingingService.activeAlarms.value.none { it.occurrenceId == pending.occurrenceId }) delay(100)
                }
                deps.coordinator().dismiss(pending.occurrenceId)
                assertTrue(deps.events().observeAll().first().any { it.occurrenceId == pending.occurrenceId && it.type == AlarmEventType.TRIGGERED })
                deps.coordinator().delete(plan.id)
            }
            "verifyAfterUnlock" -> {
                // The host first observes the foreground service while user storage
                // is locked, then unlocks the device. USER_UNLOCKED must reconcile it.
                val plan = requireNotNull(deps.plans().getById(PLAN_ID))
                withTimeout(15_000) {
                    while (deps.occurrences().getByPlanId(plan.id).none { it.state == OccurrenceState.FIRING }) delay(100)
                }
                val fired = deps.occurrences().getByPlanId(plan.id).single { it.state == OccurrenceState.FIRING }
                assertTrue(deps.events().observeAll().first().any { it.occurrenceId == fired.occurrenceId && it.type == AlarmEventType.TRIGGERED })
                deps.coordinator().dismiss(fired.occurrenceId)
                assertFalse(requireNotNull(deps.plans().getById(plan.id)).enabled)
                deps.coordinator().delete(plan.id)
            }
            "cleanup" -> deps.coordinator().delete(PLAN_ID)
            else -> error("Unknown recoveryPhase")
        }
    }

    private companion object { const val PLAN_ID = "device-test-recovery" }
}
