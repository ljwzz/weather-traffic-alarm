package com.ljwzz.weathertrafficalarm.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N004-1 pure computation: three-tier inheritance, raw date classification, zero as a
 * valid value and per-field sources.
 */
class DailySettingsResolverTest {

    private val monday = LocalDate.of(2026, 9, 7)
    private val saturday = LocalDate.of(2026, 9, 5)
    private val sunday = LocalDate.of(2026, 9, 6)

    private val home = PlaceRef("h", "家", "北京市", 116.397428, 39.90923, "110000", "010")
    private val office = PlaceRef("o", "公司", "北京市", 116.407428, 39.91923, "110000", "010")
    private val school = PlaceRef("s", "学校", "北京市", 116.417428, 39.92923, "110000", "010")

    private val profiles = WeatherBufferProfiles(
        workday = WeatherBufferProfile(10, 20, 30),
        weekend = WeatherBufferProfile(5, 10, 20),
        statutoryRest = WeatherBufferProfile(10, 15, 25),
    )

    private fun plan(
        defaultWake: String = "06:00",
        arrival: String = "09:00",
        preparation: Int = 30,
    ) = AlarmPlan(
        id = "p",
        revision = 1,
        name = "通勤",
        enabled = true,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = defaultWake,
        arrivalLocalTime = arrival,
        preparationMinutes = preparation,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
        schedule = AlarmSchedule.Workdays,
    )

    // --- Date classification ---

    @Test
    fun `weekday fallback separates ordinary workdays from ordinary weekends`() {
        val classification = DailySettingsResolver.classify(monday, null, emptyMap())

        assertEquals(DayKind.WORKDAY, classification.baseDayKind)
        assertEquals(DaySource.WEEKDAY_FALLBACK, classification.source)
        assertEquals(DayStatus.WORKDAY, classification.effectiveStatus)
        assertFalse(classification.overriddenByUser)

        val weekend = DailySettingsResolver.classify(saturday, null, emptyMap())
        assertEquals(DayKind.WEEKEND_REST, weekend.baseDayKind)
        assertEquals(DayStatus.HOLIDAY, weekend.effectiveStatus)
    }

    @Test
    fun `official holiday data wins over the weekday fallback and marks its source`() {
        val holiday = DailySettingsResolver.classify(monday, null, mapOf(monday.toString() to DayStatus.HOLIDAY))
        assertEquals(DayKind.STATUTORY_REST, holiday.baseDayKind)
        assertEquals(DaySource.HOLIDAY_CN, holiday.source)

        val compensatory = DailySettingsResolver.classify(saturday, null, mapOf(saturday.toString() to DayStatus.WORKDAY))
        assertEquals(DayKind.WORKDAY, compensatory.baseDayKind)
        assertEquals(DaySource.HOLIDAY_CN, compensatory.source)
        assertEquals(DayStatus.WORKDAY, compensatory.effectiveStatus)
    }

    /** A statutory rest day that also falls on a weekend must keep the statutory category. */
    @Test
    fun `statutory rest takes precedence over an ordinary weekend category`() {
        val classification = DailySettingsResolver.classify(sunday, null, mapOf(sunday.toString() to DayStatus.HOLIDAY))

        assertEquals(DayKind.STATUTORY_REST, classification.baseDayKind)
        assertEquals(WeatherDayKind.STATUTORY_REST, classification.weatherDayKind)
    }

    /** Overtime rewrites the effective status only; the raw category keeps selecting buffers. */
    @Test
    fun `overtime on a rest day keeps the original rest category`() {
        val weekendOvertime = DailySettingsResolver.classify(saturday, SingleDayOverride("p", saturday.toString(), DayStatus.WORKDAY), emptyMap())
        assertEquals(DayKind.WEEKEND_REST, weekendOvertime.baseDayKind)
        assertEquals(DayStatus.WORKDAY, weekendOvertime.effectiveStatus)
        assertTrue(weekendOvertime.overriddenByUser)
        assertEquals(WeatherDayKind.WEEKEND, weekendOvertime.weatherDayKind)

        val statutoryOvertime = DailySettingsResolver.classify(
            sunday, SingleDayOverride("p", sunday.toString(), DayStatus.WORKDAY), mapOf(sunday.toString() to DayStatus.HOLIDAY),
        )
        assertEquals(DayKind.STATUTORY_REST, statutoryOvertime.baseDayKind)
        assertEquals(WeatherDayKind.STATUTORY_REST, statutoryOvertime.weatherDayKind)
    }

    // --- Three-tier inheritance ---

