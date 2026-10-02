package com.ljwzz.weathertrafficalarm.core.alarm.store

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljwzz.weathertrafficalarm.core.model.DayCommitCredential
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
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
open class NextAlarmSnapshotStore @Inject constructor(
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
     * Returns occurrence snapshots, including inactive snapshots awaiting commit cleanup.
     *
     * Keys are based on occurrence IDs rather than plan IDs so a regular alarm
     * can safely coexist with its independent snooze child while an edit is
     * being registered. Older builds keyed the same serialised value by plan;
     * values remain readable because they carry an occurrence ID themselves.
     */
    fun observeAll(): Flow<List<NextAlarmSnapshot>> {
        return dataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                if (key.name.startsWith(SNAPSHOT_PREFIX)) {
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
            removeDuplicateOccurrenceKeys(prefs, snapshot.occurrenceId)
            prefs[snapshotKey(snapshot.occurrenceId)] = json.encodeToString(snapshot)
        }
    }

    /**
     * Publishes a candidate snapshot and its commit credential in one device-protected update.
     * The candidate, inactive old snapshots, retained revisions and rollback image become
     * visible together, so recovery can complete the commit or restore its previous state.
     */
    open suspend fun publishCandidate(snapshot: NextAlarmSnapshot, credential: DayCommitCredential) {
        publish(snapshot, credential)
    }

    suspend fun commitCredential(changeId: String): DayCommitCredential? =
        dataStore.data.first()[commitKey(changeId)]?.let { decodeCredential(it) }

    suspend fun commitCredentials(): List<DayCommitCredential> =
        dataStore.data.first().asMap().entries.mapNotNull { (key, value) ->
            if (key.name.startsWith(COMMIT_PREFIX)) decodeCredential(value.toString()) else null
        }

    /** Publishes only the credential, used when the change needs no new local instance. */
    open suspend fun publishCommitCredential(credential: DayCommitCredential) {
        publish(null, credential)
    }

    /** Replacement and its rollback image become durable in the same DP transaction. */
    private suspend fun publish(snapshot: NextAlarmSnapshot?, credential: DayCommitCredential) {
        dataStore.edit { prefs ->
            val affected = (credential.cancelledOccurrenceIds + credential.revisedOccurrenceIds).toSet()
            val previous = decodeSnapshots(prefs).filter { it.occurrenceId in affected }
            if (prefs[rollbackKey(credential.changeId)] == null) {
                prefs[rollbackKey(credential.changeId)] = json.encodeToString(previous)
            }
            previous.forEach { old ->
                removeDuplicateOccurrenceKeys(prefs, old.occurrenceId)
                val updated = if (old.occurrenceId in credential.cancelledOccurrenceIds) {
                    old.copy(occurrenceState = OccurrenceState.CANCELLED.name)
                } else {
                    old.copy(dayRevision = credential.dayRevision)
                }
                prefs[snapshotKey(old.occurrenceId)] = json.encodeToString(updated)
            }
            snapshot?.let {
                removeDuplicateOccurrenceKeys(prefs, it.occurrenceId)
                prefs[snapshotKey(it.occurrenceId)] = json.encodeToString(it)
            }
            prefs[commitKey(credential.changeId)] = json.encodeToString(credential)
        }
    }

    /** An uncommitted publish restores all affected snapshots before revoking its credential. */
    suspend fun rollbackCandidate(changeId: String) {
        dataStore.edit { prefs ->
            val credential = prefs[commitKey(changeId)]?.let(::decodeCredential)
            credential?.occurrenceId?.let { id ->
                removeDuplicateOccurrenceKeys(prefs, id)
                prefs.remove(snapshotKey(id))
            }
            prefs[rollbackKey(changeId)]?.let { encoded ->
                json.decodeFromString<List<NextAlarmSnapshot>>(encoded).forEach { previous ->
                    removeDuplicateOccurrenceKeys(prefs, previous.occurrenceId)
                    prefs[snapshotKey(previous.occurrenceId)] = json.encodeToString(previous)
                }
            }
            prefs.remove(commitKey(changeId))
            prefs.remove(rollbackKey(changeId))
        }
    }

    suspend fun removeCommitCredential(changeId: String) {
        dataStore.edit { prefs ->
            prefs.remove(commitKey(changeId))
            prefs.remove(rollbackKey(changeId))
        }
    }

    private fun decodeCredential(value: String): DayCommitCredential? =
        runCatching { json.decodeFromString<DayCommitCredential>(value) }.getOrNull()

    private fun commitKey(changeId: String) = stringPreferencesKey("$COMMIT_PREFIX$changeId")
    private fun rollbackKey(changeId: String) = stringPreferencesKey("$ROLLBACK_PREFIX$changeId")

    private fun removeDuplicateOccurrenceKeys(prefs: androidx.datastore.preferences.core.MutablePreferences, occurrenceId: String) {
        prefs.asMap().entries
            .filter { (key, value) ->
                key.name.startsWith(SNAPSHOT_PREFIX) && key.name != snapshotKey(occurrenceId).name && runCatching {
                    json.decodeFromString<NextAlarmSnapshot>(value.toString()).occurrenceId == occurrenceId
                }.getOrDefault(false)
            }
            .forEach { (key, _) -> prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<String>) }
    }

    /**
     * Removes the snapshot for a given plan.
     */
    suspend fun remove(planId: String) {
        dataStore.edit { prefs ->
            prefs.asMap().entries
                .filter { (key, value) ->
                    key.name.startsWith(SNAPSHOT_PREFIX) && runCatching {
                        json.decodeFromString<NextAlarmSnapshot>(value.toString()).planId == planId
                    }.getOrDefault(false)
                }
                .forEach { (key, _) -> prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<String>) }
            val credentials = prefs.asMap().entries
                .filter { (key, value) ->
                    key.name.startsWith(COMMIT_PREFIX) &&
                        decodeCredential(value.toString())?.planId == planId
                }
            credentials.forEach { (key, value) ->
                decodeCredential(value.toString())?.let { prefs.remove(rollbackKey(it.changeId)) }
                prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<String>)
            }
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
                    key.name.startsWith(SNAPSHOT_PREFIX) && runCatching {
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
            val oldKeys = prefs.asMap().keys.filter { it.name.startsWith(SNAPSHOT_PREFIX) }
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
            val oldKeys = prefs.asMap().keys.filter {
                it.name.startsWith(SNAPSHOT_PREFIX) || it.name.startsWith(COMMIT_PREFIX) || it.name.startsWith(ROLLBACK_PREFIX)
            }
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
            if (!key.name.startsWith(SNAPSHOT_PREFIX)) return@mapNotNull null
            runCatching { json.decodeFromString<NextAlarmSnapshot>(value.toString()) }.getOrNull()
        }

    private fun dataStoreFor(file: File): DataStore<Preferences> {
        file.parentFile?.mkdirs()
        return stores.computeIfAbsent(file.absolutePath) {
            PreferenceDataStoreFactory.create(produceFile = { file })
        }
    }

    internal companion object {
        const val SNAPSHOT_PREFIX = "snapshot_"
        const val COMMIT_PREFIX = "commit_"
        const val ROLLBACK_PREFIX = "rollback_"
        val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    }
}
