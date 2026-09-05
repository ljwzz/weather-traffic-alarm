package com.ljwzz.weathertrafficalarm.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CalendarRefreshPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory get() = File(context.filesDir, "holiday-calendar")
    private val diagnosticFile get() = File(directory, "refresh-diagnostics.json")
    private val clock = FakeClock()
    private val requests = mutableListOf<String>()
    private var response: (String) -> String = { throw IOException("private response body") }

    @Before fun setUp() { directory.deleteRecursively() }
    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun cacheHitIsDiagnosedWithoutRequestingASource() = runTest {
        seedCache()
        val repository = repository(backgroundScope)

        assertFalse(repository.refresh())

        assertTrue(requests.isEmpty())
        val diagnostic = repository.state.value.diagnostics.single()
        assertEquals(CalendarRefreshOutcome.SUCCESS, diagnostic.outcome)
        assertEquals(listOf(2026), diagnostic.cacheHitYears)
        assertTrue(diagnostic.attempts.isEmpty())
        assertEquals(listOf(diagnostic), HolidayCalendarRefreshStore(directory).read().diagnostics)
    }

    @Test fun fallbackRecordsFailureCategoriesAndElapsedTimeWithoutResponseDetails() = runTest {
        seedCache()
        response = { url ->
            clock.elapsed += 25
            when (URL(url).host) {
                RAW -> throw SocketTimeoutException("private timeout response")
                CDN -> throw HolidayCalendarHttpException(503)
                else -> "private invalid response body"
            }
        }
        val repository = repository(backgroundScope)

        assertFalse(repository.refresh(force = true))

        val diagnostic = repository.state.value.diagnostics.single()
        assertEquals(CalendarRefreshOutcome.FAILED, diagnostic.outcome)
        assertEquals(CalendarRefreshFailure.VALIDATION, diagnostic.failure)
        assertEquals(listOf(CalendarRefreshFailure.TIMEOUT, CalendarRefreshFailure.HTTP, CalendarRefreshFailure.VALIDATION), diagnostic.attempts.map { it.failure })
        assertEquals(75L, diagnostic.durationMillis)
        assertTrue(diagnostic.attempts.all { it.durationMillis == 25L && it.consecutiveFailures == 1 })
        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2026-01-01"])
        assertFalse(diagnosticFile.readText().contains("private"))
        assertFalse(repository.state.value.error.orEmpty().contains("private"))
        assertFalse(repository.state.value.loading)
    }

    @Test fun fourthDailyFailureBlocksFurtherRequestsAcrossRepositoryRecreationAndForce() = runTest {
        val repository = repository(backgroundScope)
        repeat(4) { repository.refresh(force = true) }
        assertEquals(12, requests.size)
        assertTrue(repository.state.value.diagnostics.last().attempts.all { it.dailyFailures == 4 })

        val restarted = repository(backgroundScope)
        restarted.refresh(force = true)
        restarted.refresh()

        assertEquals(12, requests.size)
        val diagnostic = restarted.state.value.diagnostics.last()
        assertEquals(CalendarRefreshFailure.RATE_LIMITED, diagnostic.failure)
        assertEquals(6, diagnostic.consecutiveFailures)
        assertTrue(diagnostic.attempts.all { it.outcome == CalendarSourceOutcome.SKIPPED_LIMIT && it.dailyFailures == 4 })
        assertEquals(6, HolidayCalendarRefreshStore(directory).read().diagnostics.size)
    }

    @Test fun nextDayAutomaticallyRestoresRequestsAndResetsDailyBudget() = runTest {
        val repository = repository(backgroundScope)
        repeat(4) { repository.refresh() }
        clock.date = clock.date.plusDays(1)
        response = { document() }

        assertTrue(repository.refresh())

        assertEquals(13, requests.size)
        val diagnostic = repository.state.value.diagnostics.last()
        assertEquals(CalendarRefreshOutcome.SUCCESS, diagnostic.outcome)
        assertEquals(0, diagnostic.consecutiveFailures)
        assertEquals(0, diagnostic.attempts.single().dailyFailures)
        assertEquals(0, diagnostic.attempts.single().consecutiveFailures)
        assertEquals(clock.date.toString(), HolidayCalendarRefreshStore(directory).read().sources.getValue(RAW).day)
    }

    @Test fun sourceBudgetIsSharedByCurrentAndNextYear() = runTest {
        clock.date = LocalDate.of(2026, 10, 1)
        val repository = repository(backgroundScope)
        repeat(2) { repository.refresh() }
        assertEquals(12, requests.size)
        assertTrue(requests.any { it.endsWith("2027.json") })

        repository.refresh(force = true)

        assertEquals(12, requests.size)
        assertEquals(6, repository.state.value.diagnostics.last().attempts.size)
        assertTrue(repository.state.value.diagnostics.last().attempts.all { it.outcome == CalendarSourceOutcome.SKIPPED_LIMIT })
    }

    @Test fun limitingOneSourceStillAllowsFallbackToOtherSources() = runTest {
        response = { url -> if (URL(url).host == RAW) throw IOException() else document() }
        val repository = repository(backgroundScope)
        repeat(4) { repository.refresh(force = true) }
        response = { url -> if (URL(url).host == CDN) throw IOException() else document() }

        repository.refresh(force = true)

        val attempts = repository.state.value.diagnostics.last().attempts
        assertEquals(listOf(CalendarSourceOutcome.SKIPPED_LIMIT, CalendarSourceOutcome.FAILED, CalendarSourceOutcome.SUCCESS), attempts.map { it.outcome })
        assertEquals(listOf(4, 1, 0), attempts.map { it.dailyFailures })
        assertEquals(4, requests.count { URL(it).host == RAW })
        assertNull(repository.state.value.error)
    }

    @Test fun successfulRequestResetsStreakButRetainsAccumulatedDailyFailures() = runTest {
        val repository = repository(backgroundScope)
        repository.refresh()
        response = { document() }
        repository.refresh()
        response = { url -> if (URL(url).host == RAW) throw IOException() else document() }

        repository.refresh(force = true)

        val diagnostic = repository.state.value.diagnostics.last()
        assertEquals(0, diagnostic.consecutiveFailures)
        assertEquals(1, diagnostic.attempts.first().consecutiveFailures)
        assertEquals(2, diagnostic.attempts.first().dailyFailures)
    }

    @Test fun blockedMirrorsDoNotHideAnActualHttpFailure() = runTest {
        HolidayCalendarRefreshStore(directory).write(CalendarRefreshHistory(sources = mapOf(
            CDN to CalendarSourceFailures(clock.date.toString(), 4, 4),
            FASTLY to CalendarSourceFailures(clock.date.toString(), 4, 4),
        )))
        response = { throw HolidayCalendarHttpException(502) }
        val repository = repository(backgroundScope)

        repository.refresh()

        assertEquals(1, requests.size)
        assertEquals(CalendarRefreshFailure.HTTP, repository.state.value.diagnostics.single().failure)
    }

    @Test fun cacheCanStillBeUsedAfterSourcesReachTheirLimit() = runTest {
        val repository = repository(backgroundScope)
        repeat(4) { repository.refresh() }
        seedCache()

        repository.refresh()

        assertEquals(12, requests.size)
        assertEquals(CalendarRefreshOutcome.SUCCESS, repository.state.value.diagnostics.last().outcome)
        assertNull(repository.state.value.error)
        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2026-01-01"])
    }

    @Test fun concurrentRefreshesCannotExceedTheSharedDailySourceLimit() = runTest {
        val repository = repository(backgroundScope)

        List(6) { async(Dispatchers.Default) { repository.refresh(force = true) } }.awaitAll()

        assertEquals(12, requests.size)
        assertEquals(6, repository.state.value.diagnostics.size)
        assertTrue(HolidayCalendarRefreshStore(directory).read().sources.values.all { it.dailyFailures == 4 })
    }

    @Test fun cancellationDoesNotConsumeSourceBudgetAndAlwaysClearsLoading() = runTest {
        response = { throw CancellationException("private cancellation message") }
        val repository = repository(backgroundScope)

        try {
            repository.refresh()
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(repository.state.value.loading)
            assertEquals(CalendarRefreshFailure.CANCELLED, repository.state.value.diagnostics.single().failure)
            assertTrue(HolidayCalendarRefreshStore(directory).read().sources.isEmpty())
            assertFalse(diagnosticFile.readText().contains("private"))
        }
    }

    @Test fun cacheWriteFailureRetainsOldDataAndRecordsStorageFailure() = runTest {
        seedCache()
        File(directory, "2026.json.tmp").mkdir()
        response = { document(isOffDay = false) }
        val repository = repository(backgroundScope)

        assertFalse(repository.refresh(force = true))

        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2026-01-01"])
        assertEquals(CalendarRefreshFailure.STORAGE, repository.state.value.diagnostics.single().failure)
        assertEquals(0, HolidayCalendarRefreshStore(directory).read().sources.getValue(RAW).dailyFailures)
        assertFalse(repository.state.value.loading)
    }

    @Test fun diagnosticWriteFailureStillPublishesUsableCalendarAndRecoversOnNextRefresh() = runTest {
        val blockedTemporary = File(directory, "refresh-diagnostics.json.tmp").apply { mkdirs() }
        File(blockedTemporary, "occupied").writeText("occupied")
        response = { document() }
        val repository = repository(backgroundScope)

        assertTrue(repository.refresh())

        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2026-01-01"])
        assertEquals(CalendarRefreshFailure.STORAGE, repository.state.value.diagnostics.single().failure)
        assertFalse(repository.state.value.loading)
        blockedTemporary.deleteRecursively()
        repository.refresh()
        assertNull(repository.state.value.error)
        assertEquals(2, HolidayCalendarRefreshStore(directory).read().diagnostics.size)
    }

    private fun repository(scope: CoroutineScope) = WorkdayCalendarRepository(
        context, clock, HolidayCalendarTransport { url -> requests += url; response(url) }, scope,
    )

    private fun seedCache() {
        directory.mkdirs()
        File(directory, "2026.json").apply { writeText(document()); setLastModified(clock.currentTimeMillis()) }
    }

    private fun document(isOffDay: Boolean = true) =
        """{"year":2026,"papers":["https://www.gov.cn/notice"],"days":[{"name":"元旦","date":"2026-01-01","isOffDay":$isOffDay}]}"""

    private class FakeClock : HolidayCalendarClock {
        var date: LocalDate = LocalDate.of(2026, 9, 3)
        var elapsed = 0L
        override fun today(): LocalDate = date
        override fun currentTimeMillis(): Long = date.toEpochDay() * 86_400_000
        override fun elapsedRealtimeMillis(): Long = elapsed
    }

    private companion object {
        const val RAW = "raw.githubusercontent.com"
        const val CDN = "cdn.jsdelivr.net"
        const val FASTLY = "fastly.jsdelivr.net"
    }
}
