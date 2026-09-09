package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhituStateTest {
    @Test
    fun workdayPreviewKeepsCompleteWeeksAcrossMonthYearAndWeekBoundaries() {
        listOf(
            Triple("2026-09-03", "2026-08-24", "2026-09-20"),
            Triple("2026-09-06", "2026-08-24", "2026-09-20"),
            Triple("2026-09-07", "2026-08-31", "2026-09-27"),
            Triple("2026-01-02", "2025-12-22", "2026-01-18"),
            Triple("2026-12-31", "2026-12-21", "2027-01-17"),
        ).forEach { (today, first, last) ->
            val days = workdayPreviewDays(LocalDate.parse(today), emptyMap())
            assertEquals(LocalDate.parse(first), days.first().date)
            assertEquals(LocalDate.parse(last), days.last().date)
            assertEquals(28, days.map { it.date }.distinct().size)
            assertTrue(days.zipWithNext().all { (left, right) -> right.date == left.date.plusDays(1) })
        }
    }

    @Test
    fun workdayPreviewBuildsFourMondayToSundayWeeksWithMonthBoundaryLabels() {
        val days = workdayPreviewDays(LocalDate.parse("2026-09-23"), emptyMap())

        assertEquals(28, days.size)
        assertEquals(LocalDate.parse("2026-09-14"), days.first().date)
        assertEquals(LocalDate.parse("2026-10-11"), days.last().date)
        assertEquals(listOf(1, 1, 1, 1), days.chunked(7).map { it.first().date.dayOfWeek.value })
        assertEquals("9/14", days.first().label)
        assertEquals("15", days[1].label)
        assertEquals("10/1", days.single { it.date == LocalDate.parse("2026-10-01") }.label)
        assertTrue(days.single { it.date == LocalDate.parse("2026-09-23") }.isToday)
        assertEquals(1, days.count { it.isToday })
    }

    @Test
    fun workdayPreviewClassifiesOfficialExceptionsSeparatelyFromWeekdayFallback() {
        val days = workdayPreviewDays(
            today = LocalDate.parse("2026-09-23"),
            officialDays = mapOf(
                "2026-09-20" to DayStatus.WORKDAY,
                "2026-09-25" to DayStatus.HOLIDAY,
                "2026-09-26" to DayStatus.HOLIDAY,
                "2026-09-27" to DayStatus.HOLIDAY,
            ),
        ).associateBy { it.date }

        assertEquals(WorkdayPreviewKind.WORKDAY, days.getValue(LocalDate.parse("2026-09-18")).kind)
        assertEquals(WorkdayPreviewKind.REST_DAY, days.getValue(LocalDate.parse("2026-09-19")).kind)
        assertEquals(WorkdayPreviewKind.SPECIAL_WORKDAY, days.getValue(LocalDate.parse("2026-09-20")).kind)
        assertEquals(DayStatus.WORKDAY, days.getValue(LocalDate.parse("2026-09-20")).effectiveStatus)
        listOf("2026-09-25", "2026-09-26", "2026-09-27").forEach { date ->
            assertEquals(WorkdayPreviewKind.SPECIAL_HOLIDAY, days.getValue(LocalDate.parse(date)).kind)
            assertEquals(DayStatus.HOLIDAY, days.getValue(LocalDate.parse(date)).effectiveStatus)
        }
    }

    @Test
    fun workdayPreviewAppliesPlanOverrideToEffectiveStatusWithoutChangingOfficialKind() {
        val days = workdayPreviewDays(
            today = LocalDate.parse("2026-09-23"),
            officialDays = mapOf("2026-09-25" to DayStatus.HOLIDAY),
            overrides = mapOf("2026-09-25" to DayStatus.WORKDAY),
        ).associateBy { it.date }

        val overridden = days.getValue(LocalDate.parse("2026-09-25"))
        assertEquals(WorkdayPreviewKind.SPECIAL_HOLIDAY, overridden.kind)
        assertEquals(DayStatus.WORKDAY, overridden.effectiveStatus)
        assertTrue(overridden.overridden)

        val fallback = days.getValue(LocalDate.parse("2026-09-26"))
        assertEquals(WorkdayPreviewKind.REST_DAY, fallback.kind)
        assertEquals(DayStatus.HOLIDAY, fallback.effectiveStatus)
        assertFalse(fallback.overridden)
    }

    @Test
    fun editorDraftPreservesArrivalAndAdvanceSettings() {
        val draft = AlarmPlan(
            id = "plan-editor-fields",
            revision = 3,
            name = "上班",
            enabled = true,
            zoneId = "Asia/Shanghai",
            defaultWakeLocalTime = "07:20",
            arrivalLocalTime = "09:15",
            preparationMinutes = 45,
            maxAdvanceMinutes = 80,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Workdays,
        ).toEditorDraft()

        assertEquals("09:15", draft.arrivalLocalTime)
        assertEquals(45, draft.preparationMinutes)
        assertEquals(80, draft.maxAdvanceMinutes)
        assertEquals("Asia/Shanghai", draft.zoneId)
    }

    @Test
    fun planCommuteEditorKeepsIndependentPlacesAndMode() {
        val editor = PlanCommuteEditorState(
            planId = "plan-1",
            origin = place("home", 116.4, 39.9),
            destination = place("office", 116.5, 39.8),
            mode = CommuteMode.TRANSIT,
            useGlobal = false,
        )

        assertEquals("plan-1", editor.planId)
        assertEquals("home", editor.origin?.name)
        assertEquals("office", editor.destination?.name)
        assertEquals(CommuteMode.TRANSIT, editor.mode)
        assertFalse(editor.useGlobal)
    }

    @Test
    fun routeStateStartsWithoutASelectedAlternative() {
        val state = RouteUiState(alternatives = listOf(RouteAlternative("route-1", 900, 5_000, emptyList())))

        assertNull(state.selectedRouteId)
        assertTrue(state.alternatives.isNotEmpty())
        assertFalse(state.trafficEnabled)
    }

    @Test
    fun transitCityCodesAreBackfilledFromReverseGeocodeWithoutReplacingPoiDetails() = runBlocking {
        val origin = place("家", 116.4, 39.9, citycode = "")
        val destination = place("公司", 116.5, 39.8, citycode = "021")
        var reverseCalls = 0

        val resolution = resolveTransitCityCodes(origin, destination) { point ->
            reverseCalls += 1
            PlaceRef(
                name = "逆地理地址",
                displayAddress = "逆地理地址",
                longitudeGcj02 = point.longitudeGcj02,
                latitudeGcj02 = point.latitudeGcj02,
                adcode = "110000",
                citycode = "010",
            )
        }

        val ready = resolution as TransitCityCodeResolution.Ready
        assertEquals(1, reverseCalls)
        assertEquals("家", ready.origin.name)
        assertEquals("010", ready.origin.citycode)
        assertEquals("021", ready.destination.citycode)
    }

    @Test
    fun transitCityCodesRemainUnavailableWhenReverseGeocodeHasNoCityCode() = runBlocking {
        val origin = place("家", 116.4, 39.9, citycode = "")
        val destination = place("公司", 116.5, 39.8, citycode = "")

        val resolution = resolveTransitCityCodes(origin, destination) { point ->
            PlaceRef(
                name = "逆地理地址",
                displayAddress = "逆地理地址",
                longitudeGcj02 = point.longitudeGcj02,
                latitudeGcj02 = point.latitudeGcj02,
                adcode = "",
                citycode = "",
            )
        }

        assertEquals(TransitCityCodeResolution.Unavailable, resolution)
    }

    @Test
    fun transitCityCodesDoNotReverseGeocodeWhenBothPlacesAlreadyHaveCityCodes() = runBlocking {
        val resolution = resolveTransitCityCodes(
            place("家", 116.4, 39.9, citycode = "010"),
            place("公司", 116.5, 39.8, citycode = "021"),
        ) {
            error("reverse geocoding must not run")
        }

        assertTrue(resolution is TransitCityCodeResolution.Ready)
    }

    private fun place(name: String, longitude: Double, latitude: Double, citycode: String = "010") = PlaceRef(
        name = name,
        displayAddress = name,
        longitudeGcj02 = longitude,
        latitudeGcj02 = latitude,
        adcode = "110000",
        citycode = citycode,
    )
}
