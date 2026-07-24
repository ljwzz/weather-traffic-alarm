package com.ljwzz.weathertrafficalarm.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int? = null,
    val correlationId: String? = null,
)

@Serializable
data class AttestRequest(
    val installationId: String,
    val platform: String,
    val appVersion: String,
    val integrityToken: String? = null,
)

@Serializable
data class AttestResponse(
    val installationToken: String,
    val quotaTier: String,
    val expiresAt: String,
)

@Serializable
data class CalendarDayDto(
    val date: String,
    val status: String,
    val label: String? = null,
)

@Serializable
data class CalendarResponse(
    val country: String,
    val year: Int,
    val version: Int,
    val publishedAt: String,
    val sourceUrl: String,
    val payloadSha256: String,
    val signatureAlgorithm: String,
    val signature: String,
    val days: List<CalendarDayDto>,
)

@Serializable
data class PlaceSearchRequest(
    val query: String,
    val cityCode: String? = null,
    val pageToken: String? = null,
)

@Serializable
data class PlaceRefDto(
    val poiId: String? = null,
    val name: String,
    val displayAddress: String,
    val longitudeGcj02: Double,
    val latitudeGcj02: Double,
    val adcode: String,
    val citycode: String,
)

@Serializable
data class PlaceSearchResponse(
    val items: List<PlaceRefDto>,
    val nextPageToken: String? = null,
)

@Serializable
data class AlarmEvaluationRequest(
    val requestId: String,
    val planId: String,
    val planRevision: Long,
    val targetDate: String,
    val timezone: String,
    val defaultWakeTime: String,
    val arrivalTime: String,
    val preparationMinutes: Int,
    val maxAdvanceMinutes: Int,
    val commuteMode: String,
    val origin: PlaceRefDto,
    val destination: PlaceRefDto,
    val waypoints: List<PlaceRefDto> = emptyList(),
    val routePolicy: String,
    val weatherRuleVersion: String,
)

@Serializable
data class AlarmEvaluationResponse(
    val decisionId: String,
    val planId: String,
    val planRevision: Long,
    val targetDate: String,
    val workdayStatus: String,
    val estimatedDepartureAt: String? = null,
    val commuteSeconds: Long? = null,
    val weatherSeverity: Int,
    val weatherBufferMinutes: Int,
    val weatherRuleVersion: String,
    val recommendedWakeAt: String,
    val routeProvider: String? = null,
    val routeProviderReportTime: String? = null,
    val weatherProvider: String? = null,
    val weatherProviderReportTime: String? = null,
    val weatherWindowStart: String? = null,
    val weatherWindowEnd: String? = null,
    val fallbackReason: String,
    val insufficientAdvance: Boolean,
    val generatedAt: String,
    val expiresAt: String,
)
