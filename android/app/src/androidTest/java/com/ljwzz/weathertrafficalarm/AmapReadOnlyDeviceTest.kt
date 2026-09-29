package com.ljwzz.weathertrafficalarm

import android.app.Instrumentation
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.map.AmapSdkInitialization
import com.ljwzz.weathertrafficalarm.core.map.isAmapNativeRendererSupported
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteDataSource
import com.ljwzz.weathertrafficalarm.core.model.RouteEstimate
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in, read-only smoke test against the saved encrypted AMap credentials on the
 * target device. It reuses the real application graph, starts no Activity, writes no credential,
 * setting, place, alarm, calendar or permission, and reports only service status, result counts
 * and request categories. Public landmark keywords and service-returned coordinates are used;
 * no saved personal place is requested or printed.
 */
@RunWith(AndroidJUnit4::class)
class AmapReadOnlyDeviceTest {
    @Test
    fun verifiesSavedWebKeyWithPublicTestPlacesAndAllFiveRouteModes() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(OPT_IN_HINT, instrumentation.isOptedIn())

        val dependencies = EntryPointAccessors.fromApplication(
            instrumentation.targetContext,
            DeviceTestDependencies::class.java,
        )
        val credentialsBefore = dependencies.credentials().maskedValues()
        val settingsBefore = dependencies.settings().loadInitial()
        val planIdsBefore = dependencies.plans().observeAll().first().map { it.id }.sorted()
        val failures = mutableListOf<String>()

        try {
            instrumentation.report(
                "credentialStorageReadable" to !credentialsBefore.storageError,
                "hasAmapWebKey" to credentialsBefore.hasAmapWebKey,
                "amapConsentGranted" to settingsBefore.amapConsentGranted,
                "internetCapableNetwork" to instrumentation.targetContext.hasInternetCapableNetwork(),
            )
            assumeTrue("Requires readable saved credential storage", !credentialsBefore.storageError)
            assumeTrue("Requires a saved AMap Web Service key", credentialsBefore.hasAmapWebKey)
            assumeTrue("Requires granted AMap consent on the target device", settingsBefore.amapConsentGranted)
            assumeTrue(
                "Requires an active network with the INTERNET capability",
                instrumentation.targetContext.hasInternetCapableNetwork(),
            )

            val provider = dependencies.amapWebProvider()
            val tips = probe(instrumentation, failures, "place:inputTips") {
                provider.inputTips(TIP_KEYWORD, city = TEST_CITY)
            }
            instrumentation.report("inputTipsResultCount" to (tips?.size ?: 0))
            check(failures, "place:inputTips:EMPTY_RESULT", tips?.isNotEmpty() == true)

            val pois = probe(instrumentation, failures, "place:textSearch") {
                provider.search(POI_KEYWORD, region = TEST_CITY)
            }
            instrumentation.report("textSearchResultCount" to (pois?.size ?: 0))
            check(failures, "place:textSearch:EMPTY_RESULT", pois?.isNotEmpty() == true)

            val origin = tips?.firstOrNull()
            val destination = pois?.firstOrNull()
            if (origin == null || destination == null) {
                check(failures, "route:ALL:MISSING_PUBLIC_TEST_PLACES", false)
            } else {
                verifyPlacesAndRoutes(instrumentation, failures, provider, origin, destination)
            }
        } finally {
            val credentialsAfter = dependencies.credentials().maskedValues()
            val settingsAfter = dependencies.settings().loadInitial()
            val planIdsAfter = dependencies.plans().observeAll().first().map { it.id }.sorted()
            assertEquals(
                "Credential metadata changed during read-only AMap verification",
                credentialsBefore,
                credentialsAfter,
            )
            assertEquals("Settings changed during read-only AMap verification", settingsBefore, settingsAfter)
            assertEquals("Alarm plans changed during read-only AMap verification", planIdsBefore, planIdsAfter)
        }

