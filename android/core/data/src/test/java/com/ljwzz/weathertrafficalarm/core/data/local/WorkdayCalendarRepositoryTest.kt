package com.ljwzz.weathertrafficalarm.core.data.local

import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WorkdayCalendarRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var clock: FakeClock
    private lateinit var transport: FakeTransport

    @Before
    fun setUp() {
        cacheDirectory().deleteRecursively()
        clock = FakeClock(LocalDate.of(2026, 4, 1), System.currentTimeMillis())
        transport = FakeTransport()
    }

    @After
    fun tearDown() {
        cacheDirectory().deleteRecursively()
    }

    @Test
    fun refreshWritesValidatedCacheAndReturnsWhetherEffectiveDaysChanged() = runTest {
        transport.payloads["2026.json"] = document(2026, "2026-01-01", isOffDay = true)
        val repository = repository(backgroundScope)

        assertTrue(repository.refresh(force = true))
        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2026-01-01"])
        assertTrue(cacheFile(2026).isFile)
        assertNull(repository.state.value.error)

        assertFalse(repository.refresh(force = true))
        assertNull(repository.state.value.error)
    }

    @Test
    fun failedRefreshRetainsPreviousValidatedCache() = runTest {
        cacheFile(2026).apply {
            parentFile?.mkdirs()
            writeText(document(2026, "2026-05-09", isOffDay = false))
        }
        val repository = repository(backgroundScope)

        assertFalse(repository.refresh(force = true))
        assertEquals(DayStatus.WORKDAY, repository.statuses()["2026-05-09"])
        assertTrue(repository.state.value.error.orEmpty().contains("2026"))
        assertTrue(cacheFile(2026).readText().contains("2026-05-09"))
    }

    @Test
    fun corruptedFreshCacheIsReplacedAndLeavesNoTemporaryFile() = runTest {
        cacheFile(2026).apply {
            parentFile?.mkdirs()
            writeText("not-json")
            setLastModified(clock.currentTimeMillis())
        }
        transport.payloads["2026.json"] = document(2026, "2026-06-01", isOffDay = true)
        val repository = repository(backgroundScope)

        assertTrue(repository.refresh())
        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2026-06-01"])
        assertFalse(File(cacheDirectory(), "2026.json.tmp").exists())
        assertTrue(cacheFile(2026).readText().contains("2026-06-01"))
    }

    @Test
    fun decemberMergesFollowingNoticeYearAndRetainsPreviousCacheForCalendarNavigation() = runTest {
        clock = FakeClock(LocalDate.of(2026, 12, 15), System.currentTimeMillis())
        cacheFile(2025).apply {
            parentFile?.mkdirs()
            writeText(document(2025, "2025-12-31", isOffDay = true))
        }
        transport.payloads["2026.json"] = document(2026, "2026-12-31", isOffDay = true)
        // The 2027 notice is allowed to revise a December 2026 date.
        transport.payloads["2027.json"] = document(2027, "2026-12-31", isOffDay = false)
        val repository = repository(backgroundScope)

        assertTrue(repository.refresh(force = true))
        assertEquals(DayStatus.WORKDAY, repository.statuses()["2026-12-31"])
        assertEquals(DayStatus.HOLIDAY, repository.statuses()["2025-12-31"])
    }

    @Test
    fun initializationCleansOnlyCacheOlderThanPreviousNoticeYear() = runTest {
        cacheFile(2024).apply { parentFile?.mkdirs(); writeText(document(2024, "2024-01-01", true)) }
        cacheFile(2025).writeText(document(2025, "2025-01-01", true))
        val repository = repository(CoroutineScope(StandardTestDispatcher(testScheduler)))

        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!repository.state.value.loaded) delay(10)
            }
        }
        assertFalse(cacheFile(2024).exists())
        assertTrue(cacheFile(2025).exists())
    }

    @Test
    fun statusesReadsOldCacheWhileSlowRefreshIsDownloading() = runTest {
        cacheFile(2026).apply {
            parentFile?.mkdirs()
            writeText(document(2026, "2026-07-01", true))
        }
        val transport = BlockingTransport(document(2026, "2026-07-02", false))
        val repository = WorkdayCalendarRepository(context, clock, transport, backgroundScope)
        val refresh = launch(Dispatchers.Default) { repository.refresh(force = true) }
        assertTrue(transport.started.await(1, TimeUnit.SECONDS))

        val statuses = withContext(Dispatchers.Default) {
            withTimeout(5_000) { repository.statuses() }
        }
        assertEquals(DayStatus.HOLIDAY, statuses["2026-07-01"])

        transport.release.countDown()
        refresh.join()
    }

    private fun repository(scope: CoroutineScope): WorkdayCalendarRepository =
        WorkdayCalendarRepository(context, clock, transport, scope)

    private fun cacheDirectory(): File = File(context.filesDir, "holiday-calendar")
    private fun cacheFile(year: Int): File = File(cacheDirectory(), "$year.json")

    private fun document(year: Int, date: String, isOffDay: Boolean): String =
        """{"year":$year,"papers":["https://www.gov.cn/notice"],"days":[{"name":"节日","date":"$date","isOffDay":$isOffDay}]}"""

    private class FakeClock(private val date: LocalDate, private val now: Long) : HolidayCalendarClock {
        override fun today(): LocalDate = date
        override fun currentTimeMillis(): Long = now
    }

    private class FakeTransport : HolidayCalendarTransport {
        val payloads = mutableMapOf<String, String>()

        override fun get(url: String): String = payloads.entries
            .firstOrNull { url.endsWith(it.key) }
            ?.value
            ?: error("unavailable source")
    }

    private class BlockingTransport(private val payload: String) : HolidayCalendarTransport {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun get(url: String): String {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "test transport was not released" }
            return payload
        }
    }
}
