package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import dagger.hilt.android.EntryPointAccessors
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.ExactAlarmScheduler
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
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
                        if (snapshot.occurrenceState != AlarmReceiver.STATE_SCHEDULED) return@forEach
                        when {
                            snapshot.triggerAtMillis + AlarmReceiver.LATE_TRIGGER_WINDOW_MILLIS < now -> {
                                store.save(snapshot.copy(occurrenceState = AlarmReceiver.STATE_MISSED, firedAtMillis = now))
                            }
                            snapshot.triggerAtMillis <= now -> {
                                // Starting a foreground service directly from a boot
                                // broadcast is avoided. Re-register one second ahead
                                // and let AlarmReceiver apply the same trigger gate.
                                val deferred = deferredForBoot(snapshot, now)
                                store.save(deferred)
                                scheduler.schedule(deferred)
                            }
                            else -> when (scheduler.restore(snapshot, now)) {
                                AlarmRegistrationResult.Registered -> Unit
                                is AlarmRegistrationResult.Rejected -> {
                                    // Keep the snapshot for the unlocked coordinator,
                                    // which exposes a real registration error to UI.
                                }
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal fun deferredForBoot(
            snapshot: com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot,
            now: Long,
        ): com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot =
            snapshot.copy(triggerAtMillis = now + 1_000L)
    }
}
