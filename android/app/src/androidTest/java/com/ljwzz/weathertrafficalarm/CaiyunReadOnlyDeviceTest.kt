package com.ljwzz.weathertrafficalarm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherTimeWindow
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in smoke test against the saved, encrypted Caiyun credentials on the target
 * device. It neither starts an Activity nor writes credentials, settings, locations, alarms,
 * or permissions. A successful evaluation may populate Caiyun's process-memory-only cache.
 */
@RunWith(AndroidJUnit4::class)
class CaiyunReadOnlyDeviceTest {
    @Test
    fun verifiesSavedCredentialsAndTwoLocationWeatherWithoutMutatingDeviceData() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Requires explicit -e verifyCaiyunNetwork true opt-in",
            InstrumentationRegistry.getArguments().getString("verifyCaiyunNetwork") == "true",
        )

        val dependencies = EntryPointAccessors.fromApplication(
            instrumentation.targetContext,
            DeviceTestDependencies::class.java,
        )
        val credentialsBefore = dependencies.credentials().maskedValues()
        val settingsBefore = dependencies.settings().loadInitial()
        val locations = weatherLocations(settingsBefore)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putBoolean("credentialStorageReadable", !credentialsBefore.storageError)
                putBoolean("hasCaiyunAppKey", credentialsBefore.hasCaiyunAppKey)
                putBoolean("hasCaiyunSecret", credentialsBefore.hasCaiyunSecret)
                putBoolean("distinctCoordinateEndpoints", locations != null)
            },
        )

        assumeTrue("Requires readable saved credential storage", !credentialsBefore.storageError)
        assumeTrue(
            "Requires a complete saved Caiyun credential",
            credentialsBefore.hasCaiyunAppKey && credentialsBefore.hasCaiyunSecret,
        )

        assumeTrue("Requires distinct coordinate-backed origin and destination", locations != null)
        val (home, work) = requireNotNull(locations)

        try {
            val requestedAt = Instant.now()
            val start = requestedAt.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS)

            val connection = providerCall("connection") {
                dependencies.caiyunWeatherProvider().testConnection(home, requestedAt)
            }
            assertNotNull("Connection response must include a report time", connection.providerReportTime)
            assertEquals("Connection test must not use cache", WeatherDataSource.NETWORK, connection.source)

            val evaluation = providerCall("evaluation") {
                dependencies.weatherProvider().evaluate(
                    WeatherRequest(
                        home = home,
                        work = work,
                        window = WeatherTimeWindow(start, start.plusHours(23)),
                        weatherBufferProfile = settingsBefore.workdayWeatherBuffers.toWeatherBufferProfile(),
                        requestedAt = requestedAt,
                    ),
                )
            }
            assertTrue("Two-location weather evaluation must be usable", evaluation.isUsableForScheduling)
            assertNotNull("Weather evaluation must include a report time", evaluation.providerReportTime)
            assertEquals("Weather evaluation must be fetched from network", WeatherDataSource.NETWORK, evaluation.source)
            assertEquals(
                "Weather evaluation must contain both configured endpoints",
                setOf(WeatherLocationRole.HOME, WeatherLocationRole.WORK),
                evaluation.locations.map(WeatherLocationEvaluation::role).toSet(),
            )

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean("connectionNetwork", connection.source == WeatherDataSource.NETWORK)
                    putBoolean("evaluationUsable", evaluation.isUsableForScheduling)
                    putString("evaluationSource", evaluation.source?.name)
                    putLong("reportTimeEpochMillis", requireNotNull(evaluation.providerReportTime).toEpochMilli())
                    putInt("expectedNetworkRequestCount", 3)
                },
            )
        } finally {
            val credentialsAfter = dependencies.credentials().maskedValues()
            val settingsAfter = dependencies.settings().loadInitial()
            assertTrue("Credential metadata changed during read-only Caiyun verification", credentialsBefore == credentialsAfter)
            assertTrue("Settings changed during read-only Caiyun verification", settingsBefore == settingsAfter)
        }
    }

    private suspend fun <T> providerCall(phase: String, block: suspend () -> T): T = try {
        withTimeout(PROVIDER_TIMEOUT_MILLIS) { block() }
    } catch (failure: ProviderError) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("failurePhase", phase)
                putString("failureCategory", failure.category.name)
                failure.providerCode?.takeIf { it.matches(Regex("HTTP_[0-9]{3}")) }
                    ?.removePrefix("HTTP_")?.toIntOrNull()?.let { putInt("failureHttpStatus", it) }
                failure.retryAfterSeconds?.let { putLong("retryAfterSeconds", it) }
            },
        )
        throw AssertionError("Caiyun $phase failure category=${failure.category}")
    } catch (_: Throwable) {
        throw AssertionError("Caiyun provider failed without a classified category")
    }

    private fun weatherLocations(settings: LocalSettings): Pair<WeatherLocation, WeatherLocation>? {
        val homePlace = settings.favorites.firstOrNull { it.id == settings.originId }?.placeRef ?: return null
        val workPlace = settings.favorites.firstOrNull { it.id == settings.destinationId }?.placeRef ?: return null
        val home = WeatherLocation(WeatherLocationRole.HOME, GeoPoint(homePlace.longitudeGcj02, homePlace.latitudeGcj02))
        val work = WeatherLocation(WeatherLocationRole.WORK, GeoPoint(workPlace.longitudeGcj02, workPlace.latitudeGcj02))
        return if (home.point == work.point) null else home to work
    }

    private fun com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers.toWeatherBufferProfile() =
        WeatherBufferProfile(lightMinutes, moderateMinutes, severeMinutes)

    private companion object {
        const val PROVIDER_TIMEOUT_MILLIS = 60_000L
    }
}
