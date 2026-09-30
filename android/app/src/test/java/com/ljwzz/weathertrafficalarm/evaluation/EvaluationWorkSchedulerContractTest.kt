package com.ljwzz.weathertrafficalarm.evaluation

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputs
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverride
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideChange
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.CommuteResolution
import com.ljwzz.weathertrafficalarm.core.model.CommuteSource
import com.ljwzz.weathertrafficalarm.core.model.DailySettingsResolver
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class EvaluationWorkSchedulerContractTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var decisions: DecisionRepository
    private lateinit var overrides: PlanCommuteOverrideRepository
    private lateinit var settings: LocalSettingsStore
    private lateinit var credentials: CredentialStore
    private lateinit var scheduler: EvaluationWorkScheduler

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(HoldingWorkerFactory()).build(),
        )
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(database.alarmPlanDao())
        occurrences = OccurrenceRepository(database.alarmOccurrenceDao())
        decisions = DecisionRepository(database.alarmDecisionDao())
        overrides = PlanCommuteOverrideRepository(database.planCommuteOverrideDao())
        settings = LocalSettingsStore(context)
        credentials = CredentialStore(context)
        settings.update { it.copy(amapConsentGranted = true) }
        val dayOverrides = WorkdayOverrideRepository(database.workdayOverrideDao(), database.dayOverrideCommitDao())
        val calendar = WorkdayCalendarRepository(context)
        val dailyInputs = DailyEvaluationInputResolver(plans, settings, EffectiveCommuteResolver(overrides), dayOverrides, calendar)
        scheduler = EvaluationWorkScheduler(
            context, plans, occurrences, settings, overrides,
            dayOverrides, calendar, credentials, dailyInputs, Clock.systemUTC(),
        )
        scheduler.credentialStatusReaderForTest = {
            CredentialStatus(amapWebKeyMask = "****", caiyunAppKeyMask = "****", caiyunSecretMask = "****", loaded = true)
        }
    }

    @After
    fun tearDown() = runBlocking {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        database.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** Resolved day inputs for the manual-validation cases, independent of the Room graph. */
    private fun inputs(
        plan: AlarmPlan = plan(),
        settings: LocalSettings = LocalSettings(amapConsentGranted = true),
        hasCommute: Boolean = true,
        effectiveWake: String? = null,
    ): DailyEvaluationInputs {
        val date = LocalDate.parse("2026-09-07")
        val home = PlaceRef("h", "家", "北京", 116.3, 39.9, "110000", "010")
        val work = PlaceRef("w", "公司", "北京", 116.4, 39.9, "110000", "010")
        val commute = if (hasCommute) CommuteResolution(home, work, CommuteMode.DRIVING, CommuteSource.GLOBAL) else null
        val resolved = DailySettingsResolver.resolve(plan, date, null)
        val effective = effectiveWake?.let { resolved.copy(defaultWakeLocalTime = it) } ?: resolved
        return DailyEvaluationInputs(
            plan = plan,
            date = date,
            settings = settings,
            override = null,
            committedRevision = 0,
            calendarDays = emptyMap(),
            inherited = effective,
            effective = effective,
            inheritedCommute = commute,
            effectiveCommute = commute,
        )
    }

    @Test
    fun `manual evaluation validates current prerequisites with precise reasons`() {
        val validCredentials = CredentialStatus(
            amapWebKeyMask = "****", caiyunAppKeyMask = "****", caiyunSecretMask = "****", loaded = true,
        )
        val validSettings = LocalSettings(amapConsentGranted = true)

        assertNull(validateManualEvaluation(plan(), inputs(settings = validSettings), validCredentials))
        assertEquals(
            EvaluateNowRejection.INVALID_SCHEDULE,
            validateManualEvaluation(plan(schedule = null), inputs(settings = validSettings), validCredentials),
        )
        assertEquals(
            EvaluateNowRejection.INVALID_SCHEDULE,
            validateManualEvaluation(
                plan(schedule = AlarmSchedule.Once("2020-01-01")), inputs(settings = validSettings), validCredentials,
                now = Instant.parse("2026-09-07T00:00:00Z"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { AlarmSchedule.Weekly(emptySet()) }
        assertEquals(
            EvaluateNowRejection.INVALID_TIME,
            validateManualEvaluation(plan(), inputs(settings = validSettings, effectiveWake = "invalid"), validCredentials),
        )
        assertEquals(
            EvaluateNowRejection.COMMUTE_NOT_CONFIGURED,
            validateManualEvaluation(plan(), inputs(settings = validSettings, hasCommute = false), validCredentials),
        )
        assertEquals(
            EvaluateNowRejection.AMAP_CONSENT_REQUIRED,
            validateManualEvaluation(plan(), inputs(settings = LocalSettings()), validCredentials),
        )
        assertEquals(
            EvaluateNowRejection.CREDENTIAL_STORAGE_ERROR,
            validateManualEvaluation(plan(), inputs(settings = validSettings), validCredentials.copy(storageError = true)),
        )
        assertEquals(
            EvaluateNowRejection.AMAP_WEB_KEY_MISSING,
            validateManualEvaluation(plan(), inputs(settings = validSettings), validCredentials.copy(amapWebKeyMask = null)),
        )
        assertEquals(
            EvaluateNowRejection.CAIYUN_CREDENTIALS_MISSING,
            validateManualEvaluation(plan(), inputs(settings = validSettings), validCredentials.copy(caiyunSecretMask = null)),
        )
    }

    @Test
    fun `persisted work tags retain the retry association independent of attempt text`() {
        val run = EvaluationWorkRun(
            targetDate = LocalDate.of(2026, 9, 8),
            notBefore = Instant.parse("2026-09-07T12:00:00Z"),
            deadline = Instant.parse("2026-09-07T15:30:00Z"),
            attempt = 2,
            origin = "manual",
            revision = 7,
            zoneId = "Asia/Shanghai",
            decisionId = "decision-1",
        )

        assertEquals(run, EvaluationWorkRun.fromTags(run.tags()))
        val state = EvaluationTaskState(
            phase = "RETRYING",
            nextAttemptAt = run.notBefore.toEpochMilli(),
            attemptNumber = run.attempt,
            targetDate = run.targetDate.toString(),
            planRevision = run.revision,
            origin = run.origin,
            decisionId = run.decisionId,
            workId = "work-2",
        )
        assertEquals("2026-09-08", state.targetDate)
        assertEquals(7L, state.planRevision)
        assertEquals("decision-1", state.decisionId)
        assertEquals("work-2", state.workId)
        assertTrue(state.nextAttemptAt != null)
    }

    @Test
    fun `running work has no invented retry timestamp`() {
        val state = EvaluationTaskState(phase = "RUNNING", attemptNumber = 1)

        assertNull(state.nextAttemptAt)
    }

    @Test
    fun `duplicate manual evaluation retains active work and terminal work permits a new request`() = runBlocking {
        val storedPlan = plans.save(futurePlan("scheduler-duplicate"))
        overrides.save(validCommute(storedPlan.id))
        occurrences.save(regularOccurrence(storedPlan))
        val historic = historicDecision(storedPlan)
        assertTrue(decisions.save(historic))

        val first = scheduler.evaluateNow(storedPlan.id) as EvaluateNowResult.Enqueued
        val active = requireNotNull(WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(first.workId)).get())
        assertTrue(!active.state.isFinished)
        val duplicate = scheduler.evaluateNow(storedPlan.id) as EvaluateNowResult.AlreadyQueued
        assertEquals(first.workId, duplicate.workId)
        assertEquals(first.targetDate, duplicate.targetDate)

        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(first.workId)).result.get()
        val afterTerminal = scheduler.evaluateNow(storedPlan.id) as EvaluateNowResult.Enqueued
        assertNotEquals(first.workId, afterTerminal.workId)
        val persistedHistoric = requireNotNull(decisions.getById(historic.decisionId))
        assertEquals(historic.decisionId, persistedHistoric.decisionId)
        assertEquals(historic.failureReason, persistedHistoric.failureReason)
        assertEquals(1, decisions.getByPlanId(storedPlan.id).size)
    }

    @Test
    fun `manual evaluation rejects a valid plan without a current revision future regular occurrence`() = runBlocking {
        val storedPlan = plans.save(futurePlan("scheduler-no-upcoming"))
        overrides.save(validCommute(storedPlan.id))

        assertEquals(
            EvaluateNowResult.Rejected(EvaluateNowRejection.NO_UPCOMING_OCCURRENCE),
            scheduler.evaluateNow(storedPlan.id),
        )
    }

    // --- N004 fixes: day-level inputs, date generations and replacement -----------------------

    /** F6a: a complete day-level commute alone must allow the manual evaluation. */
    @Test
    fun `manual evaluation accepts a day-only commute`() = runBlocking {
        val storedPlan = plans.save(futurePlan("day-commute-only"))
        val regular = regularOccurrence(storedPlan)
        occurrences.save(regular)
        val dayRepository = WorkdayOverrideRepository(database.workdayOverrideDao(), database.dayOverrideCommitDao())
        val commute = validCommute(storedPlan.id)
        dayRepository.commitCurrent(
            planId = storedPlan.id,
            date = regular.targetDate,
            replacement = SingleDayOverride(
                planId = storedPlan.id,
                date = regular.targetDate,
                origin = commute.origin,
                destination = commute.destination,
                commuteMode = commute.commuteMode,
            ),
            now = Instant.now().toEpochMilli(),
        )

        val result = scheduler.evaluateNow(storedPlan.id)

        assertTrue("day commute is a complete effective commute, result=$result", result is EvaluateNowResult.Enqueued)
    }

    /** F6b: the manual deadline follows the effective instance, not the plan default. */
    @Test
    fun `manual deadline uses the effective day wake`() = runBlocking {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-10-01T06:00:00Z")
        val fixedClock = Clock.fixed(now, zone)
        val baseWake = now.minusSeconds(3_600L).atZone(zone)
        val dayWake = now.plusSeconds(3 * 3_600L).atZone(zone)
        val storedPlan = plans.save(
            AlarmPlan(
                id = "day-wake", revision = 0, name = "验收", enabled = true, zoneId = zone.id,
                defaultWakeLocalTime = baseWake.toLocalTime().withNano(0).toString(),
                arrivalLocalTime = dayWake.toLocalTime().plusMinutes(30).withNano(0).toString(),
                preparationMinutes = 30, maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING,
                schedule = AlarmSchedule.Once(baseWake.toLocalDate().toString()),
            ),
        )
        overrides.save(validCommute(storedPlan.id))
        occurrences.save(
            AlarmOccurrence(
                occurrenceId = "regular-day-wake",
                planId = storedPlan.id,
                planRevision = storedPlan.revision,
                targetDate = baseWake.toLocalDate().toString(),
                scheduledWakeAt = dayWake.toInstant().toEpochMilli(),
                state = OccurrenceState.SCHEDULED,
                kind = OccurrenceKind.REGULAR,
            ),
        )
        val dayRepository = WorkdayOverrideRepository(database.workdayOverrideDao(), database.dayOverrideCommitDao())
        dayRepository.commitCurrent(
            planId = storedPlan.id,
            date = baseWake.toLocalDate().toString(),
            replacement = SingleDayOverride(
                planId = storedPlan.id,
                date = baseWake.toLocalDate().toString(),
                wakeLocalTime = dayWake.toLocalTime().withNano(0).toString(),
            ),
            now = now.toEpochMilli(),
        )
        val fixedScheduler = schedulerWith(fixedClock, dayRepository)

        val result = fixedScheduler.evaluateNow(storedPlan.id)

        assertTrue("a future day wake must permit the evaluation, result=$result", result is EvaluateNowResult.Enqueued)
    }

    /**
     * F8: the real observer must replace the changed date's generation while leaving the other
     * date's effective work in place. The earlier probe cancelled the work by hand.
     */
    @Test
    fun `a single-day change replaces that date generation and keeps the others`() = runBlocking {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-10-01T19:30:00Z")
        val fixedClock = Clock.fixed(now, zone)
        val storedPlan = plans.save(
            futurePlan("night-replacement").copy(
                zoneId = zone.id,
                schedule = AlarmSchedule.Workdays,
                defaultWakeLocalTime = "06:30",
                arrivalLocalTime = "09:00",
            ),
        )
        overrides.save(validCommute(storedPlan.id))
        val dayRepository = WorkdayOverrideRepository(database.workdayOverrideDao(), database.dayOverrideCommitDao())
        val fixedScheduler = schedulerWith(fixedClock, dayRepository)
        fixedScheduler.start()
        val manager = WorkManager.getInstance(context)
        val nightlyTag = "evaluation-night:${storedPlan.id}"

        withTimeout(20_000) {
            while (manager.getWorkInfosByTag(nightlyTag).get().count { !it.state.isFinished } < 2) delay(50)
        }
        val before = manager.getWorkInfosByTag(nightlyTag).get().filter { !it.state.isFinished }
        val changedTarget = before
            .map { info -> info.tags.first { it.startsWith("target:") }.substringAfter(':') }
            .minOrNull()
            ?: error("nightly work must carry its target date")
        val untouched = before.filterNot { it.tags.contains("target:$changedTarget") }
        assertTrue("the other date must have its own work", untouched.isNotEmpty())

        dayRepository.commitCurrent(
            planId = storedPlan.id,
            date = changedTarget,
            replacement = SingleDayOverride(storedPlan.id, changedTarget, preparationMinutes = 45),
            now = fixedClock.millis(),
        )

        withTimeout(20_000) {
            while (manager.getWorkInfosByTag(nightlyTag).get()
                    .none { !it.state.isFinished && it.tags.contains("day-revision:1") }
            ) {
                delay(50)
            }
        }
        val after = manager.getWorkInfosByTag(nightlyTag).get()
        assertTrue(
            "the changed date needs a new generation: ${after.map { it.state to it.tags.filter { tag -> tag.startsWith("day-revision") } }}",
            after.any { !it.state.isFinished && it.tags.contains("day-revision:1") },
        )
        untouched.forEach { original ->
            assertTrue(
                "an unaffected date keeps its work",
                after.any { it.id == original.id && !it.state.isFinished },
            )
        }
    }

    /** Scheduler sharing the test graph but with an explicit clock. */
    private fun schedulerWith(
        clock: Clock,
        dayRepository: WorkdayOverrideRepository,
    ): EvaluationWorkScheduler {
        val calendar = WorkdayCalendarRepository(context)
        return EvaluationWorkScheduler(
            context = context,
            plans = plans,
            occurrences = occurrences,
            settings = settings,
            commuteOverrides = overrides,
            dayOverrides = dayRepository,
            calendar = calendar,
            credentials = credentials,
            dailyInputs = DailyEvaluationInputResolver(plans, settings, EffectiveCommuteResolver(overrides), dayRepository, calendar),
            clock = clock,
        ).also { fixed ->
            fixed.credentialStatusReaderForTest = {
                CredentialStatus(amapWebKeyMask = "****", caiyunAppKeyMask = "****", caiyunSecretMask = "****", loaded = true)
            }
        }
    }

    private fun plan(defaultWake: String = "07:30", schedule: AlarmSchedule? = AlarmSchedule.Workdays) = AlarmPlan(
        id = "plan", revision = 7, name = "通勤", enabled = true, zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = defaultWake, arrivalLocalTime = "09:00", preparationMinutes = 30,
        maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING, schedule = schedule,
    )

    private fun futurePlan(id: String): AlarmPlan {
        val wake = Instant.now().plusSeconds(3 * 60 * 60).atZone(ZoneId.systemDefault())
        return AlarmPlan(
            id = id, revision = 0, name = "重试验证", enabled = true, zoneId = wake.zone.id,
            defaultWakeLocalTime = wake.toLocalTime().withNano(0).toString(),
            arrivalLocalTime = wake.toLocalTime().plusHours(1).withNano(0).toString(),
            preparationMinutes = 30, maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Once(wake.toLocalDate().toString()),
        )
    }

    private fun validCommute(planId: String) = PlanCommuteOverride(
        planId = planId,
        origin = PlaceRef(name = "起点", displayAddress = "起点", longitudeGcj02 = 116.397, latitudeGcj02 = 39.908, adcode = "110000", citycode = "010"),
        destination = PlaceRef(name = "终点", displayAddress = "终点", longitudeGcj02 = 116.407, latitudeGcj02 = 39.918, adcode = "110000", citycode = "010"),
        commuteMode = CommuteMode.DRIVING,
        updatedAt = System.currentTimeMillis(),
    )

    private fun regularOccurrence(plan: AlarmPlan): AlarmOccurrence {
        val wake = Instant.now().plusSeconds(3 * 60 * 60)
        return AlarmOccurrence(
            occurrenceId = "regular-${plan.id}", planId = plan.id, planRevision = plan.revision,
            targetDate = wake.atZone(plan.zoneIdInstance()).toLocalDate().toString(),
            scheduledWakeAt = wake.toEpochMilli(), state = OccurrenceState.SCHEDULED, kind = OccurrenceKind.REGULAR,
        )
    }

    private fun historicDecision(plan: AlarmPlan): AlarmDecision {
        val wake = Instant.now().plusSeconds(3 * 60 * 60)
        return AlarmDecision(
            decisionId = "historic-${plan.id}", planId = plan.id, planRevision = plan.revision,
            targetDate = wake.atZone(plan.zoneIdInstance()).toLocalDate().toString(), workdayStatus = WorkdayStatus.WORKDAY,
            estimatedDepartureAt = null, commuteSeconds = null, weatherSeverity = 0, weatherBufferMinutes = 0,
            recommendedWakeAt = wake.toString(), routeProvider = null, routeProviderReportTime = null,
            weatherProvider = null, weatherProviderReportTime = null, weatherWindowStart = null,
            weatherWindowEnd = null, fallbackReason = FallbackReason.NONE, insufficientAdvance = false,
            generatedAt = Instant.now().toString(), expiresAt = wake.toString(), evaluationOutcome = EvaluationOutcome.FAILED,
            failureReason = "ROUTE_NETWORK", attemptNumber = 0, applicationOutcome = "UNCHANGED",
        )
    }

    private class HoldingWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == EvaluationWorker::class.java.name) {
            HoldingWorker(appContext, workerParameters)
        } else null
    }

    private class HoldingWorker(
        context: Context,
        parameters: WorkerParameters,
    ) : ListenableWorker(context, parameters) {
        override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { "hold-evaluation-work" }
    }
}
