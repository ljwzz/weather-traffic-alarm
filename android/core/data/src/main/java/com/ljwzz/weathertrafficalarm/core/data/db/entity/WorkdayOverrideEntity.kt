package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.ljwzz.weathertrafficalarm.core.data.db.converter.Converters
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef

/**
 * Persisted single-day override for one plan and date. The composite primary key,
 * status and wake time are the pre-N004 baseline; every added column is nullable so
 * migrated rows keep the old behaviour exactly.
 */
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
@ColumnTypeConverters(Converters::class)
data class WorkdayOverrideEntity(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "date") val date: String,
    /** Null keeps the calendar classification for this date (N004); legacy rows always carry a value. */
    @ColumnInfo(name = "status") val status: DayStatus? = null,
    @ColumnInfo(name = "wake_local_time") val wakeLocalTime: String? = null,
    @ColumnInfo(name = "arrival_local_time") val arrivalLocalTime: String? = null,
    @ColumnInfo(name = "preparation_minutes") val preparationMinutes: Int? = null,
    @ColumnInfo(name = "weather_severity1_minutes") val weatherSeverity1Minutes: Int? = null,
    @ColumnInfo(name = "weather_severity2_minutes") val weatherSeverity2Minutes: Int? = null,
    @ColumnInfo(name = "weather_severity3_minutes") val weatherSeverity3Minutes: Int? = null,
    @ColumnInfo(name = "origin") val origin: PlaceRef? = null,
    @ColumnInfo(name = "destination") val destination: PlaceRef? = null,
    @ColumnInfo(name = "commute_mode") val commuteMode: CommuteMode? = null,
    @ColumnInfo(name = "day_revision", defaultValue = "0") val dayRevision: Long = 0,
)
