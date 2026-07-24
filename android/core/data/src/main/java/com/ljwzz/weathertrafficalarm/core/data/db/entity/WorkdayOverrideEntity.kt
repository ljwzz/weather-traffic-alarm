package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.ljwzz.weathertrafficalarm.core.model.DayStatus

@Entity(
    tableName = "workday_overrides",
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
data class WorkdayOverrideEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "status") val status: DayStatus,
)
