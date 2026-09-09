package com.ljwzz.weathertrafficalarm.core.data.repository

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DecisionDetailRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var decisions: DecisionRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var details: DecisionDetailRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao())
        decisions = DecisionRepository(db.alarmDecisionDao())
        occurrences = OccurrenceRepository(db.alarmOccurrenceDao())
        details = DecisionDetailRepository(decisions, occurrences, plans)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `occurrence detail follows snooze ancestry instead of treating snooze time as a decision`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        val decision = decision("decision-a", "plan-a", revision = 1)
        assertTrue(decisions.save(decision))
        val advance = occurrence("advance-a", "plan-a", revision = 1, kind = OccurrenceKind.ADVANCE,
            decisionId = decision.decisionId, targetDate = decision.targetDate, wakeAt = 1_000L)
        val snooze = occurrence("snooze-a", "plan-a", revision = 2, kind = OccurrenceKind.SNOOZE,
            parentId = advance.occurrenceId, targetDate = "2026-09-09", wakeAt = 1_600L)
        occurrences.save(advance)
        occurrences.save(snooze)

        val result = details.resolveOccurrence(snooze.occurrenceId)

        assertTrue(result is DecisionDetailLookup.OccurrenceFound)
        result as DecisionDetailLookup.OccurrenceFound
        assertEquals(decision.decisionId, result.decision.decisionId)
        assertEquals(advance.occurrenceId, result.rootAdvance.occurrenceId)
        assertEquals(snooze.occurrenceId, result.occurrence.occurrenceId)
    }

    @Test
    fun `decision detail does not select a last occurrence when associations are ambiguous`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        val decision = decision("decision-a", "plan-a", revision = 1, actualWakeAt = null)
        assertTrue(decisions.save(decision))
        occurrences.save(occurrence("advance-early", "plan-a", 1, OccurrenceKind.ADVANCE, decision.decisionId, wakeAt = 1_000L))
        occurrences.save(occurrence("advance-late", "plan-a", 1, OccurrenceKind.ADVANCE, decision.decisionId, wakeAt = 2_000L))

        val result = details.resolveDecision(decision.decisionId)

        assertTrue(result is DecisionDetailLookup.DecisionFound)
        assertTrue((result as DecisionDetailLookup.DecisionFound).occurrenceAssociation is DecisionOccurrenceAssociation.Ambiguous)
    }

    @Test
    fun `deleted plan leaves historical decision and occurrence readable by exact decision id`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        val decision = decision("decision-a", "plan-a", revision = 1)
        assertTrue(decisions.save(decision))
        occurrences.save(occurrence("advance-a", "plan-a", 1, OccurrenceKind.ADVANCE, decision.decisionId))

        plans.deleteById("plan-a")
        val result = details.resolveDecision(decision.decisionId)

        assertTrue(result is DecisionDetailLookup.DecisionFound)
        result as DecisionDetailLookup.DecisionFound
        assertNull(result.plan)
        assertNotNull(decisions.getById(decision.decisionId))
        assertEquals(1, occurrences.getByPlanId("plan-a").size)
    }

    @Test
    fun `mismatched advance root returns unavailable instead of a different decision`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        val decision = decision("decision-a", "plan-a", revision = 1)
        assertTrue(decisions.save(decision))
        val invalid = occurrence("advance-a", "plan-a", 2, OccurrenceKind.ADVANCE, decision.decisionId)
        occurrences.save(invalid)

        val result = details.resolveOccurrence(invalid.occurrenceId)

        assertEquals(
            DecisionDetailLookup.OccurrenceUnavailable(invalid.occurrenceId, OccurrenceDetailUnavailableReason.ROOT_IDENTITY_MISMATCH),
            result,
        )
    }

    @Test
    fun `decision id selects its own plan and evaluation when several plans have several records`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        db.alarmPlanDao().upsert(plan("plan-b", revision = 1))
        val olderA = decision("decision-a-old", "plan-a", revision = 1, actualWakeAt = null)
        val expectedB = decision("decision-b", "plan-b", revision = 1, actualWakeAt = null)
        val newerA = decision("decision-a-new", "plan-a", revision = 1, actualWakeAt = null)
        assertTrue(decisions.save(olderA))
        assertTrue(decisions.save(expectedB))
        assertTrue(decisions.save(newerA))
        occurrences.save(occurrence("advance-a-old", "plan-a", 1, OccurrenceKind.ADVANCE, olderA.decisionId))
        occurrences.save(occurrence("advance-b", "plan-b", 1, OccurrenceKind.ADVANCE, expectedB.decisionId))
        occurrences.save(occurrence("advance-a-new", "plan-a", 1, OccurrenceKind.ADVANCE, newerA.decisionId))

        val result = details.resolveDecision(expectedB.decisionId)

        assertTrue(result is DecisionDetailLookup.DecisionFound)
        result as DecisionDetailLookup.DecisionFound
        assertEquals("decision-b", result.decision.decisionId)
        assertEquals("plan-b", result.decision.planId)
        assertEquals("advance-b", (result.occurrenceAssociation as DecisionOccurrenceAssociation.Exact).occurrence.occurrenceId)
    }

    @Test
    fun `regular occurrence cannot open a decision detail even if it carries a decision id`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        val decision = decision("decision-a", "plan-a", revision = 1)
        assertTrue(decisions.save(decision))
        val regular = occurrence("regular-a", "plan-a", 1, OccurrenceKind.REGULAR, decision.decisionId)
        occurrences.save(regular)

        val result = details.resolveOccurrence(regular.occurrenceId)

        assertEquals(
            DecisionDetailLookup.OccurrenceUnavailable(regular.occurrenceId, OccurrenceDetailUnavailableReason.UNRELATED_INSTANCE),
            result,
        )
    }

    @Test
    fun `multiple snooze descendants retain the original advance root across later plan revisions`() = runTest {
        db.alarmPlanDao().upsert(plan("plan-a", revision = 1))
        val decision = decision("decision-a", "plan-a", revision = 1)
        assertTrue(decisions.save(decision))
        val advance = occurrence("advance-a", "plan-a", 1, OccurrenceKind.ADVANCE, decision.decisionId)
        val firstSnooze = occurrence("snooze-1", "plan-a", 2, OccurrenceKind.SNOOZE, parentId = advance.occurrenceId,
            targetDate = "2026-09-09", wakeAt = 1_600L)
        val secondSnooze = occurrence("snooze-2", "plan-a", 3, OccurrenceKind.SNOOZE, parentId = firstSnooze.occurrenceId,
            targetDate = "2026-09-09", wakeAt = 2_200L)
        occurrences.save(advance)
        occurrences.save(firstSnooze)
        occurrences.save(secondSnooze)

        val result = details.resolveOccurrence(secondSnooze.occurrenceId)

        assertTrue(result is DecisionDetailLookup.OccurrenceFound)
        result as DecisionDetailLookup.OccurrenceFound
        assertEquals(advance.occurrenceId, result.rootAdvance.occurrenceId)
        assertEquals(secondSnooze.occurrenceId, result.occurrence.occurrenceId)
    }

    private fun plan(id: String, revision: Long) = AlarmPlanEntity(
        id = id,
        revision = revision,
        name = "通勤计划",
        enabled = true,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "07:00",
        arrivalLocalTime = "09:00",
        preparationMinutes = 30,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
        origin = null,
        destination = null,
        waypoints = emptyList(),
        routePolicy = RoutePolicy.DEFAULT,
        weatherRuleVersion = "v1",
        sound = AlarmSound(),
        vibration = VibrationPattern(),
        snoozeMinutes = 10,
        schedule = AlarmSchedule.Once("2026-09-08"),
        armedState = AlarmArmedState.SCHEDULED,
        scheduleError = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun decision(
        id: String,
        planId: String,
        revision: Long,
        actualWakeAt: String? = "1970-01-01T00:00:01Z",
    ) = AlarmDecision(
        decisionId = id,
        planId = planId,
        planRevision = revision,
        targetDate = "2026-09-08",
        workdayStatus = WorkdayStatus.WORKDAY,
        estimatedDepartureAt = "2026-09-08T00:00:00Z",
        commuteSeconds = 3_661L,
        weatherSeverity = 1,
        weatherBufferMinutes = 10,
        recommendedWakeAt = "1970-01-01T00:00:01Z",
        routeProvider = "AMAP_WEB",
        routeProviderReportTime = null,
        weatherProvider = "CAIYUN_V2_6",
        weatherProviderReportTime = null,
        weatherWindowStart = null,
        weatherWindowEnd = null,
        fallbackReason = FallbackReason.NONE,
        insufficientAdvance = false,
        generatedAt = "1970-01-01T00:00:00Z",
        expiresAt = "2099-01-01T00:00:00Z",
        evaluationOutcome = EvaluationOutcome.SUCCESS,
        defaultWakeAt = "1970-01-01T00:00:02Z",
        actualWakeAt = actualWakeAt,
        planName = "历史通勤计划",
        zoneId = "Asia/Shanghai",
    )

    private fun occurrence(
        id: String,
        planId: String,
        revision: Long,
        kind: OccurrenceKind,
        decisionId: String? = null,
        parentId: String? = null,
        targetDate: String = "2026-09-08",
        wakeAt: Long = 1_000L,
    ) = AlarmOccurrence(
        occurrenceId = id,
        planId = planId,
        planRevision = revision,
        targetDate = targetDate,
        scheduledWakeAt = wakeAt,
        state = OccurrenceState.SCHEDULED,
        decisionId = decisionId,
        kind = kind,
        parentOccurrenceId = parentId,
        updatedAt = wakeAt,
    )
}
