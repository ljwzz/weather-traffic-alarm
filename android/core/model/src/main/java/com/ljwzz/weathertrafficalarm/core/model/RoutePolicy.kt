package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutePolicy {
    DEFAULT,
    LEAST_TIME,
    LEAST_DISTANCE,
    LEAST_TRAFFIC,
}
