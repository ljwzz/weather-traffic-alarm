package com.ljwzz.weathertrafficalarm.core.map

import android.content.Context
import android.content.ContextWrapper
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapSdkControllerTest {
    @Test
    fun `missing consent does not initialize sdk`() {
        val bridge = FakeSdkBridge()
        val controller = AmapSdkController.forTesting(bridge)

        val result = controller.initialize(unusedContext(), apiKey = "key", privacyConsentGranted = false)

        assertEquals(AmapSdkInitialization.ConsentRequired, result)
        assertEquals(0, bridge.initializeCalls)
    }

    @Test
    fun `locate once before initialization does not create sdk location client`() = runBlocking {
        val bridge = FakeSdkBridge()
        val controller = AmapSdkController.forTesting(bridge)

        val result = controller.locateOnce(unusedContext())

        assertEquals(MapLocationResult.InitializationRequired, result)
        assertEquals(0, bridge.createLocationClientCalls)
    }

    @Test
    fun `permission denied does not create sdk location client`() = runBlocking {
        val bridge = FakeSdkBridge()
        val controller = AmapSdkController.forTesting(bridge, permissionChecker = { false })
        initialize(controller)

        val result = controller.locateOnce(unusedContext())

        assertEquals(MapLocationResult.PermissionDenied, result)
        assertEquals(0, bridge.createLocationClientCalls)
    }

    @Test
    fun `location timeout stops and destroys client`() = runBlocking {
        val client = FakeLocationClient()
        val bridge = FakeSdkBridge(locationClient = client)
        val controller = AmapSdkController.forTesting(bridge, locationTimeoutMillis = 1)
        initialize(controller)

        val result = controller.locateOnce(unusedContext())

        assertEquals(MapLocationResult.Timeout, result)
        assertEquals(1, bridge.createLocationClientCalls)
        assertTrue(client.started)
        assertTrue(client.stoppedAndDestroyed)
    }

    @Test
    fun `location result is returned without exposing sdk type`() = runBlocking {
        val expected = MapLocationResult.Success(GeoPoint(116.397, 39.908))
        val client = FakeLocationClient(resultOnStart = expected)
        val controller = AmapSdkController.forTesting(FakeSdkBridge(locationClient = client))
        initialize(controller)

        assertEquals(expected, controller.locateOnce(unusedContext()))
        assertTrue(client.stoppedAndDestroyed)
    }

    private fun initialize(controller: AmapSdkController) {
        assertEquals(AmapSdkInitialization.Ready, controller.initialize(unusedContext(), "key", true))
    }

    private fun unusedContext(): Context = ContextWrapper(null)

    private class FakeSdkBridge(
        private val locationClient: AmapLocationClientBridge = FakeLocationClient(),
    ) : AmapSdkBridge {
        var initializeCalls = 0
        var createLocationClientCalls = 0

        override fun initialize(context: Context, apiKey: String) {
            initializeCalls++
        }

        override fun createLocationClient(context: Context): AmapLocationClientBridge {
            createLocationClientCalls++
            return locationClient
        }
    }

    private class FakeLocationClient(
        private val resultOnStart: MapLocationResult? = null,
    ) : AmapLocationClientBridge {
        var started = false
        var stoppedAndDestroyed = false

        override fun start(timeoutMillis: Long, onResult: (MapLocationResult) -> Unit) {
            started = true
            resultOnStart?.let(onResult)
        }

        override fun stopAndDestroy() {
            stoppedAndDestroyed = true
        }
    }
}
