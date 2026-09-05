package com.ljwzz.weathertrafficalarm.core.data.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedactingEventLoggerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deviceContext get() = context.createDeviceProtectedStorageContext()
    private val directory get() = File(deviceContext.noBackupFilesDir, "diagnostics")

    @Before
    fun setUp() {
        directory.deleteRecursively()
        RedactingEventLogger.resetSharedStateForTests(context)
    }

    @After
    fun tearDown() {
        RedactingEventLogger.resetSharedStateForTests(context)
        directory.deleteRecursively()
    }

    @Test
    fun retainsOnlyTheMostRecentTwoHundredEventsInOldestToNewestOrder() = kotlinx.coroutines.test.runTest {
        val logger = RedactingEventLogger(context)
        repeat(201) { index ->
            logger.record(DiagnosticEventType.EVALUATION, DiagnosticResultCode.SUCCESS, timestamp = index.toLong())
        }

        val events = logger.recentEvents()
        assertEquals(200, events.size)
        assertEquals(1L, events.first().timestamp)
        assertEquals(200L, events.last().timestamp)
        assertEquals(200, DiagnosticEventStore(deviceContext).read().size)
    }

    @Test
    fun persistsAcrossLoggerRecreation() = kotlinx.coroutines.test.runTest {
        RedactingEventLogger(context).record(
            eventType = DiagnosticEventType.ALARM_TRIGGER,
            resultCode = DiagnosticResultCode.SUCCESS,
            planId = "plan-123",
            occurrenceId = "occurrence-123",
        )
        RedactingEventLogger.resetSharedStateForTests(context)

        val restored = RedactingEventLogger(context)
        assertEquals(DiagnosticEventType.ALARM_TRIGGER, restored.recentEvents().single().eventType)
    }

    @Test
    fun storesHashedIdentifiersAndNoMessageField() = kotlinx.coroutines.test.runTest {
        val planId = "plan-secret-value"
        val occurrenceId = "occurrence-secret-value"
        val logger = RedactingEventLogger(context)
        logger.record(
            eventType = DiagnosticEventType.ALARM_REGISTRATION,
            resultCode = DiagnosticResultCode.FAILED,
            planId = planId,
            occurrenceId = occurrenceId,
        )

        val event = DiagnosticEventStore(deviceContext).read().single()
        assertTrue(event.planIdHash.orEmpty().matches(Regex("[0-9a-f]{64}")))
        assertTrue(event.occurrenceIdHash.orEmpty().matches(Regex("[0-9a-f]{64}")))
        assertFalse(event.planIdHash.orEmpty().contains(planId))
        assertFalse(event.occurrenceIdHash.orEmpty().contains(occurrenceId))
        assertNull(DiagnosticEvent::class.java.declaredFields.singleOrNull { it.name == "message" })
        assertFalse(File(directory, "events.json").readText().contains(planId))
        assertFalse(File(directory, "events.json").readText().contains(occurrenceId))
    }

    @Test
    fun storageFailureDoesNotPreventInMemoryRecording() {
        directory.parentFile?.mkdirs()
        directory.writeText("not-a-directory")
        val logger = RedactingEventLogger(context)

        logger.record(DiagnosticEventType.ALARM_PLAYBACK, DiagnosticResultCode.UNREADABLE)

        assertEquals(1, logger.recentEvents().size)
    }

    @Test
    fun clearIsDurableAndSharedAcrossLoggerInstancesBeforeItReturns() {
        val first = RedactingEventLogger(context)
        val second = RedactingEventLogger(context)
        first.record(DiagnosticEventType.ALARM_TRIGGER, DiagnosticResultCode.SUCCESS)
        assertEquals(1, second.recentEvents().size)

        second.clear()

        assertTrue(first.recentEvents().isEmpty())
        assertTrue(second.recentEvents().isEmpty())
        assertTrue(DiagnosticEventStore(deviceContext).read().isEmpty())
        first.record(DiagnosticEventType.ALARM_DISMISS, DiagnosticResultCode.SUCCESS)
        assertEquals(listOf(DiagnosticEventType.ALARM_DISMISS), second.recentEvents().map { it.eventType })
    }

    @Test
    fun failedClearKeepsTheLiveAndRebuiltHistory() {
        val logger = RedactingEventLogger(context)
        logger.record(DiagnosticEventType.ALARM_TRIGGER, DiagnosticResultCode.SUCCESS)
        val lockPath = File(directory, "events.lock")
        assertTrue(lockPath.delete())
        assertTrue(lockPath.mkdirs())

        logger.clear()

        assertEquals(1, logger.recentEvents().size)
        lockPath.deleteRecursively()
        RedactingEventLogger.resetSharedStateForTests(context)
        assertEquals(1, RedactingEventLogger(context).recentEvents().size)
    }

    @Test
    fun concurrentInstancesDoNotLoseEvents() = kotlinx.coroutines.test.runTest {
        val first = RedactingEventLogger(context)
        val second = RedactingEventLogger(context)
        val started = CountDownLatch(2)
        val finished = CountDownLatch(2)
        repeat(2) { source ->
            Thread {
                started.countDown()
                check(started.await(2, TimeUnit.SECONDS))
                repeat(100) { index ->
                    val timestamp = (source * 100L) + index
                    if (source == 0) first.record(DiagnosticEventType.ALARM_CANCEL, DiagnosticResultCode.SUCCESS, timestamp = timestamp)
                    else second.record(DiagnosticEventType.ALARM_CANCEL, DiagnosticResultCode.SUCCESS, timestamp = timestamp)
                }
                finished.countDown()
            }.start()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))

        assertEquals(200, DiagnosticEventStore(deviceContext).read().size)
    }

    @Test
    fun concurrentClearAndRecordLeaveMemoryAndPersistentStateConsistent() {
        val logger = RedactingEventLogger(context)
        logger.record(DiagnosticEventType.ALARM_TRIGGER, DiagnosticResultCode.SUCCESS, timestamp = 1L)
        val started = CountDownLatch(2)
        val finished = CountDownLatch(2)
        val clearer = Thread {
            started.countDown()
            started.await(2, TimeUnit.SECONDS)
            logger.clear()
            finished.countDown()
        }
        val recorder = Thread {
            started.countDown()
            started.await(2, TimeUnit.SECONDS)
            logger.record(DiagnosticEventType.ALARM_DISMISS, DiagnosticResultCode.SUCCESS, timestamp = 2L)
            finished.countDown()
        }
        clearer.start()
        recorder.start()
        assertTrue(finished.await(5, TimeUnit.SECONDS))

        assertEquals(DiagnosticEventStore(deviceContext).read(), logger.recentEvents())
    }
}
