package com.ljwzz.weathertrafficalarm.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.localSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_settings")

@Serializable
data class FavoritePlace(
    val id: String,
    val name: String,
    val address: String,
    /** Optional while migrating legacy text-only favorites to map-backed places. */
    val placeRef: PlaceRef? = null,
)

data class WeatherBuffers(
    val lightMinutes: Int = DEFAULT_LIGHT,
    val moderateMinutes: Int = DEFAULT_MODERATE,
    val severeMinutes: Int = DEFAULT_SEVERE,
) {
    init {
        require(lightMinutes in 0..60) { "lightMinutes must be 0-60" }
        require(moderateMinutes in 0..60) { "moderateMinutes must be 0-60" }
        require(severeMinutes in 0..60) { "severeMinutes must be 0-60" }
    }

    companion object {
        const val DEFAULT_LIGHT = 10
        const val DEFAULT_MODERATE = 20
        const val DEFAULT_SEVERE = 30
    }
}

/** Local-only preferences. Legacy favorites may remain text-only until a map place is selected. */
data class LocalSettings(
    val privacyAccepted: Boolean = false,
    /** The AMap consent revision most recently shown to this device user. */
    val amapConsentPromptedVersion: Int? = null,
    /** AMap SDK consent for [amapConsentPromptedVersion]; legacy privacyAccepted is unrelated. */
    val amapConsentGranted: Boolean = false,
    val notificationSummary: Boolean = true,
    val lockScreenSummary: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val favorites: List<FavoritePlace> = emptyList(),
    val originId: String? = null,
    val destinationId: String? = null,
    val commuteMode: CommuteMode = CommuteMode.DRIVING,
    val workdayWeatherBuffers: WeatherBuffers = WeatherBuffers(),
    val weekendWeatherBuffers: WeatherBuffers = WeatherBuffers(5, 10, 20),
    val holidayWeatherBuffers: WeatherBuffers = WeatherBuffers(10, 15, 25),
)

