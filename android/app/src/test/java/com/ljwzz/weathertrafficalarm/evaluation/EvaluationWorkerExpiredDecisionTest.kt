package com.ljwzz.weathertrafficalarm.evaluation

import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DailySettingsResolver
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputs
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvaluationWorkerExpiredDecisionTest {
    @Test
    fun `old run revision never receives current plan snapshot fields`() {
        val plan = plan(revision = 3, zoneId = "Asia/Shanghai", name = "已编辑计划", wake = "06:45", preparation = 45)
        val run = run(revision = 2, zoneId = "UTC")

        val decision = expiredDecision(plan, run, "worker-old", now)

        assertEquals("EVALUATION_INPUTS_CHANGED", decision.failureReason)
        assertEquals("NOT_APPLIED", decision.applicationOutcome)
        assertNull(decision.planName)
        assertNull(decision.defaultWakeAt)
        assertEquals(0, decision.preparationMinutes)
        assertEquals("UTC", decision.zoneId)
        assertEquals("", decision.recommendedWakeAt)
    }

    @Test
    fun `worker identity makes expired records idempotent per worker and unique across work`() {
        val plan = plan()
        val run = run()

        assertEquals(
            expiredDecision(plan, run, "worker-a", now).decisionId,
            expiredDecision(plan, run, "worker-a", now.plusSeconds(1)).decisionId,
        )
        assertNotEquals(
            expiredDecision(plan, run, "worker-a", now).decisionId,
            expiredDecision(plan, run, "worker-b", now).decisionId,
        )
    }

    @Test
    fun `same generation records verified effective day inputs`() {
        val plan = plan(revision = 2, zoneId = "Asia/Shanghai", name = "评估时计划", wake = "07:30", preparation = 35)
        val run = run(revision = 2, zoneId = "Asia/Shanghai").copy(dayRevision = 5)
        val override = SingleDayOverride(plan.id, run.targetDate.toString(), wakeLocalTime = "08:15",
            arrivalLocalTime = "10:00", preparationMinutes = 55, dayRevision = 5)
        val inputs = DailyEvaluationInputs(plan, run.targetDate, LocalSettings(), override, 5, emptyMap(),
            DailySettingsResolver.resolve(plan, run.targetDate, null),
            DailySettingsResolver.resolve(plan, run.targetDate, override), null, null)

        val decision = expiredDecision(plan, run, "worker-current", now, 5, inputs)
        val expectedWake = run.targetDate.atTime(8, 15).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString()

        assertEquals("EVALUATION_WINDOW_EXPIRED", decision.failureReason)
        assertEquals("评估时计划", decision.planName)
        assertEquals(expectedWake, decision.defaultWakeAt)
        assertEquals(expectedWake, decision.recommendedWakeAt)
        assertEquals(55, decision.preparationMinutes)
        assertEquals("10:00", decision.arrivalLocalTime)
        assertEquals(5L, decision.dayRevision)
        assertEquals("Asia/Shanghai", decision.zoneId)
    }

    @Test
    fun `changed day generation is recorded without current fields`() {
        val decision = expiredDecision(plan(), run().copy(dayRevision = 7), "changed-day", now, 8)
        assertEquals(7L, decision.dayRevision)
        assertEquals("EVALUATION_INPUTS_CHANGED", decision.failureReason)
        assertNull(decision.defaultWakeAt)
        assertNull(decision.planName)
        assertNull(decision.arrivalLocalTime)
        assertEquals(0, decision.preparationMinutes)
    }

    @Test
    fun `a missing input snapshot never falls back to plan defaults`() {
        val decision = expiredDecision(plan(), run(), "missing-inputs", now)
        assertEquals("EVALUATION_WINDOW_EXPIRED", decision.failureReason)
        assertNull(decision.defaultWakeAt)
        assertNull(decision.planName)
        assertEquals("", decision.recommendedWakeAt)
    }

    private fun plan(
        revision: Long = 2,
        zoneId: String = "Asia/Shanghai",
        name: String = "计划",
        wake: String = "07:00",
        preparation: Int = 30,
    ) = AlarmPlan(
        id = "plan-a", revision = revision, name = name, enabled = true, zoneId = zoneId,
        defaultWakeLocalTime = wake, arrivalLocalTime = "09:00", preparationMinutes = preparation,
        maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Workdays,
    )

    private fun run(revision: Long = 2, zoneId: String = "Asia/Shanghai") = EvaluationWorkRun(
        targetDate = LocalDate.parse("2026-09-08"), notBefore = now.minusSeconds(60), deadline = now.minusSeconds(1),
        attempt = 1, origin = "manual", revision = revision, zoneId = zoneId,
    )

    private companion object {
        val now: Instant = Instant.parse("2026-09-08T00:00:00Z")
    }
}
