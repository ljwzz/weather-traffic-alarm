package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorkdayStatus {
    WORKDAY,
    HOLIDAY,
}

@Serializable
enum class EvaluationOutcome {
    SUCCESS,
    FAILED,
    STALE,
    SKIPPED,
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
    val evaluationOutcome: EvaluationOutcome = EvaluationOutcome.FAILED,
    val failureReason: String? = null,
    val attemptNumber: Int = 0,
    val applicationOutcome: String? = null,
    val preparationMinutes: Int = 0,
    val defaultWakeAt: String? = null,
    val actualWakeAt: String? = null,
    val calendarSource: String? = null,
    val weatherDataSource: String? = null,
    /** Effective target-arrival local time of this evaluation; legacy rows can be absent. */
    val arrivalLocalTime: String? = null,
    /** Day revision of the single-day override this evaluation used; 0 without an override. */
    val dayRevision: Long = 0,
    /** Immutable display metadata captured during the evaluation; legacy rows can be absent. */
    val planName: String? = null,
    val zoneId: String? = null,
)
