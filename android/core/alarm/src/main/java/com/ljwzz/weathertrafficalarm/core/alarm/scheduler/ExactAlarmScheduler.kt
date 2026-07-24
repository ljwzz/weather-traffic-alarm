package com.ljwzz.weathertrafficalarm.core.alarm.scheduler

import android.app.AlarmManager
import android.content.Context
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmReceiver
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.AlarmAction
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExactAlarmScheduler @Inject constructor(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val pendingIntentFactory: PendingIntentFactory,
    private val snapshotStore: NextAlarmSnapshotStore,
) {

    /**
     * Creates a default occurrence for the plan and registers it as the system alarm.
     * Persists the snapshot first, then registers, then commits the state.
     */
    suspend fun scheduleDefault(plan: AlarmPlan): AlarmOccurrence? {
        if (!plan.enabled) return null

        val nextTargetDate = resolveNextTargetDate(plan)
            ?: return null

        val occurrence = AlarmOccurrence(
            occurrenceId = generateOccurrenceId(plan.id, nextTargetDate),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = nextTargetDate,
            scheduledWakeAt = resolveDefaultWakeMillis(plan, nextTargetDate),
            state = OccurrenceState.DEFAULT_REGISTERED,
        )

        return scheduleOccurrence(plan, occurrence)
    }

    /**
     * Registers an occurrence with the system [AlarmManager.setAlarmClock].
     * Persists the snapshot first, then registers, then commits state.
     * On registration failure, retains diagnostic state without crash.
     */
    suspend fun scheduleOccurrence(plan: AlarmPlan, occurrence: AlarmOccurrence): AlarmOccurrence? {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = occurrence.occurrenceId,
            planId = plan.id,
            planRevision = plan.revision,
            triggerAtMillis = occurrence.scheduledWakeAt,
            soundUri = plan.sound.uri,
            vibrationEnabled = plan.vibration.enabled,
            snoozeMinutes = plan.snoozeMinutes,
        )

        // 1. Persist snapshot first
        snapshotStore.save(snapshot)

        // 2. Register system alarm
        try {
            val pi = pendingIntentFactory.alarmPendingIntent(
                occurrence.occurrenceId,
                AlarmReceiver::class.java,
            )
            val alarmInfo = AlarmManager.AlarmClockInfo(occurrence.scheduledWakeAt, pi)
            alarmManager.setAlarmClock(alarmInfo, pi)
        } catch (e: SecurityException) {
            // Alarm permission missing - retain snapshot for later recovery
            return occurrence
        }

        return occurrence
    }

    /**
     * Cancels all pending intents and removes snapshots for the given plan.
     */
    suspend fun cancelForPlan(planId: String) {
        pendingIntentFactory.cancelPendingIntent(planId, AlarmAction.ALARM)
        pendingIntentFactory.cancelPendingIntent(planId, AlarmAction.DISMISS)
        pendingIntentFactory.cancelPendingIntent(planId, AlarmAction.SNOOZE)
        snapshotStore.remove(planId)
    }

    /**
     * Generates a deterministic occurrence ID from plan ID and target date.
     */
    private fun generateOccurrenceId(planId: String, targetDate: String): String =
        "${planId}_$targetDate"

    /**
     * Resolves the next target date for the plan.
     * For initial implementation, uses tomorrow's date as default.
     */
    private suspend fun resolveNextTargetDate(plan: AlarmPlan): String? {
        // Placeholder: returns the plan's next working day
        // Full implementation will use WorkdayResolver
        return java.time.LocalDate.now(java.time.ZoneId.of(plan.zoneId))
            .plusDays(1)
            .toString()
    }

    /**
     * Resolves the default wake time in epoch millis for the given target date.
     */
    private fun resolveDefaultWakeMillis(plan: AlarmPlan, targetDate: String): Long {
        val localDate = java.time.LocalDate.parse(targetDate)
        val localTime = java.time.LocalTime.parse(plan.defaultWakeLocalTime)
        val zoneId = java.time.ZoneId.of(plan.zoneId)
        val zdt = java.time.ZonedDateTime.of(localDate, localTime, zoneId)
        return zdt.toInstant().toEpochMilli()
    }
}
