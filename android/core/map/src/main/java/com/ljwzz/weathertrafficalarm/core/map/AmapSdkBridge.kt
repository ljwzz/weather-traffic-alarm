package com.ljwzz.weathertrafficalarm.core.map

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.MapsInitializer
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint

/** Internal adapter that keeps AMap's static APIs outside [AmapSdkController]. */
internal interface AmapSdkBridge {
    fun initialize(context: Context, apiKey: String)

    fun createLocationClient(context: Context): AmapLocationClientBridge
}

internal interface AmapLocationClientBridge {
    fun start(timeoutMillis: Long, onResult: (MapLocationResult) -> Unit)

    fun stopAndDestroy()
}

internal object AndroidAmapSdkBridge : AmapSdkBridge {
    override fun initialize(context: Context, apiKey: String) {
        val applicationContext = context.applicationContext
        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)
        MapsInitializer.setApiKey(apiKey)
        AMapLocationClient.setApiKey(apiKey)
    }

    override fun createLocationClient(context: Context): AmapLocationClientBridge =
        AndroidAmapLocationClientBridge(AMapLocationClient(context.applicationContext))
}

private class AndroidAmapLocationClientBridge(
    private val client: AMapLocationClient,
) : AmapLocationClientBridge {
    override fun start(timeoutMillis: Long, onResult: (MapLocationResult) -> Unit) {
        client.setLocationListener { location ->
            if (location != null && location.errorCode == 0) {
                onResult(
                    MapLocationResult.Success(
                        GeoPoint(
                            longitudeGcj02 = location.longitude,
                            latitudeGcj02 = location.latitude,
                        ),
                    ),
                )
            } else {
                onResult(MapLocationResult.Unavailable)
            }
        }
        client.setLocationOption(
            AMapLocationClientOption().apply {
                setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                setOnceLocation(true)
                setOnceLocationLatest(true)
                setHttpTimeOut(timeoutMillis)
            },
        )
        client.startLocation()
    }

    override fun stopAndDestroy() {
        client.stopLocation()
        client.onDestroy()
    }
}
