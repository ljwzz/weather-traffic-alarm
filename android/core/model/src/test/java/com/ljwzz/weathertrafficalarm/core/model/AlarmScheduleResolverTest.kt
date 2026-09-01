package com.ljwzz.weathertrafficalarm.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AlarmScheduleResolverTest {
    private fun plan(
        id: String = "plan-1",
        zone: String = "Asia/Shanghai",
        time: String = "06:30",
        schedule: AlarmSchedule,
    ) = AlarmPlan(
        id = id,
        revision = 1,
        name = "Alarm",
        enabled = true,
        zoneId = zone,
        defaultWakeLocalTime = time,
        arrivalLocalTime = AlarmPlan.DEFAULT_ARRIVAL_TIME,
        preparationMinutes = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
        maxAdvanceMinutes = AlarmPlan.DEFAULT_MAX_ADVANCE_MINUTES,
        commuteMode = CommuteMode.DRIVING,
        schedule = schedule,
    )

    @Test
    fun `once is strictly after input instant`() {
        val value = AlarmScheduleResolver.next(
            plan(schedule = AlarmSchedule.Once("2026-08-31")),
            Instant.parse("2026-08-30T22:00:00Z"),
        )
        assertEquals(Instant.parse("2026-08-30T22:30:00Z"), value)

        val afterWake = AlarmScheduleResolver.next(
            plan(schedule = AlarmSchedule.Once("2026-08-31")),
            Instant.parse("2026-08-30T22:30:00Z"),
        )
        assertNull(afterWake)
    }

    @Test
    fun `weekly crosses year boundary`() {
        val value = AlarmScheduleResolver.next(
            plan(time = "00:15", schedule = AlarmSchedule.Weekly(setOf(1))),
            Instant.parse("2026-12-31T23:00:00Z"),
        )
        val expected = LocalDate.of(2027, 1, 4).atTime(0, 15).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
        assertEquals(expected, value)
    }

    @Test
    fun `workday override takes priority over calendar and weekday`() {
        val alarm = plan(schedule = AlarmSchedule.Workdays)
        val value = AlarmScheduleResolver.next(
            alarm,
            Instant.parse("2026-08-28T00:00:00Z"),
            calendar = mapOf("2026-08-29" to DayStatus.HOLIDAY),
            overrides = listOf(WorkdayOverride(alarm.id, "2026-08-29", DayStatus.WORKDAY, "07:10")),
        )
        assertEquals(Instant.parse("2026-08-28T23:10:00Z"), value)
    }

    @Test
    fun `weekly explicit workday override enables overtime date and ignores other plans`() {
        val alarm = plan(schedule = AlarmSchedule.Weekly(setOf(1)))
        val value = AlarmScheduleResolver.next(
            alarm,
            Instant.parse("2026-08-28T00:00:00Z"),
            overrides = listOf(
                WorkdayOverride("other-plan", "2026-08-29", DayStatus.WORKDAY),
                WorkdayOverride(alarm.id, "2026-08-29", DayStatus.WORKDAY),
            ),
        )
        assertEquals(Instant.parse("2026-08-28T22:30:00Z"), value)
    }

    @Test
    fun `DST gap preserves local minute offset after gap`() {
        val value = AlarmScheduleResolver.next(
            plan(zone = "America/New_York", time = "02:30", schedule = AlarmSchedule.Once("2027-03-14")),
            Instant.parse("2027-03-13T00:00:00Z"),
        )
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), value)
    }
}
