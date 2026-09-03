package com.ljwzz.weathertrafficalarm.core.alarm

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRingingServiceTest {
    private val snapshot = NextAlarmSnapshot(
        occurrenceId = "occ-1",
        planId = "plan-1",
        planRevision = 1,
        triggerAtMillis = 1_000L,
        soundUri = null,
        vibrationEnabled = true,
        snoozeMinutes = 10,
    )

    @Test
    fun directBootTimeoutCanEndOnlyTheCurrentFiringSnapshot() {
        assertTrue(
            AlarmRingingService.canTimeoutDismiss(
                snapshot.copy(occurrenceState = AlarmReceiver.STATE_FIRING),
            ),
        )
        assertFalse(AlarmRingingService.canTimeoutDismiss(snapshot))
        assertFalse(
            AlarmRingingService.canTimeoutDismiss(
                snapshot.copy(occurrenceState = AlarmReceiver.STATE_SNOOZED),
            ),
        )
    }
}
