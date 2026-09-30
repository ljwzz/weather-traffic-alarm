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
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
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
        scheduler = EvaluationWorkScheduler(
            context, plans, occurrences, settings, overrides,
            WorkdayOverrideRepository(database.workdayOverrideDao(), database.workdayOverrideWriteDao()), WorkdayCalendarRepository(context), credentials,
            EffectiveCommuteResolver(overrides), Clock.systemUTC(),
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

    @Test
    fun `manual evaluation validates current prerequisites with precise reasons`() {
        val validCredentials = CredentialStatus(
            amapWebKeyMask = "****", caiyunAppKeyMask = "****", caiyunSecretMask = "****", loaded = true,
        )
        val validSettings = LocalSettings(amapConsentGranted = true)

        assertNull(validateManualEvaluation(plan(), validSettings, validCredentials, hasCommute = true))
        assertEquals(
            EvaluateNowRejection.INVALID_SCHEDULE,
            validateManualEvaluation(plan(schedule = null), validSettings, validCredentials, hasCommute = true),
        )
        assertEquals(
            EvaluateNowRejection.INVALID_SCHEDULE,
            validateManualEvaluation(
                plan(schedule = AlarmSchedule.Once("2020-01-01")), validSettings, validCredentials,
                hasCommute = true, now = Instant.parse("2026-09-07T00:00:00Z"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { AlarmSchedule.Weekly(emptySet()) }
        assertEquals(
            EvaluateNowRejection.INVALID_TIME,
            validateManualEvaluation(plan(defaultWake = "invalid"), validSettings, validCredentials, hasCommute = true),
        )
        assertEquals(
            EvaluateNowRejection.COMMUTE_NOT_CONFIGURED,
            validateManualEvaluation(plan(), validSettings, validCredentials, hasCommute = false),
        )
        assertEquals(
            EvaluateNowRejection.AMAP_CONSENT_REQUIRED,
            validateManualEvaluation(plan(), LocalSettings(), validCredentials, hasCommute = true),
        )
        assertEquals(
            EvaluateNowRejection.CREDENTIAL_STORAGE_ERROR,
            validateManualEvaluation(plan(), validSettings, validCredentials.copy(storageError = true), hasCommute = true),
        )
        assertEquals(
            EvaluateNowRejection.AMAP_WEB_KEY_MISSING,
            validateManualEvaluation(plan(), validSettings, validCredentials.copy(amapWebKeyMask = null), hasCommute = true),
        )
        assertEquals(
            EvaluateNowRejection.CAIYUN_CREDENTIALS_MISSING,
            validateManualEvaluation(plan(), validSettings, validCredentials.copy(caiyunSecretMask = null), hasCommute = true),
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
