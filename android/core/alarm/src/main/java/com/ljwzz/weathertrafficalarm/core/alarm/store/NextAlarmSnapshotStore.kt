package com.ljwzz.weathertrafficalarm.core.alarm.store

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NextAlarmSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val deviceContext: Context
        get() = context.createDeviceProtectedStorageContext()

    private val dataStore: DataStore<Preferences>
        get() = dataStoreFor(deviceProtectedFile())

    private fun snapshotKey(occurrenceId: String) = stringPreferencesKey("snapshot_$occurrenceId")

    /**
     * This path is intentionally derived from the device-protected Context.
     * preferencesDataStore delegates bind to an application Context internally,
     * which would resolve this store under credential-encrypted files instead.
     */
    internal fun deviceProtectedFile(): File = File(
        deviceContext.filesDir,
        "datastore/device_protected_snapshots.preferences_pb",
    )

    /**
     * Returns every armed or ringing occurrence snapshot.
     *
     * Keys are based on occurrence IDs rather than plan IDs so a regular alarm
     * can safely coexist with its independent snooze child while an edit is
     * being registered. Older builds keyed the same serialised value by plan;
     * values remain readable because they carry an occurrence ID themselves.
     */
    fun observeAll(): Flow<List<NextAlarmSnapshot>> {
        return dataStore.data.map { prefs ->
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
            }.groupBy { it.occurrenceId }
                .values
                .map { sameOccurrence -> sameOccurrence.maxBy { it.triggerAtMillis } }
        }
    }

    /**
     * Saves or updates a snapshot for a plan.
     */
    suspend fun save(snapshot: NextAlarmSnapshot) {
        dataStore.edit { prefs ->
            prefs.asMap().entries
                .filter { (key, value) ->
                    key.name.startsWith("snapshot_") && key.name != snapshotKey(snapshot.occurrenceId).name && runCatching {
                        json.decodeFromString<NextAlarmSnapshot>(value.toString()).occurrenceId == snapshot.occurrenceId
                    }.getOrDefault(false)
                }
                .forEach { (key, _) -> prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<String>) }
            prefs[snapshotKey(snapshot.occurrenceId)] = json.encodeToString(snapshot)
        }
    }

    /**
     * Removes the snapshot for a given plan.
     */
    suspend fun remove(planId: String) {
        dataStore.edit { prefs ->
            prefs.asMap().entries
                .filter { (key, value) ->
                    key.name.startsWith("snapshot_") && runCatching {
                        json.decodeFromString<NextAlarmSnapshot>(value.toString()).planId == planId
                    }.getOrDefault(false)
                }
                .forEach { (key, _) -> prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<String>) }
        }
    }

    /**
     * Returns the snapshot for a specific plan, or null if not set.
     */
    suspend fun get(planId: String): NextAlarmSnapshot? {
        val all = observeAll().first()
        return all
            .filter { it.planId == planId }
            .minByOrNull { it.triggerAtMillis }
    }

    suspend fun getByOccurrenceId(occurrenceId: String): NextAlarmSnapshot? {
        val all = observeAll().first()
        return all.find { it.occurrenceId == occurrenceId }
    }

    suspend fun removeOccurrence(occurrenceId: String) {
        dataStore.edit { prefs ->
            prefs.remove(snapshotKey(occurrenceId))
            // The pre-v2 key was based on a plan ID. Remove it only when its
            // payload is for this occurrence, preventing cross-plan deletion.
            prefs.asMap().entries
                .filter { (key, value) ->
                    key.name.startsWith("snapshot_") && runCatching {
                        json.decodeFromString<NextAlarmSnapshot>(value.toString()).occurrenceId == occurrenceId
                    }.getOrDefault(false)
                }
                .forEach { (key, _) -> prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<String>) }
        }
    }

    /**
     * Replaces all snapshots atomically.
     */
    suspend fun replaceAll(snapshots: List<NextAlarmSnapshot>) {
        dataStore.edit { prefs ->
            // Remove old snapshots
            val oldKeys = prefs.asMap().keys.filter { it.name.startsWith("snapshot_") }
            oldKeys.forEach { prefs.remove(it) }
            // Add new ones
            snapshots.forEach { snapshot ->
                prefs[snapshotKey(snapshot.occurrenceId)] = json.encodeToString(snapshot)
            }
        }
    }

    /**
     * Removes all snapshots.
     */
    suspend fun clear() {
        dataStore.edit { prefs ->
            val oldKeys = prefs.asMap().keys.filter { it.name.startsWith("snapshot_") }
            oldKeys.forEach { prefs.remove(it) }
        }
    }

    /**
     * Copies snapshots written by the earlier credential-encrypted delegate
     * after unlock. No CE path is examined during Direct Boot.
     */
    suspend fun migrateLegacyCredentialProtectedSnapshotsIfUnlocked(): Int {
        val userManager = context.getSystemService(UserManager::class.java)
        if (!userManager.isUserUnlocked) return 0
        val marker = File(deviceContext.filesDir, "datastore/device_protected_snapshots_v2_migrated")
        if (marker.isFile) return 0
        val legacyFile = File(context.filesDir, "datastore/device_protected_snapshots.preferences_pb")
        if (!legacyFile.isFile) {
            marker.parentFile?.mkdirs()
            marker.createNewFile()
            return 0
        }
        val snapshots = decodeSnapshots(dataStoreFor(legacyFile).data.first())
        snapshots.forEach { save(it) }
        marker.parentFile?.mkdirs()
        marker.createNewFile()
        legacyFile.delete()
        return snapshots.size
    }

    private fun decodeSnapshots(prefs: Preferences): List<NextAlarmSnapshot> =
        prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith("snapshot_")) return@mapNotNull null
            runCatching { json.decodeFromString<NextAlarmSnapshot>(value.toString()) }.getOrNull()
        }

    private fun dataStoreFor(file: File): DataStore<Preferences> {
        file.parentFile?.mkdirs()
        return stores.computeIfAbsent(file.absolutePath) {
            PreferenceDataStoreFactory.create(produceFile = { file })
        }
    }

    private companion object {
        val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    }
}