    @Test
    fun `an empty override inherits every value from the plan and the global category profile`() {
        val resolved = DailySettingsResolver.resolve(plan(), monday, null, emptyMap(), profiles)

        assertEquals("06:00", resolved.defaultWakeLocalTime)
        assertEquals("09:00", resolved.arrivalLocalTime)
        assertEquals(30, resolved.preparationMinutes)
        assertEquals(10, resolved.weatherProfile.lightMinutes)
        assertEquals(DailySettingSource.PLAN, resolved.wakeSource)
        assertEquals(DailySettingSource.PLAN, resolved.arrivalSource)
        assertEquals(DailySettingSource.PLAN, resolved.preparationSource)
        assertEquals(DailySettingSource.GLOBAL, resolved.weatherProfileSource)
        assertEquals(0L, resolved.dayRevision)
    }

    @Test
    fun `a single changed preparation time leaves every other value inherited`() {
        val override = SingleDayOverride("p", monday.toString(), preparationMinutes = 15)
        val resolved = DailySettingsResolver.resolve(plan(), monday, override, emptyMap(), profiles)

        assertEquals(15, resolved.preparationMinutes)
        assertEquals(DailySettingSource.DAY_OVERRIDE, resolved.preparationSource)
        assertEquals("06:00", resolved.defaultWakeLocalTime)
        assertEquals("09:00", resolved.arrivalLocalTime)
        assertEquals(10, resolved.weatherProfile.lightMinutes)
        assertEquals(DailySettingSource.PLAN, resolved.wakeSource)
    }

    @Test
    fun `day values replace the plan and global defaults per field`() {
        val override = SingleDayOverride(
            planId = "p",
            date = monday.toString(),
            arrivalLocalTime = "10:30",
            wakeLocalTime = "07:15",
            dayRevision = 4,
        )
        val resolved = DailySettingsResolver.resolve(plan(), monday, override, emptyMap(), profiles)

        assertEquals("07:15", resolved.defaultWakeLocalTime)
        assertEquals("10:30", resolved.arrivalLocalTime)
        assertEquals(30, resolved.preparationMinutes)
        assertEquals(DailySettingSource.DAY_OVERRIDE, resolved.wakeSource)
        assertEquals(DailySettingSource.DAY_OVERRIDE, resolved.arrivalSource)
        assertEquals(DailySettingSource.PLAN, resolved.preparationSource)
        assertEquals(4L, resolved.dayRevision)
    }

    /** Zero is a real value: `0` minutes of preparation must not fall back to the plan. */
    @Test
    fun `zero preparation minutes is a valid day override`() {
        val override = SingleDayOverride("p", monday.toString(), preparationMinutes = 0)
        val resolved = DailySettingsResolver.resolve(plan(preparation = 45), monday, override, emptyMap(), profiles)

        assertEquals(0, resolved.preparationMinutes)
        assertEquals(DailySettingSource.DAY_OVERRIDE, resolved.preparationSource)
    }

    @Test
    fun `invalid preparation and buffer values are rejected at construction`() {
        assertTrue(runCatching { SingleDayOverride("p", monday.toString(), preparationMinutes = -1) }.isFailure)
        assertTrue(runCatching { SingleDayOverride("p", monday.toString(), preparationMinutes = 241) }.isFailure)
        assertTrue(runCatching { WeatherBufferProfile(-1, 0, 0) }.isFailure)
        assertTrue(runCatching { WeatherBufferProfile(0, 0, 61) }.isFailure)
    }

    @Test
    fun `a day level weather profile replaces all three values without stacking`() {
        val override = SingleDayOverride("p", monday.toString(), weatherProfile = WeatherBufferProfile(0, 0, 60))
        val resolved = DailySettingsResolver.resolve(plan(), monday, override, emptyMap(), profiles)

        assertEquals(0, resolved.weatherProfile.lightMinutes)
        assertEquals(0, resolved.weatherProfile.moderateMinutes)
        assertEquals(60, resolved.weatherProfile.severeMinutes)
        assertEquals(DailySettingSource.DAY_OVERRIDE, resolved.weatherProfileSource)
    }

