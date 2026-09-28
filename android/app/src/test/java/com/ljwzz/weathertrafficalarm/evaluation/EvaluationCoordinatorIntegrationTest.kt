package com.ljwzz.weathertrafficalarm.evaluation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmSchedulingGateway
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
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
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteEstimate
import com.ljwzz.weathertrafficalarm.core.model.RouteProvider
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WeatherRules
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    private val now = Instant.parse("2026-09-05T04:00:00Z")
    private val target = LocalDate.of(2026, 9, 7)
    private val weekend = LocalDate.of(2026, 9, 6)
    private val holiday = LocalDate.of(2026, 9, 8)
    private val holidayWeekend = LocalDate.of(2026, 9, 12)
    private val clock = Clock.fixed(now, zone)
    private lateinit var context: Context
    private lateinit var fixtureDir: File
    private lateinit var settings: LocalSettingsStore
    private lateinit var previousSettings: LocalSettings
    private lateinit var calendar: WorkdayCalendarRepository
    private lateinit var snapshots: NextAlarmSnapshotStore
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var decisions: DecisionRepository
    private lateinit var overrides: PlanCommuteOverrideRepository
    private lateinit var coordinator: EvaluationCoordinator
    private lateinit var diagnostics: RedactingEventLogger

    @Before
    fun setUp() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        fixtureDir = File(base.cacheDir, "evaluation-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(fixtureDir, "files").apply { mkdirs() }
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        File(context.filesDir, "holiday-calendar/2026.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"year":2026,"papers":["https://example.test/fixture"],"days":[{"name":"fixture workday","date":"$target","isOffDay":false},{"name":"fixture holiday","date":"$holiday","isOffDay":true},{"name":"fixture holiday weekend","date":"$holidayWeekend","isOffDay":true}]}""")
        }
        diagnostics = RedactingEventLogger(context).also { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao())
        occurrences = OccurrenceRepository(db.alarmOccurrenceDao())
        decisions = DecisionRepository(db.alarmDecisionDao())
        overrides = PlanCommuteOverrideRepository(db.planCommuteOverrideDao())
        settings = LocalSettingsStore(context)
        previousSettings = settings.loadInitial()
        settings.update { LocalSettings() }
        calendar = WorkdayCalendarRepository(context, clock)
        snapshots = NextAlarmSnapshotStore(context).also { it.clear() }
        val events = AlarmEventRepository(db.alarmEventDao())
        val dayOverrides = WorkdayOverrideRepository(db.workdayOverrideDao())
        val alarm = LocalAlarmCoordinator(context, plans, occurrences, decisions, events, dayOverrides, calendar, FakeGateway(), snapshots, clock = clock)
        coordinator = EvaluationCoordinator(
            plans, settings, EffectiveCommuteResolver(overrides), dayOverrides, calendar,
            FakeRoute, FakeWeather(now), decisions, alarm, clock, diagnostics,
        )
        assertEquals(mapOf(target.toString() to DayStatus.WORKDAY, holiday.toString() to DayStatus.HOLIDAY, holidayWeekend.toString() to DayStatus.HOLIDAY), calendar.statuses())
        assertFalse(calendar.statuses().containsKey(weekend.toString()))
        assertEquals(LocalSettings(), settings.loadInitial())
    }

    @After
    fun tearDown() = runBlocking {
        db.close()
        snapshots.clear()
        settings.update { previousSettings }
        fixtureDir.deleteRecursively()
        Unit
    }

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
        val diagnostic = diagnostics.recentEvents().single { it.eventType == DiagnosticEventType.EVALUATION }
        assertEquals(DiagnosticResultCode.SUCCESS, diagnostic.resultCode)
        assertEquals(now.toEpochMilli(), diagnostic.timestamp)
        assertTrue(requireNotNull(diagnostic.durationMs) >= 0)
        assertTrue(diagnostic.planIdHash != null && diagnostic.planIdHash != plan.id)
        assertFalse(diagnostic.toString().contains(home.displayAddress))
    }

    @Test
    fun `driving fallback queries once and uses the latest fifteen minute candidate`() = runBlocking {
        val plan = persistPlan(weekend)
        val regular = regular(plan, weekend)
        val route = RecordingRoute(listOf(RouteAlternative("r", 47 * 60L, 1_000, emptyList())))
        val weather = RecordingWeather(now)
        coordinator = coordinatorWith(route, weather)

        val result = coordinator.evaluate(plan.id, targetDate = weekend, evaluationId = "driving-grid")

        val expectedDeparture = weekend.atTime(9, 0).atZone(zone).toInstant()
        val expectedWake = weekend.atTime(8, 20).atZone(zone).toInstant().toEpochMilli()
        assertEquals(EvaluationOutcome.SUCCESS, result.decision?.evaluationOutcome)
        assertEquals(FallbackReason.CURRENT_TRAFFIC_FALLBACK, result.decision?.fallbackReason)
        assertEquals(expectedDeparture.toString(), result.decision?.estimatedDepartureAt)
        assertEquals(60 * 60L, result.decision?.commuteSeconds)
        assertEquals(10, result.decision?.weatherBufferMinutes)
        assertEquals(10, weather.lastProfile?.moderateMinutes)
        assertEquals(expectedWake, result.decision?.recommendedWakeAt?.let(Instant::parse)?.toEpochMilli())
        assertEquals(1, route.requests.size)
        assertEquals(null, route.requests.single().departureAt)
        assertEquals(1, weather.requests)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        val advance = occurrences.getByPlanId(plan.id).single { it.kind == OccurrenceKind.ADVANCE && it.state == OccurrenceState.SCHEDULED }
        assertEquals(expectedWake, advance.scheduledWakeAt)
    }

    @Test
    fun `workday profile schedules at 0810`() = runBlocking {
        assertProfile(target, 20, LocalTime.of(8, 10))
    }

    @Test
    fun `official holiday profile schedules at 0815`() = runBlocking {
        assertProfile(holiday, 15, LocalTime.of(8, 15))
    }

    @Test
    fun `official holiday on weekend takes holiday profile`() = runBlocking {
        assertProfile(holidayWeekend, 15, LocalTime.of(8, 15))
    }

    @Test
    fun `custom workday profile changes both decision and schedule`() = runBlocking {
        settings.update { it.copy(workdayWeatherBuffers = WeatherBuffers(1, 23, 30)) }
        assertProfile(target, 23, LocalTime.of(8, 7))
    }

    @Test
    fun `exact fifteen minute duration selects boundary candidate`() = runBlocking {
        val plan = persistPlan()
        regular(plan)
        val route = RecordingRoute(listOf(RouteAlternative("r", 45 * 60L, 1_000, emptyList())))
        coordinator = coordinatorWith(route, RecordingWeather(now))

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "exact-grid")

        assertEquals(target.atTime(9, 15).atZone(zone).toInstant().toString(), result.decision?.estimatedDepartureAt)
        assertEquals(45 * 60L, result.decision?.commuteSeconds)
        assertEquals(target.atTime(8, 25).atZone(zone).toInstant().toString(), result.decision?.recommendedWakeAt)
        assertEquals(1, route.requests.size)
    }

    @Test
    fun `cross midnight candidate is clamped to maximum advance`() = runBlocking {
        val plan = persistPlan(arrival = "01:00", defaultWake = "00:30")
        val regular = regular(plan)
        val route = RecordingRoute(listOf(RouteAlternative("r", 180 * 60L, 1_000, emptyList())))
        coordinator = coordinatorWith(route, RecordingWeather(now))

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "cross-midnight")

        val expectedWake = target.minusDays(1).atTime(23, 30).atZone(zone).toInstant()
        assertEquals(target.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toString(), result.decision?.estimatedDepartureAt)
        assertEquals(expectedWake.toString(), result.decision?.recommendedWakeAt)
        assertEquals(expectedWake.toString(), result.decision?.actualWakeAt)
        assertTrue(result.decision?.insufficientAdvance == true)
        assertEquals("APPLIED", result.decision?.applicationOutcome)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertEquals(expectedWake.toEpochMilli(), occurrences.getByPlanId(plan.id).single { it.kind == OccurrenceKind.ADVANCE }.scheduledWakeAt)
        assertEquals(1, route.requests.size)
    }

    @Test
    fun `driving fallback includes the arrival minus 180 minute candidate`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        val route = RecordingRoute(listOf(RouteAlternative("r", 180 * 60L, 1_000, emptyList())))
        coordinator = coordinatorWith(route, RecordingWeather(now))

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "driving-180-minute-boundary")

        assertEquals(EvaluationOutcome.SUCCESS, result.decision?.evaluationOutcome)
        assertEquals(target.atTime(7, 0).atZone(zone).toInstant().toString(), result.decision?.estimatedDepartureAt)
        assertEquals(180 * 60L, result.decision?.commuteSeconds)
        assertTrue(result.decision?.insufficientAdvance == true)
        assertEquals(1, route.requests.size)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
    }

    @Test
    fun `driving fallback rejects duration beyond 180 minutes without evaluating weather`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        val route = RecordingRoute(listOf(RouteAlternative("r", 180 * 60L + 1, 1_000, emptyList())))
        val weather = RecordingWeather(now)
        coordinator = coordinatorWith(route, weather)

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "driving-over-horizon")

        assertFalse(result.retryable)
        assertEquals(EvaluationOutcome.FAILED, result.decision?.evaluationOutcome)
        assertEquals("ROUTE_ROUTE_NOT_FOUND", result.decision?.failureReason)
        assertEquals(FallbackReason.ROUTE_NOT_FOUND, result.decision?.fallbackReason)
        assertEquals(1, route.requests.size)
        assertEquals(0, weather.requests)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    @Test
    fun `driving fallback rounds route seconds up to the next candidate without querying again`() = runBlocking {
        val plan = persistPlan()
        regular(plan)
        val route = RecordingRoute(listOf(RouteAlternative("r", 45 * 60L + 1, 1_000, emptyList())))
        coordinator = coordinatorWith(route, RecordingWeather(now))

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "driving-second-rounding")

        assertEquals(EvaluationOutcome.SUCCESS, result.decision?.evaluationOutcome)
        assertEquals(target.atTime(9, 0).atZone(zone).toInstant().toString(), result.decision?.estimatedDepartureAt)
        assertEquals(60 * 60L, result.decision?.commuteSeconds)
        assertEquals(1, route.requests.size)
    }

    @Test
    fun `driving fallback reports no route without evaluating weather when estimate is empty`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        val route = RecordingRoute(emptyList())
        val weather = RecordingWeather(now)
        coordinator = coordinatorWith(route, weather)

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "driving-empty-route")

        assertEquals(EvaluationOutcome.FAILED, result.decision?.evaluationOutcome)
        assertEquals("ROUTE_ROUTE_NOT_FOUND", result.decision?.failureReason)
        assertEquals(1, route.requests.size)
        assertEquals(0, weather.requests)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
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
        assertEquals(DiagnosticResultCode.FAILED,
            diagnostics.recentEvents().single { it.eventType == DiagnosticEventType.EVALUATION }.resultCode)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    @Test
    fun `failed evaluation records no application and preserves an earlier advance`() = runBlocking {
        val plan = persistPlan()
        regular(plan)
        val successful = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "before-failure")
        val before = occurrences.getByPlanId(plan.id)
        assertTrue(before.any { it.kind == OccurrenceKind.ADVANCE && it.state == OccurrenceState.SCHEDULED })
        coordinator = coordinatorWith(FailingRoute(ProviderError.Category.NETWORK), FakeWeather(now))

        val failed = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "later-failure")

        assertEquals(EvaluationOutcome.FAILED, failed.decision?.evaluationOutcome)
        assertEquals("NOT_APPLIED", failed.decision?.applicationOutcome)
        assertEquals(before, occurrences.getByPlanId(plan.id))
        assertEquals(successful.decision, decisions.getById(requireNotNull(successful.decision).decisionId))
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
        assertEquals(listOf(DiagnosticResultCode.FAILED, DiagnosticResultCode.STALE),
            diagnostics.recentEvents().filter { it.eventType == DiagnosticEventType.EVALUATION }.map { it.resultCode })
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

    @Test
    fun `settings changed while provider is running never apply advance`() = runBlocking {
        val plan = persistPlan()
        val regular = regular(plan)
        coordinator = coordinatorWith(FakeRoute, EditingWeather(now) {
            settings.update { it.copy(workdayWeatherBuffers = WeatherBuffers(1, 22, 30)) }
        })

        val result = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "settings-during-run")

        assertEquals(EvaluationOutcome.STALE, result.decision?.evaluationOutcome)
        assertEquals("EVALUATION_INPUTS_CHANGED", result.decision?.failureReason)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        assertTrue(occurrences.getByPlanId(plan.id).none { it.kind == OccurrenceKind.ADVANCE })
    }

    @Test
    fun `input change and expiry preserve an existing advance`() = runBlocking {
        val plan = persistPlan()
        regular(plan)
        val first = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "before-stale")
        assertEquals("APPLIED", first.decision?.applicationOutcome)
        val before = occurrences.getByPlanId(plan.id)
        coordinator = coordinatorWith(FakeRoute, EditingWeather(now) {
            settings.update { it.copy(workdayWeatherBuffers = WeatherBuffers(1, 22, 30)) }
        })

        val changed = coordinator.evaluate(plan.id, targetDate = target, evaluationId = "inputs-stale")
        val expired = coordinator.evaluate(plan.id, targetDate = target, deadline = now, evaluationId = "deadline-stale")

        assertEquals(EvaluationOutcome.STALE, changed.decision?.evaluationOutcome)
        assertEquals("EVALUATION_INPUTS_CHANGED", changed.decision?.failureReason)
        assertEquals(EvaluationOutcome.STALE, expired.decision?.evaluationOutcome)
        assertEquals(before, occurrences.getByPlanId(plan.id))
    }

    @Test
    fun `inapplicable and missing plans record skipped without calling providers`() = runBlocking {
        val plan = persistPlan()
        val route = RecordingRoute(emptyList())
        val weather = RecordingWeather(now)
        coordinator = coordinatorWith(route, weather)

        coordinator.evaluate(plan.id, targetDate = target.plusDays(1))
        coordinator.evaluate("missing-plan")

        assertEquals(listOf(DiagnosticResultCode.SKIPPED, DiagnosticResultCode.SKIPPED),
            diagnostics.recentEvents().filter { it.eventType == DiagnosticEventType.EVALUATION }.map { it.resultCode })
        assertTrue(route.requests.isEmpty())
        assertEquals(0, weather.requests)
    }

    @Test
    fun `cancelled evaluation records cancellation and propagates without a decision`() = runBlocking {
        val plan = persistPlan()
        coordinator = coordinatorWith(object : RouteProvider {
            override suspend fun estimate(request: RouteRequest): RouteEstimate =
                throw CancellationException("secret=short-secret content://private/ringtone 北京 116.3")
        }, FakeWeather(now))

        try {
            coordinator.evaluate(plan.id, targetDate = target)
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            val event = diagnostics.recentEvents().single { it.eventType == DiagnosticEventType.EVALUATION }
            assertEquals(DiagnosticResultCode.CANCELLED, event.resultCode)
            assertFalse(event.toString().contains("short-secret"))
            assertFalse(event.toString().contains("content://"))
            assertTrue(occurrences.getByPlanId(plan.id).isEmpty())
        }
    }

    private suspend fun assertProfile(date: LocalDate, buffer: Int, wake: LocalTime) {
        val plan = persistPlan(date)
        val regular = regular(plan, date)
        val route = RecordingRoute(listOf(RouteAlternative("r", 47 * 60L, 1_000, emptyList())))
        val weather = RecordingWeather(now)
        coordinator = coordinatorWith(route, weather)

        val result = coordinator.evaluate(plan.id, targetDate = date, evaluationId = "profile-$date")

        val expectedDeparture = date.atTime(9, 0).atZone(zone).toInstant().toString()
        val expectedWake = date.atTime(wake).atZone(zone).toInstant()
        assertEquals(EvaluationOutcome.SUCCESS, result.decision?.evaluationOutcome)
        assertEquals("APPLIED", result.decision?.applicationOutcome)
        assertEquals(if (date == target) WorkdayStatus.WORKDAY else WorkdayStatus.HOLIDAY, result.decision?.workdayStatus)
        assertEquals(FallbackReason.CURRENT_TRAFFIC_FALLBACK, result.decision?.fallbackReason)
        assertEquals(expectedDeparture, result.decision?.estimatedDepartureAt)
        assertEquals(60 * 60L, result.decision?.commuteSeconds)
        assertEquals(buffer, weather.lastProfile?.moderateMinutes)
        assertEquals(buffer, result.decision?.weatherBufferMinutes)
        assertEquals(expectedWake.toString(), result.decision?.recommendedWakeAt)
        assertEquals(expectedWake.toString(), result.decision?.actualWakeAt)
        assertEquals(1, route.requests.size)
        assertEquals(1, weather.requests)
        assertEquals(OccurrenceState.SCHEDULED, occurrences.getById(regular.occurrenceId)?.state)
        val advance = occurrences.getByPlanId(plan.id).single { it.kind == OccurrenceKind.ADVANCE && it.state == OccurrenceState.SCHEDULED }
        assertEquals(expectedWake.toEpochMilli(), advance.scheduledWakeAt)
    }

    private suspend fun persistPlan(date: LocalDate = target, arrival: String = "10:00", defaultWake: String = "09:00"): AlarmPlan {
        val plan = AlarmPlan(
            id = "p", revision = 0, name = "通勤", enabled = true, zoneId = zone.id,
            defaultWakeLocalTime = defaultWake, arrivalLocalTime = arrival, preparationMinutes = 30,
            maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Once(date.toString()),
        )
        val saved = plans.save(plan)
        overrides.save(PlanCommuteOverride(saved.id, home, work, CommuteMode.DRIVING, now.toEpochMilli()))
        return saved
    }

    private suspend fun regular(plan: AlarmPlan, date: LocalDate = target): AlarmOccurrence {
        val wake = date.atTime(LocalTime.parse(plan.defaultWakeLocalTime)).atZone(zone).toInstant().toEpochMilli()
        return AlarmOccurrence("regular", plan.id, plan.revision, date.toString(), wake, OccurrenceState.SCHEDULED).also { occurrences.save(it) }
    }

    private fun coordinatorWith(route: RouteProvider, weather: WeatherProvider): EvaluationCoordinator {
        val dayOverrides = WorkdayOverrideRepository(db.workdayOverrideDao())
        val alarm = LocalAlarmCoordinator(context, plans, occurrences, decisions, AlarmEventRepository(db.alarmEventDao()), dayOverrides, calendar, FakeGateway(), snapshots, clock = clock)
        return EvaluationCoordinator(plans, settings, EffectiveCommuteResolver(overrides), dayOverrides, calendar, route, weather, decisions, alarm, clock, diagnostics)
    }

    private object FakeRoute : RouteProvider {
        override suspend fun estimate(request: RouteRequest) = RouteEstimate(listOf(RouteAlternative("r", 3_600, 1_000, emptyList())))
    }
    private class FailingRoute(private val category: ProviderError.Category) : RouteProvider {
        override suspend fun estimate(request: RouteRequest): RouteEstimate = throw ProviderError(category, message = "failure")
    }
    private class RecordingRoute(private val alternatives: List<RouteAlternative>) : RouteProvider {
        val requests = mutableListOf<RouteRequest>()

        override suspend fun estimate(request: RouteRequest): RouteEstimate {
            requests += request
            return RouteEstimate(alternatives)
        }
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
    private class RecordingWeather(private val report: Instant) : WeatherProvider {
        var requests = 0
            private set
        var lastProfile: WeatherBufferProfile? = null
            private set

        override suspend fun evaluate(request: WeatherRequest): WeatherEvaluation {
            requests += 1
            lastProfile = request.weatherBufferProfile
            fun location(role: WeatherLocationRole) = WeatherLocationEvaluation(role, WeatherSeverity.MODERATE, report, request.window.start, request.window.end, WeatherDataSource.NETWORK)
            return WeatherRules.combine(location(WeatherLocationRole.HOME), location(WeatherLocationRole.WORK), request.weatherBufferProfile, request.weatherRuleVersion)
        }
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
