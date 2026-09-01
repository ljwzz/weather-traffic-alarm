package com.ljwzz.weathertrafficalarm.core.alarm

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmRecoveryReceiverTest {
    @Test
    fun dueSnapshotIsDeferredToReceiverInsteadOfStartingForegroundServiceFromBoot() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "occ-1",
            planId = "plan-1",
            planRevision = 1,
            triggerAtMillis = 1_000L,
            soundUri = null,
            vibrationEnabled = true,
            snoozeMinutes = 10,
        )

        assertEquals(2_001L, AlarmRecoveryReceiver.deferredForBoot(snapshot, 1_001L).triggerAtMillis)
    }
}
