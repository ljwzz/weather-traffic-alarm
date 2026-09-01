package com.ljwzz.weathertrafficalarm.core.alarm

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmReceiverTest {
    private val snapshot = NextAlarmSnapshot(
        occurrenceId = "occ-1",
        planId = "plan-1",
        planRevision = 1,
        triggerAtMillis = 1_000_000L,
        soundUri = null,
        vibrationEnabled = true,
        snoozeMinutes = 10,
    )

    @Test
    fun triggerGateRejectsEarlyAndDuplicateBroadcasts() {
        assertEquals(
            AlarmReceiver.AlarmHandling.IGNORED,
            AlarmReceiver.triggerHandling(snapshot, snapshot.triggerAtMillis - 60_001L),
        )
        assertEquals(
            AlarmReceiver.AlarmHandling.IGNORED,
            AlarmReceiver.triggerHandling(snapshot.copy(occurrenceState = AlarmReceiver.STATE_FIRING), snapshot.triggerAtMillis),
        )
    }

    @Test
    fun triggerGateAllowsGraceWindowAndMarksLateAlarmMissed() {
        assertEquals(
            AlarmReceiver.AlarmHandling.TRIGGERED,
            AlarmReceiver.triggerHandling(snapshot, snapshot.triggerAtMillis + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS),
        )
        assertEquals(
            AlarmReceiver.AlarmHandling.MISSED,
            AlarmReceiver.triggerHandling(snapshot, snapshot.triggerAtMillis + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS + 1L),
        )
    }
}
