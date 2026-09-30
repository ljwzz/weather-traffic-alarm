package com.ljwzz.weathertrafficalarm.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
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
 * Opens a physical v7 database and migrates it with the registered V7_TO_V8 migration, so the
 * independent day revision table is seeded from the surviving override rows and the retained
 * decisions while history keeps its own values.
 */
@RunWith(RobolectricTestRunner::class)
class V7ToV8MigrationTest {

    private val FIXED_NOW = java.time.Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "v7-to-v8-${System.nanoTime()}.db"
        createV7Fixture()
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabaseMigrations.V7_TO_V8)
            .build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
    }

    /** The committed revision comes from the highest value the plan and date ever produced. */
    @Test
    fun `migration seeds both revisions from overrides and retained decisions`() = runBlocking {
        val repository = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())

        // 2026-09-05: decision revision 9 outranks the override row's 4.
        assertEquals(9L, repository.committedRevision("plan-v7", "2026-09-05"))
        assertEquals(9L, repository.getForPlanDate("plan-v7", "2026-09-05")?.dayRevision)
        // 2026-09-06: only the override row exists.
        assertEquals(1L, repository.committedRevision("plan-v7", "2026-09-06"))
        // 2026-09-07: the override was deleted earlier, the decision still carries its revision.
        assertEquals(7L, repository.committedRevision("plan-v7", "2026-09-07"))
        assertNull(repository.getForPlanDate("plan-v7", "2026-09-07"))
    }

    /** Pre-v8 values survive unchanged; only the revision column is synchronised. */
    @Test
    fun `migration preserves the stored override values`() = runBlocking {
        val stored = requireNotNull(db.workdayOverrideDao().getByPlanIdAndDate("plan-v7", "2026-09-05"))

        assertEquals(DayStatus.WORKDAY.name, stored.status?.name)
        assertEquals("07:10", stored.wakeLocalTime)
        assertEquals(45, stored.preparationMinutes)
        assertEquals(9L, stored.dayRevision)
        val untouched = requireNotNull(db.workdayOverrideDao().getByPlanIdAndDate("plan-v7", "2026-09-06"))
        assertEquals(DayStatus.HOLIDAY.name, untouched.status?.name)
        assertEquals(1L, untouched.dayRevision)
    }

    /** History is not rewritten: a decision keeps the day revision it was produced with. */
    @Test
    fun `migration keeps historical decision day revisions`() = runBlocking {
        assertEquals(9L, db.alarmDecisionDao().getById("decision-v7-high")?.dayRevision)
        assertEquals(7L, db.alarmDecisionDao().getById("decision-v7-orphan")?.dayRevision)
    }

    /** Deleting the override after the migration keeps the revision row alive. */
    @Test
    fun `deleting an override keeps the migrated revision`() = runBlocking {
        val repository = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())

        val afterDelete = requireNotNull(
            repository.commitCurrent("plan-v7", "2026-09-05", null, FIXED_NOW),
        )

        assertNull(repository.getForPlanDate("plan-v7", "2026-09-05"))
        assertEquals(10L, afterDelete.committedRevision)
        assertEquals(10L, repository.committedRevision("plan-v7", "2026-09-05"))
    }

    /** A migrated database keeps enforcing the plan foreign key on the new tables. */
    @Test
    fun `deleting the plan removes migrated revisions and pending changes`() = runBlocking {
        val repository = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val allocation = repository.allocateCandidate(
            changeId = "pending",
            planId = "plan-v7",
            date = "2026-09-06",
            replacement = SingleDayOverride("plan-v7", "2026-09-06", preparationMinutes = 15),
            expectedDayRevision = 1L,
            candidateOccurrenceId = null,
            cancelledOccurrenceIds = emptyList(),
            now = FIXED_NOW,
        )
        assertTrue(allocation is com.ljwzz.weathertrafficalarm.core.data.repository.DayOverrideAllocation.Allocated)

        db.alarmPlanDao().deleteById("plan-v7")

        assertNull(db.workdayOverrideDao().getByPlanIdAndDate("plan-v7", "2026-09-06"))
        assertTrue(repository.pendingCandidates().isEmpty())
        assertEquals(0L, repository.committedRevision("plan-v7", "2026-09-06"))
    }

    /** The migrated schema accepts a complete row written by the new commit path. */
    @Test
    fun `migrated table accepts a complete committed snapshot`() = runBlocking {
        val repository = WorkdayOverrideRepository(db.workdayOverrideDao(), db.dayOverrideCommitDao())
        val stored = requireNotNull(
            repository.commitCurrent(
                planId = "plan-v7",
                date = "2026-09-08",
                replacement = SingleDayOverride(
                    planId = "plan-v7",
                    date = "2026-09-08",
                    status = DayStatus.WORKDAY,
                    wakeLocalTime = "06:15",
                    preparationMinutes = 0,
                    weatherProfile = com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile(0, 30, 60),
                ),
                now = FIXED_NOW,
            ),
        )

        assertEquals(1L, stored.committedRevision)
        val reloaded = requireNotNull(repository.getForPlanDate("plan-v7", "2026-09-08"))
        assertEquals("06:15", reloaded.wakeLocalTime)
        assertEquals(0, reloaded.preparationMinutes)
        assertNotNull(reloaded.weatherProfile)
    }

    private fun createV7Fixture() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE alarm_plans (id TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL, enabled INTEGER NOT NULL, zone_id TEXT NOT NULL, default_wake_local_time TEXT NOT NULL, arrival_local_time TEXT NOT NULL, preparation_minutes INTEGER NOT NULL, max_advance_minutes INTEGER NOT NULL, commute_mode TEXT NOT NULL, origin TEXT, destination TEXT, waypoints TEXT NOT NULL, route_policy TEXT NOT NULL, weather_rule_version TEXT NOT NULL, sound TEXT NOT NULL, vibration TEXT NOT NULL, snooze_minutes INTEGER NOT NULL, schedule TEXT, armed_state TEXT NOT NULL, schedule_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            sqlite.execSQL(
                "CREATE TABLE alarm_decisions (decision_id TEXT NOT NULL, plan_id TEXT NOT NULL, plan_revision INTEGER NOT NULL, target_date TEXT NOT NULL, workday_status TEXT, estimated_departure_at TEXT, commute_seconds INTEGER, weather_severity INTEGER NOT NULL, weather_buffer_minutes INTEGER NOT NULL, recommended_wake_at TEXT NOT NULL, route_provider TEXT, route_provider_report_time TEXT, weather_provider TEXT, weather_provider_report_time TEXT, weather_window_start TEXT, weather_window_end TEXT, fallback_reason TEXT NOT NULL, insufficient_advance INTEGER NOT NULL, generated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, evaluation_outcome TEXT NOT NULL DEFAULT 'FAILED', failure_reason TEXT, attempt_number INTEGER NOT NULL DEFAULT 0, application_outcome TEXT, preparation_minutes INTEGER NOT NULL DEFAULT 0, default_wake_at TEXT, actual_wake_at TEXT, calendar_source TEXT, weather_data_source TEXT, plan_name TEXT, zone_id TEXT, arrival_local_time TEXT, day_revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(decision_id))",
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
            // Exactly the v7 shape produced by the V6_TO_V7 migration.
            sqlite.execSQL(
                """
                CREATE TABLE workday_overrides (
                    plan_id TEXT NOT NULL,
                    date TEXT NOT NULL,
                    status TEXT,
                    wake_local_time TEXT,
                    arrival_local_time TEXT,
                    preparation_minutes INTEGER,
                    weather_severity1_minutes INTEGER,
                    weather_severity2_minutes INTEGER,
                    weather_severity3_minutes INTEGER,
                    origin TEXT,
                    destination TEXT,
                    commute_mode TEXT,
                    day_revision INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(plan_id, date),
                    FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
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
            sqlite.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, '7f4a8b8f0d9e4b3c2a1b0c9d8e7f6a5b')")
            sqlite.execSQL("PRAGMA user_version = 7")

            sqlite.execSQL(
                "INSERT INTO alarm_plans (id, revision, name, enabled, zone_id, default_wake_local_time, arrival_local_time, preparation_minutes, max_advance_minutes, commute_mode, origin, destination, waypoints, route_policy, weather_rule_version, sound, vibration, snooze_minutes, schedule, armed_state, schedule_error, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("plan-v7", 3, "V7 通勤", 1, "Asia/Shanghai", "06:30", "09:00", 30, 60, "DRIVING", null, null, "[]", "DEFAULT", "v1", "{\"title\":\"Default\"}", "{\"enabled\":true,\"patternMillis\":[0,500,500,500]}", 10, null, "NEEDS_RULE", null, 1_000L, 2_000L),
            )
            sqlite.execSQL(
                "INSERT INTO workday_overrides (plan_id, date, status, wake_local_time, preparation_minutes, day_revision) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("plan-v7", "2026-09-05", "WORKDAY", "07:10", 45, 4),
            )
            sqlite.execSQL(
                "INSERT INTO workday_overrides (plan_id, date, status, wake_local_time, day_revision) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("plan-v7", "2026-09-06", "HOLIDAY", null, 1),
            )
            sqlite.execSQL(
                "INSERT INTO alarm_decisions (decision_id, plan_id, plan_revision, target_date, weather_severity, weather_buffer_minutes, recommended_wake_at, fallback_reason, insufficient_advance, generated_at, expires_at, day_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("decision-v7-high", "plan-v7", 3, "2026-09-05", 0, 0, "2026-09-05T06:30", "NONE", 0, 1_000L, 2_000L, 9),
            )
            sqlite.execSQL(
                "INSERT INTO alarm_decisions (decision_id, plan_id, plan_revision, target_date, weather_severity, weather_buffer_minutes, recommended_wake_at, fallback_reason, insufficient_advance, generated_at, expires_at, day_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("decision-v7-orphan", "plan-v7", 3, "2026-09-07", 0, 0, "2026-09-07T06:30", "NONE", 0, 1_000L, 2_000L, 7),
            )
        }
    }
}
