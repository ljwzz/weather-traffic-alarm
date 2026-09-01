package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.ljwzz.weathertrafficalarm.core.data.db.converter.Converters
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef

@Entity(
    tableName = "plan_commute_overrides",
    primaryKeys = ["plan_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlarmPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("plan_id")],
)
@ColumnTypeConverters(Converters::class)
data class PlanCommuteOverrideEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "origin") val origin: PlaceRef,
    @ColumnInfo(name = "destination") val destination: PlaceRef,
    @ColumnInfo(name = "commute_mode") val commuteMode: CommuteMode,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
