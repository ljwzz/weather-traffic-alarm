package com.ljwzz.weathertrafficalarm.core.alarm.pendingintent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
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
    fun findPendingIntentMatchesEachActionWithItsOriginalComponentAndFlags() {
        val occurrenceId = "occ-1"

        val alarm = factory.alarmPendingIntent(occurrenceId, StubReceiver::class.java)
        val dismiss = factory.dismissPendingIntent(occurrenceId, StubReceiver::class.java)
        val snooze = factory.snoozePendingIntent(occurrenceId, StubReceiver::class.java)
        val fullScreen = factory.fullScreenPendingIntent(occurrenceId, StubActivity::class.java)

        assertPendingIntent(
            factory.findPendingIntent(occurrenceId, AlarmAction.ALARM, StubReceiver::class.java),
            isBroadcast = true,
            isOneShot = false,
            componentClass = StubReceiver::class.java,
        )
        assertPendingIntent(
            factory.findPendingIntent(occurrenceId, AlarmAction.DISMISS, StubReceiver::class.java),
            isBroadcast = true,
            isOneShot = false,
            componentClass = StubReceiver::class.java,
        )
        assertPendingIntent(
            factory.findPendingIntent(occurrenceId, AlarmAction.SNOOZE, StubReceiver::class.java),
            isBroadcast = true,
            isOneShot = false,
            componentClass = StubReceiver::class.java,
        )
        assertPendingIntent(
            factory.findPendingIntent(occurrenceId, AlarmAction.FULL_SCREEN, StubActivity::class.java),
            isBroadcast = false,
            isOneShot = false,
            componentClass = StubActivity::class.java,
        )

        assertFalse(Shadows.shadowOf(alarm).isCanceled)
        assertFalse(Shadows.shadowOf(dismiss).isCanceled)
        assertFalse(Shadows.shadowOf(snooze).isCanceled)
        assertFalse(Shadows.shadowOf(fullScreen).isCanceled)
    }

    @Test
    fun findPendingIntentReturnsNullWithoutCreatingMissingToken() {
        assertNull(
            factory.findPendingIntent("missing", AlarmAction.ALARM, StubReceiver::class.java),
        )
        assertNull(
            factory.findPendingIntent("missing", AlarmAction.ALARM, StubReceiver::class.java),
        )
    }

    @Test
    fun cancelPendingIntentCancelsEveryActionWithoutAffectingOtherTokens() {
        val target = factory.alarmPendingIntent("occ-target", StubReceiver::class.java)
        val dismiss = factory.dismissPendingIntent("occ-target", StubReceiver::class.java)
        val snooze = factory.snoozePendingIntent("occ-target", StubReceiver::class.java)
        val fullScreen = factory.fullScreenPendingIntent("occ-target", StubActivity::class.java)
        val otherOccurrence = factory.alarmPendingIntent("occ-other", StubReceiver::class.java)
        val otherComponent = factory.alarmPendingIntent("occ-target", OtherReceiver::class.java)

        factory.cancelPendingIntent("occ-target", AlarmAction.ALARM, StubReceiver::class.java)
        factory.cancelPendingIntent("occ-target", AlarmAction.DISMISS, StubReceiver::class.java)
        factory.cancelPendingIntent("occ-target", AlarmAction.SNOOZE, StubReceiver::class.java)
        factory.cancelPendingIntent("occ-target", AlarmAction.FULL_SCREEN, StubActivity::class.java)

        assertTrue(Shadows.shadowOf(target).isCanceled)
        assertTrue(Shadows.shadowOf(dismiss).isCanceled)
        assertTrue(Shadows.shadowOf(snooze).isCanceled)
        assertTrue(Shadows.shadowOf(fullScreen).isCanceled)
        assertNull(
            factory.findPendingIntent("occ-target", AlarmAction.ALARM, StubReceiver::class.java),
        )
        assertNull(
            factory.findPendingIntent("occ-target", AlarmAction.DISMISS, StubReceiver::class.java),
        )
        assertNull(
            factory.findPendingIntent("occ-target", AlarmAction.SNOOZE, StubReceiver::class.java),
        )
        assertNull(
            factory.findPendingIntent("occ-target", AlarmAction.FULL_SCREEN, StubActivity::class.java),
        )
        assertFalse(Shadows.shadowOf(otherOccurrence).isCanceled)
        assertFalse(Shadows.shadowOf(otherComponent).isCanceled)
        assertNotNull(
            factory.findPendingIntent("occ-other", AlarmAction.ALARM, StubReceiver::class.java),
        )
        assertNotNull(
            factory.findPendingIntent("occ-target", AlarmAction.ALARM, OtherReceiver::class.java),
        )
    }

    @Test
    fun differentOccurrencesHaveDifferentDataUris() {
        val intent1 = factory.createAlarmIntent("occ-1", StubActivity::class.java)
        val intent2 = factory.createAlarmIntent("occ-2", StubActivity::class.java)
        assertEquals(intent1.javaClass, intent2.javaClass)
        assertNotNull(intent1.data)
        assertNotNull(intent2.data)
        assertFalse(intent1.data == intent2.data)
    }

    @Test
    fun showAlarmIntentTargetsTheDirectBootSafeRingingActivity() {
        val pendingIntent = factory.showAlarmPendingIntent("occ-1")
        val saved = Shadows.shadowOf(pendingIntent).savedIntent

        assertTrue(Shadows.shadowOf(pendingIntent).isImmutable)
        assertEquals(PendingIntentFactory.ACTION_SHOW_ALARM, saved.action)
        assertEquals("occ-1", saved.getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID))
        assertEquals(
            "com.ljwzz.weathertrafficalarm.ui.zhitu.AlarmRingingActivity",
            saved.component?.className,
        )
    }

    @Test
    fun sameOccurrenceSameActionReturnsSameIntent() {
        val intent1 = factory.createAlarmIntent("occ-1", StubActivity::class.java)
        val intent2 = factory.createAlarmIntent("occ-1", StubActivity::class.java)
        assertEquals(intent1.data, intent2.data)
    }

    private fun assertPendingIntent(
        pendingIntent: PendingIntent?,
        isBroadcast: Boolean,
        isOneShot: Boolean,
        componentClass: Class<*>,
    ) {
        assertNotNull(pendingIntent)
        val shadow = Shadows.shadowOf(requireNotNull(pendingIntent))
        assertEquals(isBroadcast, shadow.isBroadcast)
        assertTrue(shadow.isImmutable)
        assertEquals(isOneShot, shadow.flags and PendingIntent.FLAG_ONE_SHOT != 0)
        assertEquals(componentClass.name, shadow.savedIntent.component?.className)
    }

    class StubActivity : android.app.Activity()
    class StubReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {}
    }

    class OtherReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {}
    }
}
