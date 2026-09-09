package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DecisionDetailNavigationTest {
    @Test fun `only the explicit detail action accepts a nonblank occurrence identity`() {
        val valid = Intent().openDecisionDetailForOccurrence("advance-occurrence")

        assertEquals(ACTION_OPEN_DECISION_DETAIL, valid.action)
        assertEquals("advance-occurrence", valid.decisionDetailOccurrenceId())
        assertNull(Intent().putExtra(EXTRA_DECISION_OCCURRENCE_ID, "advance-occurrence").decisionDetailOccurrenceId())
        assertNull(Intent(ACTION_OPEN_DECISION_DETAIL).putExtra(EXTRA_DECISION_OCCURRENCE_ID, " ").decisionDetailOccurrenceId())
    }

    @Test fun `invalid explicit detail action remains a detail request for an empty state`() {
        val invalid = Intent(ACTION_OPEN_DECISION_DETAIL)
        assertEquals(ACTION_OPEN_DECISION_DETAIL, invalid.action)
        assertNull(invalid.decisionDetailOccurrenceId())
    }

    @Test fun `read only detail resumes never recover or reschedule alarms`() {
        assertEquals(false, shouldRecoverAlarmsOnResume(detailReadOnlySession = true))
        assertEquals(true, shouldRecoverAlarmsOnResume(detailReadOnlySession = false))
    }

    @Test fun `detail session survives recreation even without a fresh external intent`() {
        assertEquals(true, restoredDetailReadOnlySession(savedValue = true, occurrenceId = null))
        assertEquals(false, restoredDetailReadOnlySession(savedValue = false, occurrenceId = "advance-occurrence"))
        assertEquals(true, restoredDetailReadOnlySession(savedValue = null, occurrenceId = "advance-occurrence"))
    }
}
