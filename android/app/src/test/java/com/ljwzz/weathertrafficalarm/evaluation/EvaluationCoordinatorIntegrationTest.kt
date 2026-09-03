package com.ljwzz.weathertrafficalarm.evaluation

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmSchedulingGateway
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverride
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteEstimate
import com.ljwzz.weathertrafficalarm.core.model.RouteProvider
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WeatherRules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class EvaluationCoordinatorIntegrationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.now()
    private val target = now.atZone(zone).toLocalDate().plusDays(1)
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var decisions: DecisionRepository
    private lateinit var overrides: PlanCommuteOverrideRepository
    private lateinit var coordinator: EvaluationCoordinator

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao())
        occurrences = OccurrenceRepository(db.alarmOccurrenceDao())
        decisions = DecisionRepository(db.alarmDecisionDao())
        overrides = PlanCommuteOverrideRepository(db.planCommuteOverrideDao())
        val settings = LocalSettingsStore(context)
        val calendar = WorkdayCalendarRepository(context)
        val snapshots = NextAlarmSnapshotStore(context).also { it.clear() }
        val events = AlarmEventRepository(db.alarmEventDao())
        val dayOverrides = WorkdayOverrideRepository(db.workdayOverrideDao())
        val alarm = LocalAlarmCoordinator(context, plans, occurrences, decisions, events, dayOverrides, calendar, FakeGateway(), snapshots)
        coordinator = EvaluationCoordinator(
            plans, settings, EffectiveCommuteResolver(overrides), dayOverrides, calendar,
            FakeRoute, FakeWeather(now), decisions, alarm, Clock.fixed(now, zone),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `successful evaluation adds advance and preserves regular occurrence`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "success")

        assertFalse(result.retryable)
        assertEquals(EvaluationOutcome.SUCCESS, result.decision?.evaluationOutcome)
        assertEquals("APPLIED", result.decision?.applicationOutcome)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertEquals(1, occurrences.getByPlanId(plan.id).count { it.kind == OccurrenceKind.ADVANCE && it.state == OccurrenceState.SCHEDULED })
    }

    @Test
    fun `retryable route failure records failure and leaves regular untouched`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        coordinator = coordinatorWith(FailingRoute(ProviderError.Category.NETWORK), FakeWeather(now))

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "route-network")

        assertTrue(result.retryable)
        assertEquals(EvaluationOutcome.FAILED, result.decision?.evaluationOutcome)
        assertEquals("ROUTE_NETWORK", result.decision?.failureReason)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    @Test
    fun `weather failure and an expired evaluation both preserve regular occurrence`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        coordinator = coordinatorWith(FakeRoute, FailingWeather(ProviderError.Category.INVALID_KEY))

        val weatherFailure = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "weather-key")
        val expired = coordinator.evaluate(plan.id, targetDate = target, deadline = now, evaluationId = "expired")

        assertFalse(weatherFailure.retryable)
        assertEquals("WEATHER_INVALID_KEY", weatherFailure.decision?.failureReason)
        assertEquals(EvaluationOutcome.STALE, expired.decision?.evaluationOutcome)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    @Test
    fun `plan disabled while provider is running is recorded stale and never applied`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        coordinator = coordinatorWith(FakeRoute, EditingWeather(now) { plans.disable(plan.id) })

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "disabled-during-run")

        assertEquals(EvaluationOutcome.STALE, result.decision?.evaluationOutcome)
        assertEquals("EVALUATION_INPUTS_CHANGED", result.decision?.failureReason)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    private suspend fun persistPlan(): AlarmPlan {
        val plan = AlarmPlan(
            id = "p", revision = 0, name = "通勤", enabled = true, zoneId = zone.id,
            defaultWakeLocalTime = "09:00", arrivalLocalTime = "10:00", preparationMinutes = 30,
            maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Once(target.toString()),
        )
        val saved = plans.save(plan)
        overrides.save(PlanCommuteOverride(saved.id, home, work, CommuteMode.DRIVING, now.toEpochMilli()))
        return saved
    }

    private suspend fun regular(plan: AlarmPlan): AlarmOccurrence {
        val wake = target.atTime(LocalTime.parse(plan.defaultWakeLocalTime)).atZone(zone).toInstant().toEpochMilli()
        return AlarmOccurrence("regular", plan.id, plan.revision, target.toString(), wake, OccurrenceState.SCHEDULED).also { occurrences.save(it) }
    }

    private fun coordinatorWith(route: RouteProvider, weather: WeatherProvider): EvaluationCoordinator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calendar = WorkdayCalendarRepository(context)
        val dayOverrides = WorkdayOverrideRepository(db.workdayOverrideDao())
        val alarm = LocalAlarmCoordinator(context, plans, occurrences, decisions, AlarmEventRepository(db.alarmEventDao()), dayOverrides, calendar, FakeGateway(), NextAlarmSnapshotStore(context))
        return EvaluationCoordinator(plans, LocalSettingsStore(context), EffectiveCommuteResolver(overrides), dayOverrides, calendar, route, weather, decisions, alarm, Clock.fixed(now, zone))
    }

    private object FakeRoute : RouteProvider {
        override suspend fun estimate(request: RouteRequest) = RouteEstimate(listOf(RouteAlternative("r", 3_600, 1_000, emptyList())))
    }
    private class FailingRoute(private val category: ProviderError.Category) : RouteProvider {
        override suspend fun estimate(request: RouteRequest): RouteEstimate = throw ProviderError(category, message = "failure")
    }
    private class FakeWeather(private val report: Instant) : WeatherProvider {
        override suspend fun evaluate(request: WeatherRequest): WeatherEvaluation {
            fun location(role: WeatherLocationRole) = WeatherLocationEvaluation(role, WeatherSeverity.MODERATE, report, request.window.start, request.window.end, WeatherDataSource.NETWORK)
            return WeatherRules.combine(location(WeatherLocationRole.HOME), location(WeatherLocationRole.WORK), request.weatherBufferProfile, request.weatherRuleVersion)
        }
    }
    private class FailingWeather(private val category: ProviderError.Category) : WeatherProvider {
        override suspend fun evaluate(request: WeatherRequest): WeatherEvaluation = throw ProviderError(category, message = "failure")
    }
    private class EditingWeather(private val report: Instant, private val edit: suspend () -> Unit) : WeatherProvider {
        override suspend fun evaluate(request: WeatherRequest): WeatherEvaluation {
            edit()
            fun location(role: WeatherLocationRole) = WeatherLocationEvaluation(role, WeatherSeverity.MODERATE, report, request.window.start, request.window.end, WeatherDataSource.NETWORK)
            return WeatherRules.combine(location(WeatherLocationRole.HOME), location(WeatherLocationRole.WORK), request.weatherBufferProfile, request.weatherRuleVersion)
        }
    }
    private class FakeGateway : AlarmSchedulingGateway {
        override suspend fun schedule(snapshot: com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot) = AlarmRegistrationResult.Registered
        override suspend fun restore(snapshot: com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot, nowMillis: Long) = AlarmRegistrationResult.Registered
        override suspend fun cancelOccurrence(occurrenceId: String) = Unit
        override fun canScheduleExactAlarms() = true
    }

    private companion object {
        val home = PlaceRef("home", "家", "北京", 116.3, 39.9, "110000", "010")
        val work = PlaceRef("work", "公司", "北京", 116.4, 39.9, "110000", "010")
    }
}
