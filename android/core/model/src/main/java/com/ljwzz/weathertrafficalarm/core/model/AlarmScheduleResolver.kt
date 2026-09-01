package com.ljwzz.weathertrafficalarm.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition

/** Resolves the first schedule occurrence strictly after [after]. */
object AlarmScheduleResolver {
    private const val MAX_LOOK_AHEAD_DAYS = 366 * 6

    fun next(
        plan: AlarmPlan,
        after: Instant,
        calendar: Map<String, DayStatus> = emptyMap(),
        overrides: List<WorkdayOverride> = emptyList(),
    ): Instant? {
        val schedule = plan.schedule ?: return null
        val zone = plan.zoneIdInstance()
        val time = LocalTime.parse(plan.defaultWakeLocalTime)
        val afterDate = after.atZone(zone).toLocalDate()
        val overridesByDate = overrides
            .asSequence()
            .filter { it.planId == plan.id }
            .associateBy { it.date }

        when (schedule) {
            is AlarmSchedule.Once -> {
                val date = LocalDate.parse(schedule.date)
                val override = overridesByDate[date.toString()] ?: return localInstant(date, time, zone)
                    .takeIf { it > after }
                if (override.status == DayStatus.HOLIDAY) return null
                val wake = override.wakeLocalTime?.let(LocalTime::parse) ?: time
                return localInstant(date, wake, zone).takeIf { it > after }
            }
            is AlarmSchedule.Weekly,
            AlarmSchedule.Workdays,
            -> Unit
        }

        var candidate = afterDate
        repeat(MAX_LOOK_AHEAD_DAYS) {
            val override = overridesByDate[candidate.toString()]
            val eligible = when (schedule) {
                is AlarmSchedule.Weekly -> when (override?.status) {
                    DayStatus.WORKDAY -> true
                    DayStatus.HOLIDAY -> false
                    null -> candidate.dayOfWeek.value in schedule.days
                }
                AlarmSchedule.Workdays -> resolveDayStatus(candidate, calendar, override) == DayStatus.WORKDAY
                is AlarmSchedule.Once -> false
            }
            if (eligible) {
                val wake = override?.wakeLocalTime?.let(LocalTime::parse) ?: time
                val instant = localInstant(candidate, wake, zone)
                if (instant > after) return instant
            }
            candidate = candidate.plusDays(1)
        }
        return null
    }

    private fun resolveDayStatus(
        date: LocalDate,
        calendar: Map<String, DayStatus>,
        override: WorkdayOverride?,
    ): DayStatus = override?.status ?: calendar[date.toString()] ?: WorkdayResolver.weekdayFallback(date)

    /** Resolves DST overlaps to the earlier instant and advances gaps by the transition duration. */
    private fun localInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when {
            offsets.isNotEmpty() -> local.atOffset(offsets.first()).toInstant()
            else -> {
                val transition: ZoneOffsetTransition = requireNotNull(rules.getTransition(local))
                local.plusSeconds(transition.duration.seconds).atZone(zone).toInstant()
            }
        }
    }
}
