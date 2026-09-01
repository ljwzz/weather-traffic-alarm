package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AlarmEvent(
    val id: String,
    val planId: String,
    val occurrenceId: String? = null,
    val type: AlarmEventType,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
enum class AlarmEventType {
    REGISTERED,
    REGISTRATION_FAILED,
    TRIGGERED,
    DISMISSED,
    SNOOZED,
    MISSED,
    CANCELLED,
}
