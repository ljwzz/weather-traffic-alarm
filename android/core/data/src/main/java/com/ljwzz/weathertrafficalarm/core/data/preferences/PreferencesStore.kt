package com.ljwzz.weathertrafficalarm.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

@Singleton
class PreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> get() = context.preferencesDataStore

    private fun <T> safeGet(key: Preferences.Key<T>, defaultValue: T): Flow<T> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it[key] ?: defaultValue }

    val privacyConsentAmap: Flow<Boolean> = safeGet(PreferencesKeys.PRIVACY_CONSENT_AMAP, false)
    val privacyConsentCaiyunDisclosure: Flow<Boolean> = safeGet(PreferencesKeys.PRIVACY_CONSENT_CAIYUN_DISCLOSURE, false)
    val weatherBufferLevel1Minutes: Flow<Int> = safeGet(PreferencesKeys.WEATHER_BUFFER_LEVEL_1_MINUTES, 10)
    val weatherBufferLevel2Minutes: Flow<Int> = safeGet(PreferencesKeys.WEATHER_BUFFER_LEVEL_2_MINUTES, 20)
    val weatherBufferLevel3Minutes: Flow<Int> = safeGet(PreferencesKeys.WEATHER_BUFFER_LEVEL_3_MINUTES, 30)
    val diagnosticsEnabled: Flow<Boolean> = safeGet(PreferencesKeys.DIAGNOSTICS_ENABLED, false)

    suspend fun setPrivacyConsentAmap(granted: Boolean) {
        dataStore.edit { it[PreferencesKeys.PRIVACY_CONSENT_AMAP] = granted }
    }

    suspend fun setPrivacyConsentCaiyunDisclosure(granted: Boolean) {
        dataStore.edit { it[PreferencesKeys.PRIVACY_CONSENT_CAIYUN_DISCLOSURE] = granted }
    }

    suspend fun setWeatherBufferMinutes(level: Int, minutes: Int) {
        dataStore.edit { prefs ->
            when (level) {
                1 -> prefs[PreferencesKeys.WEATHER_BUFFER_LEVEL_1_MINUTES] = minutes
                2 -> prefs[PreferencesKeys.WEATHER_BUFFER_LEVEL_2_MINUTES] = minutes
                3 -> prefs[PreferencesKeys.WEATHER_BUFFER_LEVEL_3_MINUTES] = minutes
            }
        }
    }

    suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.DIAGNOSTICS_ENABLED] = enabled }
    }
}
