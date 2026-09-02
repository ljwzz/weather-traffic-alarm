package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhituStateTest {
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
