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

    @Test
    fun ringingActionGateAllowsOnlyTheCurrentFiringSnapshot() {
        assertEquals(
            true,
            AlarmReceiver.canApplyRingingAction(
                snapshot.copy(occurrenceState = AlarmReceiver.STATE_FIRING),
            ),
        )
        assertEquals(false, AlarmReceiver.canApplyRingingAction(snapshot))
        assertEquals(
            false,
            AlarmReceiver.canApplyRingingAction(
                snapshot.copy(occurrenceState = AlarmReceiver.STATE_SNOOZED),
            ),
        )
        assertEquals(
            false,
            AlarmReceiver.canApplyRingingAction(
                snapshot.copy(occurrenceState = AlarmReceiver.STATE_DISMISSED),
            ),
        )
    }

    @Test
    fun snoozeChildDoesNotInheritParentActionFailureReceipt() {
        val parent = snapshot.copy(
            occurrenceState = AlarmReceiver.STATE_FIRING,
            snoozeCount = 2,
            actionRevision = 7,
            actionError = "贪睡未能注册，请重试或停止闹钟",
        )

        val child = AlarmReceiver.createSnoozeSnapshot(
            snapshot = parent,
            nowMillis = 2_000_000L,
            occurrenceId = "snooze-3",
        )

        assertEquals("snooze-3", child.occurrenceId)
        assertEquals(parent.occurrenceId, child.parentOccurrenceId)
        assertEquals(parent.planRevision, child.planRevision)
        assertEquals(2_600_000L, child.triggerAtMillis)
        assertEquals("SNOOZE", child.occurrenceKind)
        assertEquals(AlarmReceiver.STATE_SCHEDULED, child.occurrenceState)
        assertEquals(3, child.snoozeCount)
        assertEquals(0L, child.actionRevision)
        assertEquals(null, child.actionError)
    }
}
