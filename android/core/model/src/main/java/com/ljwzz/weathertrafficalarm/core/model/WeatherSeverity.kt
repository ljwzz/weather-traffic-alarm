package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class WeatherSeverity(val level: Int, val defaultBufferMinutes: Int) {
    FINE(0, 0),
    LIGHT(1, 10),
    MODERATE(2, 20),
    SEVERE(3, 30),
}
