package com.ljwzz.weathertrafficalarm.core.alarm.pendingintent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AlarmAction(val path: String) {
    ALARM("alarm"),
    DISMISS("dismiss"),
    SNOOZE("snooze"),
    FULL_SCREEN("full_screen"),
}

@Singleton
class PendingIntentFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun createAlarmIntent(occurrenceId: String, componentClass: Class<*>): Intent {
        return Intent(context, componentClass).apply {
            data = Uri.parse("alarm://occurrences/$occurrenceId?action=${AlarmAction.ALARM.path}")
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(EXTRA_ACTION, AlarmAction.ALARM.path)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun createDismissIntent(occurrenceId: String, componentClass: Class<*>): Intent {
        return Intent(context, componentClass).apply {
            data = Uri.parse("alarm://occurrences/$occurrenceId?action=${AlarmAction.DISMISS.path}")
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(EXTRA_ACTION, AlarmAction.DISMISS.path)
        }
    }

    fun createSnoozeIntent(occurrenceId: String, componentClass: Class<*>): Intent {
        return Intent(context, componentClass).apply {
            data = Uri.parse("alarm://occurrences/$occurrenceId?action=${AlarmAction.SNOOZE.path}")
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(EXTRA_ACTION, AlarmAction.SNOOZE.path)
        }
    }

    fun createFullScreenIntent(occurrenceId: String, componentClass: Class<*>): Intent {
        return Intent(context, componentClass).apply {
            data = Uri.parse("alarm://occurrences/$occurrenceId?action=${AlarmAction.FULL_SCREEN.path}")
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(EXTRA_ACTION, AlarmAction.FULL_SCREEN.path)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun alarmPendingIntent(occurrenceId: String, componentClass: Class<*>): PendingIntent {
        val intent = createAlarmIntent(occurrenceId, componentClass)
        val requestCode = generateRequestCode(occurrenceId, AlarmAction.ALARM)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun dismissPendingIntent(occurrenceId: String, componentClass: Class<*>): PendingIntent {
        val intent = createDismissIntent(occurrenceId, componentClass)
        val requestCode = generateRequestCode(occurrenceId, AlarmAction.DISMISS)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
    }

    fun snoozePendingIntent(occurrenceId: String, componentClass: Class<*>): PendingIntent {
        val intent = createSnoozeIntent(occurrenceId, componentClass)
        val requestCode = generateRequestCode(occurrenceId, AlarmAction.SNOOZE)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
    }

    fun fullScreenPendingIntent(occurrenceId: String, componentClass: Class<*>): PendingIntent {
        val intent = createFullScreenIntent(occurrenceId, componentClass)
        val requestCode = generateRequestCode(occurrenceId, AlarmAction.FULL_SCREEN)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun cancelPendingIntent(occurrenceId: String, action: AlarmAction) {
        val requestCode = generateRequestCode(occurrenceId, action)
        val intent = Intent().apply {
            data = Uri.parse("alarm://occurrences/$occurrenceId?action=${action.path}")
        }
        when (action) {
            AlarmAction.ALARM, AlarmAction.FULL_SCREEN ->
                PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE).cancel()
            AlarmAction.DISMISS, AlarmAction.SNOOZE ->
                PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE).cancel()
        }
    }

    companion object {
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_ACTION = "action"

        private fun generateRequestCode(occurrenceId: String, action: AlarmAction): Int {
            return (occurrenceId.hashCode() * 31 + action.ordinal) and 0x7FFFFFFF
        }
    }
}
