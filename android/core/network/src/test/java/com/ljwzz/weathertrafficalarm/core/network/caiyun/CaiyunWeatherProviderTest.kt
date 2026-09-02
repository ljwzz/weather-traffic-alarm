package com.ljwzz.weathertrafficalarm.core.network.caiyun

import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WeatherTimeWindow
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class CaiyunWeatherProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: CaiyunWeatherProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CaiyunWeatherApi::class.java)
        provider = CaiyunWeatherProvider(
            api = api,
            credentialsProvider = CaiyunCredentialsProvider { credentials },
            nonceGenerator = CaiyunNonceGenerator { nonce },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun evaluateSignsIndependentRequestsAndCombinesTwoLocations() = runTest {
        server.dispatcher = weatherDispatcher()

        val result = provider.evaluate(request())

        assertEquals(2, server.requestCount)
        assertEquals(WeatherSeverity.SEVERE, result.severity)
        assertEquals(30, result.bufferMinutes)
        assertEquals(WeatherDataSource.NETWORK, result.source)
        assertTrue(result.isUsableForScheduling)
        repeat(2) {
            val recorded = server.takeRequest()
            val url = recorded.requestUrl!!
            assertTrue(url.encodedPath.startsWith("/v2.6/test-app-key/"))
            assertEquals("false", url.queryParameter("alert"))
            assertEquals("1", url.queryParameter("dailysteps"))
            assertEquals("24", url.queryParameter("hourlysteps"))
            assertEquals("zh_CN", url.queryParameter("lang"))
            assertEquals("metric:v2", url.queryParameter("unit"))
            assertEquals(nonce, recorded.getHeader("x-cy-nonce"))
            assertEquals(now.epochSecond.toString(), recorded.getHeader("x-cy-timestamp"))
            val expectedSignature = CaiyunSigner().signedHeaders(
                credentials,
                "GET",
                url.encodedPath,
                mapOf("alert" to "false", "dailysteps" to "1", "hourlysteps" to "24", "lang" to "zh_CN", "unit" to "metric:v2"),
                nonce,
                now.epochSecond,
            ).signature
            assertEquals(expectedSignature, recorded.getHeader("x-cy-signature"))
        }
    }

    @Test
    fun usesLocationCacheOnlyForTemporaryNetworkFailuresAndConnectionTestBypassesIt() = runTest {
        server.dispatcher = weatherDispatcher()

        provider.evaluate(request())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
        }
        // The second evaluation always reaches both endpoints before a recoverable failure uses cache.
        val cached = provider.evaluate(request())
        assertEquals(4, server.requestCount)
        assertEquals(WeatherDataSource.CACHE, cached.source)

        val connectionFailure = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(5, server.requestCount)
        assertEquals(ProviderError.Category.NETWORK, connectionFailure.category)
    }

    @Test
    fun expiredCacheDoesNotMaskTemporaryNetworkFailure() = runTest {
        server.dispatcher = weatherDispatcher()
        provider.evaluate(request())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
        }

        val error = runCatching { provider.evaluate(request(now.plusSeconds(16 * 60))) }.exceptionOrNull() as ProviderError

        assertEquals(ProviderError.Category.NETWORK, error.category)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun cacheKeyIncludesRequestedHourlyStepsEvenWhenCachedDataWouldCoverWindow() = runTest {
        val longWindowEnd = ZonedDateTime.parse("2026-09-02T02:00:00Z")
        val longHours = (0..26).map { offset ->
            now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusSeconds(offset * 60L * 60L).toString()
        }
        server.dispatcher = weatherDispatcher(hours = longHours)
        provider.evaluate(request(end = longWindowEnd))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
        }

        val error = runCatching { provider.evaluate(request()) }.exceptionOrNull() as ProviderError

        assertEquals(ProviderError.Category.NETWORK, error.category)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun unknownSkyconStaysExplicitlyUnavailableForScheduling() = runTest {
        server.dispatcher = weatherDispatcher(homeSkycon = "MYSTERY", workSkycon = "CLEAR_DAY")

        val result = provider.evaluate(request())

        assertFalse(result.isUsableForScheduling)
        assertTrue(result.unknownSkyconCodes.contains("MYSTERY"))
        assertEquals(WeatherSeverity.FINE, result.severity)
    }

    @Test
    fun missingRequiredHourProducesHorizonUnavailableEvaluation() = runTest {
        server.dispatcher = weatherDispatcher(hours = listOf("2026-09-01T01:00:00Z"))

        val result = provider.evaluate(request())

        assertFalse(result.isUsableForScheduling)
        assertTrue(result.locations.isEmpty())
        assertEquals(0, result.bufferMinutes)
    }

    @Test
    fun staleServerTimeAndDuplicatedSkyconTimestampAreRejected() = runTest {
        server.dispatcher = weatherDispatcher(serverTime = now.minusSeconds(16 * 60))
        val stale = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.PROVIDER_FAILURE, stale.category)
        assertEquals("STALE_SERVER_TIME", stale.providerCode)

        server.dispatcher = weatherDispatcher(hours = listOf("2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z"))
        val duplicate = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.MALFORMED_RESPONSE, duplicate.category)
    }

    @Test
    fun successfulHttpWithInvalidJsonOrHtmlIsMalformedResponse() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        val invalidJson = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.MALFORMED_RESPONSE, invalidJson.category)

        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway error</html>"))
        val html = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.MALFORMED_RESPONSE, html.category)
    }

    @Test
    fun httpAuthRateLimitAndTimeoutErrorsAreExplicitAndNeverContainCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"status\":\"failed\"}"))
        val auth = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.INVALID_KEY, auth.category)
        assertFalse(auth.message!!.contains(credentials.appSecret))

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17"))
        val rateLimited = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.RATE_LIMITED, rateLimited.category)
        assertEquals(17L, rateLimited.retryAfterSeconds)
        assertTrue(rateLimited.retryable)

        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val timeoutProvider = CaiyunWeatherProvider(
            api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(okhttp3.OkHttpClient.Builder().readTimeout(1, java.util.concurrent.TimeUnit.MILLISECONDS).build())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CaiyunWeatherApi::class.java),
            credentialsProvider = CaiyunCredentialsProvider { credentials },
            nonceGenerator = CaiyunNonceGenerator { nonce },
        )
        val timeout = runCatching { timeoutProvider.testConnection(home, now) }.exceptionOrNull() as ProviderError
        assertEquals(ProviderError.Category.TIMEOUT, timeout.category)
    }

    @Test
    fun standardHttpFailuresMapWithoutDecodingGatewayBodies() = runTest {
        listOf(
            400 to ProviderError.Category.INVALID_KEY,
            403 to ProviderError.Category.INVALID_KEY,
            422 to ProviderError.Category.INVALID_REQUEST,
            500 to ProviderError.Category.NETWORK,
        ).forEach { (status, _) ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(status)
                    .setBody(if (status == 500) "gateway failure" else "<html>request failed</html>"),
            )
        }

        listOf(
            400 to ProviderError.Category.INVALID_KEY,
            403 to ProviderError.Category.INVALID_KEY,
            422 to ProviderError.Category.INVALID_REQUEST,
            500 to ProviderError.Category.NETWORK,
        ).forEach { (status, category) ->
            val error = runCatching { provider.testConnection(home, now) }.exceptionOrNull() as ProviderError
            assertEquals(category, error.category)
            assertEquals("HTTP_$status", error.providerCode)
            assertEquals(status == 500, error.retryable)
        }
    }

    private fun weatherDispatcher(
        homeSkycon: String = "LIGHT_RAIN",
        workSkycon: String = "STORM_RAIN",
        hours: List<String> = listOf("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", "2026-09-01T02:00:00Z"),
        serverTime: Instant = now,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val coordinates = request.requestUrl!!.pathSegments[2].split(',')
            val longitude = coordinates[0]
            val latitude = coordinates[1]
            val skycon = if (longitude == home.point.longitudeGcj02.toString()) homeSkycon else workSkycon
            return MockResponse().setResponseCode(200).setBody(weatherBody(longitude, latitude, skycon, hours, serverTime))
        }
    }

    private fun weatherBody(
        longitude: String,
        latitude: String,
        skycon: String,
        hours: List<String>,
        serverTime: Instant,
    ): String = buildString {
        fun entries(value: (String) -> String): String = hours.joinToString(",") { timestamp -> value(timestamp) }
        append("{\"status\":\"ok\",\"api_version\":\"v2.6\",\"api_status\":\"active\",\"unit\":\"metric:v2\",")
        append("\"timezone\":\"UTC\",\"tzshift\":0,\"server_time\":${serverTime.epochSecond},\"location\":[$latitude,$longitude],\"result\":{\"hourly\":{")
        append("\"skycon\":[${entries { "{\"datetime\":\"$it\",\"value\":\"$skycon\"}" }}],")
        append("\"precipitation\":[${entries { "{\"datetime\":\"$it\",\"value\":0.0,\"probability\":0.0}" }}],")
        append("\"wind\":[${entries { "{\"datetime\":\"$it\",\"speed\":1.0,\"direction\":90.0}" }}],")
        append("\"visibility\":[${entries { "{\"datetime\":\"$it\",\"value\":10.0}" }}]}}}")
    }

    private fun request(
        requestedAt: Instant = now,
        end: ZonedDateTime = ZonedDateTime.parse("2026-09-01T02:00:00Z"),
    ) = WeatherRequest(
        home = home,
        work = work,
        window = WeatherTimeWindow(
            ZonedDateTime.parse("2026-09-01T01:00:00Z"),
            end,
        ),
        weatherBufferProfile = WeatherBufferProfile.WORKDAY_DEFAULT,
        requestedAt = requestedAt,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val now: Instant = Instant.parse("2026-09-01T00:15:00Z")
        val credentials = CaiyunCredentials("test-app-key", "test-app-secret")
        const val nonce = "0123456789abcdef"
        val home = WeatherLocation(WeatherLocationRole.HOME, GeoPoint(116.397428, 39.90923))
        val work = WeatherLocation(WeatherLocationRole.WORK, GeoPoint(116.407428, 39.91923))
    }
}
