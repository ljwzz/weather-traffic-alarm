package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Uses a physical v1 fixture to verify the registered migration preserves existing data. */
@RunWith(RobolectricTestRunner::class)
class V1ToV2MigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "v1-to-v2-${System.nanoTime()}.db"
        createV1Fixture()
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabaseMigrations.V1_TO_V2)
            .addMigrations(AppDatabaseMigrations.V2_TO_V3)
            .addMigrations(AppDatabaseMigrations.V3_TO_V4)
            .build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `migration retains old plans decisions occurrences and overrides while disarming plans`() = runBlocking {
        val plan = db.alarmPlanDao().getById("plan-v1")
        assertNotNull(plan)
        assertEquals("Old commute alarm", plan!!.name)
        assertEquals(false, plan.enabled)
        assertEquals(AlarmArmedState.NEEDS_RULE, plan.armedState)
        assertNull(plan.schedule)
        assertEquals("Home", plan.origin?.name)

        val commuteOverride = db.planCommuteOverrideDao().getByPlanId("plan-v1")
        assertNotNull(commuteOverride)
        assertEquals("Home", commuteOverride!!.origin.name)
        assertEquals("Office", commuteOverride.destination.name)
        assertEquals(2_000L, commuteOverride.updatedAt)

        val decision = db.alarmDecisionDao().getByPlanIdAndDate("plan-v1", "2026-08-31")
        assertNotNull(decision)
        assertEquals("decision-v1", decision!!.decisionId)
        assertEquals("FAILED", decision.evaluationOutcome.name)
        assertEquals(0, decision.attemptNumber)

        val occurrence = db.alarmOccurrenceDao().getById("occurrence-v1")
        assertNotNull(occurrence)
        assertEquals(OccurrenceState.DEFAULT_REGISTERED, occurrence!!.state)
        assertEquals(OccurrenceKind.REGULAR, occurrence.kind)
        assertNull(occurrence.parentOccurrenceId)

        val override = db.workdayOverrideDao().getByPlanIdAndDate("plan-v1", "2026-08-31")
        assertNotNull(override)
        assertNull(override!!.wakeLocalTime)
    }

    private fun createV1Fixture() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE alarm_plans (id TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, enabled INTEGER NOT NULL, zone_id TEXT NOT NULL, default_wake_local_time TEXT NOT NULL, arrival_local_time TEXT NOT NULL, preparation_minutes INTEGER NOT NULL, max_advance_minutes INTEGER NOT NULL, commute_mode TEXT NOT NULL, origin TEXT NOT NULL, destination TEXT NOT NULL, waypoints TEXT NOT NULL, route_policy TEXT NOT NULL, weather_rule_version TEXT NOT NULL, sound TEXT NOT NULL, vibration TEXT NOT NULL, snooze_minutes INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_decisions (decision_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, workday_status TEXT, estimated_departure_at TEXT, commute_seconds INTEGER, weather_severity INTEGER NOT NULL, weather_buffer_minutes INTEGER NOT NULL, recommended_wake_at TEXT NOT NULL, route_provider TEXT, route_provider_report_time TEXT, weather_provider TEXT, weather_provider_report_time TEXT, weather_window_start TEXT, weather_window_end TEXT, fallback_reason TEXT NOT NULL, insufficient_advance INTEGER NOT NULL, generated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, PRIMARY KEY(decision_id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_occurrences (occurrence_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, scheduled_wake_at INTEGER NOT NULL, state TEXT NOT NULL, decision_id TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(occurrence_id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE workday_overrides (plan_id TEXT NOT NULL, date TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(plan_id, date), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON DELETE CASCADE)",
            )
            sqlite.execSQL("CREATE INDEX index_alarm_decisions_plan_id ON alarm_decisions(plan_id)")
            sqlite.execSQL("CREATE INDEX index_alarm_decisions_target_date ON alarm_decisions(target_date)")
            sqlite.execSQL("CREATE INDEX index_alarm_occurrences_plan_id ON alarm_occurrences(plan_id)")
            sqlite.execSQL("CREATE INDEX index_alarm_occurrences_target_date ON alarm_occurrences(target_date)")
            sqlite.execSQL("CREATE INDEX index_workday_overrides_plan_id ON workday_overrides(plan_id)")
            sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, 'e5408e2f8e11becb19b98e263a5e0a29')")
            sqlite.execSQL("PRAGMA user_version = 1")

            val place = "{\"name\":\"Home\",\"displayAddress\":\"Home\",\"longitudeGcj02\":1.0,\"latitudeGcj02\":1.0,\"adcode\":\"1\",\"citycode\":\"1\"}"
            sqlite.execSQL(
                "INSERT INTO alarm_plans VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("plan-v1", 4, "Old commute alarm", 1, "Asia/Shanghai", "06:30", "09:00", 30, 60, "DRIVING", place, place.replace("Home", "Office"), "[]", "DEFAULT", "v1", "{\"title\":\"Default\"}", "{\"enabled\":true,\"patternMillis\":[0,500,500,500]}", 10, 1_000L, 2_000L),
            )
            sqlite.execSQL(
                "INSERT INTO alarm_decisions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("decision-v1", "plan-v1", 4, "2026-08-31", "WORKDAY", null, null, 0, 0, "2026-08-31T06:30", null, null, null, null, null, null, "NONE", 0, 1_000L, 2_000L),
            )
            sqlite.execSQL(
                "INSERT INTO alarm_occurrences VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("occurrence-v1", "plan-v1", 4, "2026-08-31", 1_000L, "DEFAULT_REGISTERED", "decision-v1", 2_000L),
            )
            sqlite.execSQL(
                "INSERT INTO workday_overrides VALUES (?, ?, ?)",
                arrayOf("plan-v1", "2026-08-31", "HOLIDAY"),
            )
        }
    }
}
