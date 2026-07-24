package com.ljwzz.weathertrafficalarm.core.alarm.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceProtectedDataStore by preferencesDataStore(name = "device_protected_snapshots")

@Singleton
class NextAlarmSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val deviceContext: Context
        get() = try {
            context.createDeviceProtectedStorageContext()
        } catch (_: Exception) {
            context
        }

    private fun snapshotKey(planId: String) = stringPreferencesKey("snapshot_$planId")

    /**
     * Returns a Flow of all current snapshots (one per enabled plan).
     */
    fun observeAll(): Flow<List<NextAlarmSnapshot>> {
        return deviceContext.deviceProtectedDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                if (key.name.startsWith("snapshot_")) {
                    try {
                        json.decodeFromString<NextAlarmSnapshot>(value.toString())
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    /**
     * Saves or updates a snapshot for a plan.
     */
    suspend fun save(snapshot: NextAlarmSnapshot) {
        deviceContext.deviceProtectedDataStore.edit { prefs ->
            prefs[snapshotKey(snapshot.planId)] = json.encodeToString(snapshot)
        }
    }

    /**
     * Removes the snapshot for a given plan.
     */
    suspend fun remove(planId: String) {
        deviceContext.deviceProtectedDataStore.edit { prefs ->
            prefs.remove(snapshotKey(planId))
        }
    }

    /**
     * Returns the snapshot for a specific plan, or null if not set.
     */
    suspend fun get(planId: String): NextAlarmSnapshot? {
        val all = observeAll().first()
        return all.find { it.planId == planId }
    }

    /**
     * Replaces all snapshots atomically.
     */
    suspend fun replaceAll(snapshots: List<NextAlarmSnapshot>) {
        deviceContext.deviceProtectedDataStore.edit { prefs ->
            // Remove old snapshots
            val oldKeys = prefs.asMap().keys.filter { it.name.startsWith("snapshot_") }
            oldKeys.forEach { prefs.remove(it) }
            // Add new ones
            snapshots.forEach { snapshot ->
                prefs[snapshotKey(snapshot.planId)] = json.encodeToString(snapshot)
            }
        }
    }

    /**
     * Removes all snapshots.
     */
    suspend fun clear() {
        deviceContext.deviceProtectedDataStore.edit { prefs ->
            val oldKeys = prefs.asMap().keys.filter { it.name.startsWith("snapshot_") }
            oldKeys.forEach { prefs.remove(it) }
        }
    }
}
