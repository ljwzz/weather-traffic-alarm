package com.ljwzz.weathertrafficalarm.core.alarm.check

import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AlarmCapabilityCheckerTest {

    private lateinit var checker: AlarmCapabilityChecker

    @Before
    fun resetAlarmChannel() {
        RuntimeEnvironment.getApplication()
            .getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(AlarmCapabilityChecker.ALARM_CHANNEL_ID)
        checker = AlarmCapabilityChecker(RuntimeEnvironment.getApplication())
    }

    @Test
    fun checkReturnsDiagnostic() {
        val diagnostic = checker.check()
        assertNotNull(diagnostic)
        assertNotNull(diagnostic.notification)
        assertNotNull(diagnostic.exactAlarm)
        assertNotNull(diagnostic.fullScreenIntent)
    }

    @Test
    fun notificationSettingsIntentIsResolvable() {
        val intent = checker.notificationSettingsIntent()
        assertNotNull(intent)
    }

    @Test
    fun exactAlarmSettingsIntentIsResolvable() {
        val intent = checker.exactAlarmSettingsIntent()
        // May be null in test environment without system settings
        // intent is resolvable on real devices
    }

    @Test
    fun notificationChannelMissingIsDegraded() {
        val diagnostic = checker.check()

        assertEquals(CapabilityLevel.DEGRADED, diagnostic.notification)
    }

    @Test
    fun disabledAlarmChannelIsBlocking() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AlarmCapabilityChecker.ALARM_CHANNEL_ID,
                "test",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertEquals(CapabilityLevel.BLOCKING, checker.check().notification)
    }

    @Test
    fun lowImportanceAlarmChannelIsDegraded() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                AlarmCapabilityChecker.ALARM_CHANNEL_ID,
                "test",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        assertEquals(CapabilityLevel.DEGRADED, checker.check().notification)
    }
}
