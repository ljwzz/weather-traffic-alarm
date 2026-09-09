package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationTaskState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionRetryLabelTest {
    @Test
    fun `label uses only the retry explicitly tied to this decision`() {
        val decision = decision()
        val label = decisionRetryLabel(decision, listOf(task(decision)))

        assertTrue(label?.contains("已安排重试：2026-09-08 08:30（Asia/Shanghai）") == true)
    }

    @Test
    fun `running and non unique retries never invent a next retry time`() {
        val decision = decision()

        assertNull(decisionRetryLabel(decision, listOf(task(decision, phase = "RUNNING", nextAttemptAt = null))))
        assertNull(decisionRetryLabel(decision, listOf(task(decision), task(decision, workId = "second"))))
    }

    @Test
    fun `similar task from another decision plan revision or date is not displayed`() {
        val decision = decision()
        val mismatches = listOf(
            task(decision, decisionId = "other-decision"),
            task(decision, planId = "other-plan"),
            task(decision, planRevision = 2),
            task(decision, targetDate = "2026-09-09"),
            task(decision, phase = "WAITING"),
        )

        mismatches.forEach { assertNull(decisionRetryLabel(decision, listOf(it))) }
    }

    private fun task(
        decision: AlarmDecision,
        decisionId: String = decision.decisionId,
        planId: String = decision.planId,
        planRevision: Long = decision.planRevision,
        targetDate: String = decision.targetDate,
        phase: String = "RETRYING",
        nextAttemptAt: Long? = java.time.Instant.parse("2026-09-08T00:30:00Z").toEpochMilli(),
        workId: String = "first",
    ) = EvaluationTaskState(
        phase = phase, planId = planId, nextAttemptAt = nextAttemptAt, attemptNumber = 1,
        targetDate = targetDate, planRevision = planRevision, origin = "manual", decisionId = decisionId, workId = workId,
    )

    private fun decision() = AlarmDecision(
        decisionId = "decision-a", planId = "plan-a", planRevision = 1, targetDate = "2026-09-08",
        workdayStatus = null, estimatedDepartureAt = null, commuteSeconds = null, weatherSeverity = 0,
        weatherBufferMinutes = 0, recommendedWakeAt = "2026-09-08T00:00:00Z", routeProvider = null,
        routeProviderReportTime = null, weatherProvider = null, weatherProviderReportTime = null,
        weatherWindowStart = null, weatherWindowEnd = null, fallbackReason = FallbackReason.NONE,
        insufficientAdvance = false, generatedAt = "2026-09-07T00:00:00Z", expiresAt = "2026-09-08T00:00:00Z",
        evaluationOutcome = EvaluationOutcome.FAILED, failureReason = "ROUTE_NETWORK", zoneId = "Asia/Shanghai",
    )
}
