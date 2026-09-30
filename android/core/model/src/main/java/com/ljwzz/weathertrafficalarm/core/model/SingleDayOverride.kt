package com.ljwzz.weathertrafficalarm.core.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Raw calendar category of a date, before any user single-day override. */
enum class DayKind {
    WORKDAY,
    WEEKEND_REST,
    STATUTORY_REST,
}

/** Origin of a [DayClassification]: official holiday data or the local weekday fallback. */
enum class DaySource {
    HOLIDAY_CN,
    WEEKDAY_FALLBACK,
}

/**
 * A date's calendar category and the status actually applied after the user override.
 * Adding a workday override never rewrites the original rest category, so weather
 * buffer selection keeps using [baseDayKind].
 */
data class DayClassification(
    val date: LocalDate,
    val baseDayKind: DayKind,
    val source: DaySource,
    val effectiveStatus: DayStatus,
) {
    /** True when the user override, not the calendar, produced [effectiveStatus]. */
    val overriddenByUser: Boolean
        get() = when (baseDayKind) {
            DayKind.WORKDAY -> effectiveStatus != DayStatus.WORKDAY
            DayKind.WEEKEND_REST, DayKind.STATUTORY_REST -> effectiveStatus != DayStatus.HOLIDAY
        }

    val weatherDayKind: WeatherDayKind
        get() = when (baseDayKind) {
            DayKind.WORKDAY -> WeatherDayKind.WORKDAY
            DayKind.WEEKEND_REST -> WeatherDayKind.WEEKEND
            DayKind.STATUTORY_REST -> WeatherDayKind.STATUTORY_REST
        }
}

/**
 * Complete single-day override for one plan and date. Every optional field inherits
 * from the plan or the global defaults when absent; `0` is a valid value and never
 * means "absent". All three weather buffer values and the commute combination are
 * replaced as one unit.
 */
data class SingleDayOverride(
    val planId: String,
    val date: String,
    /** `null` keeps the calendar classification for this date. */
    val status: DayStatus? = null,
    val wakeLocalTime: String? = null,
    val arrivalLocalTime: String? = null,
    val preparationMinutes: Int? = null,
    val weatherProfile: WeatherBufferProfile? = null,
    val origin: PlaceRef? = null,
    val destination: PlaceRef? = null,
    val commuteMode: CommuteMode? = null,
    /** Starts at 0; every persisted save or undo of this day increments it. */
    val dayRevision: Long = 0,
) {
    init {
        require(preparationMinutes == null || preparationMinutes in 0..240) {
            "preparationMinutes must be 0-240"
        }
        require(origin == null || destination == null || origin != destination) {
            "origin and destination must differ"
        }
        if (commuteMode != null && commuteMode != CommuteMode.DRIVING) {
            require(origin != null && destination != null) {
                "a day-level commute override requires origin and destination"
            }
        }
    }

    /** True when the day-level commute combination replaces the plan and global pair. */
    val hasCompleteCommute: Boolean
        get() = origin != null && destination != null && commuteMode != null

    /**
     * True when this override carries no user-visible change and can be removed so the
     * date inherits every field again. The day revision alone does not keep a row alive.
     */
    val isInheritingEverything: Boolean
        get() = status == null &&
            wakeLocalTime == null &&
            arrivalLocalTime == null &&
            preparationMinutes == null &&
            weatherProfile == null &&
            !hasCompleteCommute

    companion object {
        /** Rejects malformed persisted values instead of silently inheriting a different time. */
        fun parseLocalTime(value: String?): LocalTime? {
            if (value == null) return null
            return try {
                LocalTime.parse(value)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Fully resolved single-day inputs shared by evaluation, scheduling and page summaries.
 * Field sources keep the inherited value auditable per field.
 */
data class EffectiveDailySettings(
    val planId: String,
    val date: LocalDate,
    val classification: DayClassification,
    val defaultWakeLocalTime: String,
    val arrivalLocalTime: String,
    val preparationMinutes: Int,
    val weatherProfile: WeatherBufferProfile,
    val wakeSource: DailySettingSource,
    val arrivalSource: DailySettingSource,
    val preparationSource: DailySettingSource,
    val weatherProfileSource: DailySettingSource,
    val dayRevision: Long,
    /** Null only when no day, plan or global commute is usable. */
    val commute: CommuteResolution?,
) {
    val wakeLocalTime: LocalTime? get() = SingleDayOverride.parseLocalTime(defaultWakeLocalTime)
    val arrivalLocalTimeValue: LocalTime? get() = SingleDayOverride.parseLocalTime(arrivalLocalTime)
}

/** Per-field origin of an effective daily value. */
enum class DailySettingSource {
    DAY_OVERRIDE,
    PLAN,
    GLOBAL,
}

/** Which tier produced an effective commute combination. */
enum class CommuteSource {
    GLOBAL,
    PLAN_OVERRIDE,
    DAY_OVERRIDE,
}

/** Effective commute combination plus the tier that produced it. */
data class CommuteResolution(
    val origin: PlaceRef,
    val destination: PlaceRef,
    val commuteMode: CommuteMode,
    val source: CommuteSource,
)
