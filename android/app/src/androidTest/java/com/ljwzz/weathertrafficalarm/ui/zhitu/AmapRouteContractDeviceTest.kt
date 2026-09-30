package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ljwzz.weathertrafficalarm.DeviceTestDependencies
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.IsolatedDeviceTestStores
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebApi
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebKey
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebKeyProvider
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapConsentProvider
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebProvider
import com.ljwzz.weathertrafficalarm.core.network.di.NetworkModule
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.rules.Timeout

/** Controlled HTTP bodies through the production parser, ViewModel and Compose screen. */
@RunWith(AndroidJUnit4::class)
class AmapRouteContractDeviceTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(60)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private var host: ComponentActivity? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var stores: IsolatedDeviceTestStores
    private lateinit var viewModel: ZhituViewModel
    private val viewModels = ViewModelStore()
    private val transport = ControlledTransport()
    private lateinit var client: OkHttpClient
    private var checkSavedState: (() -> Unit)? = null
    private lateinit var directory: File

    @Before
    fun prepare() {
        reportStage("prepare")
        // Shell launch avoids this device's blocking Instrumentation.startActivitySync path.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity",
        ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull { it.javaClass == ComponentActivity::class.java }
            }
            host != null
        }
        val dependencies = EntryPointAccessors.fromApplication(instrumentation.targetContext, DeviceTestDependencies::class.java)
        runBlocking {
            reportStage("snapshot-settings")
            val settingsBefore = dependencies.settings().loadInitial()
            reportStage("snapshot-credentials")
            val credentialsBefore = dependencies.credentials().credentialsForServiceUse()?.let { listOf(it.amapWebKey, it.amapSdkKey, it.caiyunAppKey, it.caiyunSecret) }
            val metadataBefore = dependencies.credentials().maskedValues()
            reportStage("snapshot-plans")
            val plansBefore = dependencies.plans().observeAll().first()
            checkSavedState = {
                runBlocking {
                    val unchanged = settingsBefore == dependencies.settings().loadInitial() &&
                        credentialsBefore == dependencies.credentials().credentialsForServiceUse()?.let { listOf(it.amapWebKey, it.amapSdkKey, it.caiyunAppKey, it.caiyunSecret) } &&
                        metadataBefore == dependencies.credentials().maskedValues() &&
                        plansBefore == dependencies.plans().observeAll().first()
                    instrumentation.sendStatus(0, Bundle().apply { putBoolean("savedApplicationStateUnchanged", unchanged) })
                    assertTrue("Saved application state changed", unchanged)
                }
            }
        }
        directory = File(instrumentation.targetContext.cacheDir, "n003-${UUID.randomUUID()}")
        reportStage("isolated-stores")
        stores = IsolatedDeviceTestStores(directory)
        runBlocking {
            stores.settings.update { testSettings() }
            stores.credentials.replace(CredentialInput(amapWebKey = "controlled-test-key"))
        }
        client = NetworkModule.provideOkHttpClient().newBuilder().addInterceptor(transport).build()
        val api = NetworkModule.provideAmapRetrofit(client, NetworkModule.provideJson()).create(AmapWebApi::class.java)
        val provider = AmapWebProvider(api, AmapWebKeyProvider { AmapWebKey("controlled-test-key", 1) }, AmapConsentProvider { true })
        instrumentation.runOnMainSync {
            viewModel = ZhituViewModel(
                dependencies.coordinator(), dependencies.decisions(), dependencies.evaluationScheduler(),
                dependencies.calendar(), stores.settings, dependencies.workdayOverrides(), stores.credentials,
                dependencies.commuteOverrides(), dependencies.effectiveCommutes(), dependencies.dailyInputs(), dependencies.amapSdk(),
                provider, dependencies.weatherProvider(), dependencies.caiyunWeatherProvider(),
            )
            viewModels.put("n003", viewModel)
        }
        reportStage("viewmodel-ready-wait")
        compose.waitUntil(10_000) {
            viewModel.settingsReady.value && viewModel.settings.value.originId == "origin" &&
                viewModel.credentialStatus.value.hasAmapWebKey
        }
        instrumentation.runOnMainSync { requireNotNull(host).setContent {
            val settings by viewModel.settings.collectAsState()
            val route by viewModel.routeState.collectAsState()
            ZhituTheme {
                LocalRouteScreen(
                    settings, route, MapStatus.NotInitialized,
                    onSave = { _, completed -> completed(null) }, onBack = {},
                    onModeChange = { viewModel.setRouteMode(it) },
                    onRefresh = { viewModel.refreshRoute(forceRefresh = true) },
                    onSelectRoute = viewModel::selectRoute, onTrafficChange = viewModel::setTrafficEnabled,
                )
            }
        } }
        reportStage("screen-ready")
    }

    private fun reportStage(stage: String) = instrumentation.sendStatus(0, Bundle().apply { putString("controlledStage", stage) })

    @After
    fun cleanup() {
        transport.fixtures.forEach { it.release.countDown() }
        try {
            instrumentation.runOnMainSync { viewModels.clear(); host?.finish() }
            if (::client.isInitialized) {
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                assertTrue("Controlled requests did not finish", client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS))
                client.connectionPool.evictAll()
            }
        } finally {
            try {
                if (::stores.isInitialized) stores.close()
            } finally {
                val cleaned = !::directory.isInitialized || !directory.exists()
                instrumentation.sendStatus(0, Bundle().apply { putBoolean("isolatedStorageRemoved", cleaned) })
                assertTrue("Isolated storage remains", cleaned)
                checkSavedState?.invoke()
            }
        }
    }

    @Test fun noRouteIsDisplayedAndRefreshRecovers() = errorAndRecovery(error("20802"), NO_ROUTE, "no-route")
    @Test fun apiRateLimitIsDisplayedAndRefreshRecovers() = errorAndRecovery(error("10019"), RATE_LIMIT, "api-rate-limit")
    @Test fun httpRateLimitIsDisplayedAndRefreshRecovers() = errorAndRecovery(Fixture(429, "{}"), RATE_LIMIT, "http-rate-limit")

    private fun errorAndRecovery(failure: Fixture, message: String, name: String) {
        transport.enqueue(failure)
        refresh()
        compose.waitUntil(10_000) { viewModel.routeState.value.message == message }
        assertTrue(viewModel.routeState.value.alternatives.isEmpty())
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        screenshot("$name.png")
        transport.enqueue(success(600))
        refresh()
        awaitDuration(600)
        compose.onNodeWithText(message).assertDoesNotExist()
        assertEquals("DRIVING:0", viewModel.routeState.value.selectedRouteId)
        assertEquals(1200L, viewModel.routeState.value.alternatives.single().distanceMeters)
        compose.onNodeWithText("10 分钟").performScrollTo().assertIsDisplayed()
        screenshot("$name-recovered.png")
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun cachedResultsShowFailureAndSuccessfulRefreshClearsIt() {
        transport.enqueue(success(600))
        refresh()
        awaitDuration(600)
        listOf(error("20802") to NO_ROUTE, error("10019") to RATE_LIMIT).forEachIndexed { index, (failure, message) ->
            transport.enqueue(failure)
            refresh()
            compose.waitUntil(10_000) { viewModel.routeState.value.refreshError == message }
            assertEquals(600L, viewModel.routeState.value.alternatives.single().durationSeconds)
            compose.onNodeWithText("更新失败，保留上次结果：$message").performScrollTo().assertIsDisplayed()
            screenshot("cached-error-$index.png")
        }
        transport.enqueue(success(720))
        refresh()
        awaitDuration(720)
        assertEquals(null, viewModel.routeState.value.refreshError)
        compose.onNodeWithText("更新失败，保留上次结果", substring = true).assertDoesNotExist()
        compose.onNodeWithText("12 分钟").performScrollTo().assertIsDisplayed()
        screenshot("cached-recovered.png")
    }

    @Test
    fun sameNameMovedCoordinatesAndModeSwitchRejectLateResponses() {
        val oldCoordinates = success(600, delayed = true)
        transport.enqueue(oldCoordinates)
        refresh()
        awaitStarted(oldCoordinates)
        val original = viewModel.settings.value
        val moved = original.copy(favorites = original.favorites.map {
            if (it.id == "destination") it.copy(placeRef = it.placeRef!!.copy(longitudeGcj02 = 116.42)) else it
        })
        runBlocking { stores.settings.update { moved } }
        compose.waitUntil(10_000) { viewModel.settings.value == moved && !viewModel.routeState.value.loading }
        assertEquals(original.favorites.map { it.id to it.name }, moved.favorites.map { it.id to it.name })
        assertTrue(viewModel.routeState.value.alternatives.isEmpty())
        transport.enqueue(success(900))
        refresh()
        awaitDuration(900)
        oldCoordinates.release.countDown()
        awaitFinished(oldCoordinates)
        compose.waitForIdle()
        assertEquals(900L, viewModel.routeState.value.alternatives.single().durationSeconds)
        assertEquals("116.41,39.92", transport.requests[0].destination)
        assertEquals("116.42,39.92", transport.requests[1].destination)
        screenshot("same-name-coordinate-change.png")

        val delayedDriving = success(1200, delayed = true)
        transport.enqueue(delayedDriving)
        refresh()
        awaitStarted(delayedDriving)
        val delayedBicycle = success(1500, delayed = true)
        transport.enqueue(delayedBicycle)
        compose.onNodeWithText("骑行").performScrollTo().performClick()
        awaitStarted(delayedBicycle)
        transport.enqueue(success(1800))
        compose.onNodeWithText("电动车").performScrollTo().performClick()
        awaitDuration(1800)
        delayedDriving.release.countDown()
        delayedBicycle.release.countDown()
        awaitFinished(delayedDriving)
        awaitFinished(delayedBicycle)
        compose.waitForIdle()
        assertEquals(1800L, viewModel.routeState.value.alternatives.single().durationSeconds)
        assertEquals("ELECTRIC_BICYCLE:0", viewModel.routeState.value.selectedRouteId)
        assertTrue(transport.requests.last().path.endsWith("electrobike"))
        compose.onNodeWithText("电动车").assertIsSelected()
        compose.onNodeWithText("30 分钟").performScrollTo().assertIsDisplayed()
        screenshot("final-mode-after-late-responses.png")
    }

    private fun refresh() { compose.onNodeWithText("刷新").performScrollTo().performClick() }
    private fun awaitDuration(seconds: Long) {
        compose.waitUntil(10_000) {
            !viewModel.routeState.value.loading && viewModel.routeState.value.alternatives.singleOrNull()?.durationSeconds == seconds
        }
    }
    private fun awaitStarted(fixture: Fixture) { assertTrue("Request did not start", fixture.started.await(10, TimeUnit.SECONDS)) }
    private fun awaitFinished(fixture: Fixture) { assertTrue("Request did not finish", fixture.finished.await(10, TimeUnit.SECONDS)) }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir("qa/amap-supplement"))
        folder.mkdirs()
        File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private class Fixture(val status: Int, val body: String, delayed: Boolean = false) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        val finished = CountDownLatch(1)
    }
    private data class RecordedRequest(val path: String, val destination: String?)
    private class ControlledTransport : Interceptor {
        private val queue = LinkedBlockingQueue<Fixture>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val fixtures = CopyOnWriteArrayList<Fixture>()
        fun enqueue(fixture: Fixture) { fixtures += fixture; queue.add(fixture) }
        override fun intercept(chain: Interceptor.Chain): Response {
            val fixture = checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "Unexpected controlled request" }
            requests += RecordedRequest(chain.request().url.encodedPath, chain.request().url.queryParameter("destination"))
            fixture.started.countDown()
            try {
                check(fixture.release.await(20, TimeUnit.SECONDS)) { "Controlled response was not released" }
                return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(fixture.status).message("Controlled response")
                    .body(fixture.body.toResponseBody("application/json".toMediaType())).build()
            } finally { fixture.finished.countDown() }
        }
    }

    private fun error(code: String) = Fixture(200, """{"status":"0","info":"controlled failure","infocode":"$code"}""")
    private fun success(seconds: Int, delayed: Boolean = false) = Fixture(200,
        """{"status":"1","info":"OK","infocode":"10000","route":{"paths":[{"cost":{"duration":"$seconds"},"distance":"1200","steps":[{"polyline":"116.40,39.91;116.41,39.92"}]}]}}""", delayed)
    private fun testSettings(): LocalSettings {
        fun place(name: String, longitude: Double, latitude: Double) = PlaceRef(
            name = name, displayAddress = "公开测试地点", longitudeGcj02 = longitude, latitudeGcj02 = latitude,
            adcode = "110000", citycode = "010",
        )
        return LocalSettings(
            privacyAccepted = true, amapConsentGranted = true,
            favorites = listOf(
                FavoritePlace("origin", "公开起点", "公开测试地点", place("公开起点", 116.40, 39.91)),
                FavoritePlace("destination", "同名终点", "公开测试地点", place("同名终点", 116.41, 39.92)),
            ), originId = "origin", destinationId = "destination",
        )
    }
    private companion object {
        const val NO_ROUTE = "未找到可用路线"
        const val RATE_LIMIT = "请求过于频繁，请稍后重试"
    }
}
