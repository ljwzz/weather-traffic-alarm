package com.ljwzz.weathertrafficalarm.core.model

enum class DayStatus {
    WORKDAY,
    HOLIDAY,
}

data class CalendarDay(
    val date: String, // ISO LocalDate
    val status: DayStatus,
    val label: String? = null,
)

data class WorkdayOverride(
    val planId: String,
    val date: String,
    val status: DayStatus,
    val wakeLocalTime: String? = null,
)
