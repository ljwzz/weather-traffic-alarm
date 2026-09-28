package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunConnectionTestResult
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePreviewCoordinatorTest {
    private val now = Instant.parse("2026-09-05T08:01:00Z")

    @Test
    fun movedPlaceWithTheSameFavoriteIdInvalidatesRouteAndWeatherInputs() {
        val credentials = readyCredentials()
        val original = settings(originLongitude = 116.40)
        val moved = original.copy(
            favorites = original.favorites.map { favorite ->
                if (favorite.id == "home") favorite.copy(placeRef = favorite.placeRef!!.copy(longitudeGcj02 = 116.41)) else favorite
            },
        )

        val originalInputs = homePreviewInputs(original, credentials, now)
        val movedInputs = homePreviewInputs(moved, credentials, now)

        assertFalse(originalInputs.routeKey == movedInputs.routeKey)
        assertFalse(originalInputs.weatherKey == movedInputs.weatherKey)
    }

    @Test
    fun credentialReplacementInvalidatesTheRelevantPreview() {
        val settings = settings()
        val initial = readyCredentials()
        val replacement = initial.copy(amapWebVersion = initial.amapWebVersion + 1)
        val changedWeatherKey = initial.copy(caiyunVersion = initial.caiyunVersion + 1)

        assertFalse(homePreviewInputs(settings, initial, now).routeKey == homePreviewInputs(settings, replacement, now).routeKey)
        assertFalse(homePreviewInputs(settings, initial, now).weatherKey == homePreviewInputs(settings, changedWeatherKey, now).weatherKey)
        assertEquals(homePreviewInputs(settings, initial, now).weatherKey, homePreviewInputs(settings, initial.copy(caiyunTestResult = CaiyunConnectionTestResult.FAILED), now).weatherKey)
    }

    @Test
    fun legacyVersionZeroCredentialsAndStorageRecoveryInvalidateInitialSnapshots() {
        val settings = settings()
        val unread = homePreviewInputs(settings, CredentialStatus(), now)
        val ready = readyCredentials().copy(amapWebVersion = 0, caiyunVersion = 0)
        val loaded = homePreviewInputs(settings, ready, now)
        val failed = homePreviewInputs(settings, ready.copy(storageError = true), now)

        assertFalse(unread.routeKey == loaded.routeKey)
        assertFalse(unread.weatherKey == loaded.weatherKey)
        assertFalse(failed.routeKey == loaded.routeKey)
        assertFalse(failed.weatherKey == loaded.weatherKey)
        assertFalse(loaded.routeKey == homePreviewInputs(settings, ready.copy(amapWebKeyMask = null), now).routeKey)
    }

    @Test
    fun previewFreshnessExpiresAtTheExactTtlBoundary() {
        val fetchedAt = 1_000L

        assertTrue(HomePreviewPolicy.isFresh(fetchedAt, HomePreviewPolicy.ROUTE_TTL_MILLIS, fetchedAt + HomePreviewPolicy.ROUTE_TTL_MILLIS - 1))
        assertFalse(HomePreviewPolicy.isFresh(fetchedAt, HomePreviewPolicy.ROUTE_TTL_MILLIS, fetchedAt + HomePreviewPolicy.ROUTE_TTL_MILLIS))
        assertFalse(HomePreviewPolicy.isFresh(fetchedAt, HomePreviewPolicy.WEATHER_TTL_MILLIS, fetchedAt - 1))
    }

    @Test
    fun staleResultForPreviousInputsIsNotRenderedAsTheCurrentRoute() {
        val credentials = readyCredentials()
        val oldSettings = settings(originLongitude = 116.40)
        val newSettings = settings(originLongitude = 116.41)
        val oldInputs = homePreviewInputs(oldSettings, credentials, now)
        val state = RouteUiState(
            alternatives = listOf(RouteAlternative("old", 600, 2_000, emptyList())),
            selectedRouteId = "old",
            inputKey = oldInputs.routeKey,
            fetchedAtEpochMillis = now.toEpochMilli(),
            source = RouteDataSource.NETWORK,
        )

        assertEquals(
            HomeRouteCardState.Ready,
            homeRouteCard(credentials, newSettings, homePreviewInputs(newSettings, credentials, now), state),
        )
    }

    @Test
    fun successfulCacheResultKeepsSummaryAndShowsCacheSource() {
        val credentials = readyCredentials()
        val settings = settings()
        val inputs = homePreviewInputs(settings, credentials, now)
        val weather = WeatherUiState.Success(
            homeName = "家",
            workName = "公司",
            severity = WeatherSeverity.LIGHT,
            reportTime = now,
            source = WeatherDataSource.CACHE,
            inputKey = inputs.weatherKey,
            fetchedAtEpochMillis = now.toEpochMilli(),
            windowEnd = now.atZone(ZoneId.systemDefault()).plusHours(23),
            refreshError = "网络不可用，请检查连接",
        )

        val card = homeWeatherCard(credentials, inputs, weather) as HomeWeatherCardState.Success

        assertEquals(WeatherDataSource.CACHE, card.source)
        assertEquals("网络不可用，请检查连接", card.refreshError)
        assertEquals("家 → 公司", card.route)
    }

    @Test
    fun missingCredentialsAndConsentHaveSpecificRecoveryStates() {
        val settings = settings(amapConsentGranted = false)
        val empty = CredentialStatus(loaded = true)
        val inputs = homePreviewInputs(settings, empty, now)

        assertEquals(HomeWeatherCardState.MissingCredentials, homeWeatherCard(empty, inputs, WeatherUiState.Idle))
        assertEquals(HomeRouteCardState.ConsentRequired, homeRouteCard(empty, settings, inputs, RouteUiState()))
    }

    @Test
    fun savedWeatherCredentialsCanPreviewRegardlessOfDiagnosticTestResult() {
        val settings = settings()
        for (result in CaiyunConnectionTestResult.entries) {
            val credentials = readyCredentials().copy(caiyunTestResult = result)
            val inputs = homePreviewInputs(settings, credentials, now)
            assertEquals(HomeWeatherCardState.Ready, homeWeatherCard(credentials, inputs, WeatherUiState.Idle))
        }
    }

    @Test
    fun credentialStorageFailureDoesNotMasqueradeAsMissingConfiguration() {
        val credentials = CredentialStatus(loaded = true, storageError = true)
        val settings = settings()
        val inputs = homePreviewInputs(settings, credentials, now)

        assertEquals(HomeWeatherCardState.CredentialStorageError, homeWeatherCard(credentials, inputs, WeatherUiState.Idle))
        assertEquals(HomeRouteCardState.CredentialStorageError, homeRouteCard(credentials, settings, inputs, RouteUiState()))
    }

    @Test
    fun forceRefreshUpgradesAnOrdinaryRequestAndCancelledCompletionCannotClearTheReplacement() = runTest {
        val inputs = homePreviewInputs(settings(), readyCredentials(), now)
        val gate = HomeRefreshGate()
        val ordinary = gate.begin(inputs, forceRefresh = false)
        val ordinaryMayFinish = CompletableDeferred<Unit>()
        var ordinaryClearedIndicator = true
        launch {
            ordinaryMayFinish.await()
            ordinaryClearedIndicator = gate.finish(ordinary.token)
        }

        val forced = gate.begin(inputs, forceRefresh = true)
        assertTrue(ordinary.accepted)
        assertTrue(forced.accepted)
        assertTrue(forced.cancelCurrent)
        assertFalse(gate.begin(inputs, forceRefresh = true).accepted)

        ordinaryMayFinish.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(ordinaryClearedIndicator)
        assertTrue(gate.finish(forced.token))
    }

    private fun readyCredentials() = CredentialStatus(
        amapWebKeyMask = "****",
        caiyunAppKeyMask = "****",
        caiyunSecretMask = "****",
        caiyunTestResult = CaiyunConnectionTestResult.PASSED,
        loaded = true,
        amapWebVersion = 7L,
        caiyunVersion = 9L,
    )

    private fun settings(originLongitude: Double = 116.40, amapConsentGranted: Boolean = true): LocalSettings {
        val home = PlaceRef(name = "家", displayAddress = "家", longitudeGcj02 = originLongitude, latitudeGcj02 = 39.90, adcode = "110000", citycode = "010")
        val work = PlaceRef(name = "公司", displayAddress = "公司", longitudeGcj02 = 116.50, latitudeGcj02 = 39.80, adcode = "110000", citycode = "010")
        return LocalSettings(
            amapConsentGranted = amapConsentGranted,
            favorites = listOf(
                FavoritePlace("home", "家", "家", home),
                FavoritePlace("work", "公司", "公司", work),
            ),
            originId = "home",
            destinationId = "work",
            commuteMode = CommuteMode.DRIVING,
        )
    }
}
