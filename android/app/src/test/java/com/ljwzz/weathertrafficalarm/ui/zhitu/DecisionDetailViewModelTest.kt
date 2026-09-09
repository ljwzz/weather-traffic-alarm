package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionDetailLookup
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionDetailRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationWorkScheduler
import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class DecisionDetailViewModelTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var decisions: DecisionRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var viewModel: DecisionDetailViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(database.alarmPlanDao())
        decisions = DecisionRepository(database.alarmDecisionDao())
        occurrences = OccurrenceRepository(database.alarmOccurrenceDao())
        val overrides = PlanCommuteOverrideRepository(database.planCommuteOverrideDao())
        val scheduler = EvaluationWorkScheduler(
            context = context,
            plans = plans,
            occurrences = occurrences,
            settings = LocalSettingsStore(context),
            commuteOverrides = overrides,
            dayOverrides = WorkdayOverrideRepository(database.workdayOverrideDao()),
            calendar = WorkdayCalendarRepository(context),
            credentials = CredentialStore(context),
            commuteResolver = EffectiveCommuteResolver(overrides),
            clock = Clock.systemUTC(),
        )
        viewModel = DecisionDetailViewModel(
            DecisionDetailRepository(decisions, occurrences, plans), decisions, occurrences, plans, scheduler,
        )
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        database.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `open and refresh read only the anchored decision while newer records arrive`() = runBlocking {
        val plan = plans.save(plan())
        val anchored = decision("decision-a", plan)
        val newer = decision("decision-b", plan)
        assertTrue(decisions.save(anchored))
        occurrences.save(advance("advance-a", anchored))
        val plansBefore = plans.observeAllSnapshot()
        val decisionsBefore = decisions.observeAllSnapshot()
        val occurrencesBefore = occurrences.getAll()
        val workBefore = WorkManager.getInstance(context).getWorkInfosByTag(EvaluationWorkScheduler.ALL_WORK_TAG).get().map { it.id }

        viewModel.open(anchored.decisionId, null)
        await { (viewModel.lookup.value as? DecisionDetailLookup.DecisionFound)?.decision?.decisionId == anchored.decisionId }
        assertTrue(decisions.save(newer))
        viewModel.refresh()
        await { (viewModel.lookup.value as? DecisionDetailLookup.DecisionFound)?.decision?.decisionId == anchored.decisionId }

        assertEquals(plansBefore, plans.observeAllSnapshot())
        assertEquals(occurrencesBefore, occurrences.getAll())
        assertEquals(2, decisions.getByPlanId(plan.id).size)
        assertEquals(anchored.decisionId, (viewModel.lookup.value as DecisionDetailLookup.DecisionFound).decision.decisionId)
        assertEquals(workBefore, WorkManager.getInstance(context).getWorkInfosByTag(EvaluationWorkScheduler.ALL_WORK_TAG).get().map { it.id })
        // The only mutation above is the explicitly simulated newer persisted decision, not open/refresh.
        assertEquals(decisionsBefore.map { it.decisionId }.toSet() + newer.decisionId, decisions.getByPlanId(plan.id).map { it.decisionId }.toSet())
    }

    @Test
    fun `missing decision is an explicit local empty state without side effects`() = runBlocking {
        val plansBefore = plans.observeAllSnapshot()
        val occurrencesBefore = occurrences.getAll()
        val decisionsBefore = decisions.observeAllSnapshot()
        val workBefore = WorkManager.getInstance(context).getWorkInfosByTag(EvaluationWorkScheduler.ALL_WORK_TAG).get().map { it.id }

        viewModel.open("missing-decision", null)
        await { viewModel.lookup.value == DecisionDetailLookup.DecisionMissing("missing-decision") }

        assertEquals(plansBefore, plans.observeAllSnapshot())
        assertEquals(occurrencesBefore, occurrences.getAll())
        assertEquals(decisionsBefore, decisions.observeAllSnapshot())
        assertEquals(workBefore, WorkManager.getInstance(context).getWorkInfosByTag(EvaluationWorkScheduler.ALL_WORK_TAG).get().map { it.id })
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    private suspend fun AlarmPlanRepository.observeAllSnapshot() = observeAll().first()
    private suspend fun DecisionRepository.observeAllSnapshot() = observeAll().first()

    private fun plan() = AlarmPlan(
        id = "plan-a", revision = 0, name = "历史计划", enabled = true, zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "07:00", arrivalLocalTime = "09:00", preparationMinutes = 30,
        maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Workdays,
    )

    private fun decision(id: String, plan: AlarmPlan) = AlarmDecision(
        decisionId = id, planId = plan.id, planRevision = plan.revision, targetDate = "2026-09-08",
        workdayStatus = null, estimatedDepartureAt = null, commuteSeconds = null, weatherSeverity = 0,
        weatherBufferMinutes = 0, recommendedWakeAt = "2026-09-08T00:00:00Z", routeProvider = null,
        routeProviderReportTime = null, weatherProvider = null, weatherProviderReportTime = null,
        weatherWindowStart = null, weatherWindowEnd = null, fallbackReason = FallbackReason.NONE,
        insufficientAdvance = false, generatedAt = "2026-09-07T00:00:00Z", expiresAt = "2026-09-08T00:00:00Z",
        evaluationOutcome = EvaluationOutcome.FAILED, failureReason = "ROUTE_NETWORK", planName = "历史计划", zoneId = "Asia/Shanghai",
    )

    private fun advance(id: String, decision: AlarmDecision) = AlarmOccurrence(
        occurrenceId = id, planId = decision.planId, planRevision = decision.planRevision, targetDate = decision.targetDate,
        scheduledWakeAt = 1_788_825_600_000L, state = OccurrenceState.SCHEDULED, decisionId = decision.decisionId,
        kind = OccurrenceKind.ADVANCE,
    )
}
