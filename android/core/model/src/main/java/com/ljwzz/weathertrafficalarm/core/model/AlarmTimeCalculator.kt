package com.ljwzz.weathertrafficalarm.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure alarm time calculation functions.
 * From SPEC.md FR-003:
 *
 * calculatedWake = estimatedDeparture - preparation - weatherBuffer
 * earliestAllowed = defaultWake - maxAdvance
 * clampedWake = max(earliestAllowed, calculatedWake)
 * recommendedWake = min(defaultWake, clampedWake)
 * finalWake = min(alreadyScheduledWake, recommendedWake)
 */
object AlarmTimeCalculator {

    data class CalculationResult(
        val recommendedWakeAt: Long, // epoch millis
        val insufficientAdvance: Boolean,
        val fallbackReason: FallbackReason?,
    )

    /**
     * Calculate the recommended wake time.
     *
     * @param defaultWakeTime default wake LocalTime
     * @param arrivalTime target arrival LocalTime
     * @param preparationMinutes time needed to get ready
     * @param maxAdvanceMinutes max minutes alarm can be advanced
     * @param commuteSeconds estimated commute duration (null if unavailable)
     * @param weatherBufferMinutes extra buffer for weather
     * @param targetDate the date of commute
     * @param zoneId plan's timezone
     * @param alreadyScheduledWakeAt already registered alarm time (null for new occurrences)
     * @return CalculationResult with the final recommended time
     */
    fun calculate(
        defaultWakeTime: LocalTime,
        arrivalTime: LocalTime,
        preparationMinutes: Int,
        maxAdvanceMinutes: Int,
        commuteSeconds: Long?,
        weatherBufferMinutes: Int,
        targetDate: LocalDate,
        zoneId: ZoneId,
        alreadyScheduledWakeAt: Long? = null,
    ): CalculationResult {
        val targetDateZone = ZonedDateTime.of(targetDate, arrivalTime, zoneId)

        // earliestAllowed = defaultWake - maxAdvance on the SAME date
        val defaultWakeZdt = ZonedDateTime.of(targetDate, defaultWakeTime, zoneId)
        val earliestAllowed = defaultWakeZdt.minusMinutes(maxAdvanceMinutes.toLong())

        // If commute is available: calculatedWake = arrival - commute - prep - weather
        val calculatedWake: ZonedDateTime = if (commuteSeconds != null) {
            val commuteDuration = Duration.ofSeconds(commuteSeconds)
            // estimatedDeparture = arrival - commute
            val estimatedDeparture = targetDateZone.minus(commuteDuration)
            estimatedDeparture.minusMinutes((preparationMinutes + weatherBufferMinutes).toLong())
        } else {
            // No commute data means no advance
            defaultWakeZdt
        }

        val clampedWake = if (calculatedWake.isBefore(earliestAllowed)) {
            earliestAllowed
        } else {
            calculatedWake
        }

        val recommendedWake = if (clampedWake.isAfter(defaultWakeZdt)) {
            defaultWakeZdt
        } else {
            clampedWake
        }

        val insufficientAdvance = clampedWake == earliestAllowed && calculatedWake.isBefore(earliestAllowed)

        val finalWake = if (alreadyScheduledWakeAt != null) {
            val alreadyScheduled = Instant.ofEpochMilli(alreadyScheduledWakeAt).atZone(zoneId)
            if (alreadyScheduled.isBefore(recommendedWake)) alreadyScheduled else recommendedWake
        } else {
            recommendedWake
        }

        return CalculationResult(
            recommendedWakeAt = finalWake.toInstant().toEpochMilli(),
            insufficientAdvance = insufficientAdvance,
            fallbackReason = if (commuteSeconds == null) FallbackReason.ROUTE_NOT_FOUND else null,
        )
    }
}
