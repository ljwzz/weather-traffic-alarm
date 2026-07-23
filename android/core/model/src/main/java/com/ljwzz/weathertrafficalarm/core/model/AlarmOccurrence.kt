package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class OccurrenceState {
    DEFAULT_REGISTERED,
    ADVANCED,
    FIRING,
    SNOOZED,
    DISMISSED,
    MISSED,
    CANCELLED,
}

@Serializable
data class AlarmOccurrence(
    val occurrenceId: String,
    val planId: String,
    val planRevision: Long,
    val targetDate: String, // LocalDate as ISO string
    val scheduledWakeAt: Long, // Instant epoch millis
    val state: OccurrenceState = OccurrenceState.DEFAULT_REGISTERED,
    val decisionId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
