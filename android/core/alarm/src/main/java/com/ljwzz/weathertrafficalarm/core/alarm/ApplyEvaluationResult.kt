package com.ljwzz.weathertrafficalarm.core.alarm

/** Result of applying one evaluated wake recommendation to locally armed alarms. */
data class ApplyEvaluationResult(
    val outcome: String,
    val actualWakeAt: Long? = null,
)
