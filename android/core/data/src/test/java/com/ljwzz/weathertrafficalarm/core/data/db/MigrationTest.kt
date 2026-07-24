package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration test baseline for database v1.
 * Verifies that the current schema can create, open, and write data.
 * Uses in-memory database to avoid file state leaking between runs.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var planDao: AlarmPlanDao

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

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
        planDao = db.alarmPlanDao()
    }

    @Test
    fun v1SchemaCreatesAndOpens() = runBlocking {
        // Write a plan
        val plan = AlarmPlanEntity(
            id = "plan-migration-test",
            revision = 1,
            name = "Migration Test Plan",
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
            sound = AlarmSound(title = "Default"),
            vibration = VibrationPattern(enabled = true),
            snoozeMinutes = 10,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        planDao.upsert(plan)

        // Read it back
        val retrieved = planDao.getById("plan-migration-test")
        assertNotNull(retrieved)
        assertEquals("Migration Test Plan", retrieved!!.name)

        // Update revision
        val updated = plan.copy(revision = 2, updatedAt = System.currentTimeMillis())
        planDao.saveWithRevisionUpdate(updated)
        val afterUpdate = planDao.getById("plan-migration-test")
        assertEquals(2L, afterUpdate!!.revision)
    }

    @Test
    fun v1SchemaSupportsMultiplePlans() = runBlocking {
        planDao.upsert(
            AlarmPlanEntity(
                id = "plan-1",
                revision = 1,
                name = "Plan One",
                enabled = true,
                zoneId = "Asia/Shanghai",
                defaultWakeLocalTime = "06:00",
                arrivalLocalTime = "09:00",
                preparationMinutes = 30,
                maxAdvanceMinutes = 60,
                commuteMode = CommuteMode.TRANSIT,
                origin = origin,
                destination = destination,
                waypoints = emptyList(),
                routePolicy = RoutePolicy.DEFAULT,
                weatherRuleVersion = "v1",
                sound = AlarmSound(),
                vibration = VibrationPattern(),
                snoozeMinutes = 10,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        planDao.upsert(
            AlarmPlanEntity(
                id = "plan-2",
                revision = 1,
                name = "Plan Two",
                enabled = false,
                zoneId = "America/New_York",
                defaultWakeLocalTime = "07:00",
                arrivalLocalTime = "10:00",
                preparationMinutes = 45,
                maxAdvanceMinutes = 30,
                commuteMode = CommuteMode.WALKING,
                origin = origin,
                destination = destination,
                waypoints = emptyList(),
                routePolicy = RoutePolicy.LEAST_DISTANCE,
                weatherRuleVersion = "v1",
                sound = AlarmSound(uri = "content://media/ringtone"),
                vibration = VibrationPattern(enabled = false),
                snoozeMinutes = 5,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )

        val allPlans = planDao.getAll()
        assertEquals(2, allPlans.size)
    }
}
