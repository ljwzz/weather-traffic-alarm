package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class AlarmPlan(
    val id: String,
    val revision: Long,
    val name: String,
    val enabled: Boolean,
    val zoneId: String,
    val defaultWakeLocalTime: String,
    val arrivalLocalTime: String,
    val preparationMinutes: Int,
    val maxAdvanceMinutes: Int,
    val commuteMode: CommuteMode,
    val origin: PlaceRef,
    val destination: PlaceRef,
    val waypoints: List<PlaceRef> = emptyList(),
    val routePolicy: RoutePolicy = RoutePolicy.DEFAULT,
    val weatherRuleVersion: String = "v1",
    val sound: AlarmSound = AlarmSound(),
    val vibration: VibrationPattern = VibrationPattern(),
    val snoozeMinutes: Int = 10,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(preparationMinutes in 0..240) { "preparationMinutes must be 0-240" }
        require(maxAdvanceMinutes in 0..180) { "maxAdvanceMinutes must be 0-180" }
        require(snoozeMinutes in 1..30) { "snoozeMinutes must be 1-30" }
        require(origin != destination) { "origin and destination must differ" }
        if (commuteMode != CommuteMode.DRIVING) {
            require(waypoints.isEmpty()) { "waypoints only supported for DRIVING" }
        }
    }

    fun withRevisionIncremented(): AlarmPlan = copy(revision = revision + 1, updatedAt = System.currentTimeMillis())

    fun zoneIdInstance(): ZoneId = ZoneId.of(zoneId)

    companion object {
        val DEFAULT_WAKE_TIME = "06:00"
        const val DEFAULT_MAX_ADVANCE_MINUTES = 60
        const val DEFAULT_PREPARATION_MINUTES = 30
    }
}
