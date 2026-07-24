package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus

@Entity(
    tableName = "alarm_decisions",
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
data class AlarmDecisionEntity(
    @PrimaryKey @ColumnInfo(name = "decision_id") val decisionId: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "plan_revision") val planRevision: Long,
    @ColumnInfo(name = "target_date") val targetDate: String,
    @ColumnInfo(name = "workday_status") val workdayStatus: WorkdayStatus?,
    @ColumnInfo(name = "estimated_departure_at") val estimatedDepartureAt: String?,
    @ColumnInfo(name = "commute_seconds") val commuteSeconds: Long?,
    @ColumnInfo(name = "weather_severity") val weatherSeverity: Int,
    @ColumnInfo(name = "weather_buffer_minutes") val weatherBufferMinutes: Int,
    @ColumnInfo(name = "recommended_wake_at") val recommendedWakeAt: String,
    @ColumnInfo(name = "route_provider") val routeProvider: String?,
    @ColumnInfo(name = "route_provider_report_time") val routeProviderReportTime: String?,
    @ColumnInfo(name = "weather_provider") val weatherProvider: String?,
    @ColumnInfo(name = "weather_provider_report_time") val weatherProviderReportTime: String?,
    @ColumnInfo(name = "weather_window_start") val weatherWindowStart: String?,
    @ColumnInfo(name = "weather_window_end") val weatherWindowEnd: String?,
    @ColumnInfo(name = "fallback_reason") val fallbackReason: FallbackReason,
    @ColumnInfo(name = "insufficient_advance") val insufficientAdvance: Boolean,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
