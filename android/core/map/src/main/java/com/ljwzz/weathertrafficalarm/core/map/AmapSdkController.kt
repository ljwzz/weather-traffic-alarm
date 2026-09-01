package com.ljwzz.weathertrafficalarm.core.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only AMap SDK boundary exposed to feature modules.  Neither its results
 * nor its failure states expose AMap SDK types or credential values.
 */
@Singleton
class AmapSdkController private constructor(
    private val sdkBridge: AmapSdkBridge,
    private val permissionChecker: (Context) -> Boolean,
    private val locationTimeoutMillis: Long,
) {
    @Inject
    constructor() : this(
        sdkBridge = AndroidAmapSdkBridge,
        permissionChecker = ::hasLocationPermission,
        locationTimeoutMillis = LOCATION_TIMEOUT_MILLIS,
    )

    @Volatile
    private var sdkReady = false

    fun initialize(
        context: Context,
        apiKey: String,
        privacyConsentGranted: Boolean,
    ): AmapSdkInitialization {
        if (!privacyConsentGranted) {
            sdkReady = false
            return AmapSdkInitialization.ConsentRequired
        }
        if (apiKey.isBlank()) {
            sdkReady = false
            return AmapSdkInitialization.MissingApiKey
        }

        return runCatching { sdkBridge.initialize(context, apiKey) }.fold(
            onSuccess = {
                sdkReady = true
                AmapSdkInitialization.Ready
            },
            onFailure = {
                sdkReady = false
                AmapSdkInitialization.Failed
            },
        )
    }

    suspend fun locateOnce(context: Context): MapLocationResult {
        if (!sdkReady) return MapLocationResult.InitializationRequired
        if (!permissionChecker(context)) return MapLocationResult.PermissionDenied

        val result = withTimeoutOrNull(locationTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val client = runCatching { sdkBridge.createLocationClient(context) }
                    .getOrElse {
                        continuation.resume(MapLocationResult.Unavailable)
                        return@suspendCancellableCoroutine
                    }
                var completed = false

                fun release(result: MapLocationResult? = null) {
                    if (completed) return
                    completed = true
                    runCatching { client.stopAndDestroy() }
                    if (result != null && continuation.isActive) continuation.resume(result)
                }

                continuation.invokeOnCancellation { release() }
                runCatching {
                    client.start(locationTimeoutMillis, ::release)
                }.onFailure { release(MapLocationResult.Unavailable) }
            }
        }
        return result ?: MapLocationResult.Timeout
    }

    internal companion object {
        const val LOCATION_TIMEOUT_MILLIS = 10_000L

        fun forTesting(
            sdkBridge: AmapSdkBridge,
            permissionChecker: (Context) -> Boolean = { true },
            locationTimeoutMillis: Long = LOCATION_TIMEOUT_MILLIS,
        ): AmapSdkController = AmapSdkController(sdkBridge, permissionChecker, locationTimeoutMillis)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

sealed interface AmapSdkInitialization {
    data object Ready : AmapSdkInitialization
    data object ConsentRequired : AmapSdkInitialization
    data object MissingApiKey : AmapSdkInitialization
    data object Failed : AmapSdkInitialization
}

sealed interface MapLocationResult {
    data class Success(val point: GeoPoint) : MapLocationResult
    data object InitializationRequired : MapLocationResult
    data object PermissionDenied : MapLocationResult
    data object Timeout : MapLocationResult
    data object Unavailable : MapLocationResult
}
