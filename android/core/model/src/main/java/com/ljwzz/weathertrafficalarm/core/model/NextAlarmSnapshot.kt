package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

/**
 * Minimal snapshot stored in device-protected storage for Direct Boot recovery.
 * Contains no locations, addresses, or tokens.
 */
@Serializable
data class NextAlarmSnapshot(
    val occurrenceId: String,
    val planId: String,
    val planRevision: Long,
    val triggerAtMillis: Long,
    val soundUri: String?,
    val vibrationEnabled: Boolean,
    val snoozeMinutes: Int,
)
