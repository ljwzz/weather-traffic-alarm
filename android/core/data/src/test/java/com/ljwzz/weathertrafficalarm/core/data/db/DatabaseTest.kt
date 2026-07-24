package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmDecisionDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmOccurrenceDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmOccurrenceEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
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

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var planDao: AlarmPlanDao
    private lateinit var decisionDao: AlarmDecisionDao
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
        assertEquals("Home", retrieved.origin.name)
        assertEquals("Office", retrieved.destination.name)
        assertEquals(116.397428, retrieved.origin.longitudeGcj02, 0.0001)
        assertEquals(39.90923, retrieved.origin.latitudeGcj02, 0.0001)
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
