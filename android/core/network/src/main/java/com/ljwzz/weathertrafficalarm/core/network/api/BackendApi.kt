package com.ljwzz.weathertrafficalarm.core.network.api

import com.ljwzz.weathertrafficalarm.core.network.dto.AlarmEvaluationRequest
import com.ljwzz.weathertrafficalarm.core.network.dto.AlarmEvaluationResponse
import com.ljwzz.weathertrafficalarm.core.network.dto.AttestRequest
import com.ljwzz.weathertrafficalarm.core.network.dto.AttestResponse
import com.ljwzz.weathertrafficalarm.core.network.dto.CalendarResponse
import com.ljwzz.weathertrafficalarm.core.network.dto.PlaceSearchRequest
import com.ljwzz.weathertrafficalarm.core.network.dto.PlaceSearchResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface BackendApi {

    @POST("v1/installations/attest")
    suspend fun attestInstallation(@Body request: AttestRequest): Response<AttestResponse>

    @GET("v1/calendars/CN/{year}")
    suspend fun getCalendar(
        @Path("year") year: Int,
        @Header("If-None-Match") etag: String? = null,
    ): Response<CalendarResponse>

    @POST("v1/places/search")
    suspend fun searchPlaces(@Body request: PlaceSearchRequest): Response<PlaceSearchResponse>

    @POST("v1/alarm-evaluations")
    suspend fun evaluateAlarm(@Body request: AlarmEvaluationRequest): Response<AlarmEvaluationResponse>
}
