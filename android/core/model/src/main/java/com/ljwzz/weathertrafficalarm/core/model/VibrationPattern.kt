package com.ljwzz.weathertrafficalarm.core.model

import kotlinx.serialization.Serializable

@Serializable
data class VibrationPattern(
    val enabled: Boolean = true,
    val patternMillis: LongArray = longArrayOf(0, 500, 500, 500),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VibrationPattern) return false
        return enabled == other.enabled && patternMillis.contentEquals(other.patternMillis)
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + patternMillis.contentHashCode()
        return result
    }
}
