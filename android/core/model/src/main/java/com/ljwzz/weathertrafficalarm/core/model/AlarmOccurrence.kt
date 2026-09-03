package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class OccurrenceState {
    REGISTERING,
    SCHEDULED,
    FAILED,
    DEFAULT_REGISTERED,
    ADVANCED,
    FIRING,
    SNOOZED,
    DISMISSED,
    MISSED,
    CANCELLED,
}

@Serializable
enum class OccurrenceKind {
    REGULAR,
    /** An independently armed earlier occurrence derived from an evaluation decision. */
    ADVANCE,
    SNOOZE,
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
    val kind: OccurrenceKind = OccurrenceKind.REGULAR,
    val parentOccurrenceId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
