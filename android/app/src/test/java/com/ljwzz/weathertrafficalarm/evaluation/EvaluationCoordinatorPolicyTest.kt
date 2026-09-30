package com.ljwzz.weathertrafficalarm.evaluation

import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommute
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.CommuteSource
import com.ljwzz.weathertrafficalarm.core.model.DailySettingsResolver
import com.ljwzz.weathertrafficalarm.core.model.DayKind
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfiles
import java.time.LocalDate
import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationCoordinatorPolicyTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val sunday = LocalDate.of(2026, 9, 6)

    private val settings = LocalSettings(
        workdayWeatherBuffers = WeatherBuffers(1, 2, 3),
        weekendWeatherBuffers = WeatherBuffers(4, 5, 6),
        holidayWeatherBuffers = WeatherBuffers(7, 8, 9),
    )

    private val profiles = WeatherBufferProfiles(
        workday = WeatherBufferProfile(1, 2, 3),
        weekend = WeatherBufferProfile(4, 5, 6),
        statutoryRest = WeatherBufferProfile(7, 8, 9),
    )

    /**
     * The raw calendar category selects the inherited profile; a day-level override only
     * replaces it when the user set one explicitly.
     */
    @Test
    fun `weather profile follows the raw calendar category and not the overridden status`() {
        val overtimeSunday = SingleDayOverride("p", sunday.toString(), DayStatus.WORKDAY)
        val resolved = DailySettingsResolver.resolve(plan(), sunday, overtimeSunday, emptyMap(), profiles)

        assertEquals(DayKind.WEEKEND_REST, resolved.classification.baseDayKind)
        assertEquals(DayStatus.WORKDAY, resolved.classification.effectiveStatus)
        assertEquals(4, resolved.weatherProfile.lightMinutes)

        val statutoryRest = DailySettingsResolver.resolve(
            plan(), sunday, SingleDayOverride("p", sunday.toString(), DayStatus.WORKDAY),
            mapOf(sunday.toString() to DayStatus.HOLIDAY), profiles,
        )
        assertEquals(DayKind.STATUTORY_REST, statutoryRest.classification.baseDayKind)
        assertEquals(7, statutoryRest.weatherProfile.lightMinutes)

        val compensatoryWorkday = DailySettingsResolver.resolve(
            plan(), sunday, null, mapOf(sunday.toString() to DayStatus.WORKDAY), profiles,
        )
        assertEquals(DayKind.WORKDAY, compensatoryWorkday.classification.baseDayKind)
        assertEquals(1, compensatoryWorkday.weatherProfile.lightMinutes)
    }

    @Test
    fun `schedule eligibility applies per-date override before weekly or workday rule`() {
        val weekly = AlarmSchedule.Weekly(setOf(1))
        val override = SingleDayOverride("p", sunday.toString(), DayStatus.WORKDAY)

        assertFalse(EvaluationCoordinatorPolicy.isEligible(weekly, sunday, DayStatus.HOLIDAY, null))
        assertTrue(EvaluationCoordinatorPolicy.isEligible(weekly, sunday, DayStatus.WORKDAY, override))
        assertFalse(EvaluationCoordinatorPolicy.isEligible(AlarmSchedule.Workdays, monday, DayStatus.HOLIDAY, null))
    }

    @Test
    fun `weather window spans earliest allowed wake through target arrival in plan zone`() {
        val plan = plan(defaultWake = "06:00", arrival = "09:00", maxAdvance = 60)
        val window = EvaluationCoordinatorPolicy.weatherWindow(plan, effective(plan, monday))

        assertEquals("2026-09-07T05:00+08:00[Asia/Shanghai]", window.start.toString())
        assertEquals("2026-09-07T09:00+08:00[Asia/Shanghai]", window.end.toString())
    }

    /** A day-level arrival time moves the end of the participating weather window. */
    @Test
    fun `weather window follows the resolved day arrival time`() {
        val plan = plan(defaultWake = "06:00", arrival = "09:00", maxAdvance = 60)
        val override = SingleDayOverride("p", monday.toString(), arrivalLocalTime = "10:30")
        val window = EvaluationCoordinatorPolicy.weatherWindow(plan, effective(plan, monday, override))

        assertEquals("2026-09-07T10:30+08:00[Asia/Shanghai]", window.end.toString())
    }

    @Test
    fun `driving candidates span three hours in fifteen minute steps anchored to arrival`() {
        val arrival = ZonedDateTime.parse("2026-09-07T09:07:30+08:00[Asia/Shanghai]")

        assertEquals(
            listOf(
                "06:07:30", "06:22:30", "06:37:30", "06:52:30", "07:07:30", "07:22:30", "07:37:30",
                "07:52:30", "08:07:30", "08:22:30", "08:37:30", "08:52:30", "09:07:30",
            ),
            EvaluationCoordinatorPolicy.drivingCandidateDepartures(arrival).map { it.toLocalTime().toString() },
        )
    }

    @Test
    fun `driving candidate window crosses midnight in the plan zone`() {
        val arrival = ZonedDateTime.parse("2026-09-07T01:00:00+08:00[Asia/Shanghai]")
        val candidates = EvaluationCoordinatorPolicy.drivingCandidateDepartures(arrival)

        assertEquals(ZonedDateTime.parse("2026-09-06T22:00:00+08:00[Asia/Shanghai]"), candidates.first())
        assertEquals(arrival, candidates.last())
        assertEquals(13, candidates.size)
        assertTrue(candidates.all { it.zone == arrival.zone })
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
        val effective = effective(plan, monday)

        assertNotEquals(
            EvaluationCoordinatorPolicy.fingerprint(plan, commute, null, DayStatus.WORKDAY, profile, "WEEKDAY_FALLBACK", denied, effective),
            EvaluationCoordinatorPolicy.fingerprint(plan, commute, null, DayStatus.WORKDAY, profile, "WEEKDAY_FALLBACK", granted, effective),
        )
    }

    /** A save or undo that lands mid-evaluation must change the input fingerprint. */
    @Test
    fun `input fingerprint changes when the day revision or a resolved value changes`() {
        val plan = plan("06:00", "09:00", 60)
        val home = PlaceRef("h", "家", "北京", 116.3, 39.9, "110000", "010")
        val work = PlaceRef("w", "公司", "北京", 116.4, 39.9, "110000", "010")
        val commute = EffectiveCommute(home, work, CommuteMode.DRIVING, CommuteSource.GLOBAL)
        val profile = WeatherBufferProfile(1, 2, 3)
        val base = SingleDayOverride("p", monday.toString(), DayStatus.WORKDAY, dayRevision = 3)
        val bumped = base.copy(dayRevision = 4)

        assertNotEquals(
            fingerprint(plan, commute, base, profile),
            fingerprint(plan, commute, bumped, profile),
        )
        assertNotEquals(
            fingerprint(plan, commute, base, profile),
            fingerprint(plan, commute, base, WeatherBufferProfile(2, 2, 3)),
        )
    }

    private fun fingerprint(
        plan: AlarmPlan,
        commute: EffectiveCommute,
        override: SingleDayOverride?,
        profile: WeatherBufferProfile,
    ) = EvaluationCoordinatorPolicy.fingerprint(
        plan, commute, override, DayStatus.WORKDAY, profile, "PLAN_OVERRIDE", settings,
        effective(plan, monday, override, profile),
    )

    private fun effective(
        plan: AlarmPlan,
        date: LocalDate,
        override: SingleDayOverride? = null,
        profile: WeatherBufferProfile? = null,
    ) = DailySettingsResolver.resolve(
        plan = plan,
        date = date,
        override = override?.copy(weatherProfile = profile ?: override.weatherProfile),
        officialDays = emptyMap(),
        profiles = profiles,
    )

    private fun plan(defaultWake: String, arrival: String, maxAdvance: Int) = AlarmPlan(
        id = "p", revision = 1, name = "通勤", enabled = true, zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = defaultWake, arrivalLocalTime = arrival, preparationMinutes = 30,
        maxAdvanceMinutes = maxAdvance, commuteMode = CommuteMode.DRIVING, schedule = AlarmSchedule.Workdays,
    )

    private fun plan() = plan("06:00", "09:00", 60)
}
