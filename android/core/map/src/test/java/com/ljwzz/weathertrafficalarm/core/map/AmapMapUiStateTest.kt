package com.ljwzz.weathertrafficalarm.core.map

import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmapMapUiStateTest {
    @Test
    fun `selected route uses matching id`() {
        val first = route("first")
        val selected = route("selected")

        val actual = AmapMapUiState(
            routes = listOf(first, selected),
            selectedRouteId = selected.id,
        ).selectedRoute()

        assertEquals(selected, actual)
    }

    @Test
    fun `selected route falls back to first available route`() {
        val route = route("first")

        assertEquals(route, AmapMapUiState(routes = listOf(route), selectedRouteId = "missing").selectedRoute())
        assertNull(AmapMapUiState().selectedRoute())
    }

    private fun route(id: String) = RouteAlternative(
        id = id,
        distanceMeters = 1,
        durationSeconds = 1,
        polyline = listOf(GeoPoint(116.397, 39.908), GeoPoint(116.398, 39.909)),
    )
}
