package com.ljwzz.weathertrafficalarm.di

import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapConsentProvider
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebKey
import com.ljwzz.weathertrafficalarm.core.network.amap.AmapWebKeyProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Keeps encrypted credential access outside the network module and Compose UI. */
@Module
@InstallIn(SingletonComponent::class)
object AmapCredentialModule {

    @Provides
    @Singleton
    fun provideAmapWebKeyProvider(credentials: CredentialStore): AmapWebKeyProvider =
        AmapWebKeyProvider {
            credentials.credentialsForServiceUse()?.let { stored ->
                stored.amapWebKey?.let { AmapWebKey(it, stored.amapWebVersion) }
            }
        }

    @Provides
    @Singleton
    fun provideAmapConsentProvider(settings: LocalSettingsStore): AmapConsentProvider =
        AmapConsentProvider { settings.settings.value.amapConsentGranted }
}
