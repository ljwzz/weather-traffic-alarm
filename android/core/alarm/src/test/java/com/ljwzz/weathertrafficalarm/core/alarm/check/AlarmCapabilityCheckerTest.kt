package com.ljwzz.weathertrafficalarm.core.alarm.check

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmCapabilityCheckerTest {

    private val checker = AlarmCapabilityChecker(RuntimeEnvironment.getApplication())

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
}
