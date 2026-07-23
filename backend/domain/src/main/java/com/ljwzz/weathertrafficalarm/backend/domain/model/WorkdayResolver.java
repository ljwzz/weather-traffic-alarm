package com.ljwzz.weathertrafficalarm.backend.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * Workday resolution with three-tier priority:
 * 1. User per-plan override
 * 2. Official signed calendar
 * 3. Weekday fallback (Mon-Fri workday, Sat-Sun holiday)
 */
public class WorkdayResolver {

    private final Map<String, DayStatus> officialCalendar;

    public WorkdayResolver() {
        this(Map.of());
    }

    public WorkdayResolver(Map<String, DayStatus> officialCalendar) {
        this.officialCalendar = officialCalendar;
    }

    public DayStatus resolve(LocalDate date, Map<String, DayStatus> overrides) {
        var dateStr = date.toString();
        var override = overrides.get(dateStr);
        if (override != null) return override;

        var official = officialCalendar.get(dateStr);
        if (official != null) return official;

        return weekdayFallback(date);
    }

    /**
     * Find the next workday starting from {@code from} (inclusive).
     */
    public LocalDate nextWorkday(LocalDate from, Map<String, DayStatus> overrides, int maxDaysAhead) {
        var candidate = from;
        for (int i = 0; i <= maxDaysAhead; i++) {
            if (resolve(candidate, overrides) == DayStatus.WORKDAY) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return null;
    }

    public LocalDate nextWorkday(LocalDate from) {
        return nextWorkday(from, Map.of(), 365);
    }

    static DayStatus weekdayFallback(LocalDate date) {
        var dow = date.getDayOfWeek();
        return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
            ? DayStatus.HOLIDAY
            : DayStatus.WORKDAY;
    }
}
