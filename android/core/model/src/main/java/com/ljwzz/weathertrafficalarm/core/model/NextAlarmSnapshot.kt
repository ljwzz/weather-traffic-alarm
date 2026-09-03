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
    /**
     * Direct-Boot-safe data for the one occurrence currently armed by a plan.
     * The values intentionally exclude locations, provider data and credentials.
     */
    val alarmLabel: String = "闹钟",
    val vibrationPatternMillis: List<Long> = listOf(0, 500, 500, 500),
    val occurrenceKind: String = "REGULAR",
    /** Optional decision link used to rehydrate an independently armed advance occurrence. */
    val decisionId: String? = null,
    val parentOccurrenceId: String? = null,
    val occurrenceState: String = "SCHEDULED",
    val snoozeCount: Int = 0,
    val firedAtMillis: Long? = null,
    /** Logical wake date; may differ from trigger date when an advance crosses midnight. */
    val targetDate: String? = null,
    /** Baseline regular wake instant for Direct-Boot display of an advance occurrence. */
    val defaultWakeAtMillis: Long? = null,
)
