package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.UserManager
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEvent
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = DirectBootDiagnosticTestApplication::class, sdk = [30])
class AlarmRecoveryReceiverTest {
    private lateinit var context: Context
    private lateinit var snapshots: NextAlarmSnapshotStore
    private lateinit var logger: RedactingEventLogger

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        snapshots = NextAlarmSnapshotStore(context)
        snapshots.clear()
        logger = (context.applicationContext as DirectBootDiagnosticTestApplication).diagnostics
        logger.clear()
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    @After
    fun tearDown() = runBlocking {
        snapshots.clear()
        logger.clear()
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    @Test
    fun dueSnapshotIsDeferredToReceiverInsteadOfStartingForegroundServiceFromBoot() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "occ-1",
            planId = "plan-1",
            planRevision = 1,
            triggerAtMillis = 1_000L,
            soundUri = null,
            vibrationEnabled = true,
            snoozeMinutes = 10,
        )

        assertEquals(2_001L, AlarmRecoveryReceiver.deferredForBoot(snapshot, 1_001L).triggerAtMillis)
    }

    @Test
    fun lockedBootRecoveryRecordsActualRestoreMissAndRejectionInDeviceProtectedDiagnostics() = runBlocking {
        assertFalse(context.getSystemService(UserManager::class.java).isUserUnlocked)
        val restored = snapshot("restore", System.currentTimeMillis() + 60_000L)
        snapshots.save(restored)
        dispatch(AlarmRecoveryReceiver(), "test.restore")
        assertRecovery(restored, DiagnosticResultCode.SUCCESS)

        val missed = snapshot(
            "missed",
            System.currentTimeMillis() - AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS - 1L,
        )
        snapshots.save(missed)
        dispatch(AlarmRecoveryReceiver(), "test.missed")
        assertRecovery(missed, DiagnosticResultCode.MISSED)
        assertEvent(DiagnosticEventType.ALARM_MISSED, DiagnosticResultCode.MISSED, missed)
        assertEquals(AlarmReceiver.STATE_MISSED, snapshots.getByOccurrenceId(missed.occurrenceId)?.occurrenceState)

        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val rejected = snapshot("rejected", System.currentTimeMillis() + 120_000L)
        snapshots.save(rejected)
        dispatch(AlarmRecoveryReceiver(), "test.rejected")
        assertRecovery(rejected, DiagnosticResultCode.FAILED)
        assertDeviceProtectedFileContainsOnlyHashes(rejected)
    }

    @Test
    fun lockedReceiverRecordsTriggerWithoutNeedingTheCredentialProtectedCoordinator() = runBlocking {
        assertFalse(context.getSystemService(UserManager::class.java).isUserUnlocked)
        val snapshot = snapshot("trigger", System.currentTimeMillis())
        snapshots.save(snapshot)

        dispatch(
            AlarmReceiver(),
            "test.trigger",
            PendingIntentFactory(context).createAlarmIntent(snapshot.occurrenceId, AlarmReceiver::class.java).apply {
                component = null
                action = "test.trigger"
            },
        )

        assertEvent(DiagnosticEventType.ALARM_TRIGGER, DiagnosticResultCode.SUCCESS, snapshot)
        assertEquals(AlarmReceiver.STATE_FIRING, snapshots.getByOccurrenceId(snapshot.occurrenceId)?.occurrenceState)
    }

    private suspend fun dispatch(receiver: BroadcastReceiver, action: String, intent: Intent = Intent(action)) {
        val filter = IntentFilter(action).apply {
            intent.data?.scheme?.let(::addDataScheme)
        }
        context.registerReceiver(receiver, filter)
        try {
            context.sendBroadcast(intent)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun assertRecovery(snapshot: NextAlarmSnapshot, resultCode: DiagnosticResultCode) {
        assertEvent(DiagnosticEventType.ALARM_RECOVERY, resultCode, snapshot)
    }

    private suspend fun assertEvent(
        eventType: DiagnosticEventType,
        resultCode: DiagnosticResultCode,
        snapshot: NextAlarmSnapshot,
    ) {
        val event = awaitEvent {
            it.eventType == eventType &&
                it.resultCode == resultCode &&
                it.planIdHash == sha256(snapshot.planId) &&
                it.occurrenceIdHash == sha256(snapshot.occurrenceId)
        }
        assertTrue(event.appVersion.isNotBlank())
        assertTrue(event.sdkInt > 0)
    }

    private suspend fun assertDeviceProtectedFileContainsOnlyHashes(snapshot: NextAlarmSnapshot) {
        val file = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "diagnostics/events.json")
        withTimeout(5_000L) {
            while (!file.isFile || !file.readText().contains(sha256(snapshot.occurrenceId))) delay(25L)
        }
        val serialized = file.readText()
        assertFalse(serialized.contains(snapshot.planId))
        assertFalse(serialized.contains(snapshot.occurrenceId))
    }

    private suspend fun awaitEvent(predicate: (DiagnosticEvent) -> Boolean): DiagnosticEvent {
        var event = logger.recentEvents().lastOrNull(predicate)
        withTimeout(5_000L) {
            while (event == null) {
                delay(25L)
                event = logger.recentEvents().lastOrNull(predicate)
            }
        }
        return requireNotNull(event)
    }

    private fun snapshot(label: String, triggerAtMillis: Long): NextAlarmSnapshot {
        val suffix = UUID.randomUUID().toString()
        return NextAlarmSnapshot(
            occurrenceId = "$label-occurrence-$suffix",
            planId = "$label-plan-$suffix",
            planRevision = 1L,
            triggerAtMillis = triggerAtMillis,
            soundUri = null,
            vibrationEnabled = false,
            snoozeMinutes = 10,
        )
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Test-only Hilt component: Direct Boot paths can obtain diagnostics but never a coordinator/Room. */
class DirectBootDiagnosticTestApplication : Application(),
    GeneratedComponentManager<DirectBootDiagnosticTestApplication.Component> {
    val diagnostics by lazy { RedactingEventLogger(this) }
    private val component by lazy { Component(diagnostics) }

    override fun generatedComponent(): Component = component

    class Component(
        private val diagnostics: RedactingEventLogger,
    ) : GeneratedComponent, DiagnosticLoggerEntryPoint {
        override fun logger(): RedactingEventLogger = diagnostics
    }
}
