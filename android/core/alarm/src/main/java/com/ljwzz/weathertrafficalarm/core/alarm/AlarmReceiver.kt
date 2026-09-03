package com.ljwzz.weathertrafficalarm.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.AlarmAction
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.AlarmRegistrationResult
import com.ljwzz.weathertrafficalarm.core.alarm.scheduler.ExactAlarmScheduler
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Receives only explicit alarm operation PendingIntents. It uses the
 * device-protected snapshot so it can validate a trigger before user unlock;
 * Room reconciliation is intentionally delegated to LocalAlarmCoordinator
 * once credential-encrypted storage is available.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = NextAlarmSnapshotStore(context.applicationContext)
                val action = intent.getStringExtra(PendingIntentFactory.EXTRA_ACTION)
                val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
                directBootMutex.withLock {
                    // Read under the same lock as state transition. Otherwise two
                    // rapid snooze broadcasts can both observe FIRING and create
                    // separate children before either writes SNOOZED.
                    val snapshot = store.getByOccurrenceId(occurrenceId) ?: return@withLock
                    when (action) {
                    AlarmAction.ALARM.path -> {
                        when (triggerHandling(snapshot)) {
                            AlarmHandling.TRIGGERED -> {
                                if (unlocked) {
                                    if (coordinator(context).handleTrigger(snapshot.occurrenceId)) {
                                        store.getByOccurrenceId(snapshot.occurrenceId)?.let { firing ->
                                            startRinging(context, firing)
                                        }
                                    }
                                } else {
                                    handleLockedAlarm(context, store, snapshot)
                                }
                            }
                            AlarmHandling.MISSED -> if (unlocked) coordinator(context).handleMissed(snapshot.occurrenceId)
                            else store.save(snapshot.copy(occurrenceState = STATE_MISSED, firedAtMillis = System.currentTimeMillis()))
                            AlarmHandling.IGNORED -> Unit
                        }
                    }
                    AlarmAction.DISMISS.path -> {
                        if (canApplyRingingAction(snapshot)) {
                            if (unlocked) coordinator(context).dismiss(snapshot.occurrenceId)
                            else handleDismiss(context, store, snapshot)
                        }
                    }
                    AlarmAction.SNOOZE.path -> {
                        if (canApplyRingingAction(snapshot)) {
                            if (unlocked) coordinator(context).snooze(snapshot.occurrenceId)
                            else handleSnooze(context, store, snapshot)
                        }
                    }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleLockedAlarm(
        context: Context,
        store: NextAlarmSnapshotStore,
        snapshot: NextAlarmSnapshot,
    ) {
        if (triggerHandling(snapshot) != AlarmHandling.TRIGGERED) return
        val now = System.currentTimeMillis()
        val firing = snapshot.copy(occurrenceState = STATE_FIRING, firedAtMillis = now)
        store.save(firing)
        startRinging(context, firing)
    }

    private suspend fun handleDismiss(
        context: Context,
        store: NextAlarmSnapshotStore,
        snapshot: NextAlarmSnapshot,
    ) {
        if (snapshot.occurrenceState != STATE_FIRING) return
        store.save(snapshot.withActionReceipt(STATE_DISMISSED))
        context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_DISMISS, snapshot))
    }

    private suspend fun handleSnooze(
        context: Context,
        store: NextAlarmSnapshotStore,
        snapshot: NextAlarmSnapshot,
    ) {
        if (snapshot.occurrenceState != STATE_FIRING) return
        val scheduler = ExactAlarmScheduler(
            context.applicationContext,
            context.getSystemService(android.app.AlarmManager::class.java),
            PendingIntentFactory(context.applicationContext),
            store,
        )
        val next = createSnoozeSnapshot(
            snapshot = snapshot,
            nowMillis = System.currentTimeMillis(),
            occurrenceId = UUID.randomUUID().toString(),
        )
        store.save(next)
        when (scheduler.schedule(next)) {
            AlarmRegistrationResult.Registered -> {
                store.save(snapshot.withActionReceipt(STATE_SNOOZED))
                context.startService(AlarmRingingService.intent(context, AlarmRingingService.ACTION_SNOOZE, snapshot))
            }
            is AlarmRegistrationResult.Rejected -> {
                // Keep the original ringing when the child could not be armed.
                store.removeOccurrence(next.occurrenceId)
                store.save(snapshot.withActionReceipt(STATE_FIRING, SNOOZE_RETRY_MESSAGE))
            }
        }
    }

    private fun NextAlarmSnapshot.withActionReceipt(
        occurrenceState: String,
        actionError: String? = null,
    ): NextAlarmSnapshot = copy(
        occurrenceState = occurrenceState,
        actionRevision = actionRevision + 1,
        actionError = actionError,
    )

    companion object {
        const val ACTION_ALARM = "alarm"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SNOOZE = "snooze"

        const val STATE_SCHEDULED = "SCHEDULED"
        const val STATE_FIRING = "FIRING"
        const val STATE_SNOOZED = "SNOOZED"
        const val STATE_DISMISSED = "DISMISSED"
        const val STATE_MISSED = "MISSED"

        const val LATE_TRIGGER_WINDOW_MILLIS = 10 * 60_000L
        private const val EARLY_TRIGGER_TOLERANCE_MILLIS = 60_000L
        const val SNOOZE_RETRY_MESSAGE = "贪睡未能注册，请重试或停止闹钟"
        private val directBootMutex = Mutex()

        fun startRinging(context: Context, snapshot: NextAlarmSnapshot) {
            ContextCompat.startForegroundService(
                context,
                AlarmRingingService.intent(context, AlarmRingingService.ACTION_RING, snapshot),
            )
        }

        private fun coordinator(context: Context): LocalAlarmCoordinator =
            EntryPointAccessors.fromApplication(context.applicationContext, AlarmCoordinatorEntryPoint::class.java)
                .coordinator()

        internal fun triggerHandling(snapshot: NextAlarmSnapshot, now: Long = System.currentTimeMillis()): AlarmHandling = when {
            snapshot.occurrenceState != STATE_SCHEDULED -> AlarmHandling.IGNORED
            now < snapshot.triggerAtMillis - EARLY_TRIGGER_TOLERANCE_MILLIS -> AlarmHandling.IGNORED
            now > snapshot.triggerAtMillis + LATE_TRIGGER_WINDOW_MILLIS -> AlarmHandling.MISSED
            else -> AlarmHandling.TRIGGERED
        }

        /**
         * Notification and full-screen actions are admitted only for the
         * currently ringing snapshot. The state is read under [directBootMutex]
         * before this guard runs, so a queued second action cannot undo a
         * successful snooze or stop.
         */
        internal fun canApplyRingingAction(snapshot: NextAlarmSnapshot): Boolean =
            snapshot.occurrenceState == STATE_FIRING

        /** A snooze is a new occurrence and must not inherit parent action feedback. */
        internal fun createSnoozeSnapshot(
            snapshot: NextAlarmSnapshot,
            nowMillis: Long,
            occurrenceId: String,
        ): NextAlarmSnapshot = snapshot.copy(
            occurrenceId = occurrenceId,
            triggerAtMillis = nowMillis + snapshot.snoozeMinutes * 60_000L,
            occurrenceKind = "SNOOZE",
            decisionId = null,
            parentOccurrenceId = snapshot.occurrenceId,
            occurrenceState = STATE_SCHEDULED,
            snoozeCount = snapshot.snoozeCount + 1,
            firedAtMillis = null,
            actionRevision = 0,
            actionError = null,
        )

        internal suspend fun <T> withDirectBootLock(block: suspend () -> T): T = directBootMutex.withLock { block() }
    }

    internal enum class AlarmHandling {
        TRIGGERED,
        MISSED,
        IGNORED,
    }
}
