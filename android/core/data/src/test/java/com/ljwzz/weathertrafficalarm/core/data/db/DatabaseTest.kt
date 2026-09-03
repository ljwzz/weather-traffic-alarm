package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmDecisionDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmEventDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmOccurrenceDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmEventEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmOccurrenceEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var planDao: AlarmPlanDao
    private lateinit var decisionDao: AlarmDecisionDao
    private lateinit var eventDao: AlarmEventDao
    private lateinit var occurrenceDao: AlarmOccurrenceDao
    private lateinit var overrideDao: WorkdayOverrideDao

    private val origin = PlaceRef(
        name = "Home",
        displayAddress = "123 Main St",
        longitudeGcj02 = 116.397428,
        latitudeGcj02 = 39.90923,
        adcode = "110000",
        citycode = "010",
    )

    private val destination = PlaceRef(
        name = "Office",
        displayAddress = "456 Business Ave",
        longitudeGcj02 = 116.407428,
        latitudeGcj02 = 39.91923,
        adcode = "110000",
        citycode = "010",
    )

    private fun createTestPlan(id: String = "plan-1") = AlarmPlanEntity(
        id = id,
        revision = 1,
        name = "Morning Commute",
        enabled = true,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "06:30",
        arrivalLocalTime = "09:00",
        preparationMinutes = 30,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
        origin = origin,
        destination = destination,
        waypoints = emptyList(),
        routePolicy = RoutePolicy.LEAST_TRAFFIC,
        weatherRuleVersion = "v1",
        sound = AlarmSound(title = "Gentle"),
        vibration = VibrationPattern(enabled = true),
        snoozeMinutes = 10,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    private fun createTestDecision(planId: String = "plan-1") = AlarmDecisionEntity(
        decisionId = "dec-1",
        planId = planId,
        planRevision = 1,
        targetDate = "2026-07-25",
        workdayStatus = WorkdayStatus.WORKDAY,
        estimatedDepartureAt = "2026-07-25T08:00:00",
        commuteSeconds = 3600,
        weatherSeverity = 1,
        weatherBufferMinutes = 10,
        recommendedWakeAt = "2026-07-25T06:30:00",
        routeProvider = "amap",
        routeProviderReportTime = "2026-07-24T20:00:00",
        weatherProvider = "openweathermap",
        weatherProviderReportTime = "2026-07-24T20:00:00",
        weatherWindowStart = "2026-07-25T06:00:00",
        weatherWindowEnd = "2026-07-25T09:00:00",
        fallbackReason = FallbackReason.NONE,
        insufficientAdvance = false,
        generatedAt = 1690219200000L,
        expiresAt = 1690262400000L,
    )

    private fun createTestOccurrence(planId: String = "plan-1") = AlarmOccurrenceEntity(
        occurrenceId = "occ-1",
        planId = planId,
        planRevision = 1,
        targetDate = "2026-07-25",
        scheduledWakeAt = 1690264200000L,
        state = OccurrenceState.DEFAULT_REGISTERED,
        decisionId = "dec-1",
        updatedAt = System.currentTimeMillis(),
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        planDao = db.alarmPlanDao()
        decisionDao = db.alarmDecisionDao()
        eventDao = db.alarmEventDao()
        occurrenceDao = db.alarmOccurrenceDao()
        overrideDao = db.workdayOverrideDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // --- AlarmPlan tests ---

    @Test
    fun insertAndRetrievePlan() = runTest {
        val plan = createTestPlan()
        planDao.upsert(plan)

        val retrieved = planDao.getById("plan-1")
        assertNotNull(retrieved)
        assertEquals("plan-1", retrieved!!.id)
        assertEquals("Morning Commute", retrieved.name)
        assertEquals(CommuteMode.DRIVING, retrieved.commuteMode)
        val savedOrigin = requireNotNull(retrieved.origin)
        val savedDestination = requireNotNull(retrieved.destination)
        assertEquals("Home", savedOrigin.name)
        assertEquals("Office", savedDestination.name)
        assertEquals(116.397428, savedOrigin.longitudeGcj02, 0.0001)
        assertEquals(39.90923, savedOrigin.latitudeGcj02, 0.0001)
    }

    @Test
    fun upsertPlanReplacesExisting() = runTest {
        val plan = createTestPlan()
        planDao.upsert(plan)

        val updated = plan.copy(name = "Evening Commute", revision = 2)
        planDao.upsert(updated)

        val retrieved = planDao.getById("plan-1")
        assertNotNull(retrieved)
        assertEquals("Evening Commute", retrieved!!.name)
        assertEquals(2L, retrieved.revision)
    }

    @Test
    fun getAllPlans() = runTest {
        planDao.upsert(createTestPlan("plan-1"))
        planDao.upsert(createTestPlan("plan-2"))

        val all = planDao.getAll()
        assertEquals(2, all.size)
    }

    @Test
    fun deletePlan() = runTest {
        planDao.upsert(createTestPlan())
        planDao.deleteById("plan-1")

        assertNull(planDao.getById("plan-1"))
    }

    @Test
    fun basicAlarmPlanPersistsWithoutRoutePlaces() = runTest {
        val plan = createTestPlan("basic-plan").copy(
            origin = null,
            destination = null,
            enabled = true,
            armedState = AlarmArmedState.NEEDS_PERMISSION,
        )
        planDao.upsert(plan)

        val stored = planDao.getById("basic-plan")
        assertNull(stored!!.origin)
        assertNull(stored.destination)
        assertEquals(AlarmArmedState.NEEDS_PERMISSION, stored.armedState)
    }

    @Test
    fun updatingPlanPreservesDependentDecisionOccurrenceAndOverride() = runTest {
        val original = createTestPlan()
        planDao.upsert(original)
        decisionDao.upsert(createTestDecision())
        occurrenceDao.upsert(createTestOccurrence())
        overrideDao.upsert(WorkdayOverrideEntity("plan-1", "2026-07-25", DayStatus.HOLIDAY))

        planDao.upsert(original.copy(name = "Updated", revision = 2, updatedAt = System.currentTimeMillis()))

        assertEquals("Updated", planDao.getById("plan-1")!!.name)
        assertNotNull(decisionDao.getByPlanIdAndDate("plan-1", "2026-07-25"))
        assertNotNull(occurrenceDao.getById("occ-1"))
        assertNotNull(overrideDao.getByPlanIdAndDate("plan-1", "2026-07-25"))
    }

    // --- AlarmDecision tests ---

    @Test
    fun insertAndRetrieveDecision() = runTest {
        planDao.upsert(createTestPlan())
        val decision = createTestDecision()
        decisionDao.upsert(decision)

        val retrieved = decisionDao.getByPlanIdAndDate("plan-1", "2026-07-25")
        assertNotNull(retrieved)
        assertEquals("dec-1", retrieved!!.decisionId)
        assertEquals(WorkdayStatus.WORKDAY, retrieved.workdayStatus)
        assertEquals(FallbackReason.NONE, retrieved.fallbackReason)
    }

    @Test
    fun decisionHistoryFieldsPersist() = runTest {
        planDao.upsert(createTestPlan())
        decisionDao.upsert(
            createTestDecision().copy(
                evaluationOutcome = EvaluationOutcome.SUCCESS,
                failureReason = "WEATHER_UNAVAILABLE",
                attemptNumber = 2,
                applicationOutcome = "APPLIED",
                preparationMinutes = 25,
                defaultWakeAt = "2026-07-25T06:45:00",
                actualWakeAt = "2026-07-25T06:20:00",
                calendarSource = "HOLIDAY_API",
                weatherDataSource = "CAIYUN",
            ),
        )

        val stored = decisionDao.getById("dec-1")
        assertNotNull(stored)
        assertEquals(EvaluationOutcome.SUCCESS, stored!!.evaluationOutcome)
        assertEquals("WEATHER_UNAVAILABLE", stored.failureReason)
        assertEquals(2, stored.attemptNumber)
        assertEquals("APPLIED", stored.applicationOutcome)
        assertEquals(25, stored.preparationMinutes)
        assertEquals("2026-07-25T06:45:00", stored.defaultWakeAt)
        assertEquals("2026-07-25T06:20:00", stored.actualWakeAt)
        assertEquals("HOLIDAY_API", stored.calendarSource)
        assertEquals("CAIYUN", stored.weatherDataSource)
    }

    @Test
    fun decisionSaveRequiresExistingPlanAndListsLatestTargetDateFirst() = runTest {
        assertFalse(decisionDao.saveIfPlanExists(createTestDecision("missing-plan")))

        planDao.upsert(createTestPlan())
        assertTrue(decisionDao.saveIfPlanExists(createTestDecision()))
        decisionDao.upsert(
            createTestDecision().copy(
                decisionId = "dec-2",
                targetDate = "2026-07-26",
                generatedAt = 1690305600000L,
            ),
        )

        assertEquals(
            listOf("dec-2", "dec-1"),
            decisionDao.observeAll().first().map { it.decisionId },
        )
        assertEquals("dec-1", decisionDao.getById("dec-1")?.decisionId)
    }

    @Test
    fun decisionRepositoryOnlySavesForExistingPlan() = runTest {
        val repository = DecisionRepository(decisionDao)
        assertFalse(repository.save(createTestDecision("missing-plan").toDomain()))

        planDao.upsert(createTestPlan())
        assertTrue(repository.save(createTestDecision().toDomain()))
        assertEquals("dec-1", repository.getById("dec-1")?.decisionId)
        assertEquals(listOf("dec-1"), repository.observeAll().first().map { it.decisionId })
    }

    @Test
    fun decisionRepositoryRoundTripsIsoAndLegacyEpochTimesAndRetainsRecentHistory() = runTest {
        val repository = DecisionRepository(decisionDao)
        planDao.upsert(createTestPlan())
        val generatedAt = Instant.parse("2026-09-03T12:00:00Z")
        val expiresAt = Instant.parse("2026-09-03T15:30:00Z")

        assertTrue(
            repository.save(
                createTestDecision().toDomain().copy(
                    generatedAt = generatedAt.toString(),
                    expiresAt = expiresAt.toString(),
                ),
            ),
        )
        assertTrue(
            repository.save(
                createTestDecision().copy(decisionId = "dec-legacy").toDomain().copy(
                    generatedAt = "1690219200000",
                    expiresAt = "1690262400000",
                ),
            ),
        )

        assertEquals(generatedAt.toString(), repository.getById("dec-1")!!.generatedAt)
        assertEquals(expiresAt.toString(), repository.getById("dec-1")!!.expiresAt)
        assertEquals(Instant.ofEpochMilli(1690219200000L).toString(), repository.getById("dec-legacy")!!.generatedAt)

        repository.deleteOlderThan(generatedAt.minus(Duration.ofDays(30)).toEpochMilli())
        assertNotNull(repository.getById("dec-1"))
        assertNull(repository.getById("dec-legacy"))
    }

    @Test
    fun decisionRepositoryRejectsInvalidDecisionTimes() = runTest {
        val repository = DecisionRepository(decisionDao)
        planDao.upsert(createTestPlan())

        val error = runCatching {
            repository.save(createTestDecision().toDomain().copy(generatedAt = "not-a-timestamp"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("generatedAt must be an epoch millisecond or ISO-8601 instant", error?.message)
    }

    @Test
    fun getDecisionsByPlanId() = runTest {
        planDao.upsert(createTestPlan())
        decisionDao.upsert(createTestDecision())

        val decisions = decisionDao.getByPlanId("plan-1")
        assertEquals(1, decisions.size)
    }

    @Test
    fun deleteDecisionsOlderThan() = runTest {
        planDao.upsert(createTestPlan())
        decisionDao.upsert(createTestDecision())

        // Cutoff far in the future => should delete everything
        decisionDao.deleteOlderThan(System.currentTimeMillis() + 1_000_000_000L)

        val remaining = decisionDao.getByPlanId("plan-1")
        assertTrue(remaining.isEmpty())
    }

    // --- AlarmOccurrence tests ---

    @Test
    fun insertAndRetrieveOccurrence() = runTest {
        planDao.upsert(createTestPlan())
        val occurrence = createTestOccurrence()
        occurrenceDao.createOccurrence(occurrence)

        val retrieved = occurrenceDao.getByPlanIdAndDate("plan-1", "2026-07-25")
        assertNotNull(retrieved)
        assertEquals("occ-1", retrieved!!.occurrenceId)
        assertEquals(OccurrenceState.DEFAULT_REGISTERED, retrieved.state)
    }

    @Test
    fun updateOccurrenceState() = runTest {
        planDao.upsert(createTestPlan())
        occurrenceDao.createOccurrence(createTestOccurrence())

        occurrenceDao.updateState("occ-1", OccurrenceState.FIRING.name, System.currentTimeMillis())

        val updated = occurrenceDao.getById("occ-1")
        assertNotNull(updated)
        assertEquals(OccurrenceState.FIRING, updated!!.state)
    }

    @Test
    fun alarmEventsPersistAndAreRetainedIndependentlyOfOccurrences() = runTest {
        planDao.upsert(createTestPlan())
        eventDao.upsert(
            AlarmEventEntity(
                id = "event-1",
                planId = "plan-1",
                occurrenceId = null,
                type = AlarmEventType.REGISTERED,
                message = "Registered",
                createdAt = 1_000L,
            ),
        )
        assertEquals(1, eventDao.observeAll().first().size)
        eventDao.deleteOlderThan(1_001L)
        assertTrue(eventDao.observeAll().first().isEmpty())
    }

    // --- WorkdayOverride tests ---

    @Test
    fun insertAndRetrieveOverride() = runTest {
        planDao.upsert(createTestPlan())
        val override = WorkdayOverrideEntity(
            planId = "plan-1",
            date = "2026-07-25",
            status = DayStatus.HOLIDAY,
        )
        overrideDao.upsert(override)

        val retrieved = overrideDao.getByPlanIdAndDate("plan-1", "2026-07-25")
        assertNotNull(retrieved)
        assertEquals(DayStatus.HOLIDAY, retrieved!!.status)
    }

    @Test
    fun deleteOverride() = runTest {
        planDao.upsert(createTestPlan())
        overrideDao.upsert(
            WorkdayOverrideEntity("plan-1", "2026-07-25", DayStatus.HOLIDAY),
        )

        overrideDao.deleteByPlanIdAndDate("plan-1", "2026-07-25")
        assertNull(overrideDao.getByPlanIdAndDate("plan-1", "2026-07-25"))
    }

    // --- Transaction tests ---

    @Test
    fun planSaveWithRevisionUpdateIsAtomic() = runTest {
        val plan = createTestPlan()
        planDao.saveWithRevisionUpdate(plan)

        val retrieved = planDao.getById("plan-1")
        assertNotNull(retrieved)

        val updatedPlan = retrieved!!.copy(revision = 2, updatedAt = System.currentTimeMillis())
        planDao.saveWithRevisionUpdate(updatedPlan)

        val afterUpdate = planDao.getById("plan-1")
        assertEquals(2L, afterUpdate!!.revision)
    }

    @Test
    fun occurrenceCreationIsAtomic() = runTest {
        planDao.upsert(createTestPlan())
        occurrenceDao.createOccurrence(createTestOccurrence())

        val retrieved = occurrenceDao.getById("occ-1")
        assertNotNull(retrieved)
    }

    // --- PlaceRef redacted toString test ---

    @Test
    fun placeRefToStringDoesNotContainCoordinates() {
        val placeRef = origin
        val str = placeRef.toString()
        assertTrue("toString should not contain longitudeGcj02 value", !str.contains("116.397428"))
        assertTrue("toString should not contain latitudeGcj02 value", !str.contains("39.90923"))
        assertTrue("toString should contain name", str.contains("Home"))
    }
}
