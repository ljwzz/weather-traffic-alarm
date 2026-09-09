package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionDetailLookup
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionOccurrenceAssociation
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionDetailModelsTest {
    @Test
    fun `legacy snapshot keeps its instant in UTC and never takes current plan display fields`() {
        val ui = decision(planName = null, zoneId = null).toDecisionDetailUi()

        assertEquals("本次未提供", ui.planName)
        assertEquals("1月1日 00:00 UTC", ui.recommendedWake)
        assertEquals("本次未提供实例记录", ui.occurrenceLabel)
    }

    @Test
    fun `successful no advance and failed application retain different conclusions`() {
        val noAdvance = decision(applicationOutcome = "CANCELLED").toDecisionDetailUi()
        val registrationFailure = decision(
            evaluationOutcome = EvaluationOutcome.FAILED,
            applicationOutcome = "FAILED",
            failureReason = "ROUTE_TIMEOUT",
        ).toDecisionDetailUi()

        assertEquals("本次无需提前", noAdvance.title)
        assertEquals("无需提前：本次未新增提前提醒", noAdvance.applicationLabel)
        assertEquals("提前提醒注册失败", registrationFailure.title)
        assertEquals("路线服务响应超时", registrationFailure.failureReason)
        assertTrue(DecisionRecoveryAction.DIAGNOSTICS in registrationFailure.recoveryActions)
    }

    @Test
    fun `weather and duration are not invented when provider data is absent`() {
        val ui = decision(weatherProvider = null, weatherDataSource = null, commuteSeconds = 3_601L)
            .toDecisionDetailUi()

        assertEquals("本次未提供", ui.weather)
        assertEquals("1小时1分钟", ui.commute)
        assertNull(ui.nextRetryLabel)
        assertFalse(ui.sourceLines.any { it.startsWith("天气来源") })
    }

    @Test
    fun `registration failure is warning even when the evaluation itself succeeded`() {
        val ui = decision(applicationOutcome = "FAILED").toDecisionDetailUi()

        assertEquals("提前提醒注册失败", ui.title)
        assertEquals(DecisionDetailTone.WARNING, ui.evaluationTone)
    }

    @Test
    fun `retained prior advance is labelled as a non current decision instance`() {
        val ui = decision(applicationOutcome = "UNCHANGED", actualWakeAt = "2026-01-01T00:10:00Z")
            .toDecisionDetailUi()

        assertEquals("未调整：保留已有提醒（非本次决策实例）", ui.applicationLabel)
        assertEquals("本次未提供实例记录", ui.occurrenceLabel)
    }

    @Test
    fun `stale changed inputs do not present a default preparation value as historical data`() {
        val ui = decision(planName = null, zoneId = "UTC", actualWakeAt = null)
            .copyForMissingSnapshot()
            .toDecisionDetailUi()

        assertEquals("本次未提供", ui.preparation)
    }

    @Test
    fun `expired validity never changes historical success while stale skipped and insufficient remain distinct`() {
        val success = decision().copy(expiresAt = "2000-01-01T00:00:00Z").toDecisionDetailUi()
        assertEquals("已应用提前提醒", success.title)
        assertEquals("已应用：已注册本次提前提醒", success.applicationLabel)
        assertTrue(success.expiredNotice != null)
        assertEquals("评估已过期", decision(evaluationOutcome = EvaluationOutcome.STALE, applicationOutcome = "NOT_APPLIED").toDecisionDetailUi().title)
        assertEquals("本次跳过评估", decision(evaluationOutcome = EvaluationOutcome.SKIPPED, applicationOutcome = "NOT_APPLIED").toDecisionDetailUi().title)
        assertEquals("提前额度不足", decision().copy(insufficientAdvance = true).toDecisionDetailUi().title)
    }

    @Test
    fun `unapplied failure does not present its base time placeholder as a recommendation`() {
        val ui = decision(evaluationOutcome = EvaluationOutcome.FAILED, applicationOutcome = "NOT_APPLIED", weatherProvider = null, weatherDataSource = null).toDecisionDetailUi()
        assertEquals("本次未新增或调整提醒", ui.applicationLabel)
        assertEquals("本次未生成建议", ui.recommendedWake)
        assertEquals("本次未提供", ui.weatherBuffer)
    }

    @Test
    fun `historical zone controls cross day display independently of the system zone`() {
        val ui = decision(zoneId = "America/Los_Angeles").toDecisionDetailUi()
        assertEquals("12月31日 16:00", ui.recommendedWake)
        assertEquals("12月31日 15:00", ui.evaluatedAt)
        assertEquals("America/Los_Angeles", ui.zoneLabel)
        assertEquals("0小时1分钟", decision(commuteSeconds = 1).toDecisionDetailUi().commute)
        assertEquals("0小时1分钟", decision(commuteSeconds = 60).toDecisionDetailUi().commute)
        assertEquals("0小时2分钟", decision(commuteSeconds = 61).toDecisionDetailUi().commute)
    }

    @Test
    fun `a previous detail projection cannot render while a different anchor is loading`() {
        val previous = DecisionDetailLookup.DecisionFound(decision(), null, DecisionOccurrenceAssociation.Missing)
        assertTrue(previous.matchesDetailAnchor("decision-1", null))
        assertFalse(previous.matchesDetailAnchor("decision-2", null))
        assertFalse(previous.matchesDetailAnchor(null, "occurrence-1"))
        assertFalse(previous.matchesDetailAnchor(null, null))
    }

    private fun decision(
        planName: String? = "历史计划",
        zoneId: String? = "Asia/Shanghai",
        evaluationOutcome: EvaluationOutcome = EvaluationOutcome.SUCCESS,
        applicationOutcome: String? = "APPLIED",
        failureReason: String? = null,
        weatherProvider: String? = "CAIYUN_V2_6",
        weatherDataSource: String? = "NETWORK",
        commuteSeconds: Long? = 3_600L,
        actualWakeAt: String? = null,
    ) = AlarmDecision(
        decisionId = "decision-1",
        planId = "plan-1",
        planRevision = 1,
        targetDate = "2026-09-08",
        workdayStatus = null,
        estimatedDepartureAt = "2026-01-01T00:00:00Z",
        commuteSeconds = commuteSeconds,
        weatherSeverity = 0,
        weatherBufferMinutes = 0,
        recommendedWakeAt = "2026-01-01T00:00:00Z",
        routeProvider = null,
        routeProviderReportTime = null,
        weatherProvider = weatherProvider,
        weatherProviderReportTime = null,
        weatherWindowStart = null,
        weatherWindowEnd = null,
        fallbackReason = FallbackReason.NONE,
        insufficientAdvance = false,
        generatedAt = "2025-12-31T23:00:00Z",
        expiresAt = "2099-01-01T00:00:00Z",
        evaluationOutcome = evaluationOutcome,
        failureReason = failureReason,
        applicationOutcome = applicationOutcome,
        defaultWakeAt = "2026-01-01T00:30:00Z",
        actualWakeAt = actualWakeAt,
        planName = planName,
        zoneId = zoneId,
        weatherDataSource = weatherDataSource,
    )

    private fun AlarmDecision.copyForMissingSnapshot() = copy(defaultWakeAt = null, preparationMinutes = 0)
}
