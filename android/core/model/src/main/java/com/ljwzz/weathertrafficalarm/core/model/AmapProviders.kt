package com.ljwzz.weathertrafficalarm.core.model

import java.time.LocalDateTime

/** A GCJ-02 longitude/latitude pair used at provider boundaries. */
data class GeoPoint(
    val longitudeGcj02: Double,
    val latitudeGcj02: Double,
) {
    init {
        require(longitudeGcj02 in -180.0..180.0) { "longitude out of range" }
        require(latitudeGcj02 in -90.0..90.0) { "latitude out of range" }
    }

    fun asAmapParameter(): String = "$longitudeGcj02,$latitudeGcj02"
}

interface PlaceProvider {
    suspend fun inputTips(
        keywords: String,
        city: String? = null,
        location: GeoPoint? = null,
    ): List<PlaceRef>

    suspend fun search(
        keywords: String,
        region: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): List<PlaceRef>

    suspend fun reverseGeocode(location: GeoPoint): PlaceRef
}

interface RouteProvider {
    suspend fun estimate(request: RouteRequest): RouteEstimate
}

data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val mode: CommuteMode,
    val policy: RoutePolicy = RoutePolicy.DEFAULT,
    val waypoints: List<GeoPoint> = emptyList(),
    val originCity: String? = null,
    val destinationCity: String? = null,
    val departureAt: LocalDateTime? = null,
) {
    init {
        require(waypoints.size <= 16) { "at most 16 waypoints are supported" }
    }
}

data class RouteEstimate(
    val alternatives: List<RouteAlternative>,
)

data class RouteAlternative(
    val id: String,
    val durationSeconds: Long,
    val distanceMeters: Long,
    val polyline: List<GeoPoint>,
)

class ProviderError(
    val category: Category,
    val providerCode: String? = null,
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message, cause) {
    val retryable: Boolean
        get() = category == Category.NETWORK || category == Category.TIMEOUT || category == Category.RATE_LIMITED

    enum class Category {
        CONSENT_REQUIRED,
        MISSING_KEY,
        INVALID_REQUEST,
        INVALID_KEY,
        QUOTA_EXCEEDED,
        RATE_LIMITED,
        ROUTE_NOT_FOUND,
        NETWORK,
        TIMEOUT,
        MALFORMED_RESPONSE,
        PROVIDER_FAILURE,
    }
}
