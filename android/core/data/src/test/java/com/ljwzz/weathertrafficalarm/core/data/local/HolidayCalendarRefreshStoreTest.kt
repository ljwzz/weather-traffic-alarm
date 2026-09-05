package com.ljwzz.weathertrafficalarm.core.data.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class HolidayCalendarRefreshStoreTest {
    private val directory: File = Files.createTempDirectory("calendar-refresh-diagnostics").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun persistsHistoryAcrossStoreRecreation() {
        val history = CalendarRefreshHistory(
            sources = mapOf(
                "raw.githubusercontent.com" to CalendarSourceFailures(
                    day = "2026-09-03",
                    dailyFailures = 2,
                    consecutiveFailures = 4,
                ),
            ),
            consecutiveFailures = 4,
            diagnostics = listOf(
                CalendarRefreshDiagnostic(
                    startedAt = 1_700_000_000_000L,
                    durationMillis = 123L,
                    outcome = CalendarRefreshOutcome.FAILED,
                    failure = CalendarRefreshFailure.NETWORK,
                    consecutiveFailures = 4,
                    cacheHitYears = listOf(2026),
                    refreshedYears = listOf(2027),
                    attempts = listOf(
                        CalendarSourceAttempt(
                            year = 2027,
                            sourceHost = "raw.githubusercontent.com",
                            outcome = CalendarSourceOutcome.FAILED,
                            failure = CalendarRefreshFailure.NETWORK,
                            durationMillis = 123L,
                            consecutiveFailures = 4,
                            dailyFailures = 2,
                        ),
                    ),
                ),
            ),
        )

        HolidayCalendarRefreshStore(directory).write(history)

        assertEquals(history, HolidayCalendarRefreshStore(directory).read())
    }

    @Test
    fun keepsOnlyTheLatestHundredDiagnostics() {
        val diagnostics = (0..101).map { index -> diagnostic(index.toLong()) }

        HolidayCalendarRefreshStore(directory).write(
            CalendarRefreshHistory(diagnostics = diagnostics),
        )

        assertEquals(diagnostics.takeLast(100), HolidayCalendarRefreshStore(directory).read().diagnostics)
    }

    @Test
    fun corruptFileFallsBackToAnEmptyHistory() {
        directory.mkdirs()
        File(directory, "refresh-diagnostics.json").writeText("{not valid json")

        assertEquals(CalendarRefreshHistory(), HolidayCalendarRefreshStore(directory).read())
    }

    @Test
    fun failedSaveLeavesTheExistingHistoryReadable() {
        val store = HolidayCalendarRefreshStore(directory)
        val existing = CalendarRefreshHistory(diagnostics = listOf(diagnostic(1L)))
        store.write(existing)
        assertTrue(File(directory, "refresh-diagnostics.json.tmp").mkdir())

        assertThrows(IOException::class.java) {
            store.write(CalendarRefreshHistory(diagnostics = listOf(diagnostic(2L))))
        }

        assertEquals(existing, store.read())
    }

    @Test
    fun diagnosticSchemaDoesNotContainResponseOrExceptionDetailFields() {
        val serialized = Json.encodeToString(
            diagnostic(1L).copy(failure = CalendarRefreshFailure.HTTP),
        )

        assertTrue(serialized.contains("\"failure\""))
        assertFalse(serialized.contains("responseBody"))
        assertFalse(serialized.contains("exceptionMessage"))
    }

    private fun diagnostic(startedAt: Long): CalendarRefreshDiagnostic = CalendarRefreshDiagnostic(
        startedAt = startedAt,
        durationMillis = 1L,
        outcome = CalendarRefreshOutcome.SUCCESS,
        consecutiveFailures = 0,
    )
}
