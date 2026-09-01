package com.ljwzz.weathertrafficalarm.core.network.di

import com.ljwzz.weathertrafficalarm.core.network.BuildConfig
import com.ljwzz.weathertrafficalarm.core.network.api.BackendApi
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapConsentProvider
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebApi
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebKeyProvider
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebProvider
import com.ljwzz.weathertrafficalarm.core.model.PlaceProvider
import com.ljwzz.weathertrafficalarm.core.model.RouteProvider
import javax.inject.Qualifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AmapRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val WRITE_TIMEOUT_SECONDS = 15L

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideBackendApi(retrofit: Retrofit): BackendApi {
        return retrofit.create(BackendApi::class.java)
    }

    @Provides
    @Singleton
    @AmapRetrofit
    fun provideAmapRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        return Retrofit.Builder()
            .baseUrl(AMAP_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideAmapWebApi(@AmapRetrofit retrofit: Retrofit): AmapWebApi =
        retrofit.create(AmapWebApi::class.java)

    @Provides
    @Singleton
    fun provideAmapWebProvider(
        api: AmapWebApi,
        keyProvider: AmapWebKeyProvider,
        consentProvider: AmapConsentProvider,
    ): AmapWebProvider = AmapWebProvider(api, keyProvider, consentProvider)

    @Provides
    @Singleton
    fun providePlaceProvider(provider: AmapWebProvider): PlaceProvider = provider

    @Provides
    @Singleton
    fun provideRouteProvider(provider: AmapWebProvider): RouteProvider = provider

    private const val AMAP_BASE_URL = "https://restapi.amap.com/"
}
