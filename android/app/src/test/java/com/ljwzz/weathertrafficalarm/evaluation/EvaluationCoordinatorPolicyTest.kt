package com.ljwzz.weathertrafficalarm.evaluation

import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
import com.ljwzz.weathertrafficalarm.core.data.repository.CommuteSource
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommute
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import java.time.LocalDate
import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationCoordinatorPolicyTest {
    private val monday = LocalDate.of(2026, 9, 7)

    @Test
    fun `workday profile wins for compensatory workday while calendar holiday selects holiday profile`() {
        val settings = LocalSettings(
            workdayWeatherBuffers = WeatherBuffers(1, 2, 3),
            weekendWeatherBuffers = WeatherBuffers(4, 5, 6),
            holidayWeatherBuffers = WeatherBuffers(7, 8, 9),
        )

        assertEquals(1, EvaluationCoordinatorPolicy.weatherProfile(monday, emptyMap(), settings).lightMinutes)
        assertEquals(7, EvaluationCoordinatorPolicy.weatherProfile(monday, mapOf(monday.toString() to DayStatus.HOLIDAY), settings).lightMinutes)
        val sunday = LocalDate.of(2026, 9, 6)
        assertEquals(4, EvaluationCoordinatorPolicy.weatherProfile(sunday, emptyMap(), settings).lightMinutes)
    }

    @Test
    fun `schedule eligibility applies per-date override before weekly or workday rule`() {
        val weekend = LocalDate.of(2026, 9, 6)
        val weekly = AlarmSchedule.Weekly(setOf(1))
        val override = WorkdayOverride("p", weekend.toString(), DayStatus.WORKDAY)

        assertFalse(EvaluationCoordinatorPolicy.isEligible(weekly, weekend, DayStatus.HOLIDAY, null))
        assertTrue(EvaluationCoordinatorPolicy.isEligible(weekly, weekend, DayStatus.WORKDAY, override))
        assertFalse(EvaluationCoordinatorPolicy.isEligible(AlarmSchedule.Workdays, monday, DayStatus.HOLIDAY, null))
    }

    @Test
    fun `weather window spans earliest allowed wake through target arrival in plan zone`() {
        val plan = plan(defaultWake = "06:00", arrival = "09:00", maxAdvance = 60)
        val window = EvaluationCoordinatorPolicy.weatherWindow(plan, monday)

        assertEquals("2026-09-07T05:00+08:00[Asia/Shanghai]", window.start.toString())
        assertEquals("2026-09-07T09:00+08:00[Asia/Shanghai]", window.end.toString())
    }

    @Test
    fun `transit candidates begin ninety minutes before arrival then advance in fifteen minute steps`() {
        val arrival = ZonedDateTime.parse("2026-09-07T09:00:00+08:00[Asia/Shanghai]")

        assertEquals(
            listOf("07:30", "07:15", "07:00", "06:45"),
            EvaluationCoordinatorPolicy.transitCandidateDepartures(arrival).map { it.toLocalTime().toString() },
        )
    }

    @Test
    fun `expiry is exclusive and provider failures are retried only when transport is retryable`() {
        val now = Instant.parse("2026-09-06T12:00:00Z")

        assertTrue(EvaluationCoordinatorPolicy.isExpired(now, now))
        assertFalse(EvaluationCoordinatorPolicy.isExpired(now, now.plusSeconds(1)))
        assertTrue(EvaluationCoordinatorPolicy.isRetryable(ProviderError(ProviderError.Category.NETWORK, message = "network")))
        assertTrue(EvaluationCoordinatorPolicy.isRetryable(ProviderError(ProviderError.Category.PROVIDER_FAILURE, "HTTP_500", "server")))
        assertFalse(EvaluationCoordinatorPolicy.isRetryable(ProviderError(ProviderError.Category.INVALID_KEY, message = "key")))
    }

    @Test
    fun `input fingerprint changes when amap consent changes during provider evaluation`() {
        val plan = plan("06:00", "09:00", 60)
        val home = PlaceRef("h", "家", "北京", 116.3, 39.9, "110000", "010")
        val work = PlaceRef("w", "公司", "北京", 116.4, 39.9, "110000", "010")
        val commute = EffectiveCommute(home, work, CommuteMode.DRIVING, CommuteSource.GLOBAL)
        val profile = WeatherBufferProfile(1, 2, 3)
        val denied = LocalSettings(amapConsentGranted = false)
        val granted = denied.copy(amapConsentGranted = true)

        assertFalse(
            EvaluationCoordinatorPolicy.fingerprint(plan, commute, null, DayStatus.WORKDAY, profile, "WEEKDAY_FALLBACK", denied) ==
                EvaluationCoordinatorPolicy.fingerprint(plan, commute, null, DayStatus.WORKDAY, profile, "WEEKDAY_FALLBACK", granted),
        )
    }

    private fun plan(defaultWake: String, arrival: String, maxAdvance: Int) = AlarmPlan(
        id = "p", revision = 1, name = "通勤", enabled = true, zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = defaultWake, arrivalLocalTime = arrival, preparationMinutes = 30,
        maxAdvanceMinutes = maxAdvance, commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Workdays,
    )
}
