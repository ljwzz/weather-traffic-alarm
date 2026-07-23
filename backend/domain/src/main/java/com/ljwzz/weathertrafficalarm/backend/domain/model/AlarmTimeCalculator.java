package com.ljwzz.weathertrafficalarm.backend.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure alarm time calculation (SPEC.md FR-003).
 * Must produce identical results to the Android-side implementation.
 */
public class AlarmTimeCalculator {

    public record CalculationResult(
        long recommendedWakeAtEpochMillis,
        boolean insufficientAdvance,
        FallbackReason fallbackReason
    ) {}

    public static CalculationResult calculate(
        LocalTime defaultWakeTime,
        LocalTime arrivalTime,
        int preparationMinutes,
        int maxAdvanceMinutes,
        Long commuteSeconds,
        int weatherBufferMinutes,
        LocalDate targetDate,
        ZoneId zoneId,
        Long alreadyScheduledWakeAtMillis
    ) {
        var targetDateZone = ZonedDateTime.of(targetDate, arrivalTime, zoneId);
        var defaultWakeZdt = ZonedDateTime.of(targetDate, defaultWakeTime, zoneId);
        var earliestAllowed = defaultWakeZdt.minusMinutes(maxAdvanceMinutes);

        ZonedDateTime calculatedWake;
        FallbackReason fallbackReason;

        if (commuteSeconds != null) {
            var commuteDuration = Duration.ofSeconds(commuteSeconds);
            var estimatedDeparture = targetDateZone.minus(commuteDuration);
            calculatedWake = estimatedDeparture.minusMinutes(preparationMinutes + weatherBufferMinutes);
            fallbackReason = FallbackReason.NONE;
        } else {
            calculatedWake = defaultWakeZdt;
            fallbackReason = FallbackReason.ROUTE_NOT_FOUND;
        }

        var clampedWake = calculatedWake.isBefore(earliestAllowed) ? earliestAllowed : calculatedWake;
        var recommendedWake = clampedWake.isAfter(defaultWakeZdt) ? defaultWakeZdt : clampedWake;
        var insufficientAdvance = clampedWake.equals(earliestAllowed) && calculatedWake.isBefore(earliestAllowed);

        ZonedDateTime finalWake;
        if (alreadyScheduledWakeAtMillis != null) {
            var alreadyScheduled = Instant.ofEpochMilli(alreadyScheduledWakeAtMillis).atZone(zoneId);
            finalWake = alreadyScheduled.isBefore(recommendedWake) ? alreadyScheduled : recommendedWake;
        } else {
            finalWake = recommendedWake;
        }

        return new CalculationResult(
            finalWake.toInstant().toEpochMilli(),
            insufficientAdvance,
            fallbackReason
        );
    }
}
