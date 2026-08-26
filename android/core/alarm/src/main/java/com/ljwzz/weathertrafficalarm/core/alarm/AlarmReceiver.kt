package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory

/**
 * Receives the system alarm broadcast and shows a high-priority notification
 * that rings with the default alarm sound.
 *
 * Minimal demo implementation (ring via notification channel sound).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID) ?: return
        val planName = occurrenceId.substringBefore('_').takeIf { it.isNotBlank() } ?: "通勤闹钟"

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(notificationManager)

        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val contentPendingIntent = contentIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$planName:提前闹钟响铃")
            .setContentText("演示模式 · 未接入高德/彩云评估")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        runCatching { notificationManager.notify(occurrenceId.hashCode(), notification) }
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "闹钟响铃通知"
                setSound(
                    Settings.System.DEFAULT_ALARM_ALERT_URI ?: Uri.parse("content://settings/system/alarm_alert"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "alarm_ring"
        private const val CHANNEL_NAME = "闹钟响铃"
    }
}
