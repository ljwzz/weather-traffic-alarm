package com.ljwzz.weathertrafficalarm.core.network.amap

import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import com.ljwzz.weathertrafficalarm.core.network.di.NetworkModule
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AmapWebProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: AmapWebProvider
    private var clock = 0L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AmapWebApi::class.java)
        provider = AmapWebProvider(
            api = api,
            keyProvider = AmapWebKeyProvider { "test-key" },
            consentProvider = AmapConsentProvider { true },
            nowMillis = { clock },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun inputTipsMapsPlacesWithoutCachingQueriesOrCoordinates() = runTest {
        server.enqueue(success("""{"tips":[{"id":"B000A","name":"天安门","district":"东城区","location":"116.397428,39.90923","adcode":"110101","citycode":"010"}]}"""))
        server.enqueue(success("""{"tips":[{"id":"B000A","name":"天安门","district":"东城区","location":"116.397428,39.90923","adcode":"110101","citycode":"010"}]}"""))

        val first = provider.inputTips("天安门", city = "010", location = point())
        val second = provider.inputTips("天安门", city = "010", location = point())

        assertEquals(first, second)
        assertEquals(2, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v3/assistant/inputtips", request.requestUrl!!.encodedPath)
        assertEquals("test-key", request.requestUrl!!.queryParameter("key"))
        assertEquals("116.397428,39.90923", request.requestUrl!!.queryParameter("location"))
        assertEquals("天安门", first.single().name)
        assertEquals("东城区", first.single().displayAddress)
        assertEquals("/v3/assistant/inputtips", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun searchAndReverseGeocodeUseRequiredEndpoints() = runTest {
        server.enqueue(success("""{"pois":[{"id":"B000A","name":"天安门","address":"东城区","location":"116.397428,39.90923","adcode":"110101","citycode":"010"}]}"""))
        server.enqueue(success("""{"regeocode":{"formatted_address":"北京市东城区天安门","addressComponent":{"adcode":"110101","citycode":"010"}}}"""))

        val places = provider.search("天安门", region = "北京", page = 2, pageSize = 10)
        val reverse = provider.reverseGeocode(point())

        val search = server.takeRequest()
        assertEquals("/v5/place/text", search.requestUrl!!.encodedPath)
        assertEquals("2", search.requestUrl!!.queryParameter("page_num"))
        assertEquals("10", search.requestUrl!!.queryParameter("page_size"))
        assertEquals("天安门", places.single().name)
        val regeo = server.takeRequest()
        assertEquals("/v3/geocode/regeo", regeo.requestUrl!!.encodedPath)
        assertEquals("北京市东城区天安门", reverse.displayAddress)
        assertEquals("110101", reverse.adcode)
    }

    @Test
    fun routesSelectEveryAmapEndpointLimitAlternativesAndParsePolylines() = runTest {
        repeat(4) { server.enqueue(success(routeBody(paths = 4))) }
        server.enqueue(success(transitBody(transits = 4)))
        val expectedPaths = listOf(
            CommuteMode.DRIVING to "/v5/direction/driving",
            CommuteMode.WALKING to "/v5/direction/walking",
            CommuteMode.BICYCLING to "/v5/direction/bicycling",
            CommuteMode.ELECTRIC_BICYCLE to "/v5/direction/electrobike",
            CommuteMode.TRANSIT to "/v5/direction/transit/integrated",
        )

        expectedPaths.forEach { (mode, path) ->
            val estimate = provider.estimate(
                RouteRequest(point(), GeoPoint(116.407428, 39.91923), mode, originCity = "010", destinationCity = "010"),
            )
            assertEquals(3, estimate.alternatives.size)
            assertEquals("${mode.name}:0", estimate.alternatives.first().id)
            assertEquals(100L, estimate.alternatives.first().durationSeconds)
            assertEquals(2, estimate.alternatives.first().polyline.size)
            val received = server.takeRequest().requestUrl!!
            assertEquals(path, received.encodedPath)
            assertEquals("cost,polyline", received.queryParameter("show_fields"))
            when (mode) {
                CommuteMode.DRIVING -> {
                    assertEquals("32", received.queryParameter("strategy"))
                    assertEquals("3", received.queryParameter("alternative_route"))
                }
                CommuteMode.WALKING -> assertEquals("3", received.queryParameter("alternative_route"))
                CommuteMode.TRANSIT -> assertEquals("3", received.queryParameter("AlternativeRoute"))
                CommuteMode.BICYCLING, CommuteMode.ELECTRIC_BICYCLE -> assertEquals(null, received.queryParameter("alternative_route"))
            }
        }
    }

    @Test
    fun routesUseTopLevelDurationWhenCostIsAbsent() = runTest {
        server.enqueue(success(legacyRouteBody()))

        val estimate = provider.estimate(RouteRequest(point(), GeoPoint(116.407428, 39.91923), CommuteMode.DRIVING))

        assertEquals(600L, estimate.alternatives.single().durationSeconds)
        assertEquals("cost,polyline", server.takeRequest().requestUrl!!.queryParameter("show_fields"))
    }

    @Test
    fun transitRejectsMissingCityCodesBeforeSendingARequest() = runTest {
        val error = runCatching {
            provider.estimate(RouteRequest(point(), GeoPoint(116.407428, 39.91923), CommuteMode.TRANSIT))
        }.exceptionOrNull() as ProviderError

        assertEquals(ProviderError.Category.INVALID_REQUEST, error.category)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun transitUsesAmapDateAndHyphenatedTimeFormat() = runTest {
        server.enqueue(success(transitBody(transits = 1)))

        provider.estimate(
            RouteRequest(
                origin = point(),
                destination = GeoPoint(116.407428, 39.91923),
                mode = CommuteMode.TRANSIT,
                originCity = "010",
                destinationCity = "010",
                departureAt = LocalDateTime.of(2026, 9, 2, 9, 54),
            ),
        )

        val request = server.takeRequest().requestUrl!!
        assertEquals("2026-09-02", request.queryParameter("date"))
        assertEquals("9-54", request.queryParameter("time"))
    }

    @Test
    fun unconsentedProviderRejectsAllCallsBeforeReadingKeyOrSendingRequests() = runTest {
        val denied = AmapWebProvider(
            api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(AmapWebApi::class.java),
            keyProvider = AmapWebKeyProvider { error("key must not be read") },
            consentProvider = AmapConsentProvider { false },
        )
        val routeRequest = RouteRequest(point(), GeoPoint(116.407428, 39.91923), CommuteMode.DRIVING)

        listOf(
            runCatching { denied.inputTips("天安门") }.exceptionOrNull(),
            runCatching { denied.search("天安门") }.exceptionOrNull(),
            runCatching { denied.reverseGeocode(point()) }.exceptionOrNull(),
            runCatching { denied.estimate(routeRequest) }.exceptionOrNull(),
        ).forEach { error ->
            assertTrue(error is ProviderError)
            assertEquals(ProviderError.Category.CONSENT_REQUIRED, (error as ProviderError).category)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun routeCacheExpiresAfterFiveMinutesAndCleansExpiredEntriesOnAccess() = runTest {
        server.enqueue(success(routeBody(paths = 1)))
        val request = RouteRequest(point(), GeoPoint(116.407428, 39.91923), CommuteMode.DRIVING)

        provider.estimate(request)
        provider.estimate(request)
        assertEquals(1, server.requestCount)

        server.enqueue(success(routeBody(paths = 1)))
        provider.estimate(RouteRequest(point(), GeoPoint(116.417428, 39.92923), CommuteMode.DRIVING))
        assertEquals(2, routeCacheSize())

        clock += 5 * 60 * 1000L
        server.enqueue(success(routeBody(paths = 1)))
        provider.estimate(RouteRequest(point(), GeoPoint(116.427428, 39.93923), CommuteMode.DRIVING))

        assertEquals(3, server.requestCount)
        assertEquals(1, routeCacheSize())
    }

    @Test
    fun amapInfoCodeIsClassifiedWithoutExposingKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"0","info":"CUQPS has exceeded the limit","infocode":"10019"}"""))

        val error = runCatching { provider.inputTips("天安门") }.exceptionOrNull() as ProviderError

        assertEquals(ProviderError.Category.RATE_LIMITED, error.category)
        assertEquals("10019", error.providerCode)
        assertFalse(error.message!!.contains("test-key"))
    }

    @Test
    fun networkModuleDoesNotInstallRequestUrlLogging() {
        val client = NetworkModule.provideOkHttpClient()
        assertTrue(client.interceptors.none { it is HttpLoggingInterceptor })
        assertTrue(client.networkInterceptors.none { it is HttpLoggingInterceptor })
    }

    private fun point() = GeoPoint(116.397428, 39.90923)

    private fun success(body: String) = MockResponse().setResponseCode(200).setBody("""{"status":"1","info":"OK","infocode":"10000",${body.removePrefix("{")}""")

    private fun routeBody(paths: Int): String = buildString {
        append("{\"route\":{\"paths\":[")
        repeat(paths) { index ->
            if (index > 0) append(',')
            append("{\"duration\":\"999\",\"distance\":\"${200 + index}\",\"cost\":{\"duration\":\"${100 + index}\"},\"steps\":[{\"polyline\":\"116.397428,39.90923;116.407428,39.91923\"}]}")
        }
        append("]}}")
    }

    private fun transitBody(transits: Int): String = buildString {
        append("{\"route\":{\"transits\":[")
        repeat(transits) { index ->
            if (index > 0) append(',')
            append("{\"distance\":\"${200 + index}\",\"cost\":{\"duration\":\"${100 + index}\"},\"segments\":[{\"walking\":{\"polyline\":\"116.397428,39.90923;116.407428,39.91923\"}}]}")
        }
        append("]}}")
    }

    private fun legacyRouteBody() = """{"route":{"paths":[{"duration":"600","distance":"200","steps":[{"polyline":"116.397428,39.90923;116.407428,39.91923"}]}]}}"""

    private fun routeCacheSize(): Int {
        val field = AmapWebProvider::class.java.getDeclaredField("cache")
        field.isAccessible = true
        return (field.get(provider) as Map<*, *>).size
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
