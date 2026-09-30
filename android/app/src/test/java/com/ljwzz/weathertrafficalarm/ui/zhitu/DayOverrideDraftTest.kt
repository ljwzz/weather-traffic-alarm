package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DailySettingsResolver
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfiles
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Contract of the single-day draft: what the page builds before it calls the save callback.
 *
 * The draft is the only place that can validate a partial commute, so these cases cover the
 * validation results, the complete replacement snapshot and the real inheritance baseline.
 */
class DayOverrideDraftTest {
    private val date = LocalDate.of(2026, 10, 1)
    private val plan = AlarmPlan(
        id = "draft-plan",
        revision = 1,
        name = "draft",
        enabled = true,
        zoneId = "Asia/Shanghai",
        defaultWakeLocalTime = "06:30",
        arrivalLocalTime = "09:00",
        preparationMinutes = 30,
        maxAdvanceMinutes = 60,
        commuteMode = CommuteMode.DRIVING,
    )
    private val home = PlaceRef(
        poiId = null,
        name = "home",
        displayAddress = "home",
        longitudeGcj02 = 116.397,
        latitudeGcj02 = 39.908,
        adcode = "110000",
        citycode = "010",
    )
    private val office = home.copy(name = "work", longitudeGcj02 = 116.407)

    private fun inputs(
        stored: SingleDayOverride? = null,
        profiles: WeatherBufferProfiles = WeatherBufferProfiles(),
        dayRevision: Long = stored?.dayRevision ?: 0,
    ): DayEditorInputs {
        val inherited = DailySettingsResolver.resolve(
            plan = plan,
            date = date,
            override = null,
            profiles = profiles,
        ).copy(dayRevision = dayRevision)
        val effective = DailySettingsResolver.resolve(
            plan = plan,
            date = date,
            override = stored,
            profiles = profiles,
        ).copy(dayRevision = dayRevision)
        return DayEditorInputs(
            plan = plan,
            settings = LocalSettings(),
            dayOverride = stored,
            planCommute = null,
            effective = effective,
            inherited = inherited,
            dayRevision = dayRevision,
        )
    }

    /** Restoring commute inheritance submits three nulls and clears the stored combination. */
    @Test
    fun `clearing the commute submits an inheriting combination`() {
        val stored = SingleDayOverride(
            planId = plan.id,
            date = date.toString(),
            preparationMinutes = 45,
            origin = home,
            destination = office,
            commuteMode = CommuteMode.TRANSIT,
        )
        val inputs = inputs(stored)
        val draft = DayOverrideDraft.from(inputs).copy(
            origin = null,
            destination = null,
            originFavoriteId = null,
            destinationFavoriteId = null,
            commuteMode = null,
            commuteEdited = false,
        )

        val change = draft.toChange(inputs)

        assertNull("inherit commute must remove the stored combination", change.replacement?.origin)
        assertNull(change.replacement?.destination)
        assertNull(change.replacement?.commuteMode)
        assertEquals(45, change.replacement?.preparationMinutes)
        assertEquals(inputs.dayRevision, change.expectedDayRevision)
    }

    /** The editor inherits the user's configured global profile, never a factory constant. */
    @Test
    fun `inherited weather uses the configured global profile`() {
        val configured = WeatherBufferProfile(1, 2, 3)
        val inputs = inputs(profiles = WeatherBufferProfiles(workday = configured))

        assertEquals(configured, inputs.inherited.weatherProfile)
        assertEquals(configured, bufferBaseline(DayOverrideDraft(), inputs.inherited))
        // The first relative step starts from that real baseline: 1 -> 6 with the other two unchanged.
        val stepped = bufferBaseline(DayOverrideDraft(), inputs.inherited)
            .copy(lightMinutes = bufferBaseline(DayOverrideDraft(), inputs.inherited).lightMinutes + 5)
        assertEquals(WeatherBufferProfile(6, 2, 3), stepped)
        val change = DayOverrideDraft(weatherProfile = stepped, weatherEdited = true).toChange(inputs)
        assertEquals(stepped, change.replacement?.weatherProfile)
    }

    /** An incomplete commute is reported as a field error instead of throwing before the callback. */
    @Test
    fun `an incomplete commute is rejected by validation`() {
        val inputs = inputs()
        val incomplete = DayOverrideDraft(commuteMode = CommuteMode.TRANSIT, commuteEdited = true)

        val errors = incomplete.validate(inputs)

        assertTrue(errors.hasErrors)
        assertEquals("本日通勤需要同时选择起点和终点", errors.commute)
        // The page never builds the domain object for an invalid draft.
        assertNull(incomplete.toChangeOrNull(inputs))
    }

    /** The same place twice is also invalid: a day-level combination must replace both endpoints. */
    @Test
    fun `identical endpoints are rejected by validation`() {
        val inputs = inputs()
        val duplicated = DayOverrideDraft(
            origin = home,
            destination = home,
            commuteMode = CommuteMode.DRIVING,
            commuteEdited = true,
        )

        assertEquals("本日通勤的起点和终点不能相同", duplicated.validate(inputs).commute)
        assertNull(duplicated.toChangeOrNull(inputs))
    }

    /** A complete day commute validates and travels as one unit. */
    @Test
    fun `a complete commute validates and replaces all three fields`() {
        val inputs = inputs()
        val draft = DayOverrideDraft(
            origin = home,
            destination = office,
            commuteMode = CommuteMode.TRANSIT,
            commuteEdited = true,
        )

        assertFalse(draft.validate(inputs).hasErrors)
        val replacement = requireNotNull(draft.toChange(inputs).replacement)
        assertEquals(home, replacement.origin)
        assertEquals(office, replacement.destination)
        assertEquals(CommuteMode.TRANSIT, replacement.commuteMode)
    }

    /** Cleared fields stay null in the snapshot, which is what makes them inherit again. */
    @Test
    fun `a draft with cleared fields keeps them cleared`() {
        val stored = SingleDayOverride(
            planId = plan.id,
            date = date.toString(),
            status = DayStatus.WORKDAY,
            wakeLocalTime = "07:10",
            preparationMinutes = 45,
        )
        val inputs = inputs(stored, dayRevision = 4)
        val draft = DayOverrideDraft.from(inputs).copy(wake = null)

        val replacement = requireNotNull(draft.toChange(inputs).replacement)

        assertNull(replacement.wakeLocalTime)
        assertEquals(DayStatus.WORKDAY, replacement.status)
        assertEquals(45, replacement.preparationMinutes)
        assertEquals(4L, draft.toChange(inputs).expectedDayRevision)
    }

    /** Out-of-range numbers and unparseable times are field errors, not exceptions. */
    @Test
    fun `invalid numbers and times are reported as field errors`() {
        val inputs = inputs()
        val invalid = DayOverrideDraft(wake = "25:99", preparationMinutes = 300)

        val errors = invalid.validate(inputs)

        assertNotNull(errors.wake)
        assertNotNull(errors.preparation)
        assertNull(invalid.toChangeOrNull(inputs))
        // The buffer type itself refuses out-of-range values, so a bad triple can never be drafted.
        assertThrows(IllegalArgumentException::class.java) { WeatherBufferProfile(0, 61, 0) }
    }
}
