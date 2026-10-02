package com.ljwzz.weathertrafficalarm.core.alarm.store

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.DayCommitCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NextAlarmSnapshotStoreDeviceStorageTest {
    @Test
    fun snapshotsUseDeviceProtectedFilesDirectory() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = NextAlarmSnapshotStore(context)
        val file = store.deviceProtectedFile()
        val deviceProtectedDir = context.createDeviceProtectedStorageContext().filesDir

        assertTrue(file.absolutePath.startsWith(deviceProtectedDir.absolutePath))
        store.save(
            NextAlarmSnapshot(
                occurrenceId = "device-protected-occurrence",
                planId = "plan-1",
                planRevision = 1,
                triggerAtMillis = 9_999L,
                soundUri = null,
                vibrationEnabled = true,
                snoozeMinutes = 10,
            ),
        )
        assertTrue(file.isFile)
        store.clear()
    }

    @Test
    fun repeatedPublicationPreservesTheOriginalRollbackImage() = runTest {
        val store = NextAlarmSnapshotStore(RuntimeEnvironment.getApplication())
        store.clear()
        val original = NextAlarmSnapshot("old", "plan", 1, 9_999L, null, true, 10,
            targetDate = "2099-10-02", dayRevision = 4)
        val candidate = original.copy(occurrenceId = "new", triggerAtMillis = 10_999, dayRevision = 5)
        val credential = DayCommitCredential("change", original.planId, original.targetDate!!, 5,
            occurrenceId = candidate.occurrenceId, cancelledOccurrenceIds = listOf(original.occurrenceId))
        store.save(original)
        store.publishCandidate(candidate, credential)
        assertEquals("CANCELLED", store.getByOccurrenceId(original.occurrenceId)?.occurrenceState)
        assertEquals(candidate, store.getByOccurrenceId(candidate.occurrenceId))
        assertEquals(credential, store.commitCredential(credential.changeId))
        store.publishCandidate(candidate, credential)
        store.rollbackCandidate(credential.changeId)
        assertEquals(original, store.getByOccurrenceId(original.occurrenceId))
        assertNull(store.getByOccurrenceId(candidate.occurrenceId))
        assertNull(store.commitCredential(credential.changeId))
        store.clear()
    }

    @Test
    fun rollbackRestoresTheRetainedInstancesOriginalRevision() = runTest {
        val store = NextAlarmSnapshotStore(RuntimeEnvironment.getApplication())
        store.clear()
        val original = NextAlarmSnapshot("retained", "plan", 1, 9_999L, null, true, 10,
            targetDate = "2099-10-02", dayRevision = 3)
        val credential = DayCommitCredential("change", original.planId, original.targetDate!!, 4,
            revisedOccurrenceIds = listOf(original.occurrenceId))
        store.save(original)
        store.publishCommitCredential(credential)
        assertEquals(4L, store.getByOccurrenceId(original.occurrenceId)?.dayRevision)
        store.rollbackCandidate(credential.changeId)
        assertEquals(original, store.getByOccurrenceId(original.occurrenceId))
        assertNull(store.commitCredential(credential.changeId))
        store.clear()
    }
}
