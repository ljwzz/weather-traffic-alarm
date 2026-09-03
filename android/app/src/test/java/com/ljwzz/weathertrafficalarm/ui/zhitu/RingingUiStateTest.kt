package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RingingUiStateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val parent = NextAlarmSnapshot(
        occurrenceId = "parent", planId = "plan", planRevision = 1,
        triggerAtMillis = Instant.parse("2026-09-02T15:55:00Z").toEpochMilli(),
        soundUri = null, vibrationEnabled = true, snoozeMinutes = 10,
        alarmLabel = "工作闹钟", occurrenceState = "FIRING",
    )

    @Test fun loadingAndMissingNeverShowAnActionableAlarm() {
        assertEquals(RingingPhase.LOADING, ringingUiState("parent", null, emptySet()).phase)
        assertEquals(RingingPhase.UNAVAILABLE, project(emptyList()).phase)
        assertEquals(RingingPhase.UNAVAILABLE, ringingUiState(null, listOf(parent), setOf("parent")).phase)
        assertEquals(RingingPhase.UNAVAILABLE, project(listOf(parent), emptySet()).phase)
        val error = "解锁后可查看闹钟。"
        assertEquals(error, ringingUiState(null, emptyList(), emptySet(), errorMessage = error).errorMessage)
    }

    @Test fun scheduledCancelledAndMissedInstancesAreNotRinging() {
        assertEquals(RingingPhase.SCHEDULED, project(listOf(parent.copy(occurrenceState = "SCHEDULED"))).phase)
        listOf("MISSED", "CANCELLED", "FAILED", "ADVANCED").forEach { status ->
            assertEquals(RingingPhase.UNAVAILABLE, project(listOf(parent.copy(occurrenceState = status))).phase)
        }
    }

    @Test fun realFiringUsesTheActualPlanTimeAndLocalDate() {
        val ui = project(listOf(parent))
        assertEquals(RingingPhase.RINGING, ui.phase)
        assertEquals("23:55", ui.alarmTime)
        assertEquals("9月2日  星期三", ui.dateLabel)
        assertEquals("工作闹钟", ui.reasonTitle)
        assertFalse(ui.reason.contains("47"))
        assertFalse(ui.footer.contains("模拟"))
    }

    @Test fun advanceRingingShowsTheRegisteredLeadAndSnoozeUsesItsOwnReason() {
        val advance = parent.copy(occurrenceKind = "ADVANCE", defaultWakeAtMillis = parent.triggerAtMillis + 12 * 60_000)
        val ui = project(listOf(advance))
        assertEquals("知途 · 提前提醒", ui.header)
        assertEquals("提前 12 分钟提醒", ui.badge)
        assertTrue(ui.reason.contains("基础闹钟仍按原定时间响铃"))
        val snooze = project(listOf(advance.copy(occurrenceKind = "SNOOZE", parentOccurrenceId = "original")))
        assertEquals("贪睡后再次响铃", snooze.badge)
        assertFalse(snooze.reason.contains("提前"))
    }

    @Test fun completionWaitsUntilThisOccurrenceLeavesTheAudioService() {
        val stopped = parent.copy(occurrenceState = "DISMISSED")
        val processing = project(listOf(stopped))
        assertEquals(RingingPhase.RINGING, processing.phase)
        assertTrue(processing.busy)
        val confirmed = project(listOf(stopped), setOf("unrelated"))
        assertEquals(RingingPhase.STOPPED, confirmed.phase)
        assertFalse(confirmed.busy)
    }

    @Test fun snoozeRequiresAnArmedMatchingChildAndUsesItsNextDay() {
        val snoozed = parent.copy(occurrenceState = "SNOOZED")
        val child = parent.copy(
            occurrenceId = "child", parentOccurrenceId = "parent", occurrenceKind = "SNOOZE",
            occurrenceState = "SCHEDULED", triggerAtMillis = parent.triggerAtMillis + 600_000,
        )
        assertEquals(RingingPhase.UNAVAILABLE, project(listOf(snoozed), emptySet()).phase)
        listOf(child.copy(planId = "other"), child.copy(planRevision = 2), child.copy(occurrenceState = "FAILED"), child.copy(parentOccurrenceId = "other")).forEach {
            assertEquals(RingingPhase.UNAVAILABLE, project(listOf(snoozed, it), emptySet()).phase)
        }
        val ui = project(listOf(snoozed, child), emptySet())
        assertEquals(RingingPhase.SNOOZED, ui.phase)
        assertEquals("00:05", ui.alarmTime)
        assertEquals("9月3日  星期四", ui.dateLabel)
        assertTrue(ui.reason.contains("00:05"))
    }

    @Test fun childUsesSnoozeCopyAndFailureRemainsRetryable() {
        val child = parent.copy(occurrenceKind = "SNOOZE", parentOccurrenceId = "earlier")
        val ui = ringingUiState("parent", listOf(child), setOf("parent"), errorMessage = "贪睡未能注册", zoneId = zone)
        assertEquals(RingingPhase.RINGING, ui.phase)
        assertEquals("贪睡后再次响铃", ui.badge)
        assertEquals("贪睡提醒", ui.reasonTitle)
        assertEquals("贪睡未能注册", ui.errorMessage)
    }

    private fun project(snapshots: List<NextAlarmSnapshot>, active: Set<String> = setOf("parent")) =
        ringingUiState("parent", snapshots, active, zoneId = zone)
}
