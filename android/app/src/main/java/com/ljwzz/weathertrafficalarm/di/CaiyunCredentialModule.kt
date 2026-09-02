package com.ljwzz.weathertrafficalarm.di

import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.network.caiyun.CaiyunCredentials
import com.ljwzz.weathertrafficalarm.core.network.caiyun.CaiyunCredentialsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Adapts encrypted app-owned credential storage to the network-only Caiyun contract. */
@Module
@InstallIn(SingletonComponent::class)
object CaiyunCredentialModule {

    @Provides
    @Singleton
    fun provideCaiyunCredentialsProvider(credentials: CredentialStore): CaiyunCredentialsProvider =
        CaiyunCredentialsProvider {
            val stored = credentials.credentialsForServiceUse() ?: return@CaiyunCredentialsProvider null
            val appKey = stored.caiyunAppKey?.trim().orEmpty()
            val appSecret = stored.caiyunSecret?.trim().orEmpty()
            if (appKey.isEmpty() || appSecret.isEmpty()) null else CaiyunCredentials(appKey, appSecret)
        }
}
