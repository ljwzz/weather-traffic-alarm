package com.ljwzz.weathertrafficalarm.core.network.caiyun

import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationEvaluation
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherRules
import com.ljwzz.weathertrafficalarm.core.model.WeatherTimeWindow
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import retrofit2.Response

/** Secure nonce generator for production; tests inject deterministic values. */
object UuidCaiyunNonceGenerator : CaiyunNonceGenerator {
    override fun next(): String = UUID.randomUUID().toString()
}

/**
 * Caiyun v2.6 adapter. It retains only normalized, short-lived hourly observations in memory;
 * raw responses, credentials and coordinate-bearing request URLs never reach diagnostics.
 */
class CaiyunWeatherProvider internal constructor(
    private val api: CaiyunWeatherApi,
    private val credentialsProvider: CaiyunCredentialsProvider,
    private val signer: CaiyunSigner = CaiyunSigner(),
    private val nonceGenerator: CaiyunNonceGenerator = UuidCaiyunNonceGenerator,
    private val clock: Clock = Clock.systemUTC(),
) : WeatherProvider {

    private val cache = ConcurrentHashMap<CaiyunCacheKey, CacheEntry>()

    override suspend fun evaluate(request: WeatherRequest): WeatherEvaluation {
        val hourlySteps = request.window.hourlyStepsFor(request.requestedAt)
            ?: return WeatherRules.horizonUnavailable(request.window, request.weatherRuleVersion)
        val credentials = credentialsProvider.currentCredentials()
            ?: throw ProviderError(ProviderError.Category.MISSING_KEY, message = "Caiyun credentials are not configured")
        return try {
            coroutineScope {
                val home = async { evaluateLocation(request.home, request.window, request.requestedAt, hourlySteps, credentials, allowCache = true) }
                val work = async { evaluateLocation(request.work, request.window, request.requestedAt, hourlySteps, credentials, allowCache = true) }
                WeatherRules.combine(
                    home = home.await(),
                    work = work.await(),
                    profile = request.weatherBufferProfile,
                    weatherRuleVersion = request.weatherRuleVersion,
                )
            }
        } catch (error: ProviderError) {
            if (error.providerCode == WEATHER_HORIZON_UNAVAILABLE) {
                WeatherRules.horizonUnavailable(request.window, request.weatherRuleVersion)
            } else {
                throw error
            }
        }
    }

    /** Uses saved credentials and always performs a network request. */
    suspend fun testConnection(location: WeatherLocation, requestedAt: Instant = clock.instant()): WeatherLocationEvaluation {
        val credentials = credentialsProvider.currentCredentials()
            ?: throw ProviderError(ProviderError.Category.MISSING_KEY, message = "Caiyun credentials are not configured")
        return testConnection(credentials, location, requestedAt)
    }

    /** Tests candidate credentials without retaining their result in the location cache. */
    suspend fun testConnection(
        credentials: CaiyunCredentials,
        location: WeatherLocation,
        requestedAt: Instant = clock.instant(),
    ): WeatherLocationEvaluation {
        val sampleHour = requestedAt.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
        return evaluateLocation(
            location = location,
            window = WeatherTimeWindow(sampleHour, sampleHour),
            requestedAt = requestedAt,
            hourlySteps = MIN_HOURLY_STEPS,
            credentials = credentials,
            allowCache = false,
        )
    }

    private suspend fun evaluateLocation(
        location: WeatherLocation,
        window: WeatherTimeWindow,
        requestedAt: Instant,
        hourlySteps: Int,
        credentials: CaiyunCredentials,
        allowCache: Boolean,
    ): WeatherLocationEvaluation {
        val cacheKey = CaiyunCacheKey(location.point.longitudeGcj02, location.point.latitudeGcj02, hourlySteps)
        return try {
            val response = requestWeather(location, hourlySteps, credentials, requestedAt)
            val parsed = validate(response, location, requestedAt)
            parsed.requireCoverage(window)
            if (allowCache) cache[cacheKey] = CacheEntry(requestedAt, parsed)
            parsed.toEvaluation(location, window, WeatherDataSource.NETWORK)
        } catch (error: ProviderError) {
            if (allowCache && error.canUseCacheFallback()) {
                cached(cacheKey, requestedAt, window)?.let { cached ->
                    return cached.toEvaluation(location, window, WeatherDataSource.CACHE)
                }
            }
            throw error
        }
    }

    private suspend fun requestWeather(
        location: WeatherLocation,
        hourlySteps: Int,
        credentials: CaiyunCredentials,
        requestedAt: Instant,
    ): CaiyunWeatherResponse {
        val coordinates = "${location.point.longitudeGcj02},${location.point.latitudeGcj02}"
        val path = "/v2.6/${credentials.appKey}/$coordinates/weather"
        val query = REQUEST_QUERY + ("hourlysteps" to hourlySteps.toString())
        val headers = signer.signedHeaders(
            credentials = credentials,
            method = "GET",
            path = path,
            query = query,
            nonce = nonceGenerator.next(),
            timestampSeconds = requestedAt.epochSecond,
        )
        val response = try {
            api.weather(
                appKey = credentials.appKey,
                coordinates = coordinates,
                alert = false,
                dailySteps = 1,
                hourlySteps = hourlySteps,
                language = "zh_CN",
                unit = "metric:v2",
                nonce = headers.nonce,
                timestampSeconds = headers.timestampSeconds,
                signature = headers.signature,
            )
        } catch (error: SocketTimeoutException) {
            throw ProviderError(ProviderError.Category.TIMEOUT, providerCode = TIMEOUT, message = "Caiyun request timed out", cause = error)
        } catch (error: IOException) {
            throw ProviderError(ProviderError.Category.NETWORK, message = "Caiyun network request failed", cause = error)
        } catch (error: SerializationException) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun response could not be decoded", cause = error)
        }
        return response.bodyOrError()
    }

    private fun Response<CaiyunWeatherResponse>.bodyOrError(): CaiyunWeatherResponse {
        if (!isSuccessful) {
            val category = when (code()) {
                400, 401, 403 -> ProviderError.Category.INVALID_KEY
                422 -> ProviderError.Category.INVALID_REQUEST
                429 -> ProviderError.Category.RATE_LIMITED
                in 500..599 -> ProviderError.Category.NETWORK
                else -> ProviderError.Category.PROVIDER_FAILURE
            }
            val providerCode = when {
                code() == 429 -> HTTP_429
                code() in 500..599 -> "HTTP_${code()}"
                else -> "HTTP_${code()}"
            }
            throw ProviderError(
                category = category,
                providerCode = providerCode,
                message = "Caiyun HTTP request failed",
                retryAfterSeconds = if (code() == 429) headers()["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 } else null,
            )
        }
        return body() ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun response body is empty")
    }

    private fun validate(
        response: CaiyunWeatherResponse,
        location: WeatherLocation,
        receivedAt: Instant,
    ): ValidatedWeather {
        if (response.status != "ok") {
            throw ProviderError(ProviderError.Category.PROVIDER_FAILURE, message = "Caiyun returned unsuccessful status")
        }
        if (response.apiVersion != API_VERSION || response.apiStatus != API_STATUS_ACTIVE || response.unit != EXPECTED_UNIT) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun response metadata is invalid")
        }
        if (response.timezone.isNullOrBlank() || response.tzshift == null) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun response timezone metadata is missing")
        }
        val reportTime = response.serverTime?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun server time is missing")
        if (abs(reportTime.epochSecond - receivedAt.epochSecond) > MAX_SERVER_TIME_DRIFT_SECONDS) {
            throw ProviderError(ProviderError.Category.PROVIDER_FAILURE, providerCode = STALE_SERVER_TIME, message = "Caiyun server time is outside the accepted freshness window")
        }
        validateLocation(response.location)
        val hourly = response.result?.hourly
            ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun hourly data is missing")
        val skycon = hourly.skycon.map { hour ->
            val timestamp = hour.datetime.toInstantOrMalformed("skycon")
            val code = hour.value?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun skycon value is missing")
            timestamp to code
        }
        if (skycon.isEmpty()) throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun skycon data is missing")
        if (skycon.map(Pair<Instant, String>::first).toSet().size != skycon.size) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun skycon timestamps are duplicated")
        }
        validateSupplemental(hourly.precipitation, skycon, "precipitation") { it.value != null && it.probability != null }
        validateSupplemental(hourly.wind, skycon, "wind") { it.speed != null && it.direction != null }
        validateSupplemental(hourly.visibility, skycon, "visibility") { it.value != null }
        return ValidatedWeather(reportTime, skycon.sortedBy(Pair<Instant, String>::first))
    }

    private fun validateLocation(rawLocation: List<Double>) {
        if (rawLocation.size != 2 || rawLocation.any { !it.isFinite() }) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun response location is invalid")
        }
        val latitude = rawLocation[0]
        val longitude = rawLocation[1]
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun response location is out of range")
        }
    }

    private fun <T> validateSupplemental(
        values: List<T>,
        skycon: List<Pair<Instant, String>>,
        name: String,
        present: (T) -> Boolean,
    ) where T : CaiyunTimedHour {
        if (values.size != skycon.size || values.any { !present(it) }) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun $name data is incomplete")
        }
        val actual = values.map { it.datetime.toInstantOrMalformed(name) }.toSet()
        if (actual.size != values.size || actual != skycon.map(Pair<Instant, String>::first).toSet()) {
            throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun $name timestamps do not match skycon")
        }
    }

    private fun cached(key: CaiyunCacheKey, now: Instant, window: WeatherTimeWindow): ValidatedWeather? {
        cache.entries.removeIf { (_, entry) -> now.toEpochMilli() - entry.createdAt.toEpochMilli() >= CACHE_TTL_MILLIS }
        return cache[key]?.takeIf { now.toEpochMilli() - it.createdAt.toEpochMilli() < CACHE_TTL_MILLIS }
            ?.weather
            ?.takeIf { it.covers(window) }
    }

    private fun ProviderError.canUseCacheFallback(): Boolean = category == ProviderError.Category.NETWORK ||
        category == ProviderError.Category.TIMEOUT ||
        category == ProviderError.Category.RATE_LIMITED ||
        (providerCode?.startsWith("HTTP_5") == true)

    private data class CaiyunCacheKey(val longitude: Double, val latitude: Double, val hourlySteps: Int)
    private data class CacheEntry(val createdAt: Instant, val weather: ValidatedWeather)

    private data class ValidatedWeather(
        val reportTime: Instant,
        val skycon: List<Pair<Instant, String>>,
    ) {
        fun covers(window: WeatherTimeWindow): Boolean {
            val required = requiredHours(window)
            return required.all { requiredHour -> skycon.any { it.first == requiredHour } }
        }

        fun requireCoverage(window: WeatherTimeWindow) {
            if (!covers(window)) {
                throw ProviderError(
                    ProviderError.Category.INVALID_REQUEST,
                    providerCode = WEATHER_HORIZON_UNAVAILABLE,
                    message = "Caiyun response does not cover the requested weather window",
                )
            }
        }

        fun toEvaluation(location: WeatherLocation, window: WeatherTimeWindow, source: WeatherDataSource): WeatherLocationEvaluation {
            val required = requiredHours(window)
            val selected = skycon.filter { it.first in required.toSet() }
            return WeatherRules.evaluateLocation(
                role = location.role,
                skyconCodes = selected.map(Pair<Instant, String>::second),
                providerReportTime = reportTime,
                participatingWindowStart = required.first().atZone(window.start.zone),
                participatingWindowEnd = required.last().atZone(window.end.zone),
                source = source,
            )
        }

        private fun requiredHours(window: WeatherTimeWindow): List<Instant> {
            val first = window.start.toInstant().truncatedTo(ChronoUnit.HOURS)
            val last = window.end.toInstant().truncatedTo(ChronoUnit.HOURS)
            return generateSequence(first) { previous -> previous.plus(1, ChronoUnit.HOURS).takeIf { !it.isAfter(last) } }.toList()
        }
    }

    private companion object {
        const val API_VERSION = "v2.6"
        const val API_STATUS_ACTIVE = "active"
        const val EXPECTED_UNIT = "metric:v2"
        const val MIN_HOURLY_STEPS = 24
        const val CACHE_TTL_MILLIS = 15 * 60 * 1_000L
        const val MAX_SERVER_TIME_DRIFT_SECONDS = 15 * 60L
        const val TIMEOUT = "TIMEOUT"
        const val HTTP_429 = "HTTP_429"
        const val STALE_SERVER_TIME = "STALE_SERVER_TIME"
        const val WEATHER_HORIZON_UNAVAILABLE = "WEATHER_HORIZON_UNAVAILABLE"
        val REQUEST_QUERY = mapOf(
            "alert" to "false",
            "dailysteps" to "1",
            "lang" to "zh_CN",
            "unit" to EXPECTED_UNIT,
        )
    }
}

private fun String?.toInstantOrMalformed(field: String): Instant = try {
    this?.let(Instant::parse) ?: throw IllegalArgumentException("missing datetime")
} catch (_: Exception) {
    try {
        this?.let(OffsetDateTime::parse)?.toInstant() ?: throw IllegalArgumentException("missing datetime")
    } catch (error: Exception) {
        throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Caiyun $field datetime is invalid", cause = error)
    }
}
