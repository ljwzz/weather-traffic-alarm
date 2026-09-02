package com.ljwzz.weathertrafficalarm.core.model

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Provider boundary for weather evaluation. Provider failures use [ProviderError]. */
interface WeatherProvider {
    suspend fun evaluate(request: WeatherRequest): WeatherEvaluation
}

/** The two endpoints that jointly determine commute-weather severity. */
enum class WeatherLocationRole {
    HOME,
    WORK,
}

data class WeatherLocation(
    val role: WeatherLocationRole,
    val point: GeoPoint,
)

/**
 * The target weather interval in the plan's time zone.
 *
 * The requested API horizon starts at the provider's current-hour sample and ends at the
 * target arrival hour. A null [hourlyStepsFor] result means that the interval is either
 * already over or outside Caiyun's 360-hour hourly horizon.
 */
data class WeatherTimeWindow(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    init {
        require(!end.isBefore(start)) { "weather window end must not be before start" }
    }

    fun hourlyStepsFor(requestedAt: Instant): Int? {
        val firstProviderHour = requestedAt.atZone(end.zone).truncatedTo(ChronoUnit.HOURS)
        val lastRequiredHour = end.truncatedTo(ChronoUnit.HOURS)
        val requiredHours = ChronoUnit.HOURS.between(firstProviderHour, lastRequiredHour) + 1
        if (requiredHours !in 1..MAX_HOURLY_STEPS) return null
        return (((requiredHours + HOURS_PER_REQUEST_BLOCK - 1) / HOURS_PER_REQUEST_BLOCK) * HOURS_PER_REQUEST_BLOCK).toInt()
    }

    companion object {
        const val MAX_HOURLY_STEPS = 360
        private const val HOURS_PER_REQUEST_BLOCK = 24
    }
}

data class WeatherRequest(
    val home: WeatherLocation,
    val work: WeatherLocation,
    val window: WeatherTimeWindow,
    val weatherBufferProfile: WeatherBufferProfile,
    val weatherRuleVersion: String = WeatherRules.VERSION,
    val requestedAt: Instant,
) {
    init {
        require(home.role == WeatherLocationRole.HOME) { "home location must have HOME role" }
        require(work.role == WeatherLocationRole.WORK) { "work location must have WORK role" }
        require(weatherRuleVersion.isNotBlank()) { "weather rule version must not be blank" }
    }
}

/** Whether a location result was fetched now or reused from a still-valid provider cache. */
enum class WeatherDataSource {
    NETWORK,
    CACHE,
    MIXED,
}

/** A reason that remains explicit even when the conservative buffer is zero. */
enum class WeatherUnavailableReason {
    UNKNOWN_SKYCON,
    HORIZON_UNAVAILABLE,
}

data class WeatherLocationEvaluation(
    val role: WeatherLocationRole,
    /** Null only when every participating skycon was unavailable. */
    val severity: WeatherSeverity?,
    val providerReportTime: Instant,
    val participatingWindowStart: ZonedDateTime,
    val participatingWindowEnd: ZonedDateTime,
    val source: WeatherDataSource,
    val unknownSkyconCodes: Set<String> = emptySet(),
) {
    init {
        require(!participatingWindowEnd.isBefore(participatingWindowStart)) {
            "participating weather window end must not be before start"
        }
        require(severity != null || unknownSkyconCodes.isNotEmpty()) {
            "a weather location evaluation requires severity or an unavailable skycon code"
        }
        require(source != WeatherDataSource.MIXED) { "a single location result cannot have MIXED source" }
    }
}

/**
 * Normalized output from the two location queries. It contains no raw provider DTO or
 * credentials, and therefore remains safe for the domain layer and decision history.
 */
data class WeatherEvaluation(
    val severity: WeatherSeverity,
    val bufferMinutes: Int,
    val weatherRuleVersion: String,
    /** The oldest provider time is retained so the combined result cannot appear fresher than either location. */
    val providerReportTime: Instant?,
    val participatingWindowStart: ZonedDateTime,
    val participatingWindowEnd: ZonedDateTime,
    val locations: List<WeatherLocationEvaluation>,
    val source: WeatherDataSource?,
    val unknownSkyconCodes: Set<String> = emptySet(),
    val unavailableReasons: Set<WeatherUnavailableReason> = emptySet(),
    val fallbackReason: FallbackReason = FallbackReason.NONE,
) {
    /** Future evaluation orchestration must reject partial or unavailable weather results. */
    val isUsableForScheduling: Boolean get() = unavailableReasons.isEmpty()

    init {
        require(bufferMinutes in 0..60) { "weather buffer must be 0-60 minutes" }
        require(weatherRuleVersion.isNotBlank()) { "weather rule version must not be blank" }
        require(!participatingWindowEnd.isBefore(participatingWindowStart)) {
            "participating weather window end must not be before start"
        }
        if (locations.isEmpty()) {
            require(unavailableReasons.contains(WeatherUnavailableReason.HORIZON_UNAVAILABLE)) {
                "an empty weather evaluation must be horizon unavailable"
            }
            require(providerReportTime == null && source == null) {
                "a horizon-unavailable evaluation cannot claim provider data"
            }
            require(fallbackReason == FallbackReason.WEATHER_HORIZON_UNAVAILABLE) {
                "a horizon-unavailable evaluation must expose its fallback reason"
            }
        } else {
            require(
                locations.size == WeatherLocationRole.entries.size &&
                    locations.map(WeatherLocationEvaluation::role).toSet() == WeatherLocationRole.entries.toSet(),
            ) {
                "weather evaluation must contain exactly home and work results"
            }
            require(providerReportTime != null && source != null) {
                "a weather evaluation with location data requires provider metadata"
            }
        }
    }
}
