package com.ljwzz.weathertrafficalarm.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmEventDao
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmEventEntity
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedDiagnosticsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cache get() = File(context.filesDir, "holiday-calendar")
    private val logFile get() = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "diagnostics/events.json")
    private lateinit var logger: RedactingEventLogger

    @Before fun setUp() {
        cache.deleteRecursively()
        logger = RedactingEventLogger(context).also { it.clear() }
    }

    @After fun tearDown() {
        cache.deleteRecursively()
        logger.clear()
    }

    @Test fun calendarWritesSuccessCacheAndFailureToTheSharedRecordSource() = runTest {
        val clock = TestClock()
        var fail = false
        val repository = WorkdayCalendarRepository(context, clock, HolidayCalendarTransport {
            clock.elapsed += 7
            if (fail) throw IOException(PRIVATE_TEXT)
            """{"year":2026,"papers":["https://www.gov.cn/notice"],"days":[{"name":"元旦","date":"2026-01-01","isOffDay":true}]}"""
        }, backgroundScope, logger)

        repository.refresh()
        repository.refresh()
        fail = true
        repository.refresh(force = true)

        val events = logger.recentEvents()
        assertEquals(listOf(DiagnosticResultCode.SUCCESS, DiagnosticResultCode.CACHE_HIT, DiagnosticResultCode.NETWORK), events.map { it.resultCode })
        assertTrue(events.all { it.eventType == DiagnosticEventType.CALENDAR_REFRESH })
        assertEquals(listOf(7L, 0L, 21L), events.map { it.durationMs })
        assertFalse(logFile.readText().contains(PRIVATE_TEXT))
    }

    @Test fun calendarCancellationIsDiagnosedAndStillPropagates() = runTest {
        val repository = WorkdayCalendarRepository(context, TestClock(), HolidayCalendarTransport {
            throw CancellationException(PRIVATE_TEXT)
        }, backgroundScope, logger)

        try {
            repository.refresh()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(DiagnosticResultCode.CANCELLED, logger.recentEvents().single().resultCode)
            assertFalse(logFile.readText().contains(PRIVATE_TEXT))
        }
    }

    @Test fun alarmRepositoryRecordsEveryLifecycleTypeWithoutCopyingItsMessage() = runTest {
        val saved = mutableListOf<AlarmEventEntity>()
        val repository = AlarmEventRepository(object : AlarmEventDao {
            override suspend fun upsert(event: AlarmEventEntity) { saved += event }
            override fun observeAll() = flowOf(saved.toList())
            override suspend fun deleteOlderThan(cutoffMillis: Long) = Unit
        }, logger)

        AlarmEventType.entries.forEach {
            repository.record("private-plan", "private-occurrence", it, PRIVATE_TEXT)
        }

        assertEquals(AlarmEventType.entries.size, saved.size)
        assertEquals(AlarmEventType.entries.size, logger.recentEvents().size)
        val serialized = logFile.readText()
        listOf(PRIVATE_TEXT, "private-plan", "private-occurrence", "message").forEach { assertFalse(serialized.contains(it)) }
        val expectedFields = setOf("eventType", "resultCode", "appVersion", "sdkInt", "planIdHash", "occurrenceIdHash", "durationMs", "timestamp")
        Json.parseToJsonElement(serialized).jsonArray.forEach { assertEquals(expectedFields, it.jsonObject.keys) }
    }

    private class TestClock : HolidayCalendarClock {
        var elapsed = 0L
        override fun today(): LocalDate = LocalDate.of(2026, 9, 5)
        override fun currentTimeMillis() = today().toEpochDay() * 86_400_000
        override fun elapsedRealtimeMillis() = elapsed
    }

    private companion object {
        const val PRIVATE_TEXT = "key=short-secret content://private/ringtone 北京 116.397"
    }
}
