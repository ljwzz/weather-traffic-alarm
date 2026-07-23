package com.ljwzz.weathertrafficalarm.core.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Workday resolution with three-tier priority:
 * 1. User per-plan override
 * 2. Official signed calendar
 * 3. Weekday fallback (Mon-Fri workday, Sat-Sun holiday)
 */
class WorkdayResolver(
    private val officialCalendar: Map<String, DayStatus> = emptyMap(),
) {
    fun resolve(date: LocalDate, overrides: Map<String, DayStatus> = emptyMap()): DayStatus {
        val dateStr = date.toString()
        overrides[dateStr]?.let { return it }
        officialCalendar[dateStr]?.let { return it }
        return weekdayFallback(date)
    }

    /**
     * Find the next workday starting from [from] (inclusive).
     * Returns null if no workday found within [maxDaysAhead] days.
     */
    fun nextWorkday(
        from: LocalDate,
        overrides: Map<String, DayStatus> = emptyMap(),
        maxDaysAhead: Int = 365,
    ): LocalDate? {
        var candidate = from
        for (i in 0..maxDaysAhead) {
            if (resolve(candidate, overrides) == DayStatus.WORKDAY) {
                return candidate
            }
            candidate = candidate.plusDays(1)
        }
        return null
    }

    companion object {
        fun weekdayFallback(date: LocalDate): DayStatus {
            return when (date.dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> DayStatus.HOLIDAY
                else -> DayStatus.WORKDAY
            }
        }
    }
}
