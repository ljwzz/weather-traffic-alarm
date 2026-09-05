package com.ljwzz.weathertrafficalarm.core.alarm.check

import android.net.Uri
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlarmRingtoneCheckerTest {
    private val selected = Uri.parse("content://sound/selected")
    private val fallback = Uri.parse("android.resource://app/raw/fallback")

    @Test
    fun doesNotProbeWhenNoPlanSoundIsConfigured() = runTest {
        var probeCount = 0
        val checker = checker { probeCount++; true }

        val result = checker.check(emptyList())

        assertEquals(RingtoneReadabilityResult.NOT_CONFIGURED, result.result)
        assertEquals(0, probeCount)
    }

    @Test
    fun reportsReadableWhenSelectedSoundCanBeOpened() = runTest {
        val checker = checker { uri -> uri == selected }

        val result = checker.check(listOf(AlarmSound(uri = selected.toString())))

        assertEquals(RingtoneReadabilityResult.READABLE, result.result)
        assertEquals(1, result.configuredSoundCount)
    }

    @Test
    fun reportsReadableWhenDefaultAlarmIsTheFirstReadableCandidate() = runTest {
        val checker = checker { uri -> uri == fallback }

        val result = checker.check(listOf(AlarmSound()))

        assertEquals(RingtoneReadabilityResult.READABLE, result.result)
    }

    @Test
    fun reportsDefaultFallbackWhenSelectedSoundCannotBeOpened() = runTest {
        val checker = checker { uri -> uri == fallback }

        val result = checker.check(listOf(AlarmSound(uri = selected.toString())))

        assertEquals(RingtoneReadabilityResult.DEFAULT_FALLBACK, result.result)
        assertEquals(1, result.fallbackCount)
    }

    @Test
    fun reportsUnreadableWhenNeitherSelectedNorFallbackCanBeOpened() = runTest {
        val checker = checker { false }

        val result = checker.check(listOf(AlarmSound(uri = selected.toString())))

        assertEquals(RingtoneReadabilityResult.UNREADABLE, result.result)
        assertEquals(1, result.unreadableCount)
    }

    private fun checker(readable: (Uri) -> Boolean) = AlarmRingtoneChecker(
        candidatesFor = { selectedSound -> listOfNotNull(selectedSound, fallback).distinct() },
        isReadable = readable,
    )
}
