package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

/** User-selected recurrence for a locally scheduled alarm. */
@Serializable
sealed interface AlarmSchedule {
    @Serializable
    data class Once(val date: String) : AlarmSchedule {
        init {
            require(runCatching { java.time.LocalDate.parse(date) }.isSuccess) {
                "Once alarms require an ISO-8601 date"
            }
        }
    }

    @Serializable
    data class Weekly(val days: Set<Int>) : AlarmSchedule {
        init {
            require(days.isNotEmpty()) { "Weekly alarms require at least one day" }
            require(days.all { it in 1..7 }) { "Weekly days must use ISO values 1..7" }
        }
    }

    @Serializable
    data object Workdays : AlarmSchedule
}

@Serializable
enum class AlarmArmedState {
    DISABLED,
    NEEDS_RULE,
    NEEDS_PERMISSION,
    SCHEDULED,
    FAILED,
    COMPLETED,
}
