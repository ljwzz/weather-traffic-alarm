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

    /**
     * Decision and occurrence rows are immutable history. They must outlive a plan so a user can
     * inspect the decision that caused an earlier alarm after editing or deleting that plan.
     */
    val V4_TO_V5: Migration = Migration(4, 5) { db ->
        db.execSQL(
            """
            CREATE TABLE alarm_decisions_v5 (
                decision_id TEXT NOT NULL,
                plan_id TEXT NOT NULL,
                plan_revision INTEGER NOT NULL,
                target_date TEXT NOT NULL,
                workday_status TEXT,
                estimated_departure_at TEXT,
                commute_seconds INTEGER,
                weather_severity INTEGER NOT NULL,
                weather_buffer_minutes INTEGER NOT NULL,
                recommended_wake_at TEXT NOT NULL,
                route_provider TEXT,
                route_provider_report_time TEXT,
                weather_provider TEXT,
                weather_provider_report_time TEXT,
                weather_window_start TEXT,
                weather_window_end TEXT,
                fallback_reason TEXT NOT NULL,
                insufficient_advance INTEGER NOT NULL,
                generated_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                evaluation_outcome TEXT NOT NULL DEFAULT 'FAILED',
                failure_reason TEXT,
                attempt_number INTEGER NOT NULL DEFAULT 0,
                application_outcome TEXT,
                preparation_minutes INTEGER NOT NULL DEFAULT 0,
                default_wake_at TEXT,
                actual_wake_at TEXT,
                calendar_source TEXT,
                weather_data_source TEXT,
                PRIMARY KEY(decision_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO alarm_decisions_v5
            SELECT decision_id, plan_id, plan_revision, target_date, workday_status,
                estimated_departure_at, commute_seconds, weather_severity,
                weather_buffer_minutes, recommended_wake_at, route_provider,
                route_provider_report_time, weather_provider, weather_provider_report_time,
                weather_window_start, weather_window_end, fallback_reason, insufficient_advance,
                generated_at, expires_at, evaluation_outcome, failure_reason, attempt_number,
                application_outcome, preparation_minutes, default_wake_at, actual_wake_at,
                calendar_source, weather_data_source
            FROM alarm_decisions
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE alarm_decisions")
        db.execSQL("ALTER TABLE alarm_decisions_v5 RENAME TO alarm_decisions")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_decisions_plan_id ON alarm_decisions(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_decisions_target_date ON alarm_decisions(target_date)")

        db.execSQL(
            """
            CREATE TABLE alarm_occurrences_v5 (
                occurrence_id TEXT NOT NULL,
                plan_id TEXT NOT NULL,
                plan_revision INTEGER NOT NULL,
                target_date TEXT NOT NULL,
                scheduled_wake_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                decision_id TEXT,
                kind TEXT NOT NULL,
                parent_occurrence_id TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(occurrence_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO alarm_occurrences_v5
            SELECT occurrence_id, plan_id, plan_revision, target_date, scheduled_wake_at,
                state, decision_id, kind, parent_occurrence_id, updated_at
            FROM alarm_occurrences
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE alarm_occurrences")
        db.execSQL("ALTER TABLE alarm_occurrences_v5 RENAME TO alarm_occurrences")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_occurrences_plan_id ON alarm_occurrences(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_occurrences_target_date ON alarm_occurrences(target_date)")
    }

    /** Adds immutable display metadata to decision history without reading a later plan revision. */
    val V5_TO_V6: Migration = Migration(5, 6) { db ->
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN plan_name TEXT")
        db.execSQL("ALTER TABLE alarm_decisions ADD COLUMN zone_id TEXT")
    }
}
