package com.ljwzz.weathertrafficalarm.core.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

object PreferencesKeys {
    val PRIVACY_CONSENT_AMAP = booleanPreferencesKey("privacy_consent_amap")
    val PRIVACY_CONSENT_CAIYUN_DISCLOSURE = booleanPreferencesKey("privacy_consent_caiyun_disclosure")
    val WEATHER_BUFFER_LEVEL_1_MINUTES = intPreferencesKey("weather_buffer_level_1_minutes")
    val WEATHER_BUFFER_LEVEL_2_MINUTES = intPreferencesKey("weather_buffer_level_2_minutes")
    val WEATHER_BUFFER_LEVEL_3_MINUTES = intPreferencesKey("weather_buffer_level_3_minutes")
    val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
}
