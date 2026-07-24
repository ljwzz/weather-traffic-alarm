package com.ljwzz.weathertrafficalarm.backend.domain.model;

public record CalendarDay(
    String date,
    DayStatus status,
    String label
) {}
