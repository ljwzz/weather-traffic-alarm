package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Owns the immutable initial configuration of the local alarm channel. */
object AlarmNotificationChannel {
    const val ID = "alarm_ringing"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                ID,
                "闹钟响铃",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "本地闹钟响铃通知"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}
