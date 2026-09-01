package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.ColumnTypeConverters
import com.ljwzz.weathertrafficalarm.core.data.db.converter.Converters
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern

@Entity(tableName = "alarm_plans")
@ColumnTypeConverters(Converters::class)
data class AlarmPlanEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "default_wake_local_time") val defaultWakeLocalTime: String,
    @ColumnInfo(name = "arrival_local_time") val arrivalLocalTime: String,
    @ColumnInfo(name = "preparation_minutes") val preparationMinutes: Int,
    @ColumnInfo(name = "max_advance_minutes") val maxAdvanceMinutes: Int,
    @ColumnInfo(name = "commute_mode") val commuteMode: CommuteMode,
    @ColumnInfo(name = "origin") val origin: PlaceRef?,
    @ColumnInfo(name = "destination") val destination: PlaceRef?,
    @ColumnInfo(name = "waypoints") val waypoints: List<PlaceRef>,
    @ColumnInfo(name = "route_policy") val routePolicy: RoutePolicy,
    @ColumnInfo(name = "weather_rule_version") val weatherRuleVersion: String,
    @ColumnInfo(name = "sound") val sound: AlarmSound,
    @ColumnInfo(name = "vibration") val vibration: VibrationPattern,
    @ColumnInfo(name = "snooze_minutes") val snoozeMinutes: Int,
    @ColumnInfo(name = "schedule") val schedule: AlarmSchedule? = null,
    @ColumnInfo(name = "armed_state") val armedState: AlarmArmedState = AlarmArmedState.DISABLED,
    @ColumnInfo(name = "schedule_error") val scheduleError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
