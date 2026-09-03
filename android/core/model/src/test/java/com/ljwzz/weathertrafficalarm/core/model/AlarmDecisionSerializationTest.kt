package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmDecisionSerializationTest {

    @Test
    fun `legacy decision payload uses evaluation history defaults`() {
        val decision = Json.decodeFromString<AlarmDecision>(
            """
            {
              "decisionId":"decision-1",
              "planId":"plan-1",
              "planRevision":1,
              "targetDate":"2026-09-03",
              "workdayStatus":"WORKDAY",
              "estimatedDepartureAt":null,
              "commuteSeconds":null,
              "weatherSeverity":0,
              "weatherBufferMinutes":0,
              "recommendedWakeAt":"2026-09-03T06:30:00",
              "routeProvider":null,
              "routeProviderReportTime":null,
              "weatherProvider":null,
              "weatherProviderReportTime":null,
              "weatherWindowStart":null,
              "weatherWindowEnd":null,
              "fallbackReason":"NONE",
              "insufficientAdvance":false,
              "generatedAt":"1",
              "expiresAt":"2"
            }
            """.trimIndent(),
        )

        assertEquals(EvaluationOutcome.FAILED, decision.evaluationOutcome)
        assertNull(decision.failureReason)
        assertEquals(0, decision.attemptNumber)
        assertNull(decision.applicationOutcome)
        assertEquals(0, decision.preparationMinutes)
        assertNull(decision.defaultWakeAt)
        assertNull(decision.actualWakeAt)
        assertNull(decision.calendarSource)
        assertNull(decision.weatherDataSource)
    }
}
