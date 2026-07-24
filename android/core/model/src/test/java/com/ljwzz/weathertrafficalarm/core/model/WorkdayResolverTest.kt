package com.ljwzz.weathertrafficalarm.core.model

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkdayResolverTest {

    private val emptyResolver = WorkdayResolver()
    private val officialCalendar = mapOf(
        "2026-10-01" to DayStatus.HOLIDAY, // National Day
        "2026-10-02" to DayStatus.HOLIDAY,
        "2026-10-03" to DayStatus.HOLIDAY,
        "2026-10-04" to DayStatus.HOLIDAY,
        "2026-10-05" to DayStatus.HOLIDAY,
        "2026-10-06" to DayStatus.HOLIDAY,
        "2026-10-07" to DayStatus.HOLIDAY,
        "2026-09-27" to DayStatus.WORKDAY, // makeup workday
    )
    private val resolver = WorkdayResolver(officialCalendar)

    @Test
    fun `weekday fallback returns WORKDAY for Monday`() {
        val monday = LocalDate.of(2026, 7, 27)
        assertEquals(DayStatus.WORKDAY, emptyResolver.resolve(monday))
    }

    @Test
    fun `weekday fallback returns HOLIDAY for Saturday`() {
        val saturday = LocalDate.of(2026, 7, 25)
        assertEquals(DayStatus.HOLIDAY, emptyResolver.resolve(saturday))
    }

    @Test
    fun `official calendar overrides weekday fallback`() {
        val makeUpSunday = LocalDate.of(2026, 9, 27)
        assertEquals(DayStatus.WORKDAY, resolver.resolve(makeUpSunday))
    }

    @Test
    fun `official calendar marks National Day as HOLIDAY`() {
        val nationalDay = LocalDate.of(2026, 10, 1)
        assertEquals(DayStatus.HOLIDAY, resolver.resolve(nationalDay))
    }

    @Test
    fun `user override takes highest priority`() {
        val date = LocalDate.of(2026, 7, 27) // Monday
        val overrides = mapOf("2026-07-27" to DayStatus.HOLIDAY)
        assertEquals(DayStatus.HOLIDAY, resolver.resolve(date, overrides))
    }

    @Test
    fun `nextWorkday returns itself on a workday`() {
        val monday = LocalDate.of(2026, 7, 27)
        val next = emptyResolver.nextWorkday(monday)
        assertEquals(monday, next)
    }

    @Test
    fun `nextWorkday finds next weekday from Saturday`() {
        val saturday = LocalDate.of(2026, 7, 25)
        val next = emptyResolver.nextWorkday(saturday)
        assertEquals(LocalDate.of(2026, 7, 27), next) // Monday
    }

    @Test
    fun `nextWorkday returns null beyond maxDaysAhead`() {
        // Use a resolver with maxDaysAhead=0: today itself must be workday
        val result = emptyResolver.nextWorkday(
            LocalDate.of(2026, 7, 25), // Saturday
            maxDaysAhead = 0,
        )
        assertNull(result)
    }
}
