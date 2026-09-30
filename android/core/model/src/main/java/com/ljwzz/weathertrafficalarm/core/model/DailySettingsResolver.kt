package com.ljwzz.weathertrafficalarm.core.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure three-tier resolution of the effective inputs for one plan and date:
 * single-day override value -> plan configuration -> global default.
 *
 * The resolver performs no I/O. Callers pass the calendar classification and the
 * already-loaded override and settings so the same result can be reused by the
 * evaluation coordinator, the scheduler and the calendar page.
 */
object DailySettingsResolver {

    /** Global fallbacks used when neither the day override nor the plan provides a value. */
    data class GlobalDefaults(
        val defaultWakeLocalTime: String = AlarmPlan.DEFAULT_WAKE_TIME,
        val arrivalLocalTime: String = AlarmPlan.DEFAULT_ARRIVAL_TIME,
        val preparationMinutes: Int = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
    )

    /**
     * Classifies [date] from the official calendar and an optional user override.
     * `holiday-cn` rows always win over the weekday fallback, and a user override only
     * changes the effective status, never the raw category used for weather buffers.
     */
    fun classify(
        date: LocalDate,
        override: SingleDayOverride?,
        officialDays: Map<String, DayStatus>,
    ): DayClassification {
        val official = officialDays[date.toString()]
        val baseDayKind = when (official) {
            DayStatus.WORKDAY -> DayKind.WORKDAY
            DayStatus.HOLIDAY -> DayKind.STATUTORY_REST
            null -> if (WeekdayFallback.isRestDay(date)) DayKind.WEEKEND_REST else DayKind.WORKDAY
        }
        val source = if (official == null) DaySource.WEEKDAY_FALLBACK else DaySource.HOLIDAY_CN
        val calendarStatus = if (baseDayKind == DayKind.WORKDAY) DayStatus.WORKDAY else DayStatus.HOLIDAY
        return DayClassification(
            date = date,
            baseDayKind = baseDayKind,
            source = source,
            effectiveStatus = override?.status ?: calendarStatus,
        )
    }

    fun resolve(
        plan: AlarmPlan,
        date: LocalDate,
        override: SingleDayOverride?,
        officialDays: Map<String, DayStatus> = emptyMap(),
        profiles: WeatherBufferProfiles = WeatherBufferProfiles(),
        commute: CommuteResolution? = null,
        globals: GlobalDefaults = GlobalDefaults(),
    ): EffectiveDailySettings {
        val classification = classify(date, override, officialDays)

        val wake = override?.wakeLocalTime
        val arrival = override?.arrivalLocalTime
        val preparation = override?.preparationMinutes

        val defaultWakeLocalTime = wake ?: plan.defaultWakeLocalTime
        val arrivalLocalTime = arrival ?: plan.arrivalLocalTime
        val preparationMinutes = preparation ?: plan.preparationMinutes

        val inheritedProfile = WeatherBufferSelector.select(classification.weatherDayKind, profiles)
        val weatherProfile = override?.weatherProfile ?: inheritedProfile

        return EffectiveDailySettings(
            planId = plan.id,
            date = date,
            classification = classification,
            defaultWakeLocalTime = defaultWakeLocalTime,
            arrivalLocalTime = arrivalLocalTime,
            preparationMinutes = preparationMinutes,
            weatherProfile = weatherProfile,
            wakeSource = if (wake != null) DailySettingSource.DAY_OVERRIDE else DailySettingSource.PLAN,
            arrivalSource = if (arrival != null) DailySettingSource.DAY_OVERRIDE else DailySettingSource.PLAN,
            preparationSource = if (preparation != null) DailySettingSource.DAY_OVERRIDE else DailySettingSource.PLAN,
            weatherProfileSource = if (override?.weatherProfile != null) DailySettingSource.DAY_OVERRIDE else DailySettingSource.GLOBAL,
            dayRevision = override?.dayRevision ?: 0,
            commute = commute,
        )
    }
}

object WeekdayFallback {
    fun isRestDay(date: LocalDate): Boolean = WorkdayResolver.weekdayFallback(date) == DayStatus.HOLIDAY
}
