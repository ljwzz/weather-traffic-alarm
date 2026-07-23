package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AlarmSound(
    val uri: String? = null,
    val title: String = "Default",
)
