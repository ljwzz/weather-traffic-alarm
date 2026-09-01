package com.ljwzz.weathertrafficalarm.core.map

import android.graphics.Color
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
}

/**
 * Lifecycle-aware Compose host for [TextureMapView]. The callback returns a
 * [GeoPoint], so callers never need to access a SDK [LatLng].
 */
@Composable
fun AmapMap(
    state: AmapMapUiState,
    modifier: Modifier = Modifier,
    stateKey: String = DEFAULT_STATE_KEY,
    onMapClick: ((GeoPoint) -> Unit)? = null,
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
            if (renderedState != state) {
                view.render(state)
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
}

private fun TextureMapView.render(state: AmapMapUiState) {
    val aMap = map
    aMap.clear()
    aMap.isTrafficEnabled = state.trafficEnabled

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
    state.selectedRoute()?.polyline?.takeIf { it.size >= 2 }?.let { points ->
        aMap.addPolyline(
            PolylineOptions()
                .addAll(points.map(GeoPoint::toLatLng))
                .width(14f)
                .color(Color.rgb(23, 125, 255)),
        )
    }
    aMap.moveCameraFor(state)
}

private fun AMap.moveCameraFor(state: AmapMapUiState) {
    val points = buildList {
        addAll(state.markers.map(MapMarker::point))
        state.selectedPoint?.let(::add)
        state.selectedRoute()?.polyline?.let(::addAll)
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
