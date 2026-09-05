package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import dagger.hilt.android.EntryPointAccessors
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.ExactAlarmScheduler
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores device-protected snapshots after boot or clock changes. It does not
 * access Room; LocalAlarmCoordinator.recover() reconciles the resulting state
 * after the user unlocks the device.
 */
class AlarmRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val startedElapsed = SystemClock.elapsedRealtime()
            try {
                val appContext = context.applicationContext
                val userManager = appContext.getSystemService(UserManager::class.java)
                if (userManager.isUserUnlocked) {
                    EntryPointAccessors
                        .fromApplication(appContext, AlarmCoordinatorEntryPoint::class.java)
                        .coordinator()
                        .recover()
                    return@launch
                }
                val store = NextAlarmSnapshotStore(appContext)
                val scheduler = ExactAlarmScheduler(
                    appContext,
                    appContext.getSystemService(AlarmManager::class.java),
                    PendingIntentFactory(appContext),
                    store,
                )
                val now = System.currentTimeMillis()
                AlarmReceiver.withDirectBootLock {
                    store.observeAll().first().forEach { snapshot ->
                        if (snapshot.occurrenceState != AlarmReceiver.STATE_SCHEDULED) {
                            recordRecovery(context, snapshot, DiagnosticResultCode.SKIPPED, startedElapsed)
                            return@forEach
                        }
                        when {
                            snapshot.triggerAtMillis + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS < now -> {
                                store.save(snapshot.copy(occurrenceState = AlarmReceiver.STATE_MISSED, firedAtMillis = now))
                                AlarmReceiver.recordDiagnostic(
                                    appContext,
                                    DiagnosticEventType.ALARM_MISSED,
                                    DiagnosticResultCode.MISSED,
                                    snapshot,
                                )
                                recordRecovery(context, snapshot, DiagnosticResultCode.MISSED, startedElapsed)
                            }
                            snapshot.triggerAtMillis <= now -> {
                                // Starting a foreground service directly from a boot
                                // broadcast is avoided. Re-register one second ahead
                                // and let AlarmReceiver apply the same trigger gate.
                                val deferred = deferredForBoot(snapshot, now)
                                store.save(deferred)
                                when (scheduler.schedule(deferred)) {
                                    AlarmRegistrationResult.Registered ->
                                        recordRecovery(context, deferred, DiagnosticResultCode.SUCCESS, startedElapsed)
                                    is AlarmRegistrationResult.Rejected ->
                                        recordRecovery(context, deferred, DiagnosticResultCode.FAILED, startedElapsed)
                                }
                            }
                            else -> when (scheduler.restore(snapshot, now)) {
                                AlarmRegistrationResult.Registered ->
                                    recordRecovery(context, snapshot, DiagnosticResultCode.SUCCESS, startedElapsed)
                                is AlarmRegistrationResult.Rejected -> {
                                    // Keep the snapshot for the unlocked coordinator,
                                    // which exposes a real registration error to UI.
                                    recordRecovery(context, snapshot, DiagnosticResultCode.FAILED, startedElapsed)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                AlarmReceiver.recordDiagnostic(
                    context.applicationContext,
                    DiagnosticEventType.ALARM_RECOVERY,
                    DiagnosticResultCode.CANCELLED,
                    durationMs = SystemClock.elapsedRealtime() - startedElapsed,
                )
                throw cancelled
            } catch (_: Exception) {
                AlarmReceiver.recordDiagnostic(
                    context.applicationContext,
                    DiagnosticEventType.ALARM_RECOVERY,
                    DiagnosticResultCode.FAILED,
                    durationMs = SystemClock.elapsedRealtime() - startedElapsed,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private fun recordRecovery(
            context: Context,
            snapshot: com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot,
            resultCode: DiagnosticResultCode,
            startedElapsed: Long,
        ) {
            AlarmReceiver.recordDiagnostic(
                context.applicationContext,
                DiagnosticEventType.ALARM_RECOVERY,
                resultCode,
                snapshot,
                durationMs = SystemClock.elapsedRealtime() - startedElapsed,
            )
        }

        internal fun deferredForBoot(
            snapshot: com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot,
            now: Long,
        ): com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot =
            snapshot.copy(triggerAtMillis = now + 1_000L)
    }
}
