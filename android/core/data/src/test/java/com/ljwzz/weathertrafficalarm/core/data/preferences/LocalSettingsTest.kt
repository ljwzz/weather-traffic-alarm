package com.ljwzz.weathertrafficalarm.core.data.preferences

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalSettingsTest {
    private lateinit var store: LocalSettingsStore

    @Before
    fun setUp() {
        store = LocalSettingsStore(RuntimeEnvironment.getApplication())
        runBlocking { store.update { LocalSettings() } }
    }

    @Test
    fun profileDefaultsMatchProductValues() {
        val settings = LocalSettings()

        assertEquals(WeatherBuffers(10, 20, 30), settings.workdayWeatherBuffers)
        assertEquals(WeatherBuffers(5, 10, 20), settings.weekendWeatherBuffers)
        assertEquals(WeatherBuffers(10, 15, 25), settings.holidayWeatherBuffers)
    }

    @Test
    fun weatherBuffersRejectValuesOutsideUiRange() {
        assertThrows(IllegalArgumentException::class.java) { WeatherBuffers(lightMinutes = 61) }
        assertThrows(IllegalArgumentException::class.java) { WeatherBuffers(moderateMinutes = -1) }
    }

    @Test
    fun concurrentTransactionsPreserveIndependentSettingChanges() = runTest {
        coroutineScope {
            launch { store.update { it.copy(notificationSummary = false) } }
            launch { store.update { it.copy(lockScreenSummary = false) } }
        }

        val settings = store.settings.first { !it.notificationSummary && !it.lockScreenSummary }
        assertFalse(settings.notificationSummary)
        assertFalse(settings.lockScreenSummary)
    }

    @Test
    fun legacyFavoriteJsonDecodesWithoutMapCoordinates() {
        val favorite = Json.decodeFromString<FavoritePlace>(
            """{"id":"home","name":"家","address":"北京市"}""",
        )

        assertEquals("home", favorite.id)
        assertEquals(null, favorite.placeRef)
    }

    @Test
    fun amapConsentIsIndependentFromLegacyPrivacyAcceptance() = runTest {
        store.update { it.copy(privacyAccepted = true) }

        val settings = store.settings.first { it.privacyAccepted }
        assertEquals(null, settings.amapConsentPromptedVersion)
        assertFalse(settings.amapConsentGranted)

        store.update { it.copy(amapConsentPromptedVersion = 3, amapConsentGranted = true) }
        val consented = store.settings.first {
            it.amapConsentPromptedVersion == 3 && it.amapConsentGranted
        }
        assertEquals(3, consented.amapConsentPromptedVersion)
        assertEquals(true, consented.amapConsentGranted)
    }
}
