package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opens a physical v6 database and migrates it with the registered V6_TO_V7 migration, so
 * Room validates the new schema and the pre-N004 rows must survive unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class V6ToV7MigrationTest {

    private val FIXED_NOW = java.time.Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    private val home = "{\"name\":\"Home\",\"displayAddress\":\"Home\",\"longitudeGcj02\":116.397428,\"latitudeGcj02\":39.90923,\"adcode\":\"110000\",\"citycode\":\"010\"}"
    private val office = home.replace("Home", "Office").replace("116.397428", "116.407428")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "v6-to-v7-${System.nanoTime()}.db"
        createV6Fixture()
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabaseMigrations.V6_TO_V7)
            .addMigrations(AppDatabaseMigrations.V7_TO_V8)
            .build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
    }

    /**
     * Legacy rows keep their status, wake time and identity, and every N004 column starts
     * absent so the date keeps inheriting plan and global values.
     */
    @Test
    fun `migration preserves legacy override values and leaves the new fields absent`() = runBlocking {
        val stored = db.workdayOverrideDao().getByPlanIdAndDate("plan-v6", "2026-09-05")
        assertNotNull(stored)
        assertEquals(DayStatus.WORKDAY, stored!!.status)
        assertEquals("07:10", stored.wakeLocalTime)
        assertNull(stored.arrivalLocalTime)
        assertNull(stored.preparationMinutes)
        assertNull(stored.weatherSeverity1Minutes)
        assertNull(stored.weatherSeverity2Minutes)
        assertNull(stored.weatherSeverity3Minutes)
        assertNull(stored.origin)
        assertNull(stored.destination)
        assertNull(stored.commuteMode)
        assertEquals(0L, stored.dayRevision)

        // A legacy row without a wake time keeps a null status column value only when it was null in v6.
        val inherited = db.workdayOverrideDao().getByPlanIdAndDate("plan-v6", "2026-09-06")
        assertNotNull(inherited)
        assertNull(inherited!!.wakeLocalTime)
    }

    @Test
    fun `migration keeps decision history and adds the day revision default`() = runBlocking {
        val decision = db.alarmDecisionDao().getById("decision-v6")
        assertNotNull(decision)
        assertEquals("decision-v6", decision!!.decisionId)
        assertEquals(0L, decision.dayRevision)
        assertNull(decision.arrivalLocalTime)
    }

    @Test
    fun `migration keeps the foreign key so deleting a plan removes its day overrides`() = runBlocking {
        db.alarmPlanDao().deleteById("plan-v6")

        assertNull(db.workdayOverrideDao().getByPlanIdAndDate("plan-v6", "2026-09-05"))
        assertTrue(db.workdayOverrideDao().getByPlanId("plan-v6").isEmpty())
        // Decision and occurrence history outlive the plan.
        assertNotNull(db.alarmDecisionDao().getById("decision-v6"))
    }

    @Test
    fun `the migrated table accepts the complete N004 row and round trips it`() = runBlocking {
        val repository = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val saved = requireNotNull(
            repository.commitCurrent(
                planId = "plan-v6",
                date = "2026-09-07",
                now = FIXED_NOW,
                replacement = SingleDayOverride(
                planId = "plan-v6",
                date = "2026-09-07",
                status = DayStatus.WORKDAY,
                wakeLocalTime = "06:15",
                arrivalLocalTime = "08:45",
                preparationMinutes = 0,
                weatherProfile = WeatherBufferProfile(0, 30, 60),
                origin = PlaceRef("h", "Home", "Home", 116.397428, 39.90923, "110000", "010"),
                destination = PlaceRef("o", "Office", "Office", 116.407428, 39.91923, "110000", "010"),
                    commuteMode = CommuteMode.TRANSIT,
                ),
            ),
        )

        assertEquals(1L, saved.committedRevision)
        val reloaded = repository.getForPlanDate("plan-v6", "2026-09-07")
        assertNotNull(reloaded)
        assertEquals("08:45", reloaded!!.arrivalLocalTime)
        assertEquals(0, reloaded.preparationMinutes)
        assertEquals(WeatherBufferProfile(0, 30, 60), reloaded.weatherProfile)
        assertEquals(CommuteMode.TRANSIT, reloaded.commuteMode)
        assertEquals("Office", reloaded.destination?.name)
        // The migrated legacy row is untouched by the new save.
        assertEquals("07:10", repository.getForPlanDate("plan-v6", "2026-09-05")?.wakeLocalTime)
    }

    private fun createV6Fixture() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE alarm_plans (id TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, enabled INTEGER NOT NULL, zone_id TEXT NOT NULL, default_wake_local_time TEXT NOT NULL, arrival_local_time TEXT NOT NULL, preparation_minutes INTEGER NOT NULL, max_advance_minutes INTEGER NOT NULL, commute_mode TEXT NOT NULL, origin TEXT, destination TEXT, waypoints TEXT NOT NULL, route_policy TEXT NOT NULL, weather_rule_version TEXT NOT NULL, sound TEXT NOT NULL, vibration TEXT NOT NULL, snooze_minutes INTEGER NOT NULL, schedule TEXT, armed_state TEXT NOT NULL, schedule_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_decisions (decision_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, workday_status TEXT, estimated_departure_at TEXT, commute_seconds INTEGER, weather_severity INTEGER NOT NULL, weather_buffer_minutes INTEGER NOT NULL, recommended_wake_at TEXT NOT NULL, route_provider TEXT, route_provider_report_time TEXT, weather_provider TEXT, weather_provider_report_time TEXT, weather_window_start TEXT, weather_window_end TEXT, fallback_reason TEXT NOT NULL, insufficient_advance INTEGER NOT NULL, generated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, evaluation_outcome TEXT NOT NULL DEFAULT 'FAILED', failure_reason TEXT, attempt_number INTEGER NOT NULL DEFAULT 0, application_outcome TEXT, preparation_minutes INTEGER NOT NULL DEFAULT 0, default_wake_at TEXT, actual_wake_at TEXT, calendar_source TEXT, weather_data_source TEXT, plan_name TEXT, zone_id TEXT, PRIMARY KEY(decision_id))",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_events (id TEXT NOT NULL, plan_id TEXT NOT NULL, occurrence_id TEXT, type TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_occurrences (occurrence_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, scheduled_wake_at INTEGER NOT NULL, state TEXT NOT NULL, decision_id TEXT, kind TEXT NOT NULL, parent_occurrence_id TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(occurrence_id))",
            )
            sqlite.execSQL(
                "CREATE TABLE plan_commute_overrides (plan_id TEXT NOT NULL, origin TEXT NOT NULL, destination TEXT NOT NULL, commute_mode TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(plan_id), FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
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
            sqlite.execSQL("CREATE INDEX index_plan_commute_overrides_plan_id ON plan_commute_overrides(plan_id)")
            sqlite.execSQL("CREATE INDEX index_workday_overrides_plan_id ON workday_overrides(plan_id)")
            sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, '2b6bc4b1b0e0dcf6c1d1c1b0b57ba2f1')")
            sqlite.execSQL("PRAGMA user_version = 6")

            sqlite.execSQL(
                "INSERT INTO alarm_plans (id, revision, name, enabled, zone_id, default_wake_local_time, arrival_local_time, preparation_minutes, max_advance_minutes, commute_mode, origin, destination, waypoints, route_policy, weather_rule_version, sound, vibration, snooze_minutes, schedule, armed_state, schedule_error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("plan-v6", 3, "V6 通勤", 1, "Asia/Shanghai", "06:30", "09:00", 30, 60, "DRIVING", home, office, "[]", "DEFAULT", "v1", "{\"title\":\"Default\"}", "{\"enabled\":true,\"patternMillis\":[0,500,500,500]}", 10, null, "NEEDS_RULE", null, 1_000L, 2_000L),
            )
            sqlite.execSQL(
                "INSERT INTO alarm_decisions (decision_id, plan_id, plan_revision, target_date, workday_status, weather_severity, weather_buffer_minutes, recommended_wake_at, fallback_reason, insufficient_advance, generated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("decision-v6", "plan-v6", 3, "2026-09-05", "WORKDAY", 0, 0, "2026-09-05T06:30", "NONE", 0, 1_000L, 2_000L),
            )
            sqlite.execSQL(
                "INSERT INTO workday_overrides (plan_id, date, status, wake_local_time) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("plan-v6", "2026-09-05", "WORKDAY", "07:10"),
            )
            sqlite.execSQL(
                "INSERT INTO workday_overrides (plan_id, date, status, wake_local_time) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("plan-v6", "2026-09-06", "HOLIDAY", null),
            )
        }
    }
}
