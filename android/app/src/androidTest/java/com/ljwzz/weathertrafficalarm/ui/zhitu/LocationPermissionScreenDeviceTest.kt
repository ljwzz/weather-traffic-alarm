package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.map.AmapMapUiState
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Isolated location-permission UI contract. It uses a renderer-unavailable
 * map status so no native map, provider request, or location client is
 * created; callbacks only increment local counters.
 */
@RunWith(AndroidJUnit4::class)
class LocationPermissionScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun currentLocationShowsPurposeBeforeAnyPermissionOrLocationOperation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Requires location permission to be absent",
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED,
        )
        assumeTrue(
            "Requires device location services to be enabled",
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true,
        )
        val locationCalls = mutableIntStateOf(0)

        setPicker(mapStatus = MapStatus.RendererUnavailable, onUseCurrentLocation = {
            locationCalls.intValue++
            it()
        })

        compose.onNodeWithText("使用当前位置").performClick()
        compose.onNodeWithText("仅在本次点击后获取前台位置", substring = true).assertExists()
        assertEquals(0, locationCalls.intValue)
        writeScreenshot("location-permission-purpose.png")

        compose.onNodeWithText("取消").performClick()
        assertEquals(0, locationCalls.intValue)
    }

    @Test
    fun missingAmapConsentBlocksBeforeRealPermissionRequest() {
        val locationCalls = mutableIntStateOf(0)

        setPicker(mapStatus = MapStatus.ConsentRequired, onUseCurrentLocation = {
            locationCalls.intValue++
            it()
        })

        compose.onNodeWithText("使用当前位置").performClick()
        compose.onNodeWithText("请先完成高德地图专项授权，再使用当前位置。").assertExists()
        assertEquals(0, locationCalls.intValue)
    }

    private fun setPicker(
        mapStatus: MapStatus,
        onUseCurrentLocation: ((() -> Unit) -> Unit),
    ) {
        compose.setContent {
            ZhituTheme {
                PlacePickerScreen(
                    target = PlaceSelectionTarget.ORIGIN,
                    query = "",
                    candidates = emptyList(),
                    loading = false,
                    message = null,
                    mapStatus = mapStatus,
                    mapState = AmapMapUiState(),
                    onQueryChanged = {},
                    onUseCurrentLocation = onUseCurrentLocation,
                    onLocationPermissionDenied = {},
                    onMapClick = {},
                    onConfirm = {},
                    onBack = {},
                )
            }
        }
    }

    private fun writeScreenshot(fileName: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("permission-qa"))
        directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
