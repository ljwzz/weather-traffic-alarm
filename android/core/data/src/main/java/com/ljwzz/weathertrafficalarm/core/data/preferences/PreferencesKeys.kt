package com.ljwzz.weathertrafficalarm.core.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferencesKeys {
    val PRIVACY_CONSENT_AMAP = booleanPreferencesKey("privacy_consent_amap")
    val PRIVACY_CONSENT_CAIYUN_DISCLOSURE = booleanPreferencesKey("privacy_consent_caiyun_disclosure")
    val WEATHER_BUFFER_LEVEL_1_MINUTES = intPreferencesKey("weather_buffer_level_1_minutes")
    val WEATHER_BUFFER_LEVEL_2_MINUTES = intPreferencesKey("weather_buffer_level_2_minutes")
    val WEATHER_BUFFER_LEVEL_3_MINUTES = intPreferencesKey("weather_buffer_level_3_minutes")
    val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")

    val PRIVACY_ACCEPTED = booleanPreferencesKey("local_privacy_accepted")
    val NOTIFICATION_SUMMARY = booleanPreferencesKey("notification_summary")
    val LOCK_SCREEN_SUMMARY = booleanPreferencesKey("lock_screen_summary")
    val LOCAL_DIAGNOSTICS_ENABLED = booleanPreferencesKey("local_diagnostics_enabled")
    val FAVORITE_PLACES = stringPreferencesKey("favorite_places")
    val ORIGIN_FAVORITE_ID = stringPreferencesKey("origin_favorite_id")
    val DESTINATION_FAVORITE_ID = stringPreferencesKey("destination_favorite_id")
    val COMMUTE_MODE = stringPreferencesKey("commute_mode")
    val WORKDAY_WEATHER_BUFFERS = stringPreferencesKey("workday_weather_buffers")
    val WEEKEND_WEATHER_BUFFERS = stringPreferencesKey("weekend_weather_buffers")
    val HOLIDAY_WEATHER_BUFFERS = stringPreferencesKey("holiday_weather_buffers")
}