        assertTrue("Read-only AMap verification failures: $failures", failures.isEmpty())
    }

    @Test
    fun initializesSavedAndroidSdkKeyAndChecksNativeRendererSupport() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(OPT_IN_HINT, instrumentation.isOptedIn())

        val dependencies = EntryPointAccessors.fromApplication(
            instrumentation.targetContext,
            DeviceTestDependencies::class.java,
        )
        val credentials = dependencies.credentials().maskedValues()
        val settings = dependencies.settings().loadInitial()
        instrumentation.report(
            "credentialStorageReadable" to !credentials.storageError,
            "hasAmapSdkKey" to credentials.hasAmapSdkKey,
            "amapConsentGranted" to settings.amapConsentGranted,
        )
        assumeTrue("Requires readable saved credential storage", !credentials.storageError)
        assumeTrue("Requires granted AMap consent on the target device", settings.amapConsentGranted)
        assumeTrue("Requires a saved AMap Android SDK key", credentials.hasAmapSdkKey)

        val sdkKey = dependencies.credentials().credentialsForServiceUse()?.amapSdkKey.orEmpty()
        val initialization = dependencies.amapSdk().initialize(
            instrumentation.targetContext,
            sdkKey,
            settings.amapConsentGranted,
        )
        val rendererSupported = isAmapNativeRendererSupported()
        instrumentation.report(
            "sdkInitialization" to initialization::class.java.simpleName,
            "nativeRendererSupported" to rendererSupported,
            "virtualDevice" to instrumentation.targetContext.isVirtualDevice(),
        )

        assertEquals("Saved AMap Android SDK key must initialize the SDK", AmapSdkInitialization.Ready, initialization)
        assertEquals(
            "Native renderer support must follow the SPEC FR-011 virtual device rule",
            !instrumentation.targetContext.isVirtualDevice(),
            rendererSupported,
        )
    }

    private suspend fun verifyPlacesAndRoutes(
        instrumentation: Instrumentation,
        failures: MutableList<String>,
        provider: com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebProvider,
        origin: PlaceRef,
        destination: PlaceRef,
    ) {
        check(
            failures,
            "place:PUBLIC_TEST_PLACES_NOT_DISTINCT",
            origin.longitudeGcj02 != destination.longitudeGcj02 ||
                origin.latitudeGcj02 != destination.latitudeGcj02,
        )

        val originGeocode = probe(instrumentation, failures, "place:reverseGeocodeOrigin") {
            provider.reverseGeocode(origin.geoPoint())
        }
        val destinationGeocode = probe(instrumentation, failures, "place:reverseGeocodeDestination") {
            provider.reverseGeocode(destination.geoPoint())
        }
        instrumentation.report(
            "reverseGeocodeResultCount" to listOfNotNull(originGeocode, destinationGeocode).size,
        )
        check(failures, "place:reverseGeocodeOrigin:EMPTY_NAME", originGeocode?.name?.isNotBlank() == true)
        check(failures, "place:reverseGeocodeDestination:EMPTY_NAME", destinationGeocode?.name?.isNotBlank() == true)
        val originCityCode = originGeocode?.citycode?.takeIf(String::isNotBlank)
        val destinationCityCode = destinationGeocode?.citycode?.takeIf(String::isNotBlank)
        instrumentation.report("transitCityCodeResolved" to (originCityCode != null && destinationCityCode != null))
        check(failures, "place:TRANSIT_CITY_CODE_UNRESOLVED", originCityCode != null && destinationCityCode != null)

        ROUTE_MODES.forEach { mode ->
            val request = RouteRequest(
                origin = origin.geoPoint(),
                destination = destination.geoPoint(),
                mode = mode,
                originCity = originCityCode.takeIf { mode == CommuteMode.TRANSIT },
                destinationCity = destinationCityCode.takeIf { mode == CommuteMode.TRANSIT },
                departureAt = LocalDateTime.now().takeIf { mode == CommuteMode.TRANSIT },
            )
            val estimate = probe(instrumentation, failures, "route:${mode.name}") {
                provider.estimate(request)
            } ?: return@forEach
            verifyRoute(instrumentation, failures, mode, estimate)
        }
    }

    private fun verifyRoute(
        instrumentation: Instrumentation,
        failures: MutableList<String>,
        mode: CommuteMode,
        estimate: RouteEstimate,
    ) {
        val category = "route:${mode.name}"
        val alternatives = estimate.alternatives
        instrumentation.report(
            "${category}:alternativeCount" to alternatives.size,
            "${category}:polylineAlternativeCount" to alternatives.count { it.polyline.isNotEmpty() },
            "${category}:source" to estimate.source.name,
        )
        check(failures, "$category:NO_ALTERNATIVE", alternatives.isNotEmpty())
        check(failures, "$category:OVER_THREE_ALTERNATIVES", alternatives.size <= MAX_DISPLAYED_ROUTES)
        check(failures, "$category:NON_POSITIVE_DURATION", alternatives.all { it.durationSeconds > 0 })
        check(failures, "$category:NON_POSITIVE_DISTANCE", alternatives.all { it.distanceMeters > 0 })
        check(failures, "$category:MISSING_POLYLINE", alternatives.any { it.polyline.isNotEmpty() })
        check(failures, "$category:NOT_FROM_NETWORK", estimate.source == RouteDataSource.NETWORK)
    }

    private suspend fun <T> probe(
        instrumentation: Instrumentation,
        failures: MutableList<String>,
        category: String,
        block: suspend () -> T,
    ): T? {
        val startedAtMillis = System.currentTimeMillis()
        fun reportFailure(categoryName: String, providerCode: String?, failureType: String?) {
            failures += "$category:$categoryName"
            instrumentation.report(
                "failedRequestCategory" to category,
                "failureCategory" to categoryName,
                "failureElapsedMillis" to (System.currentTimeMillis() - startedAtMillis),
                "providerCode" to providerCode.orEmpty(),
                "failureType" to failureType.orEmpty(),
            )
        }
        return try {
            val value = withTimeout(REQUEST_TIMEOUT_MILLIS) { block() }
            instrumentation.report(
                "requestCategory" to category,
                "requestResult" to "OK",
                "elapsedMillis" to (System.currentTimeMillis() - startedAtMillis),
            )
            value
        } catch (timeout: TimeoutCancellationException) {
            reportFailure(ProviderError.Category.TIMEOUT.name, null, timeout::class.java.simpleName)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProviderError) {
            reportFailure(
                failure.category.name,
                failure.providerCode?.takeIf { it.matches(SANITIZED_PROVIDER_CODE) },
                failure::class.java.simpleName,
            )
            null
        } catch (failure: Throwable) {
            // Only the failure type is reported: raw exceptions can embed request URLs carrying credentials.
            Log.w(TAG, "AMap read-only request failed without a classified category: ${failure::class.java.simpleName}")
            reportFailure("UNCLASSIFIED", null, failure::class.java.simpleName)
            null
        }
    }

    private fun check(failures: MutableList<String>, message: String, condition: Boolean) {
        if (!condition) failures += message
    }

    private fun PlaceRef.geoPoint(): GeoPoint = GeoPoint(longitudeGcj02, latitudeGcj02)

    private companion object {
        const val TAG = "AmapReadOnlyDeviceTest"
        const val OPT_IN_HINT = "Requires explicit -e verifyAmapNetwork true opt-in"
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
        const val MAX_DISPLAYED_ROUTES = 3

        /** Public test landmarks; no saved personal place is used or printed. */
        const val TIP_KEYWORD = "天安门"
        const val POI_KEYWORD = "北京南站"
        const val TEST_CITY = "010"
        val SANITIZED_PROVIDER_CODE = Regex("(HTTP_)?[0-9]{3,6}")

        val ROUTE_MODES = listOf(
            CommuteMode.DRIVING,
            CommuteMode.TRANSIT,
            CommuteMode.WALKING,
            CommuteMode.BICYCLING,
            CommuteMode.ELECTRIC_BICYCLE,
        )
    }
}

private fun Instrumentation.isOptedIn(): Boolean =
    InstrumentationRegistry.getArguments().getString("verifyAmapNetwork") == "true"

private fun Instrumentation.report(vararg values: Pair<String, Any?>): Unit =
    sendStatus(0, Bundle().apply { values.forEach { (key, value) -> putAny(key, value) } })

private fun Bundle.putAny(key: String, value: Any?) {
    when (value) {
        null -> putString(key, null)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is String -> putString(key, value)
        else -> putString(key, value.toString())
    }
}

private fun Context.hasInternetCapableNetwork(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun Context.isVirtualDevice(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    return Build.HARDWARE.lowercase() in setOf("goldfish", "ranchu") ||
        "generic" in fingerprint ||
        "emulator" in fingerprint ||
        "emulator" in model ||
        "sdk_gphone" in model
}
