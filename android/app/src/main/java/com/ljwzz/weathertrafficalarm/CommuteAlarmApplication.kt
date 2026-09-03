package com.ljwzz.weathertrafficalarm

import android.app.Application
import android.os.UserManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmNotificationChannel
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationWorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class CommuteAlarmApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var evaluationScheduler: Provider<EvaluationWorkScheduler>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AlarmNotificationChannel.ensureCreated(this)
        if (getSystemService(UserManager::class.java).isUserUnlocked) evaluationScheduler.get().start()
    }
}
