package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorkdayStatus {
    WORKDAY,
    HOLIDAY,
}

@Serializable
data class AlarmDecision(
    val decisionId: String,
    val planId: String,
    val planRevision: Long,
    val targetDate: String,
    val workdayStatus: WorkdayStatus?,
    val estimatedDepartureAt: String?,
    val commuteSeconds: Long?,
    val weatherSeverity: Int,
    val weatherBufferMinutes: Int,
    val recommendedWakeAt: String,
    val routeProvider: String?,
    val routeProviderReportTime: String?,
    val weatherProvider: String?,
    val weatherProviderReportTime: String?,
    val weatherWindowStart: String?,
    val weatherWindowEnd: String?,
    val fallbackReason: FallbackReason,
    val insufficientAdvance: Boolean,
    val generatedAt: String,
    val expiresAt: String,
)
