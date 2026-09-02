package com.ljwzz.weathertrafficalarm.core.model

import java.time.ZonedDateTime
import java.util.Locale

data class WeatherBufferProfile(
    val lightMinutes: Int,
    val moderateMinutes: Int,
    val severeMinutes: Int,
) {
    init {
        require(lightMinutes in 0..60) { "light weather buffer must be 0-60" }
        require(moderateMinutes in 0..60) { "moderate weather buffer must be 0-60" }
        require(severeMinutes in 0..60) { "severe weather buffer must be 0-60" }
    }

    fun minutesFor(severity: WeatherSeverity): Int = when (severity) {
        WeatherSeverity.FINE -> 0
        WeatherSeverity.LIGHT -> lightMinutes
        WeatherSeverity.MODERATE -> moderateMinutes
        WeatherSeverity.SEVERE -> severeMinutes
    }

    companion object {
        val WORKDAY_DEFAULT = WeatherBufferProfile(lightMinutes = 10, moderateMinutes = 20, severeMinutes = 30)
        val WEEKEND_DEFAULT = WeatherBufferProfile(lightMinutes = 5, moderateMinutes = 10, severeMinutes = 20)
        val STATUTORY_REST_DEFAULT = WeatherBufferProfile(lightMinutes = 10, moderateMinutes = 15, severeMinutes = 25)
    }
}

data class WeatherBufferProfiles(
    val workday: WeatherBufferProfile = WeatherBufferProfile.WORKDAY_DEFAULT,
    val weekend: WeatherBufferProfile = WeatherBufferProfile.WEEKEND_DEFAULT,
    val statutoryRest: WeatherBufferProfile = WeatherBufferProfile.STATUTORY_REST_DEFAULT,
)

enum class WeatherDayKind {
    WORKDAY,
    WEEKEND,
    STATUTORY_REST,
}

object WeatherBufferSelector {
    fun select(
        dayKind: WeatherDayKind,
        profiles: WeatherBufferProfiles,
        override: WeatherBufferProfile? = null,
    ): WeatherBufferProfile = override ?: when (dayKind) {
        WeatherDayKind.WORKDAY -> profiles.workday
        WeatherDayKind.WEEKEND -> profiles.weekend
        WeatherDayKind.STATUTORY_REST -> profiles.statutoryRest
    }
}

data class SkyconMapping(
    val code: String,
    val severity: WeatherSeverity?,
    val unavailableReason: WeatherUnavailableReason? = null,
)

object WeatherRules {
    const val VERSION = "v1"

    /** Maps the complete v2.6 skycon vocabulary. Unknown values are deliberately unavailable. */
    fun mapSkycon(code: String): SkyconMapping {
        val normalized = code.trim().uppercase(Locale.ROOT)
        val severity = when (normalized) {
            "CLEAR_DAY", "CLEAR_NIGHT", "PARTLY_CLOUDY_DAY", "PARTLY_CLOUDY_NIGHT", "CLOUDY" -> WeatherSeverity.FINE
            "LIGHT_RAIN", "LIGHT_SNOW", "LIGHT_HAZE" -> WeatherSeverity.LIGHT
            "MODERATE_RAIN", "HEAVY_RAIN", "MODERATE_SNOW", "HEAVY_SNOW", "FOG", "DUST", "SAND", "WIND", "MODERATE_HAZE", "HEAVY_HAZE" -> WeatherSeverity.MODERATE
            "STORM_RAIN", "STORM_SNOW" -> WeatherSeverity.SEVERE
            else -> null
        }
        return if (severity != null) {
            SkyconMapping(code = normalized, severity = severity)
        } else {
            SkyconMapping(
                code = normalized,
                severity = null,
                unavailableReason = WeatherUnavailableReason.UNKNOWN_SKYCON,
            )
        }
    }

