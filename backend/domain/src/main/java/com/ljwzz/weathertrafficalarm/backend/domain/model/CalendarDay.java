package com.ljwzz.weathertrafficalarm.backend.domain.model;

public enum DayStatus {
    WORKDAY,
    HOLIDAY,
}

public record CalendarDay(
    String date,
    DayStatus status,
    String label
) {}
