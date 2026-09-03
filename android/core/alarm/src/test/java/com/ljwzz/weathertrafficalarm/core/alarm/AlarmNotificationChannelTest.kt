package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AlarmNotificationChannelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun removeChannel() {
        manager.deleteNotificationChannel(AlarmNotificationChannel.ID)
    }

    @Test
    fun createsTheInitialAlarmChannelConfiguration() {
        AlarmNotificationChannel.ensureCreated(context)

        val channel = requireNotNull(manager.getNotificationChannel(AlarmNotificationChannel.ID))
        assertEquals("闹钟响铃", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("本地闹钟响铃通知", channel.description)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test
    fun preservesAnExistingUserConfiguredChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                AlarmNotificationChannel.ID,
                "用户已配置",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        AlarmNotificationChannel.ensureCreated(context)

        val channel = requireNotNull(manager.getNotificationChannel(AlarmNotificationChannel.ID))
        assertEquals("用户已配置", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
    }
}
