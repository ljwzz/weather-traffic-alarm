package com.ljwzz.weathertrafficalarm.core.network

import com.ljwzz.weathertrafficalarm.core.network.api.BackendApi
import com.ljwzz.weathertrafficalarm.core.network.dto.AlarmEvaluationRequest
import com.ljwzz.weathertrafficalarm.core.network.dto.AlarmEvaluationResponse
import com.ljwzz.weathertrafficalarm.core.network.dto.AttestRequest
import com.ljwzz.weathertrafficalarm.core.network.dto.AttestResponse
import com.ljwzz.weathertrafficalarm.core.network.dto.CalendarResponse
import com.ljwzz.weathertrafficalarm.core.network.dto.PlaceRefDto
import com.ljwzz.weathertrafficalarm.core.network.dto.PlaceSearchRequest
import com.ljwzz.weathertrafficalarm.core.network.dto.PlaceSearchResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class BackendApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BackendApi
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(okhttp3.OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BackendApi::class.java)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun attestInstallationSuccess() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"installationToken":"tok_abc","quotaTier":"anonymous","expiresAt":"2026-07-25T00:00:00Z"}"""),
        )

        val response = api.attestInstallation(
            AttestRequest(
                installationId = "00000000-0000-0000-0000-000000000001",
                platform = "android",
                appVersion = "0.1.0",
            ),
        )

        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals("tok_abc", body!!.installationToken)
        assertEquals("anonymous", body.quotaTier)
    }

    @Test
    fun getCalendarSuccess() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "country": "CN",
                        "year": 2026,
                        "version": 1,
                        "publishedAt": "2025-11-01T00:00:00Z",
                        "sourceUrl": "https://gov.example.com/notice",
                        "payloadSha256": "abc123",
                        "signatureAlgorithm": "ECDSA_P256",
                        "signature": "sig_abc",
                        "days": [
                            {"date": "2026-01-01", "status": "HOLIDAY", "label": "元旦"}
                        ]
                    }
                    """,
                ),
        )

        val response = api.getCalendar(2026)
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals(2026, body.year)
        assertEquals(1, body.days.size)
        assertEquals("元旦", body.days[0].label)
    }

    @Test
    fun searchPlacesSuccess() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "items": [
                            {
                                "poiId": "B000A8UIN8",
                                "name": "天安门",
                                "displayAddress": "北京市东城区",
                                "longitudeGcj02": 116.397428,
                                "latitudeGcj02": 39.90923,
                                "adcode": "110000",
                                "citycode": "010"
                            }
                        ],
                        "nextPageToken": "next_page_1"
                    }
                    """,
                ),
        )

        val response = api.searchPlaces(PlaceSearchRequest(query = "天安门", cityCode = "010"))
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals(1, body.items.size)
        assertEquals("天安门", body.items[0].name)
        assertEquals("B000A8UIN8", body.items[0].poiId)
    }

    @Test
    fun evaluateAlarmSuccess() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                        "decisionId": "dec-001",
                        "planId": "plan-001",
                        "planRevision": 1,
                        "targetDate": "2026-07-25",
                        "workdayStatus": "WORKDAY",
                        "estimatedDepartureAt": "2026-07-25T08:00:00Z",
                        "commuteSeconds": 3600,
                        "weatherSeverity": 1,
                        "weatherBufferMinutes": 10,
                        "weatherRuleVersion": "v1",
                        "recommendedWakeAt": "2026-07-25T06:30:00Z",
                        "routeProvider": "amap",
                        "routeProviderReportTime": "2026-07-24T20:00:00Z",
                        "weatherProvider": "caiyun",
                        "weatherProviderReportTime": "2026-07-24T20:00:00Z",
                        "weatherWindowStart": "2026-07-25T06:00:00Z",
                        "weatherWindowEnd": "2026-07-25T09:00:00Z",
                        "fallbackReason": "NONE",
                        "insufficientAdvance": false,
                        "generatedAt": "2026-07-24T20:00:00Z",
                        "expiresAt": "2026-07-25T06:00:00Z"
                    }
                    """,
                ),
        )

        val origin = PlaceRefDto(
            name = "Home",
            displayAddress = "123 Main St",
            longitudeGcj02 = 116.397428,
            latitudeGcj02 = 39.90923,
            adcode = "110000",
            citycode = "010",
        )
        val destination = PlaceRefDto(
            name = "Office",
            displayAddress = "456 Work Ave",
            longitudeGcj02 = 116.407428,
            latitudeGcj02 = 39.91923,
            adcode = "110000",
            citycode = "010",
        )
        val request = AlarmEvaluationRequest(
            requestId = "req-001",
            planId = "plan-001",
            planRevision = 1,
            targetDate = "2026-07-25",
            timezone = "Asia/Shanghai",
            defaultWakeTime = "06:00",
            arrivalTime = "09:00",
            preparationMinutes = 30,
            maxAdvanceMinutes = 60,
            commuteMode = "DRIVING",
            origin = origin,
            destination = destination,
            routePolicy = "default",
            weatherRuleVersion = "v1",
        )

        val response = api.evaluateAlarm(request)
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals("NONE", body.fallbackReason)
        assertEquals(3600L, body.commuteSeconds)
        assertEquals(1, body.weatherSeverity)
    }

    @Test
    fun errorResponseParsesCorrectly() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"code":"VALIDATION_ERROR","message":"Invalid request","retryable":false,"correlationId":"corr-001"}"""),
        )

        val response = api.attestInstallation(
            AttestRequest(
                installationId = "00000000-0000-0000-0000-000000000001",
                platform = "android",
                appVersion = "0.1.0",
            ),
        )

        assertTrue(!response.isSuccessful)
        assertEquals(400, response.code())
    }
}
