package com.ljwzz.weathertrafficalarm.core.alarm.store

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import kotlinx.coroutines.test.runTest
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
}
