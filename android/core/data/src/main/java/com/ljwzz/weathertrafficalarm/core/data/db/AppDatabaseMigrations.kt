package com.ljwzz.weathertrafficalarm.core.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/** Explicit non-destructive migration from the original commute-plan schema. */
object AppDatabaseMigrations {
    val V1_TO_V2: Migration = Migration(1, 2) { db ->
        // Keep dependent tables intact while rebuilding the parent with nullable places.
        db.execSQL("PRAGMA legacy_alter_table = ON")
        db.execSQL("ALTER TABLE alarm_plans RENAME TO alarm_plans_v1")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS alarm_plans (
                id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                zone_id TEXT NOT NULL,
                default_wake_local_time TEXT NOT NULL,
                arrival_local_time TEXT NOT NULL,
                preparation_minutes INTEGER NOT NULL,
                max_advance_minutes INTEGER NOT NULL,
                commute_mode TEXT NOT NULL,
                origin TEXT,
                destination TEXT,
                waypoints TEXT NOT NULL,
                route_policy TEXT NOT NULL,
                weather_rule_version TEXT NOT NULL,
                sound TEXT NOT NULL,
                vibration TEXT NOT NULL,
                snooze_minutes INTEGER NOT NULL,
                schedule TEXT,
                armed_state TEXT NOT NULL,
                schedule_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO alarm_plans (
                id, revision, name, enabled, zone_id, default_wake_local_time,
                arrival_local_time, preparation_minutes, max_advance_minutes, commute_mode,
                origin, destination, waypoints, route_policy, weather_rule_version, sound,
                vibration, snooze_minutes, schedule, armed_state, schedule_error, created_at, updated_at
            )
            SELECT
                id, revision, name, 0, zone_id, default_wake_local_time,
                arrival_local_time, preparation_minutes, max_advance_minutes, commute_mode,
                origin, destination, waypoints, route_policy, weather_rule_version, sound,
                vibration, snooze_minutes, NULL, 'NEEDS_RULE', NULL, created_at, updated_at
            FROM alarm_plans_v1
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE alarm_plans_v1")

        db.execSQL(
            "ALTER TABLE alarm_occurrences ADD COLUMN kind TEXT NOT NULL DEFAULT 'REGULAR'",
        )
        db.execSQL("ALTER TABLE alarm_occurrences ADD COLUMN parent_occurrence_id TEXT")
        db.execSQL("ALTER TABLE workday_overrides ADD COLUMN wake_local_time TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS alarm_events (
                id TEXT NOT NULL,
                plan_id TEXT NOT NULL,
                occurrence_id TEXT,
                type TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_events_plan_id ON alarm_events(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_events_occurrence_id ON alarm_events(occurrence_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_events_created_at ON alarm_events(created_at)")
        db.execSQL("PRAGMA legacy_alter_table = OFF")
    }

    /** Preserves legacy plan-level commute locations as independent overrides. */
    val V2_TO_V3: Migration = Migration(2, 3) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_commute_overrides (
                plan_id TEXT NOT NULL,
                origin TEXT NOT NULL,
                destination TEXT NOT NULL,
                commute_mode TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(plan_id),
                FOREIGN KEY(plan_id) REFERENCES alarm_plans(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_plan_commute_overrides_plan_id ON plan_commute_overrides(plan_id)")
        db.execSQL(
            """
            INSERT INTO plan_commute_overrides (plan_id, origin, destination, commute_mode, updated_at)
            SELECT id, origin, destination, commute_mode, updated_at
            FROM alarm_plans
            WHERE origin IS NOT NULL AND destination IS NOT NULL
            """.trimIndent(),
        )
    }

    /** Adds per-evaluation outcome, retry, application, and source metadata to decision history. */
    val V3_TO_V4: Migration = Migration(3, 4) { db ->
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN evaluation_outcome TEXT NOT NULL DEFAULT 'FAILED'")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN failure_reason TEXT")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN application_outcome TEXT")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN preparation_minutes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN default_wake_at TEXT")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN actual_wake_at TEXT")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN calendar_source TEXT")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN weather_data_source TEXT")
    }
}
