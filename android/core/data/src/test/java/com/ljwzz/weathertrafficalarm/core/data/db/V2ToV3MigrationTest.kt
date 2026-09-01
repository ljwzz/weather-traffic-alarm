package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Opens a physical v2 database directly, so Room validates the v3 schema after migration. */
@RunWith(RobolectricTestRunner::class)
class V2ToV3MigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "v2-to-v3-${System.nanoTime()}.db"
        createV2Fixture()
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabaseMigrations.V2_TO_V3)
            .build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `direct v2 migration validates v3 schema copies route and cascades override`() = runBlocking {
        // The first DAO operation opens the database and makes Room validate the complete v3 schema.
        val plan = db.alarmPlanDao().getById("plan-v2")
        assertNotNull(plan)

        val override = db.planCommuteOverrideDao().getByPlanId("plan-v2")
        assertNotNull(override)
        assertEquals("Home", override!!.origin.name)
        assertEquals("Office", override.destination.name)
        assertEquals("TRANSIT", override.commuteMode.name)
        assertEquals(2_000L, override.updatedAt)

        db.alarmPlanDao().deleteById("plan-v2")
        assertNull(db.planCommuteOverrideDao().getByPlanId("plan-v2"))
    }

    private fun createV2Fixture() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE alarm_plans (id TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, enabled INTEGER NOT NULL, zone_id TEXT NOT NULL, default_wake_local_time TEXT NOT NULL, arrival_local_time TEXT NOT NULL, preparation_minutes INTEGER NOT NULL, max_advance_minutes INTEGER NOT NULL, commute_mode TEXT NOT NULL, origin TEXT, destination TEXT, waypoints TEXT NOT NULL, route_policy TEXT NOT NULL, weather_rule_version TEXT NOT NULL, sound TEXT NOT NULL, vibration TEXT NOT NULL, snooze_minutes INTEGER NOT NULL, schedule TEXT, armed_state TEXT NOT NULL, schedule_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_decisions (decision_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, workday_status TEXT, estimated_departure_at TEXT, commute_seconds INTEGER, weather_severity INTEGER NOT NULL, weather_buffer_minutes INTEGER NOT NULL, recommended_wake_at TEXT NOT NULL, route_provider TEXT, route_provider_report_time TEXT, weather_provider TEXT, weather_provider_report_time TEXT, weather_window_start TEXT, weather_window_end TEXT, fallback_reason TEXT NOT NULL, insufficient_advance INTEGER NOT NULL, generated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, PRIMARY KEY(decision_id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_events (id TEXT NOT NULL, plan_id TEXT NOT NULL, occurrence_id TEXT, type TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_occurrences (occurrence_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, scheduled_wake_at INTEGER NOT NULL, state TEXT NOT NULL, decision_id TEXT, kind TEXT NOT NULL, parent_occurrence_id TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(occurrence_id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE workday_overrides (plan_id TEXT NOT NULL, date TEXT NOT NULL, status TEXT NOT NULL, wake_local_time TEXT, PRIMARY KEY(plan_id, date), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL("CREATE INDEX index_alarm_decisions_plan_id ON alarm_decisions(plan_id)")
            sqlite.execSQL("CREATE INDEX index_alarm_decisions_target_date ON alarm_decisions(target_date)")
            sqlite.execSQL("CREATE INDEX index_alarm_events_plan_id ON alarm_events(plan_id)")
            sqlite.execSQL("CREATE INDEX index_alarm_events_occurrence_id ON alarm_events(occurrence_id)")
            sqlite.execSQL("CREATE INDEX index_alarm_events_created_at ON alarm_events(created_at)")
            sqlite.execSQL("CREATE INDEX index_alarm_occurrences_plan_id ON alarm_occurrences(plan_id)")
            sqlite.execSQL("CREATE INDEX index_alarm_occurrences_target_date ON alarm_occurrences(target_date)")
            sqlite.execSQL("CREATE INDEX index_workday_overrides_plan_id ON workday_overrides(plan_id)")
            sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            val v2IdentityHash = "7ae253815f4063da" + "9e5348549e604f35"
            sqlite.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, '$v2IdentityHash')")
            sqlite.execSQL("PRAGMA user_version = 2")

            val home = "{\"name\":\"Home\",\"displayAddress\":\"Home\",\"longitudeGcj02\":116.397428,\"latitudeGcj02\":39.90923,\"adcode\":\"110000\",\"citycode\":\"010\"}"
            val office = home.replace("Home", "Office").replace("116.397428", "116.407428")
            sqlite.execSQL(
                "INSERT INTO alarm_plans (id, revision, name, enabled, zone_id, default_wake_local_time, arrival_local_time, preparation_minutes, max_advance_minutes, commute_mode, origin, destination, waypoints, route_policy, weather_rule_version, sound, vibration, snooze_minutes, schedule, armed_state, schedule_error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("plan-v2", 4, "V2 commute alarm", 0, "Asia/Shanghai", "06:30", "09:00", 30, 60, "TRANSIT", home, office, "[]", "DEFAULT", "v1", "{\"title\":\"Default\"}", "{\"enabled\":true,\"patternMillis\":[0,500,500,500]}", 10, null, "NEEDS_RULE", null, 1_000L, 2_000L),
            )
        }
    }
}
