package com.ljwzz.weathertrafficalarm.core.alarm.scheduler

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmReceiver
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.AlarmAction
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

sealed interface AlarmRegistrationResult {
    data object Registered : AlarmRegistrationResult
    data class Rejected(
        val reason: RegistrationFailure,
        val detail: String? = null,
    ) : AlarmRegistrationResult
}

enum class RegistrationFailure {
    PAST_TRIGGER,
    EXACT_ALARM_PERMISSION,
    NOTIFICATIONS_DISABLED,
    PLATFORM_REJECTED,
}

/**
 * Thin platform gateway. It never decides which occurrence should be armed;
 * LocalAlarmCoordinator owns that decision and persists its occurrence before
 * calling this class. Keeping this class side-effect focused also makes it safe
 * to use from the Direct-Boot restorer with only a snapshot.
 */
@Singleton
class ExactAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
    private val pendingIntentFactory: PendingIntentFactory,
    private val snapshotStore: NextAlarmSnapshotStore,
) : AlarmSchedulingGateway {
    override suspend fun schedule(snapshot: NextAlarmSnapshot): AlarmRegistrationResult {
        if (snapshot.triggerAtMillis <= System.currentTimeMillis()) {
            return AlarmRegistrationResult.Rejected(RegistrationFailure.PAST_TRIGGER)
        }
        if (!canScheduleExactAlarms()) {
            return AlarmRegistrationResult.Rejected(RegistrationFailure.EXACT_ALARM_PERMISSION)
        }
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            return AlarmRegistrationResult.Rejected(RegistrationFailure.NOTIFICATIONS_DISABLED)
        }
        return try {
            val operation = pendingIntentFactory.alarmPendingIntent(
                snapshot.occurrenceId,
                AlarmReceiver::class.java,
            )
            val showIntent = pendingIntentFactory.showAlarmPendingIntent(snapshot.occurrenceId)
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(snapshot.triggerAtMillis, showIntent),
                operation,
            )
            AlarmRegistrationResult.Registered
        } catch (security: SecurityException) {
            AlarmRegistrationResult.Rejected(RegistrationFailure.PLATFORM_REJECTED, security.message)
        }
    }

    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Restore a still-valid Direct-Boot snapshot. */
    override suspend fun restore(snapshot: NextAlarmSnapshot, nowMillis: Long): AlarmRegistrationResult {
        if (snapshot.triggerAtMillis <= nowMillis) {
            return AlarmRegistrationResult.Rejected(RegistrationFailure.PAST_TRIGGER)
        }
        return schedule(snapshot)
    }

    override suspend fun cancelOccurrence(occurrenceId: String) {
        pendingIntentFactory.findPendingIntent(
            occurrenceId,
            AlarmAction.ALARM,
            AlarmReceiver::class.java,
        )?.let { pendingIntent ->
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        pendingIntentFactory.cancelPendingIntent(
            occurrenceId,
            AlarmAction.DISMISS,
            AlarmReceiver::class.java,
        )
        pendingIntentFactory.cancelPendingIntent(
            occurrenceId,
            AlarmAction.SNOOZE,
            AlarmReceiver::class.java,
        )
        pendingIntentFactory.cancelPendingIntent(
            occurrenceId,
            AlarmAction.SHOW_ALARM,
            AlarmReceiver::class.java,
        )
        snapshotStore.removeOccurrence(occurrenceId)
    }

    suspend fun cancelForPlan(planId: String) {
        val snapshots = snapshotStore.observeAll().first()
            .filter { it.planId == planId }
        snapshots.forEach { cancelOccurrence(it.occurrenceId) }
        // Also removes a legacy plan-keyed snapshot that may not be parseable as
        // an occurrence-keyed entry during an upgrade.
        snapshotStore.remove(planId)
    }

    /**
     * Compatibility path for the pre-local-alarm demo. New production flows
     * must create an occurrence and snapshot through LocalAlarmCoordinator.
     */
    suspend fun scheduleDefault(plan: AlarmPlan): AlarmOccurrence? {
        if (!plan.enabled) return null
        val targetDate = LocalDate.now(ZoneId.of(plan.zoneId)).plusDays(1)
        val triggerAt = targetDate.atTime(LocalTime.parse(plan.defaultWakeLocalTime))
            .atZone(ZoneId.of(plan.zoneId))
            .toInstant()
            .toEpochMilli()
        val occurrence = AlarmOccurrence(
            occurrenceId = "${plan.id}_$targetDate",
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = targetDate.toString(),
            scheduledWakeAt = triggerAt,
            state = OccurrenceState.DEFAULT_REGISTERED,
        )
        val snapshot = NextAlarmSnapshot(
            occurrenceId = occurrence.occurrenceId,
            planId = plan.id,
            planRevision = plan.revision,
            triggerAtMillis = triggerAt,
            soundUri = plan.sound.uri,
            vibrationEnabled = plan.vibration.enabled,
            vibrationPatternMillis = plan.vibration.patternMillis.toList(),
            snoozeMinutes = plan.snoozeMinutes,
            alarmLabel = plan.name,
        )
        snapshotStore.save(snapshot)
        return when (schedule(snapshot)) {
            AlarmRegistrationResult.Registered -> occurrence
            is AlarmRegistrationResult.Rejected -> {
                snapshotStore.removeOccurrence(occurrence.occurrenceId)
                null
            }
        }
    }
}
