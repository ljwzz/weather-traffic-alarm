package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.DeviceTestDependencies
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunConnectionTestResult
import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunCredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.local.ServiceCredentials
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WeatherRules
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real ViewModel refresh jobs against the installed Hilt graph. The weather
 * adapter is delayed deliberately so superseded requests complete after the input changed.
 */
@RunWith(AndroidJUnit4::class)
class HomePreviewConcurrencyDeviceTest {

    @Test
    fun supersededWeatherCannotOverwriteNewInputsOrClearNewRefreshIndicator() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        val settings = dependencies.settings()
        val credentials = dependencies.credentials()
        val originalSettings = settings.loadInitial()
        val originalCredentials = credentials.credentialsForServiceUse()
        val originalCredentialStatus = credentials.maskedValues()
        val fakeWeather = DelayedWeatherProvider(destinationA.longitudeGcj02, destinationB.longitudeGcj02)
        val viewModelStore = ViewModelStore()
        var homeCollector: kotlinx.coroutines.Job? = null

        try {
            settings.update { homeSettings(destinationA) }
            await { settings.settings.value.destinationId == DESTINATION_A_ID }
            credentials.saveVerifiedCaiyun(CaiyunCredentialInput("device-test-app-key", "device-test-secret"))
            await { credentials.state.value.caiyunTestResult == CaiyunConnectionTestResult.PASSED }

            val viewModel = ZhituViewModel(
                coordinator = dependencies.coordinator(),
                decisionRepository = dependencies.decisions(),
                evaluationWorkScheduler = dependencies.evaluationScheduler(),
                calendar = dependencies.calendar(),
                settingsStore = settings,
                overrideRepository = dependencies.workdayOverrides(),
                credentials = credentials,
                planCommuteOverrideRepository = dependencies.commuteOverrides(),
                effectiveCommuteResolver = dependencies.effectiveCommutes(),
                amapSdk = dependencies.amapSdk(),
                amapProvider = dependencies.amapWebProvider(),
                weatherProvider = fakeWeather,
                caiyunWeatherProvider = dependencies.caiyunWeatherProvider(),
            )
            viewModelStore.put("home-preview-concurrency", viewModel)
            homeCollector = launch { viewModel.homeUiState.collect() }
            await { viewModel.settingsReady.value }

            withContext(Dispatchers.Main.immediate) { viewModel.refreshHomePreviews(forceRefresh = true) }
            withTimeout(10_000) { fakeWeather.aStarted.await() }
            await { viewModel.homeUiState.value.isRefreshing }

            settings.update { homeSettings(destinationB) }
            await { settings.settings.value.destinationId == DESTINATION_B_ID }
            withContext(Dispatchers.Main.immediate) { viewModel.refreshHomePreviews(forceRefresh = true) }
            withTimeout(10_000) { fakeWeather.bStarted.await() }
            await { viewModel.homeUiState.value.isRefreshing }

            fakeWeather.aResponse.complete(fakeWeather.evaluationForA)
            withTimeout(10_000) { fakeWeather.aReturned.await() }
            await { viewModel.homeUiState.value.isRefreshing }
            assertTrue(viewModel.weatherState.value is WeatherUiState.Loading)

            withContext(Dispatchers.Main.immediate) {
                viewModel.refreshHomePreviews(forceRefresh = true)
                viewModel.refreshHomePreviews(forceRefresh = true)
            }
            delay(100)
            assertEquals(1, fakeWeather.bRequests.get())
            assertTrue(viewModel.homeUiState.value.isRefreshing)

            fakeWeather.bResponse.complete(fakeWeather.evaluationForB)
            await { (viewModel.weatherState.value as? WeatherUiState.Success)?.workName == destinationB.name }
            await { !viewModel.homeUiState.value.isRefreshing }
            assertEquals(1, fakeWeather.aRequests.get())
            assertEquals(1, fakeWeather.bRequests.get())

            settings.update { homeSettings(destinationC) }
            await { settings.settings.value.destinationId == DESTINATION_C_ID }
            await { viewModel.weatherState.value is WeatherUiState.Idle }
            assertFalse(viewModel.weatherState.value is WeatherUiState.Success)
        } finally {
            fakeWeather.aResponse.complete(fakeWeather.evaluationForA)
            fakeWeather.bResponse.complete(fakeWeather.evaluationForB)
            homeCollector?.cancelAndJoin()
            withContext(Dispatchers.Main.immediate) { viewModelStore.clear() }
            restoreCredentials(credentials, originalCredentials, originalCredentialStatus)
            settings.update { originalSettings }
        }
    }

    private suspend fun restoreCredentials(
        credentials: CredentialStore,
        original: ServiceCredentials?,
        originalStatus: CredentialStatus,
    ) {
        if (original == null) {
            credentials.clear()
            return
        }
        credentials.replace(
            CredentialInput(
                amapWebKey = original.amapWebKey.orEmpty(),
                amapSdkKey = original.amapSdkKey.orEmpty(),
                caiyunAppKey = original.caiyunAppKey.orEmpty(),
                caiyunSecret = original.caiyunSecret.orEmpty(),
            ),
        )
        if (!original.caiyunAppKey.isNullOrBlank() && !original.caiyunSecret.isNullOrBlank()) {
            when (originalStatus.caiyunTestResult) {
                CaiyunConnectionTestResult.PASSED -> credentials.recordStoredCaiyunTestSuccess(
                    originalStatus.caiyunLastTestedAtEpochMillis ?: System.currentTimeMillis(),
                )
                CaiyunConnectionTestResult.FAILED -> credentials.recordCaiyunTestFailure(
                    originalStatus.caiyunLastTestedAtEpochMillis ?: System.currentTimeMillis(),
                )
                CaiyunConnectionTestResult.NEVER_TESTED -> Unit
            }
        }
    }

    private fun homeSettings(destination: PlaceRef): LocalSettings = LocalSettings(
        privacyAccepted = true,
        amapConsentPromptedVersion = 1,
        amapConsentGranted = false,
        favorites = listOf(
            FavoritePlace(ORIGIN_ID, origin.name, origin.displayAddress, origin),
            FavoritePlace(destination.poiId!!, destination.name, destination.displayAddress, destination),
        ),
        originId = ORIGIN_ID,
        destinationId = destination.poiId,
        commuteMode = CommuteMode.DRIVING,
    )

    private class DelayedWeatherProvider(
        private val destinationALongitude: Double,
        private val destinationBLongitude: Double,
    ) : WeatherProvider {
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val aReturned = CompletableDeferred<Unit>()
        val aResponse = CompletableDeferred<WeatherEvaluation>()
        val bResponse = CompletableDeferred<WeatherEvaluation>()
        val aRequests = AtomicInteger()
        val bRequests = AtomicInteger()
        val evaluationForA = evaluation(WeatherSeverity.LIGHT)
        val evaluationForB = evaluation(WeatherSeverity.SEVERE)

        override suspend fun evaluate(request: WeatherRequest): WeatherEvaluation = withContext(NonCancellable) {
            when (request.work.point.longitudeGcj02) {
                destinationALongitude -> {
                    aRequests.incrementAndGet()
                    aStarted.complete(Unit)
                    aResponse.await().also { aReturned.complete(Unit) }
                }
                destinationBLongitude -> {
                    bRequests.incrementAndGet()
                    bStarted.complete(Unit)
                    bResponse.await()
                }
                else -> error("Unexpected weather destination")
            }
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(20)
        }
    }

    private companion object {
        const val ORIGIN_ID = "home-preview-origin"
        const val DESTINATION_A_ID = "home-preview-destination-a"
        const val DESTINATION_B_ID = "home-preview-destination-b"
        const val DESTINATION_C_ID = "home-preview-destination-c"
        val origin = place(ORIGIN_ID, "测试起点", 116.390, 39.900)
        val destinationA = place(DESTINATION_A_ID, "测试终点 A", 116.400, 39.910)
        val destinationB = place(DESTINATION_B_ID, "测试终点 B", 116.410, 39.920)
        val destinationC = place(DESTINATION_C_ID, "测试终点 C", 116.420, 39.930)

        fun place(id: String, name: String, longitude: Double, latitude: Double) = PlaceRef(
            poiId = id,
            name = name,
            displayAddress = "设备测试地址",
            longitudeGcj02 = longitude,
            latitudeGcj02 = latitude,
            adcode = "110000",
            citycode = "010",
        )

        fun evaluation(severity: WeatherSeverity): WeatherEvaluation {
            val reportTime = Instant.now()
            val start = reportTime.atZone(java.time.ZoneId.systemDefault()).withMinute(0).withSecond(0).withNano(0)
            val end = start.plusHours(23)
            return WeatherEvaluation(
                severity = severity,
                bufferMinutes = 0,
                weatherRuleVersion = WeatherRules.VERSION,
                providerReportTime = reportTime,
                participatingWindowStart = start,
                participatingWindowEnd = end,
                locations = listOf(
                    WeatherLocationEvaluation(WeatherLocationRole.HOME, severity, reportTime, start, end, WeatherDataSource.NETWORK),
                    WeatherLocationEvaluation(WeatherLocationRole.WORK, severity, reportTime, start, end, WeatherDataSource.NETWORK),
                ),
                source = WeatherDataSource.NETWORK,
            )
        }
    }
}
