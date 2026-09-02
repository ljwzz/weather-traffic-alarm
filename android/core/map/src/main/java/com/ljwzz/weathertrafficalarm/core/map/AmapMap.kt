package com.ljwzz.weathertrafficalarm.core.map

import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative

@Stable
data class MapMarker(
    val id: String,
    val point: GeoPoint,
    val title: String,
)

/** UI-only state. It is deliberately independent from AMap view and model classes. */
@Stable
data class AmapMapUiState(
    val markers: List<MapMarker> = emptyList(),
    val selectedPoint: GeoPoint? = null,
    val routes: List<RouteAlternative> = emptyList(),
    val selectedRouteId: String? = null,
    val trafficEnabled: Boolean = false,
) {
    fun selectedRoute(): RouteAlternative? =
        routes.firstOrNull { it.id == selectedRouteId } ?: routes.firstOrNull()

    /**
     * Draw at most three usable alternatives. The selected alternative is
     * rendered last so it stays above overlapping candidate routes.
     */
    internal fun routesForMap(): List<RouteAlternative> {
        val usable = routes.asSequence().filter { it.polyline.size >= 2 }.take(MAX_DISPLAYED_ROUTES).toList()
        val selected = usable.firstOrNull { it.id == selectedRouteId } ?: usable.firstOrNull()
        return usable.filterNot { it.id == selected?.id } + listOfNotNull(selected)
    }
}

/**
 * Lifecycle-aware Compose host for [TextureMapView]. Callers receive either a
 * [GeoPoint] for a map tap or a business route ID for a polyline tap, and
 * never need to access SDK map objects.
 */
@Composable
fun AmapMap(
    state: AmapMapUiState,
    modifier: Modifier = Modifier,
    stateKey: String = DEFAULT_STATE_KEY,
    onMapClick: ((GeoPoint) -> Unit)? = null,
    onRouteClick: ((String) -> Unit)? = null,
) {
    if (!isAmapNativeRendererSupported()) return

    val lifecycleOwner = LocalLifecycleOwner.current
    val savedStateOwner = lifecycleOwner as? SavedStateRegistryOwner
    val savedMapState = remember(stateKey, savedStateOwner) {
        savedStateOwner?.savedStateRegistry?.consumeRestoredStateForKey(stateKey) ?: Bundle()
    }
    val mapViewHolder = remember { MapViewHolder() }
    var renderedState by remember { mutableStateOf<AmapMapUiState?>(null) }

    AndroidView(
        factory = { context ->
            TextureMapView(context).also { view ->
                view.onCreate(savedMapState)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    view.onResume()
                }
                mapViewHolder.view = view
            }
        },
        modifier = modifier,
        update = { view ->
            view.map.setOnMapClickListener { point ->
                onMapClick?.invoke(
                    GeoPoint(longitudeGcj02 = point.longitude, latitudeGcj02 = point.latitude),
                )
            }
            view.map.setOnPolylineClickListener { polyline ->
                mapViewHolder.routeIdsByPolylineId[polyline.id]?.let { routeId ->
                    onRouteClick?.invoke(routeId)
                }
            }
            if (renderedState != state) {
                mapViewHolder.routeIdsByPolylineId = view.render(state)
                renderedState = state
            }
        },
    )

    DisposableEffect(lifecycleOwner, savedStateOwner, stateKey) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewHolder.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewHolder.view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        savedStateOwner?.savedStateRegistry?.registerSavedStateProvider(stateKey) {
            Bundle().also { state -> mapViewHolder.view?.onSaveInstanceState(state) }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            savedStateOwner?.savedStateRegistry?.unregisterSavedStateProvider(stateKey)
            mapViewHolder.view?.onSaveInstanceState(savedMapState)
            mapViewHolder.view?.onDestroy()
            mapViewHolder.view = null
        }
    }
}

private class MapViewHolder {
    var view: TextureMapView? = null
    var routeIdsByPolylineId: Map<String, String> = emptyMap()
}

private fun TextureMapView.render(state: AmapMapUiState): Map<String, String> {
    val aMap = map
    aMap.clear()
    aMap.isTrafficEnabled = state.trafficEnabled
    val routeIdsByPolylineId = mutableMapOf<String, String>()

    state.markers.forEach { marker ->
        aMap.addMarker(
            MarkerOptions()
                .position(marker.point.toLatLng())
                .title(marker.title),
        )
    }
    state.selectedPoint?.let { point ->
        aMap.addMarker(
            MarkerOptions()
                .position(point.toLatLng())
                .title("已选位置")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
        )
    }
    val routes = state.routesForMap()
    val selectedRouteId = routes.lastOrNull()?.id
    routes.forEach { route ->
        val selected = route.id == selectedRouteId
        val polyline = aMap.addPolyline(
            PolylineOptions()
                .addAll(route.polyline.map(GeoPoint::toLatLng))
                .width(if (selected) SELECTED_ROUTE_WIDTH_PX else CANDIDATE_ROUTE_WIDTH_PX)
                .color(if (selected) SELECTED_ROUTE_COLOR else CANDIDATE_ROUTE_COLOR)
                .zIndex(if (selected) SELECTED_ROUTE_Z_INDEX else CANDIDATE_ROUTE_Z_INDEX),
        )
        routeIdsByPolylineId[polyline.id] = route.id
    }
    aMap.moveCameraFor(state)
    return routeIdsByPolylineId
}

private fun AMap.moveCameraFor(state: AmapMapUiState) {
    val points = buildList {
        addAll(state.markers.map(MapMarker::point))
        state.selectedPoint?.let(::add)
        state.routesForMap().forEach { route -> addAll(route.polyline) }
    }
    when (points.size) {
        0 -> Unit
        1 -> moveCamera(CameraUpdateFactory.newLatLngZoom(points.single().toLatLng(), DEFAULT_ZOOM))
        else -> {
            val bounds = LatLngBounds.Builder().also { builder ->
                points.forEach { builder.include(it.toLatLng()) }
            }.build()
            moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
        }
    }
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(latitudeGcj02, longitudeGcj02)

fun isAmapNativeRendererSupported(): Boolean = isAmapNativeRendererSupported(
    hardware = Build.HARDWARE,
    fingerprint = Build.FINGERPRINT,
    model = Build.MODEL,
)

internal fun isAmapNativeRendererSupported(
    hardware: String,
    fingerprint: String,
    model: String,
): Boolean {
    val normalizedHardware = hardware.lowercase()
    val normalizedFingerprint = fingerprint.lowercase()
    val normalizedModel = model.lowercase()
    return normalizedHardware != "goldfish" &&
        normalizedHardware != "ranchu" &&
        "generic" !in normalizedFingerprint &&
        "emulator" !in normalizedFingerprint &&
        "emulator" !in normalizedModel &&
        "sdk_gphone" !in normalizedModel
}

private const val DEFAULT_ZOOM = 15f
private const val CAMERA_PADDING_PX = 96
private const val DEFAULT_STATE_KEY = "amap-map"
private const val MAX_DISPLAYED_ROUTES = 3
private const val SELECTED_ROUTE_WIDTH_PX = 14f
private const val CANDIDATE_ROUTE_WIDTH_PX = 10f
private const val SELECTED_ROUTE_Z_INDEX = 2f
private const val CANDIDATE_ROUTE_Z_INDEX = 1f
private val SELECTED_ROUTE_COLOR = 0xFF007F78.toInt()
private val CANDIDATE_ROUTE_COLOR = 0x874F94C4.toInt()
