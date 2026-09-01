package com.ljwzz.weathertrafficalarm.core.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapRendererSupportTest {
    @Test
    fun emulatorRenderersAreRejectedBeforeCreatingAnAmapView() {
        assertFalse(isAmapNativeRendererSupported("ranchu", "google/sdk_gphone64_arm64/generic", "sdk_gphone64_arm64"))
        assertFalse(isAmapNativeRendererSupported("goldfish", "generic/sdk/generic", "Android SDK built for arm64"))
    }

    @Test
    fun physicalDeviceRendererIsAllowed() {
        assertTrue(isAmapNativeRendererSupported("qcom", "xiaomi/nuwa/nuwa:16/release-keys", "Xiaomi 15 Ultra"))
    }
}
