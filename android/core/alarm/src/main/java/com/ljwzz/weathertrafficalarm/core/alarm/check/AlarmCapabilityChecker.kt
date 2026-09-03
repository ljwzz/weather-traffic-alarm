package com.ljwzz.weathertrafficalarm.core.alarm.check

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmNotificationChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class CapabilityLevel {
    AVAILABLE,
    DEGRADED,
    BLOCKING,
}

data class CapabilityDiagnostic(
    val notification: CapabilityLevel,
    val exactAlarm: CapabilityLevel,
    val fullScreenIntent: CapabilityLevel,
)

@Singleton
class AlarmCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun check(): CapabilityDiagnostic {
        val notificationLevel = checkNotification()
        val exactAlarmLevel = checkExactAlarm()
        val fullScreenLevel = checkFullScreenIntent()
        return CapabilityDiagnostic(notificationLevel, exactAlarmLevel, fullScreenLevel)
    }

    private fun checkNotification(): CapabilityLevel {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return CapabilityLevel.BLOCKING
        }
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return CapabilityLevel.BLOCKING
        if (!nm.areNotificationsEnabled()) return CapabilityLevel.BLOCKING
        val channel = nm.getNotificationChannel(ALARM_CHANNEL_ID)
        if (channel == null) return CapabilityLevel.DEGRADED
        if (channel.group?.let { groupId -> nm.getNotificationChannelGroup(groupId)?.isBlocked } == true) {
            return CapabilityLevel.BLOCKING
        }
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return CapabilityLevel.BLOCKING
        }
        if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            return CapabilityLevel.DEGRADED
        }
        return CapabilityLevel.AVAILABLE
    }

    private fun checkExactAlarm(): CapabilityLevel {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            if (am?.canScheduleExactAlarms() != true) return CapabilityLevel.BLOCKING
        }
        return CapabilityLevel.AVAILABLE
    }

    private fun checkFullScreenIntent(): CapabilityLevel {
        if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.canUseFullScreenIntent() != true) return CapabilityLevel.DEGRADED
        }
        return CapabilityLevel.AVAILABLE
    }

    fun exactAlarmSettingsIntent(): Intent? {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    fun notificationSettingsIntent(): Intent? {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return intent
    }

    companion object {
        const val ALARM_CHANNEL_ID = AlarmNotificationChannel.ID
    }
}