    fun evaluateLocation(
        role: WeatherLocationRole,
        skyconCodes: Iterable<String>,
        providerReportTime: java.time.Instant,
        participatingWindowStart: ZonedDateTime,
        participatingWindowEnd: ZonedDateTime,
        source: WeatherDataSource,
    ): WeatherLocationEvaluation {
        val mappings = skyconCodes.map(::mapSkycon).toList()
        require(mappings.isNotEmpty()) { "at least one participating skycon is required" }
        val knownSeverity = mappings.mapNotNull(SkyconMapping::severity).maxByOrNull(WeatherSeverity::level)
        val unknownCodes = mappings.filter { it.unavailableReason == WeatherUnavailableReason.UNKNOWN_SKYCON }
            .map(SkyconMapping::code)
            .toSortedSet()
        return WeatherLocationEvaluation(
            role = role,
            severity = knownSeverity,
            providerReportTime = providerReportTime,
            participatingWindowStart = participatingWindowStart,
            participatingWindowEnd = participatingWindowEnd,
            source = source,
            unknownSkyconCodes = unknownCodes,
        )
    }

    /** Combines home and work by their highest known severity; all-unknown input remains explicit. */
    fun combine(
        home: WeatherLocationEvaluation,
        work: WeatherLocationEvaluation,
        profile: WeatherBufferProfile,
        weatherRuleVersion: String = VERSION,
    ): WeatherEvaluation {
        require(home.role == WeatherLocationRole.HOME) { "home evaluation must have HOME role" }
        require(work.role == WeatherLocationRole.WORK) { "work evaluation must have WORK role" }
        require(weatherRuleVersion.isNotBlank()) { "weather rule version must not be blank" }

        val knownSeverity = listOfNotNull(home.severity, work.severity).maxByOrNull(WeatherSeverity::level)
        val unknownCodes = (home.unknownSkyconCodes + work.unknownSkyconCodes).toSortedSet()
        val unavailableReasons = buildSet {
            if (unknownCodes.isNotEmpty()) add(WeatherUnavailableReason.UNKNOWN_SKYCON)
        }
        val finalSeverity = knownSeverity ?: WeatherSeverity.FINE
        return WeatherEvaluation(
            severity = finalSeverity,
            bufferMinutes = if (knownSeverity == null) 0 else profile.minutesFor(finalSeverity),
            weatherRuleVersion = weatherRuleVersion,
            providerReportTime = minOf(home.providerReportTime, work.providerReportTime),
            participatingWindowStart = minByInstant(home.participatingWindowStart, work.participatingWindowStart),
            participatingWindowEnd = maxByInstant(home.participatingWindowEnd, work.participatingWindowEnd),
            locations = listOf(home, work),
            source = if (home.source == work.source) home.source else WeatherDataSource.MIXED,
            unknownSkyconCodes = unknownCodes,
            unavailableReasons = unavailableReasons,
            fallbackReason = if (unknownCodes.isEmpty()) FallbackReason.NONE else FallbackReason.WEATHER_UNKNOWN_CODE,
        )
    }

    /** A parsed request that cannot be served within the provider's hourly forecast horizon. */
    fun horizonUnavailable(
        window: WeatherTimeWindow,
        weatherRuleVersion: String = VERSION,
    ): WeatherEvaluation = WeatherEvaluation(
        severity = WeatherSeverity.FINE,
        bufferMinutes = 0,
        weatherRuleVersion = weatherRuleVersion,
        providerReportTime = null,
        participatingWindowStart = window.start,
        participatingWindowEnd = window.end,
        locations = emptyList(),
        source = null,
        unavailableReasons = setOf(WeatherUnavailableReason.HORIZON_UNAVAILABLE),
        fallbackReason = FallbackReason.WEATHER_HORIZON_UNAVAILABLE,
    )

    private fun minByInstant(first: ZonedDateTime, second: ZonedDateTime): ZonedDateTime =
        if (first.toInstant() <= second.toInstant()) first else second

    private fun maxByInstant(first: ZonedDateTime, second: ZonedDateTime): ZonedDateTime =
        if (first.toInstant() >= second.toInstant()) first else second
}
