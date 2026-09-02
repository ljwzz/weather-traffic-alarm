package com.ljwzz.weathertrafficalarm.core.network.caiyun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

internal interface CaiyunTimedHour {
    val datetime: String?
}

internal interface CaiyunWeatherApi {
    @GET("v2.6/{appKey}/{coordinates}/weather")
    suspend fun weather(
        @Path("appKey") appKey: String,
        @Path(value = "coordinates", encoded = true) coordinates: String,
        @Query("alert") alert: Boolean = false,
        @Query("dailysteps") dailySteps: Int = 1,
        @Query("hourlysteps") hourlySteps: Int,
        @Query("lang") language: String = "zh_CN",
        @Query("unit") unit: String = "metric:v2",
        @Header("x-cy-nonce") nonce: String,
        @Header("x-cy-timestamp") timestampSeconds: Long,
        @Header("x-cy-signature") signature: String,
    ): Response<CaiyunWeatherResponse>
}

@Serializable
internal data class CaiyunWeatherResponse(
    val status: String? = null,
    @SerialName("api_version") val apiVersion: String? = null,
    @SerialName("api_status") val apiStatus: String? = null,
    val unit: String? = null,
    val timezone: String? = null,
    val tzshift: Int? = null,
    val location: List<Double> = emptyList(),
    @SerialName("server_time") val serverTime: Long? = null,
    val result: CaiyunWeatherResult? = null,
)

@Serializable
internal data class CaiyunWeatherResult(
    val hourly: CaiyunHourly? = null,
)

@Serializable
internal data class CaiyunHourly(
    val skycon: List<CaiyunSkyconHour> = emptyList(),
    val precipitation: List<CaiyunPrecipitationHour> = emptyList(),
    val wind: List<CaiyunWindHour> = emptyList(),
    val visibility: List<CaiyunVisibilityHour> = emptyList(),
)

@Serializable
internal data class CaiyunSkyconHour(
    override val datetime: String? = null,
    val value: String? = null,
) : CaiyunTimedHour

@Serializable
internal data class CaiyunPrecipitationHour(
    override val datetime: String? = null,
    val value: Double? = null,
    val probability: Double? = null,
) : CaiyunTimedHour

@Serializable
internal data class CaiyunWindHour(
    override val datetime: String? = null,
    val speed: Double? = null,
    val direction: Double? = null,
) : CaiyunTimedHour

@Serializable
internal data class CaiyunVisibilityHour(
    override val datetime: String? = null,
    val value: Double? = null,
) : CaiyunTimedHour
