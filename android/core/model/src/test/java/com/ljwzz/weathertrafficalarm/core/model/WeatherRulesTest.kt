package com.ljwzz.weathertrafficalarm.core.model

import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val windowStart = ZonedDateTime.of(2026, 9, 2, 5, 0, 0, 0, zone)
    private val windowEnd = ZonedDateTime.of(2026, 9, 2, 9, 0, 0, 0, zone)
    private val reportTime = Instant.parse("2026-09-01T11:00:00Z")

    @Test
    fun `maps every documented v26 skycon code`() {
        val expected = mapOf(
            "CLEAR_DAY" to WeatherSeverity.FINE,
            "CLEAR_NIGHT" to WeatherSeverity.FINE,
            "PARTLY_CLOUDY_DAY" to WeatherSeverity.FINE,
            "PARTLY_CLOUDY_NIGHT" to WeatherSeverity.FINE,
            "CLOUDY" to WeatherSeverity.FINE,
            "LIGHT_HAZE" to WeatherSeverity.LIGHT,
            "MODERATE_HAZE" to WeatherSeverity.MODERATE,
            "HEAVY_HAZE" to WeatherSeverity.MODERATE,
            "LIGHT_RAIN" to WeatherSeverity.LIGHT,
            "MODERATE_RAIN" to WeatherSeverity.MODERATE,
            "HEAVY_RAIN" to WeatherSeverity.MODERATE,
            "STORM_RAIN" to WeatherSeverity.SEVERE,
            "FOG" to WeatherSeverity.MODERATE,
            "LIGHT_SNOW" to WeatherSeverity.LIGHT,
            "MODERATE_SNOW" to WeatherSeverity.MODERATE,
            "HEAVY_SNOW" to WeatherSeverity.MODERATE,
            "STORM_SNOW" to WeatherSeverity.SEVERE,
            "DUST" to WeatherSeverity.MODERATE,
            "SAND" to WeatherSeverity.MODERATE,
            "WIND" to WeatherSeverity.MODERATE,
        )

        expected.forEach { (code, severity) ->
            val mapping = WeatherRules.mapSkycon(code)
            assertEquals(severity, mapping.severity, code)
            assertNull(mapping.unavailableReason, code)
        }
    }

    @Test
    fun `unknown skycon is explicitly unavailable rather than fine`() {
        val mapping = WeatherRules.mapSkycon("FREEZING_RAIN")

        assertNull(mapping.severity)
        assertEquals(WeatherUnavailableReason.UNKNOWN_SKYCON, mapping.unavailableReason)
    }

    @Test
    fun `two locations use highest known severity and preserve unknown codes`() {
        val home = WeatherRules.evaluateLocation(
            role = WeatherLocationRole.HOME,
            skyconCodes = listOf("LIGHT_RAIN", "FUTURE_CODE"),
            providerReportTime = reportTime,
            participatingWindowStart = windowStart,
            participatingWindowEnd = windowEnd,
            source = WeatherDataSource.NETWORK,
        )
        val work = WeatherRules.evaluateLocation(
            role = WeatherLocationRole.WORK,
            skyconCodes = listOf("STORM_SNOW"),
            providerReportTime = reportTime.plusSeconds(30),
            participatingWindowStart = windowStart.plusHours(1),
            participatingWindowEnd = windowEnd,
            source = WeatherDataSource.CACHE,
        )

        val result = WeatherRules.combine(home, work, WeatherBufferProfile.WORKDAY_DEFAULT)

        assertEquals(WeatherSeverity.SEVERE, result.severity)
        assertEquals(30, result.bufferMinutes)
        assertEquals(setOf("FUTURE_CODE"), result.unknownSkyconCodes)
        assertEquals(WeatherDataSource.MIXED, result.source)
        assertEquals(reportTime, result.providerReportTime)
        assertEquals(windowStart, result.participatingWindowStart)
        assertEquals(false, result.isUsableForScheduling)
        assertEquals(FallbackReason.WEATHER_UNKNOWN_CODE, result.fallbackReason)
    }

    @Test
    fun `all unknown locations use zero buffer and remain marked unavailable`() {
        val home = WeatherRules.evaluateLocation(
            WeatherLocationRole.HOME,
            listOf("UNKNOWN_A"),
            reportTime,
            windowStart,
            windowEnd,
            WeatherDataSource.NETWORK,
        )
        val work = WeatherRules.evaluateLocation(
            WeatherLocationRole.WORK,
            listOf("UNKNOWN_B"),
            reportTime,
            windowStart,
            windowEnd,
            WeatherDataSource.NETWORK,
        )

        val result = WeatherRules.combine(home, work, WeatherBufferProfile.WORKDAY_DEFAULT)

        assertEquals(WeatherSeverity.FINE, result.severity)
        assertEquals(0, result.bufferMinutes)
        assertEquals(setOf(WeatherUnavailableReason.UNKNOWN_SKYCON), result.unavailableReasons)
        assertEquals(setOf("UNKNOWN_A", "UNKNOWN_B"), result.unknownSkyconCodes)
        assertEquals(false, result.isUsableForScheduling)
        assertEquals(FallbackReason.WEATHER_UNKNOWN_CODE, result.fallbackReason)
    }

    @Test
    fun `profile selection uses non additive day profile or explicit override`() {
        val profiles = WeatherBufferProfiles()

        assertEquals(5, WeatherBufferSelector.select(WeatherDayKind.WEEKEND, profiles).minutesFor(WeatherSeverity.LIGHT))
        assertEquals(25, WeatherBufferSelector.select(WeatherDayKind.STATUTORY_REST, profiles).minutesFor(WeatherSeverity.SEVERE))
        assertEquals(
            7,
            WeatherBufferSelector.select(
                WeatherDayKind.WORKDAY,
                profiles,
                WeatherBufferProfile(lightMinutes = 7, moderateMinutes = 8, severeMinutes = 9),
            ).minutesFor(WeatherSeverity.LIGHT),
        )
    }

    @Test
    fun `hourly steps cover the arrival hour in 24 hour blocks and reject horizons beyond 360`() {
        val requestedAt = ZonedDateTime.of(2026, 9, 1, 19, 30, 0, 0, zone).toInstant()
        val tomorrowMorning = WeatherTimeWindow(windowStart, windowEnd)
        val beyondHorizon = WeatherTimeWindow(
            windowStart,
            ZonedDateTime.of(2026, 9, 16, 19, 0, 0, 0, zone),
        )

        assertEquals(24, tomorrowMorning.hourlyStepsFor(requestedAt))
        assertNull(beyondHorizon.hourlyStepsFor(requestedAt))
    }

    @Test
    fun `horizon unavailable is explicit and cannot be scheduled`() {
        val result = WeatherRules.horizonUnavailable(WeatherTimeWindow(windowStart, windowEnd))

        assertEquals(WeatherSeverity.FINE, result.severity)
        assertEquals(0, result.bufferMinutes)
        assertEquals(FallbackReason.WEATHER_HORIZON_UNAVAILABLE, result.fallbackReason)
        assertTrue(result.locations.isEmpty())
        assertEquals(false, result.isUsableForScheduling)
    }

    @Test
    fun `weather request preserves required home and work roles`() {
        val home = WeatherLocation(WeatherLocationRole.HOME, GeoPoint(116.4, 39.9))
        val work = WeatherLocation(WeatherLocationRole.WORK, GeoPoint(116.5, 39.9))
        val request = WeatherRequest(
            home = home,
            work = work,
            window = WeatherTimeWindow(windowStart, windowEnd),
            weatherBufferProfile = WeatherBufferProfile.WORKDAY_DEFAULT,
            requestedAt = reportTime,
        )

        assertEquals(WeatherRules.VERSION, request.weatherRuleVersion)
        assertTrue(request.window.end.isAfter(request.window.start))
    }
}
