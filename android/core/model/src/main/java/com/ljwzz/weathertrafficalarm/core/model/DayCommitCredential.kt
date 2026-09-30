package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

/**
 * Minimal device-protected credential proving that a candidate single-day change was published.
 *
 * It carries no draft values: the full replacement lives in Room. Recovery uses it to decide
 * whether an interrupted change must be completed or discarded, so it is written in the same
 * device-protected update as the candidate snapshot.
 */
@Serializable
data class DayCommitCredential(
    val changeId: String,
    val planId: String,
    val date: String,
    val dayRevision: Long,
    val occurrenceId: String? = null,
    val targetDate: String? = null,
    val triggerAtMillis: Long? = null,
)
