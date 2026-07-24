package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState

@Entity(
    tableName = "alarm_occurrences",
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
        Index("target_date"),
    ],
)
data class AlarmOccurrenceEntity(
    @PrimaryKey @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "plan_revision") val planRevision: Long,
    @ColumnInfo(name = "target_date") val targetDate: String,
    @ColumnInfo(name = "scheduled_wake_at") val scheduledWakeAt: Long,
    @ColumnInfo(name = "state") val state: OccurrenceState,
    @ColumnInfo(name = "decision_id") val decisionId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
