package com.ljwzz.weathertrafficalarm.evaluation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EvaluationWorkPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 3)

    @Test
    fun `night schedule stays today before cutoff and moves to tomorrow at cutoff`() {
        val beforeStart = Instant.parse("2026-09-03T10:00:00Z") // 18:00 local
        val inWindow = Instant.parse("2026-09-03T12:00:00Z") // 20:00 local
        val atCutoff = Instant.parse("2026-09-03T15:30:00Z") // 23:30 local

        assertEquals(Instant.parse("2026-09-03T11:05:00Z"), EvaluationWorkPolicy.nextNight(beforeStart, zone, 5))
        assertEquals(inWindow, EvaluationWorkPolicy.nextNight(inWindow, zone, 5))
        assertEquals(Instant.parse("2026-09-04T11:05:00Z"), EvaluationWorkPolicy.nextNight(atCutoff, zone, 5))
    }

    @Test
    fun `future night skips an in-progress evaluation window`() {
        val now = Instant.parse("2026-09-03T12:00:00Z") // 20:00 local

        assertEquals(
            Instant.parse("2026-09-04T11:00:00Z"),
            EvaluationWorkPolicy.nextNight(now, zone, 0, futureOnly = true),
        )
    }

    @Test
    fun `retries respect fixed sequence retry-after and deadline`() {
        val now = Instant.parse("2026-09-03T12:00:00Z")
        val deadline = Instant.parse("2026-09-03T15:30:00Z")

        assertEquals(Instant.parse("2026-09-03T12:15:00Z"), EvaluationWorkPolicy.retryAt(now, 0, deadline))
        assertEquals(Instant.parse("2026-09-03T12:30:00Z"), EvaluationWorkPolicy.retryAt(now, 1, deadline))
        assertEquals(Instant.parse("2026-09-03T13:00:00Z"), EvaluationWorkPolicy.retryAt(now, 2, deadline))
        assertEquals(Instant.parse("2026-09-03T12:20:00Z"), EvaluationWorkPolicy.retryAt(now, 0, deadline, 1_200))
        assertNull(EvaluationWorkPolicy.retryAt(now, 3, deadline))
        assertNull(EvaluationWorkPolicy.retryAt(now, 0, now.plusSeconds(900)))
    }

    @Test
    fun `execution interval is not valid at deadline`() {
        val notBefore = Instant.parse("2026-09-03T11:00:00Z")
        val deadline = Instant.parse("2026-09-03T15:30:00Z")

        assertFalse(EvaluationWorkPolicy.mayExecute(notBefore.minusSeconds(1), notBefore, deadline))
        assertTrue(EvaluationWorkPolicy.mayExecute(notBefore, notBefore, deadline))
        assertFalse(EvaluationWorkPolicy.mayExecute(deadline, notBefore, deadline))
    }

    @Test
    fun `work run tags round trip and reject malformed values`() {
        val run = EvaluationWorkRun(
            targetDate = date,
            notBefore = Instant.parse("2026-09-03T11:00:00Z"),
            deadline = Instant.parse("2026-09-03T15:30:00Z"),
            attempt = 2,
            origin = "night",
            revision = 4,
            zoneId = "Asia/Shanghai",
        )

        assertEquals(run, EvaluationWorkRun.fromTags(run.tags()))
        assertEquals(run.copy(attempt = 3), EvaluationWorkRun.fromTags(run.tags() - "attempt:2" + "attempt:3"))
        assertNull(EvaluationWorkRun.fromTags(run.tags() - "attempt:2" + "attempt:4"))
        assertNull(EvaluationWorkRun.fromTags(run.tags() - "origin:night" + "origin:unknown"))
    }
}
