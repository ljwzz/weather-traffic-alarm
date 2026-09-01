package com.ljwzz.weathertrafficalarm.core.network.amap

import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.PlaceProvider
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteEstimate
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.RouteProvider
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.Response

/** Supplies the current encrypted credential without exposing storage to this module. */
fun interface AmapWebKeyProvider {
    suspend fun currentKey(): String?
}

/** Authorizes provider use before credentials are read or a network request can be made. */
fun interface AmapConsentProvider {
    suspend fun hasConsent(): Boolean
}

class AmapWebProvider(
    private val api: AmapWebApi,
    private val keyProvider: AmapWebKeyProvider,
    private val consentProvider: AmapConsentProvider = AmapConsentProvider { false },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PlaceProvider, RouteProvider {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun inputTips(keywords: String, city: String?, location: GeoPoint?): List<PlaceRef> {
        requireConsent()
        requireKeywords(keywords)
        val response = call { api.inputTips(key(), keywords, city, location?.asAmapParameter()) }
        return response.bodyOrError().tips.mapNotNull(::toPlaceRef)
    }

    override suspend fun search(keywords: String, region: String?, page: Int, pageSize: Int): List<PlaceRef> {
        requireConsent()
        requireKeywords(keywords)
        if (page < 1) throw ProviderError(ProviderError.Category.INVALID_REQUEST, message = "page must be positive")
        if (pageSize !in 1..25) throw ProviderError(ProviderError.Category.INVALID_REQUEST, message = "pageSize must be in 1..25")
        val response = call { api.textSearch(key(), keywords, region, page, pageSize) }
        return response.bodyOrError().pois.mapNotNull(::toPlaceRef)
    }

    override suspend fun reverseGeocode(location: GeoPoint): PlaceRef {
        requireConsent()
        val response = call { api.reverseGeocode(key(), location.asAmapParameter()) }
        val result = response.bodyOrError().regeocode
            ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Missing regeo result")
        return toReverseGeocodePlace(result, location)
    }

    override suspend fun estimate(request: RouteRequest): RouteEstimate {
        requireConsent()
        return cached("route|$request") {
            val key = key()
            val origin = request.origin.asAmapParameter()
            val destination = request.destination.asAmapParameter()
            val response = call {
                when (request.mode) {
                    CommuteMode.DRIVING -> api.driving(
                        key,
                        origin,
                        destination,
                        request.waypoints.parameterOrNull(),
                        request.policy.drivingStrategy(),
                        MAX_ROUTE_ALTERNATIVES,
                    )
                    CommuteMode.WALKING -> api.walking(key, origin, destination, MAX_ROUTE_ALTERNATIVES)
                    CommuteMode.BICYCLING -> api.bicycling(key, origin, destination)
                    CommuteMode.ELECTRIC_BICYCLE -> api.electricBicycle(key, origin, destination)
                    CommuteMode.TRANSIT -> api.transit(
                        key = key,
                        origin = origin,
                        destination = destination,
                        originCity = request.originCity,
                        destinationCity = request.destinationCity,
                        strategy = request.policy.transitStrategy(),
                        date = request.departureAt?.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        time = request.departureAt?.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                        alternativeRoute = MAX_ROUTE_ALTERNATIVES,
                    )
                }
            }
            toRouteEstimate(response.bodyOrError().route, request.mode)
        }
    }

    private suspend fun requireConsent() {
        if (!consentProvider.hasConsent()) {
            throw ProviderError(ProviderError.Category.CONSENT_REQUIRED, message = "Amap consent is required")
        }
    }

    private suspend fun key(): String = keyProvider.currentKey()?.trim()?.takeIf(String::isNotEmpty)
        ?: throw ProviderError(ProviderError.Category.MISSING_KEY, message = "Amap Web key is not configured")

    private suspend fun <T> call(block: suspend () -> Response<T>): Response<T> = try {
        block()
    } catch (exception: IOException) {
        throw ProviderError(ProviderError.Category.NETWORK, message = "Amap network request failed", cause = exception)
    } catch (exception: SerializationException) {
        throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Amap response could not be decoded", cause = exception)
    }

    private fun <T : AmapStatusResponse> Response<T>.bodyOrError(): T {
        if (!isSuccessful) {
            val category = when (code()) {
                401, 403 -> ProviderError.Category.INVALID_KEY
                429 -> ProviderError.Category.RATE_LIMITED
                in 500..599 -> ProviderError.Category.NETWORK
                else -> ProviderError.Category.PROVIDER_FAILURE
            }
            throw ProviderError(category, providerCode = code().toString(), message = "Amap HTTP request failed")
        }
        val body = body() ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Amap response body is empty")
        if (body.status != "1") throw amapError(body.info, body.infocode)
        return body
    }

    private fun amapError(info: String?, infoCode: String?): ProviderError {
        val category = when (infoCode) {
            "10001" -> ProviderError.Category.INVALID_KEY
            "10003", "10044" -> ProviderError.Category.QUOTA_EXCEEDED
            "10019", "10020", "10021" -> ProviderError.Category.RATE_LIMITED
            "20800", "20801", "20802", "20803" -> ProviderError.Category.ROUTE_NOT_FOUND
            else -> ProviderError.Category.PROVIDER_FAILURE
        }
        return ProviderError(category, providerCode = infoCode, message = info ?: "Amap request failed")
    }

    private fun toPlaceRef(objectValue: JsonObject): PlaceRef? {
        val point = objectValue.string("location")?.toGeoPointOrNull() ?: return null
        val name = objectValue.string("name")?.trim().orEmpty()
        if (name.isEmpty()) return null
        return PlaceRef(
            poiId = objectValue.string("id"),
            name = name,
            displayAddress = objectValue.string("address") ?: objectValue.string("district").orEmpty(),
            longitudeGcj02 = point.longitudeGcj02,
            latitudeGcj02 = point.latitudeGcj02,
            adcode = objectValue.string("adcode").orEmpty(),
            citycode = objectValue.string("citycode").orEmpty(),
        )
    }

    private fun toReverseGeocodePlace(result: JsonObject, location: GeoPoint): PlaceRef {
        val component = result["addressComponent"] as? JsonObject
        return PlaceRef(
            name = result.string("formatted_address")?.takeIf(String::isNotBlank) ?: "反向地理编码结果",
            displayAddress = result.string("formatted_address").orEmpty(),
            longitudeGcj02 = location.longitudeGcj02,
            latitudeGcj02 = location.latitudeGcj02,
            adcode = component?.string("adcode").orEmpty(),
            citycode = component?.string("citycode").orEmpty(),
        )
    }

    private fun toRouteEstimate(route: JsonObject?, mode: CommuteMode): RouteEstimate {
        val result = route ?: throw ProviderError(ProviderError.Category.MALFORMED_RESPONSE, message = "Missing route result")
        val alternatives = (result["paths"] as? JsonArray ?: result["transits"] as? JsonArray)
            ?.take(MAX_ROUTE_ALTERNATIVES)
            ?.mapIndexedNotNull { index, item -> toRouteAlternative(item, "${mode.name}:$index") }
            .orEmpty()
        if (alternatives.isEmpty()) throw ProviderError(ProviderError.Category.ROUTE_NOT_FOUND, message = "Amap returned no route alternatives")
        return RouteEstimate(alternatives)
    }

    private fun toRouteAlternative(item: JsonElement, id: String): RouteAlternative? {
        val objectValue = item as? JsonObject ?: return null
        val duration = objectValue.string("duration")?.toLongOrNull() ?: return null
        val distance = objectValue.string("distance")?.toLongOrNull() ?: 0L
        return RouteAlternative(id, duration, distance, objectValue.polylines().flatMap(::parsePolyline))
    }

    private fun JsonObject.polylines(): List<String> = buildList {
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (name, child) ->
                    if (name == "polyline") (child as? JsonPrimitive)?.contentOrNull?.let(::add) else visit(child)
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(this@polylines)
    }

    private fun parsePolyline(value: String): List<GeoPoint> = value.split(';').mapNotNull(String::toGeoPointOrNull)

    private fun requireKeywords(keywords: String) {
        if (keywords.isBlank()) throw ProviderError(ProviderError.Category.INVALID_REQUEST, message = "keywords must not be blank")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(cacheKey: String, block: suspend () -> T): T {
        val now = nowMillis()
        cache.entries.forEach { entry ->
            if (now - entry.value.createdAtMillis >= CACHE_TTL_MILLIS) cache.remove(entry.key, entry.value)
        }
        cache[cacheKey]?.takeIf { now - it.createdAtMillis < CACHE_TTL_MILLIS }?.let { return it.value as T }
        val value = block()
        cache[cacheKey] = CacheEntry(now, value)
        return value
    }

    private data class CacheEntry(val createdAtMillis: Long, val value: Any)

    private companion object {
        const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
        const val MAX_ROUTE_ALTERNATIVES = 3
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun String.toGeoPointOrNull(): GeoPoint? {
    val parts = split(',')
    if (parts.size != 2) return null
    val longitude = parts[0].toDoubleOrNull() ?: return null
    val latitude = parts[1].toDoubleOrNull() ?: return null
    return runCatching { GeoPoint(longitude, latitude) }.getOrNull()
}

private fun List<GeoPoint>.parameterOrNull(): String? = if (isEmpty()) null else joinToString(";") { it.asAmapParameter() }

private fun RoutePolicy.drivingStrategy(): String? = when (this) {
    RoutePolicy.DEFAULT -> "32"
    RoutePolicy.LEAST_TIME -> "0"
    RoutePolicy.LEAST_DISTANCE -> "2"
    RoutePolicy.LEAST_TRAFFIC -> "4"
}

private fun RoutePolicy.transitStrategy(): String? = when (this) {
    RoutePolicy.DEFAULT, RoutePolicy.LEAST_TIME -> "0"
    RoutePolicy.LEAST_DISTANCE -> "2"
    RoutePolicy.LEAST_TRAFFIC -> null
}
