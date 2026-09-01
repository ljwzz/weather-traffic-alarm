package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType

@Entity(
    tableName = "alarm_events",
    foreignKeys = [
        ForeignKey(
            entity = AlarmPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id"), Index("occurrence_id"), Index("created_at")],
)
data class AlarmEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
    @ColumnInfo(name = "type") val type: AlarmEventType,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