@Singleton
class LocalSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        dataStore = context.localSettingsDataStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    val settings: StateFlow<LocalSettings> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map(::decode)
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, LocalSettings())

    /** Reads persisted settings before an owner lifts an initial-loading gate. */
    suspend fun loadInitial(): LocalSettings = withContext(Dispatchers.IO) {
        dataStore.data
            .catch { failure ->
                if (failure is IOException) emit(emptyPreferences()) else throw failure
            }
            .first()
            .let(::decode)
    }

    /** Updates all related values in one DataStore transaction. */
    suspend fun update(transform: (LocalSettings) -> LocalSettings) {
        withContext(Dispatchers.IO) {
            dataStore.edit { preferences ->
                val updated = transform(decode(preferences)).normalized()
                preferences[PRIVACY_ACCEPTED] = updated.privacyAccepted
                updated.amapConsentPromptedVersion.writeNullable(preferences, AMAP_CONSENT_PROMPTED_VERSION)
                preferences[AMAP_CONSENT_GRANTED] = updated.amapConsentGranted
                preferences[NOTIFICATION_SUMMARY] = updated.notificationSummary
                preferences[LOCK_SCREEN_SUMMARY] = updated.lockScreenSummary
                preferences[DIAGNOSTICS_ENABLED] = updated.diagnosticsEnabled
                preferences[FAVORITES] = json.encodeToString(updated.favorites)
                updated.originId.writeNullable(preferences, ORIGIN_ID)
                updated.destinationId.writeNullable(preferences, DESTINATION_ID)
                preferences[COMMUTE_MODE] = updated.commuteMode.name
                updated.workdayWeatherBuffers.writeTo(preferences, WORKDAY_BUFFERS)
                updated.weekendWeatherBuffers.writeTo(preferences, WEEKEND_BUFFERS)
                updated.holidayWeatherBuffers.writeTo(preferences, HOLIDAY_BUFFERS)
            }
        }
    }

    private fun decode(preferences: Preferences): LocalSettings {
        val favorites = preferences[FAVORITES].decodeFavorites()
        return LocalSettings(
        privacyAccepted = preferences[PRIVACY_ACCEPTED] ?: false,
        amapConsentPromptedVersion = preferences[AMAP_CONSENT_PROMPTED_VERSION]?.takeIf { it > 0 },
        amapConsentGranted = preferences[AMAP_CONSENT_GRANTED] ?: false,
        notificationSummary = preferences[NOTIFICATION_SUMMARY] ?: true,
        lockScreenSummary = preferences[LOCK_SCREEN_SUMMARY] ?: true,
        diagnosticsEnabled = preferences[DIAGNOSTICS_ENABLED] ?: false,
        favorites = favorites,
        originId = preferences[ORIGIN_ID]?.takeIf { id -> favorites.any { it.id == id } },
        destinationId = preferences[DESTINATION_ID]?.takeIf { id -> favorites.any { it.id == id } },
        commuteMode = preferences[COMMUTE_MODE].toCommuteMode(),
        workdayWeatherBuffers = preferences[WORKDAY_BUFFERS.serialized].decodeBuffers(WeatherBuffers()),
        weekendWeatherBuffers = preferences[WEEKEND_BUFFERS.serialized].decodeBuffers(WeatherBuffers(5, 10, 20)),
        holidayWeatherBuffers = preferences[HOLIDAY_BUFFERS.serialized].decodeBuffers(WeatherBuffers(10, 15, 25)),
        ).normalized()
    }

    private fun LocalSettings.normalized(): LocalSettings {
        val normalizedFavorites = favorites.map { favorite ->
            favorite.copy(id = favorite.id.trim(), name = favorite.name.trim(), address = favorite.address.trim())
        }
        require(normalizedFavorites.all { it.id.isNotEmpty() && it.name.isNotEmpty() && it.address.isNotEmpty() }) {
            "Favorite places require id, name and address"
        }
        require(normalizedFavorites.map(FavoritePlace::id).distinct().size == normalizedFavorites.size) {
            "Favorite place ids must be unique"
        }
        require(originId == null || normalizedFavorites.any { it.id == originId }) { "originId must reference a favorite" }
        require(destinationId == null || normalizedFavorites.any { it.id == destinationId }) { "destinationId must reference a favorite" }
        require(originId == null || originId != destinationId) { "originId and destinationId must differ" }
        return copy(favorites = normalizedFavorites)
    }

    private fun String?.decodeFavorites(): List<FavoritePlace> =
        this?.let { runCatching { json.decodeFromString<List<FavoritePlace>>(it) }.getOrDefault(emptyList()) } ?: emptyList()

    private fun String?.toCommuteMode(): CommuteMode =
        this?.let { runCatching { CommuteMode.valueOf(it) }.getOrNull() } ?: CommuteMode.DRIVING

    private fun String?.decodeBuffers(defaults: WeatherBuffers): WeatherBuffers = this?.split(',')
        ?.takeIf { it.size == 3 }
        ?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 3 }
        ?.let { values -> runCatching { WeatherBuffers(values[0], values[1], values[2]) }.getOrNull() }
        ?: defaults

    private fun WeatherBuffers.writeTo(preferences: androidx.datastore.preferences.core.MutablePreferences, keys: BufferKeys) {
        preferences[keys.serialized] = "$lightMinutes,$moderateMinutes,$severeMinutes"
    }

    private fun String?.writeNullable(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
    ) {
        if (isNullOrBlank()) preferences.remove(key) else preferences[key] = trim()
    }

    private fun Int?.writeNullable(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<Int>,
    ) {
        if (this == null) preferences.remove(key) else preferences[key] = this
    }

    private data class BufferKeys(val serialized: Preferences.Key<String>)

    private companion object {
        val PRIVACY_ACCEPTED = PreferencesKeys.PRIVACY_ACCEPTED
        val AMAP_CONSENT_PROMPTED_VERSION = PreferencesKeys.AMAP_CONSENT_PROMPTED_VERSION
        val AMAP_CONSENT_GRANTED = PreferencesKeys.AMAP_CONSENT_GRANTED
        val NOTIFICATION_SUMMARY = PreferencesKeys.NOTIFICATION_SUMMARY
        val LOCK_SCREEN_SUMMARY = PreferencesKeys.LOCK_SCREEN_SUMMARY
        val DIAGNOSTICS_ENABLED = PreferencesKeys.LOCAL_DIAGNOSTICS_ENABLED
        val FAVORITES = PreferencesKeys.FAVORITE_PLACES
        val ORIGIN_ID = PreferencesKeys.ORIGIN_FAVORITE_ID
        val DESTINATION_ID = PreferencesKeys.DESTINATION_FAVORITE_ID
        val COMMUTE_MODE = PreferencesKeys.COMMUTE_MODE
        val WORKDAY_BUFFERS = BufferKeys(PreferencesKeys.WORKDAY_WEATHER_BUFFERS)
        val WEEKEND_BUFFERS = BufferKeys(PreferencesKeys.WEEKEND_WEATHER_BUFFERS)
        val HOLIDAY_BUFFERS = BufferKeys(PreferencesKeys.HOLIDAY_WEATHER_BUFFERS)
        val json = Json { ignoreUnknownKeys = true }
    }
}
