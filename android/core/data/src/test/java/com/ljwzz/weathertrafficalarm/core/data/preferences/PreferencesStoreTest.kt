package com.ljwzz.weathertrafficalarm.core.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesStoreTest {

    private lateinit var store: PreferencesStore

    @Before
    fun setup() {
        store = PreferencesStore(RuntimeEnvironment.getApplication())
        // Reset to defaults to prevent state leaking between tests
        runBlocking {
            store.setPrivacyConsentCaiyunDisclosure(false)
            store.setWeatherBufferMinutes(1, 10)
            store.setWeatherBufferMinutes(2, 20)
            store.setWeatherBufferMinutes(3, 30)
            store.setDiagnosticsEnabled(false)
        }
    }

    @Test
    fun defaultPrivacyConsentCaiyunDisclosureIsFalse() = runTest {
        assertFalse(store.privacyConsentCaiyunDisclosure.first())
    }

    @Test
    fun setPrivacyConsentCaiyunDisclosure() = runTest {
        store.setPrivacyConsentCaiyunDisclosure(true)
        assertTrue(store.privacyConsentCaiyunDisclosure.first())
    }

    @Test
    fun defaultWeatherBufferLevel1Is10Minutes() = runTest {
        assertEquals(10, store.weatherBufferLevel1Minutes.first())
    }

    @Test
    fun defaultWeatherBufferLevel2Is20Minutes() = runTest {
        assertEquals(20, store.weatherBufferLevel2Minutes.first())
    }

    @Test
    fun defaultWeatherBufferLevel3Is30Minutes() = runTest {
        assertEquals(30, store.weatherBufferLevel3Minutes.first())
    }

    @Test
    fun setWeatherBufferLevel1() = runTest {
        store.setWeatherBufferMinutes(1, 15)
        assertEquals(15, store.weatherBufferLevel1Minutes.first())
    }

    @Test
    fun setWeatherBufferLevel2() = runTest {
        store.setWeatherBufferMinutes(2, 25)
        assertEquals(25, store.weatherBufferLevel2Minutes.first())
    }

    @Test
    fun setWeatherBufferLevel3() = runTest {
        store.setWeatherBufferMinutes(3, 45)
        assertEquals(45, store.weatherBufferLevel3Minutes.first())
    }

    @Test
    fun defaultDiagnosticsEnabledIsFalse() = runTest {
        assertFalse(store.diagnosticsEnabled.first())
    }

    @Test
    fun setDiagnosticsEnabled() = runTest {
        store.setDiagnosticsEnabled(true)
        assertTrue(store.diagnosticsEnabled.first())
    }
}
