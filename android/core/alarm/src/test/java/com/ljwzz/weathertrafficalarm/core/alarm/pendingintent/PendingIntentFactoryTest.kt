package com.ljwzz.weathertrafficalarm.core.alarm.pendingintent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingIntentFactoryTest {

    private lateinit var factory: PendingIntentFactory
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        factory = PendingIntentFactory(context)
    }

    @Test
    fun alarmPendingIntentIsCreated() {
        val pi = factory.alarmPendingIntent("occ-1", StubActivity::class.java)
        assertNotNull(pi)
    }

    @Test
    fun dismissPendingIntentIsCreated() {
        val pi = factory.dismissPendingIntent("occ-1", StubReceiver::class.java)
        assertNotNull(pi)
    }

    @Test
    fun snoozePendingIntentIsCreated() {
        val pi = factory.snoozePendingIntent("occ-1", StubReceiver::class.java)
        assertNotNull(pi)
    }

    @Test
    fun fullScreenPendingIntentIsCreated() {
        val pi = factory.fullScreenPendingIntent("occ-1", StubActivity::class.java)
        assertNotNull(pi)
    }

    @Test
    fun differentOccurrencesHaveDifferentDataUris() {
        val intent1 = factory.createAlarmIntent("occ-1", StubActivity::class.java)
        val intent2 = factory.createAlarmIntent("occ-2", StubActivity::class.java)
        assertEquals(intent1.javaClass, intent2.javaClass)
        assertNotNull(intent1.data)
        assertNotNull(intent2.data)
    }

    @Test
    fun sameOccurrenceSameActionReturnsSameIntent() {
        val intent1 = factory.createAlarmIntent("occ-1", StubActivity::class.java)
        val intent2 = factory.createAlarmIntent("occ-1", StubActivity::class.java)
        assertEquals(intent1.data, intent2.data)
    }

    class StubActivity : android.app.Activity()
    class StubReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {}
    }
}
