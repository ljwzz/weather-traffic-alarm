package com.ljwzz.weathertrafficalarm.core.model

/**
 * Minimal snapshot stored in device-protected storage for Direct Boot recovery.
 * Contains no locations, addresses, or tokens.
 */
data class NextAlarmSnapshot(
    val occurrenceId: String,
    val planId: String,
    val planRevision: Long,
    val triggerAtMillis: Long,
    val soundUri: String?,
    val vibrationEnabled: Boolean,
    val snoozeMinutes: Int,
)
