package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Independent day revision per plan and date (database v8).
 *
 * [committedRevision] is the revision that evaluation, the queue and the page read.
 * [lastIssuedRevision] is the upper bound already handed to a candidate change, so a failed or
 * interrupted candidate never re-uses its number. The row survives deleting the override row,
 * which keeps undo-then-recreate strictly increasing.
 */
@Entity(
    tableName = "workday_day_revisions",
    primaryKeys = ["plan_id", "date"],
    foreignKeys = [
        ForeignKey(
            entity = AlarmPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("plan_id"),
    ],
)
data class WorkdayDayRevisionEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "committed_revision", defaultValue = "0") val committedRevision: Long = 0,
    @ColumnInfo(name = "last_issued_revision", defaultValue = "0") val lastIssuedRevision: Long = 0,
)
