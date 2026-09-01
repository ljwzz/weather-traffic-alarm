package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
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

    private fun place(name: String, longitude: Double, latitude: Double) = PlaceRef(
        name = name,
        displayAddress = name,
        longitudeGcj02 = longitude,
        latitudeGcj02 = latitude,
        adcode = "110000",
        citycode = "010",
    )
}
