package com.ljwzz.weathertrafficalarm.core.network.amap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface AmapWebApi {
    @GET("v3/assistant/inputtips")
    suspend fun inputTips(
        @Query("key") key: String,
        @Query("keywords") keywords: String,
        @Query("city") city: String? = null,
        @Query("location") location: String? = null,
    ): Response<AmapPlacesResponse>

    @GET("v5/place/text")
    suspend fun textSearch(
        @Query("key") key: String,
        @Query("keywords") keywords: String,
        @Query("region") region: String? = null,
        @Query("page_num") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
    ): Response<AmapPlacesResponse>

    @GET("v3/geocode/regeo")
    suspend fun reverseGeocode(
        @Query("key") key: String,
        @Query("location") location: String,
    ): Response<AmapReverseGeocodeResponse>

    @GET("v5/direction/driving")
    suspend fun driving(
        @Query("key") key: String,
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("waypoints") waypoints: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("alternative_route") alternativeRoute: Int? = null,
    ): Response<AmapRouteResponse>

    @GET("v5/direction/walking")
    suspend fun walking(
        @Query("key") key: String,
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("alternative_route") alternativeRoute: Int? = null,
    ): Response<AmapRouteResponse>

    @GET("v5/direction/bicycling")
    suspend fun bicycling(
        @Query("key") key: String,
        @Query("origin") origin: String,
        @Query("destination") destination: String,
    ): Response<AmapRouteResponse>

    @GET("v5/direction/electrobike")
    suspend fun electricBicycle(
        @Query("key") key: String,
        @Query("origin") origin: String,
        @Query("destination") destination: String,
    ): Response<AmapRouteResponse>

    @GET("v5/direction/transit/integrated")
    suspend fun transit(
        @Query("key") key: String,
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("city1") originCity: String? = null,
        @Query("city2") destinationCity: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("date") date: String? = null,
        @Query("time") time: String? = null,
        @Query("AlternativeRoute") alternativeRoute: Int? = null,
    ): Response<AmapRouteResponse>
}

@Serializable
data class AmapPlacesResponse(
    override val status: String? = null,
    override val info: String? = null,
    override val infocode: String? = null,
    val tips: List<JsonObject> = emptyList(),
    val pois: List<JsonObject> = emptyList(),
) : AmapStatusResponse

@Serializable
data class AmapReverseGeocodeResponse(
    override val status: String? = null,
    override val info: String? = null,
    override val infocode: String? = null,
    val regeocode: JsonObject? = null,
) : AmapStatusResponse

@Serializable
data class AmapRouteResponse(
    override val status: String? = null,
    override val info: String? = null,
    override val infocode: String? = null,
    val route: JsonObject? = null,
) : AmapStatusResponse

interface AmapStatusResponse {
    val status: String?
    val info: String?
    val infocode: String?
}