    @Test
    fun `the inherited profile follows the raw date category for every kind`() {
        val saturdayOvertime = SingleDayOverride("p", saturday.toString(), DayStatus.WORKDAY)
        val statutoryOvertime = SingleDayOverride("p", sunday.toString(), DayStatus.WORKDAY)

        assertEquals(
            5,
            DailySettingsResolver.resolve(plan(), saturday, saturdayOvertime, emptyMap(), profiles).weatherProfile.lightMinutes,
        )
        assertEquals(
            10,
            DailySettingsResolver.resolve(
                plan(), sunday, statutoryOvertime, mapOf(sunday.toString() to DayStatus.HOLIDAY), profiles,
            ).weatherProfile.lightMinutes,
        )
        assertEquals(
            WeatherBufferProfile.STATUTORY_REST_DEFAULT,
            DailySettingsResolver.resolve(
                plan(), sunday, null, mapOf(sunday.toString() to DayStatus.HOLIDAY),
            ).weatherProfile,
        )
    }

    /** `0` minutes and the upper bounds of every range must survive resolution. */
    @Test
    fun `boundary values are preserved by resolution`() {
        val override = SingleDayOverride(
            planId = "p",
            date = monday.toString(),
            preparationMinutes = 240,
            weatherProfile = WeatherBufferProfile(0, 60, 0),
        )
        val resolved = DailySettingsResolver.resolve(plan(), monday, override, emptyMap(), profiles)

        assertEquals(240, resolved.preparationMinutes)
        assertEquals(0, resolved.weatherProfile.lightMinutes)
        assertEquals(60, resolved.weatherProfile.moderateMinutes)
        assertEquals(0, resolved.weatherProfile.severeMinutes)
    }

    // --- Commute combination ---

    @Test
    fun `a complete day commute is one replacement and an incomplete one does not apply`() {
        val complete = SingleDayOverride(
            planId = "p", date = monday.toString(),
            origin = home, destination = school, commuteMode = CommuteMode.WALKING,
        )
        assertTrue(complete.hasCompleteCommute)

        val commute = CommuteResolution(home, school, CommuteMode.WALKING, CommuteSource.DAY_OVERRIDE)
        val resolved = DailySettingsResolver.resolve(plan(), monday, complete, emptyMap(), profiles, commute)
        assertEquals(CommuteSource.DAY_OVERRIDE, resolved.commute?.source)
        assertEquals(school, resolved.commute?.destination)

        val originOnly = SingleDayOverride("p", monday.toString(), origin = home)
        assertFalse(originOnly.hasCompleteCommute)
        assertNull(
            DailySettingsResolver.resolve(plan(), monday, originOnly, emptyMap(), profiles)
                .commute,
        )
    }

    @Test
    fun `a non driving day commute requires both places`() {
        val failure = runCatching {
            SingleDayOverride("p", monday.toString(), commuteMode = CommuteMode.TRANSIT)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `an override without user visible values is reported as fully inheriting`() {
        assertTrue(SingleDayOverride("p", monday.toString(), dayRevision = 7).isInheritingEverything)
        assertFalse(SingleDayOverride("p", monday.toString(), status = DayStatus.HOLIDAY).isInheritingEverything)
        assertFalse(SingleDayOverride("p", monday.toString(), preparationMinutes = 0).isInheritingEverything)
        assertFalse(SingleDayOverride("p", monday.toString(), weatherProfile = WeatherBufferProfile(0, 0, 0)).isInheritingEverything)
    }

    /** A migrated v6 row carries its stored status and wake time but no new N004 columns. */
    @Test
    fun `a migrated row keeps its status and inherits every new field`() {
        val migrated = SingleDayOverride(
            planId = "p",
            date = monday.toString(),
            status = DayStatus.WORKDAY,
            wakeLocalTime = "07:00",
            dayRevision = 0,
        )
        val resolved = DailySettingsResolver.resolve(
            plan(), monday, migrated, mapOf(monday.toString() to DayStatus.HOLIDAY), profiles,
        )

        assertEquals(DayStatus.WORKDAY, resolved.classification.effectiveStatus)
        assertEquals(DayKind.STATUTORY_REST, resolved.classification.baseDayKind)
        assertEquals("07:00", resolved.defaultWakeLocalTime)
        assertEquals(30, resolved.preparationMinutes)
        // A migrated row never fabricates a plan-level weather profile override.
        assertEquals(DailySettingSource.GLOBAL, resolved.weatherProfileSource)
        // The official holiday category selects the statutory rest profile, not the workday one.
        assertEquals(WeatherBufferProfile.STATUTORY_REST_DEFAULT, resolved.weatherProfile)
    }

    @Test
    fun `a malformed persisted time is reported as missing instead of parsed as midnight`() {
        assertNull(SingleDayOverride.parseLocalTime("25:99"))
        assertNull(SingleDayOverride.parseLocalTime(null))
        assertEquals(java.time.LocalTime.of(6, 30), SingleDayOverride.parseLocalTime("06:30"))
    }
}
