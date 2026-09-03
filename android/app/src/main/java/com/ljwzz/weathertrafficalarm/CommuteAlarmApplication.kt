package com.ljwzz.weathertrafficalarm

import android.app.Application
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmNotificationChannel
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CommuteAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmNotificationChannel.ensureCreated(this)
    }
}
