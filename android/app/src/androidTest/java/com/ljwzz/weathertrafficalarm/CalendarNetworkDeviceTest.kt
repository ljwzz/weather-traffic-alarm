package com.ljwzz.weathertrafficalarm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in network smoke test; ordinary tests remain deterministic and offline. */
@RunWith(AndroidJUnit4::class)
class CalendarNetworkDeviceTest {
    @Test fun fetchesValidatedHolidayCalendarOnDevice() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNetwork") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deps = EntryPointAccessors.fromApplication(instrumentation.targetContext, DeviceTestDependencies::class.java)
        deps.calendar().refresh(force = true)
        val state = deps.calendar().state.value
        assertNull(state.error, state.error)
        assertTrue(state.loaded)
        assertTrue(state.days.isNotEmpty())
        assertNotNull(state.fetchedAt)
        assertTrue(state.sourceUrl?.startsWith("https://") == true)
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("validatedDays", state.days.size)
            putString("source", state.sourceUrl)
        })
    }
}
