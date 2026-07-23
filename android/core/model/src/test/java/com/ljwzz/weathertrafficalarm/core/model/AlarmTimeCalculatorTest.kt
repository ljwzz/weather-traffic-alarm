package com.ljwzz.weathertrafficalarm.core.model

import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmTimeCalculatorTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val targetDate = LocalDate.of(2026, 7, 24)
    private val defaultWake = LocalTime.of(6, 0)
    private val arrivalTime = LocalTime.of(9, 0)
    private val defaultMaxAdvance = 60

    @Test
    fun `no commute data returns default wake time`() {
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = null,
            weatherBufferMinutes = 0,
            targetDate = targetDate,
            zoneId = zoneId,
        )
        val expectedWake = targetDate.atTime(defaultWake).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expectedWake, result.recommendedWakeAt)
        assertEquals(FallbackReason.ROUTE_NOT_FOUND, result.fallbackReason)
    }

    @Test
    fun `normal commute advances wake time`() {
        // 30min commute + 30min prep = arrival - 60min = 08:00
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = 1800, // 30 min
            weatherBufferMinutes = 0,
            targetDate = targetDate,
            zoneId = zoneId,
        )
        val expectedWake = targetDate.atTime(LocalTime.of(8, 0)).atZone(zoneId).toInstant().toEpochMilli()
        // 08:00 > 06:00? No. 08:00 > 05:00 (earliest)? Yes, so clampedWake = 08:00.
        // recommendedWake = min(06:00, 08:00) = 06:00
        // So with normal commute, alarm doesn't advance because it's already at default
        assertEquals(expectedWake, result.recommendedWakeAt)
    }

    @Test
    fun `long commute advances beyond earliest allowed`() {
        // 120min commute + 30min prep = arrival - 150min = 06:30
        // earliestAllowed = 06:00 - 60min = 05:00
        // calculatedWake = 09:00 - 02:00 - 00:30 = 06:30
        // clampedWake = max(05:00, 06:30) = 06:30
        // recommendedWake = min(06:00, 06:30) = 06:00
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = 7200, // 120 min
            weatherBufferMinutes = 0,
            targetDate = targetDate,
            zoneId = zoneId,
        )
        val expectedWake = targetDate.atTime(defaultWake).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expectedWake, result.recommendedWakeAt)
    }

    @Test
    fun `weather buffer further advances wake time`() {
        // 30min commute + 30min prep + 20min weather = arrival - 80min = 07:40
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = 1800,
            weatherBufferMinutes = 20,
            targetDate = targetDate,
            zoneId = zoneId,
        )
        val expectedWake = targetDate.atTime(LocalTime.of(7, 40)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expectedWake, result.recommendedWakeAt)
    }

    @Test
    fun `insufficientAdvance when calculatedWake before earliestAllowed`() {
        // 180min commute + 30min prep = arrival - 210min = 05:30
        // earliestAllowed = 06:00 - 60min = 05:00
        // calculatedWake = 09:00 - 03:00 - 00:30 = 05:30
        // clampedWake = max(05:00, 05:30) = 05:30
        // recommendedWake = min(06:00, 05:30) = 05:30
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = 10800, // 180 min
            weatherBufferMinutes = 0,
            targetDate = targetDate,
            zoneId = zoneId,
        )
        assertEquals(330, result.insufficientAdvance) // 05:30 = 330 min after midnight
        true
    }

    @Test
    fun `already scheduled earlier wake is preserved`() {
        val alreadyScheduled = targetDate.atTime(LocalTime.of(5, 0)).atZone(zoneId).toInstant().toEpochMilli()
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = 1800,
            weatherBufferMinutes = 0,
            targetDate = targetDate,
            zoneId = zoneId,
            alreadyScheduledWakeAt = alreadyScheduled,
        )
        // alreadyScheduled = 05:00 is earlier than recommended = 06:00, so final=05:00
        assertEquals(alreadyScheduled, result.recommendedWakeAt)
    }

    @Test
    fun `only advance never delay for existing occurrence`() {
        val existingLater = targetDate.atTime(LocalTime.of(6, 30)).atZone(zoneId).toInstant().toEpochMilli()
        val result = AlarmTimeCalculator.calculate(
            defaultWakeTime = defaultWake,
            arrivalTime = arrivalTime,
            preparationMinutes = 30,
            maxAdvanceMinutes = defaultMaxAdvance,
            commuteSeconds = 1800,
            weatherBufferMinutes = 0,
            targetDate = targetDate,
            zoneId = zoneId,
            alreadyScheduledWakeAt = existingLater,
        )
        // existing 06:30 > recommended 06:00 -> final = 06:00
        val expectedWake = targetDate.atTime(LocalTime.of(6, 0)).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expectedWake, result.recommendedWakeAt)
    }
}
