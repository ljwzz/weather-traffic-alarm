package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CommuteMode {
    DRIVING,
    TRANSIT,
    WALKING,
    BICYCLING,
    ELECTRIC_BICYCLE,
}
