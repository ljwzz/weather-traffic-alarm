package com.ljwzz.weathertrafficalarm.evaluation

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.*
import com.ljwzz.weathertrafficalarm.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class DailyEvaluationSchedulingRegressionTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var plans: AlarmPlanRepository
    private lateinit var days: WorkdayOverrideRepository
    private lateinit var scheduler: EvaluationWorkScheduler
    private val now = Instant.parse("2026-10-01T19:30:00Z")
    private val date = "2026-10-10"
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setWorkerFactory(HoldingWorkerFactory()).build())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        plans = AlarmPlanRepository(db.alarmPlanDao())
        days = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val settings = LocalSettingsStore(context)
        settings.update { it.copy(amapConsentGranted = true, originId = null, destinationId = null) }
        val commutes = PlanCommuteOverrideRepository(db.planCommuteOverrideDao())
        val calendar = WorkdayCalendarRepository(context)
        scheduler = EvaluationWorkScheduler(context, plans, OccurrenceRepository(db.alarmOccurrenceDao()),
            settings, commutes, days, calendar, CredentialStore(context),
            DailyEvaluationInputResolver(plans, settings, EffectiveCommuteResolver(commutes), days, calendar),
            Clock.fixed(now, ZoneId.of("UTC")))
    }
    @After fun cleanup() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private fun plan() = AlarmPlan("future-day", 1, "review", true, "UTC", "06:30", "09:00",
        30, 60, CommuteMode.DRIVING, schedule = AlarmSchedule.Once(date))

    @Test fun reviewFutureDayOnlyCommuteReceivesItsOwnNightWindow() = runBlocking {
        val plan = plans.save(plan())
        val origin = PlaceRef(poiId = "origin", name = "origin", displayAddress = "origin",
            longitudeGcj02 = 116.39, latitudeGcj02 = 39.90, adcode = "110000", citycode = "010")
        val destination = PlaceRef(poiId = "destination", name = "destination", displayAddress = "destination",
            longitudeGcj02 = 116.40, latitudeGcj02 = 39.91, adcode = "110000", citycode = "010")
        days.commitCurrent(plan.id, date, SingleDayOverride(plan.id, date,
            origin = origin, destination = destination, commuteMode = CommuteMode.DRIVING), now.toEpochMilli())
        scheduler.ensureNightly(plan, replace = true, changedDates = setOf(LocalDate.parse(date)))
        val work = WorkManager.getInstance(context).getWorkInfosByTag(EvaluationWorkScheduler.planTag(plan.id)).get()
        assertTrue("enabled Once($date) has only a complete day commute; actual work=${work.map { it.tags }}",
            work.any { !it.state.isFinished && "target:$date" in it.tags })
    }

    private class HoldingWorkerFactory : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String,
            workerParameters: WorkerParameters): ListenableWorker? =
            if (workerClassName == EvaluationWorker::class.java.name) HoldingWorker(appContext, workerParameters) else null
    }
    private class HoldingWorker(context: Context, parameters: WorkerParameters) : ListenableWorker(context, parameters) {
        override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { "hold-review-work" }
    }
}
