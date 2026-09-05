package com.ljwzz.weathertrafficalarm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmReceiver
import com.ljwzz.weathertrafficalarm.core.alarm.DiagnosticLoggerEntryPoint
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEvent
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production registration, receiver, dismissal and recovery paths as one log source. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsAlarmLifecycleDeviceTest {
    private lateinit var context: Context
    private lateinit var dependencies: DeviceTestDependencies
    private lateinit var logger: RedactingEventLogger
    private val ownedPlanIds = mutableListOf<String>()

    @Before
    fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dependencies = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        logger = EntryPointAccessors.fromApplication(context, DiagnosticLoggerEntryPoint::class.java).logger()
    }

    @After
    fun cleanUpOwnedPlan() = runBlocking {
        ownedPlanIds.asReversed().forEach { dependencies.coordinator().delete(it) }
    }

    @Test
    fun registrationTriggerDismissAndRecoveryShareDurableRedactedDiagnostics() = runBlocking {
        val secret = "diagnostic-secret-${UUID.randomUUID()}"
        val plan = upcomingPlan(secret)
        val saved = dependencies.coordinator().save(plan)
        assertEquals(AlarmArmedState.SCHEDULED, saved.armedState)
        val occurrence = scheduledOccurrence(plan.id)

        // Exercise recovery while the real device-protected snapshot is still armed.
        val recoveryStartedAt = System.currentTimeMillis()
        dependencies.coordinator().recover()

        // This is the exact broadcast identity registered with AlarmManager, sent now to avoid
        // the minute-long wall-clock wait of LocalAlarmDeviceTest.
        context.sendBroadcast(
            PendingIntentFactory(context).createAlarmIntent(occurrence.occurrenceId, AlarmReceiver::class.java),
        )
        await { dependencies.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.FIRING }
        await {
            logger.recentEvents().any {
                it.occurrenceIdHash == sha256(occurrence.occurrenceId) &&
                    it.eventType == DiagnosticEventType.ALARM_PLAYBACK &&
                    it.resultCode in setOf(DiagnosticResultCode.SUCCESS, DiagnosticResultCode.DEFAULT_FALLBACK)
            }
        }
        assertTrue(dependencies.coordinator().dismiss(occurrence.occurrenceId))
        await { dependencies.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.DISMISSED }

        val planHash = sha256(plan.id)
        val occurrenceHash = sha256(occurrence.occurrenceId)
        val expectedTypes = setOf(
            DiagnosticEventType.ALARM_REGISTRATION,
            DiagnosticEventType.ALARM_TRIGGER,
            DiagnosticEventType.ALARM_PLAYBACK,
            DiagnosticEventType.ALARM_DISMISS,
        )
        val inMemory = awaitEvents(logger, planHash, expectedTypes)
        assertDiagnosticsAreRedacted(inMemory, plan.id, occurrence.occurrenceId, planHash, occurrenceHash)
        assertRecoveryRecorded(logger, recoveryStartedAt)

        // A second logger reads device-protected storage. It is a same-process recreation,
        // so this validates durable storage rather than claiming to simulate process death.
        val deviceContext = context.createDeviceProtectedStorageContext()
        val reopened = RedactingEventLogger(deviceContext)
        val restored = awaitEvents(reopened, planHash, expectedTypes)
        assertDiagnosticsAreRedacted(restored, plan.id, occurrence.occurrenceId, planHash, occurrenceHash)
        assertRecoveryRecorded(reopened, recoveryStartedAt)

        val stored = File(deviceContext.noBackupFilesDir, "diagnostics/events.json")
        await { stored.isFile }
        val serialized = stored.readText()
        assertTrue(serialized.contains(planHash))
        expectedTypes.forEach { assertTrue(serialized.contains(it.name)) }
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains(occurrence.occurrenceId))
    }

    private fun upcomingPlan(secret: String): AlarmPlan {
        val at = Instant.now().plusSeconds(30).atZone(ZoneId.systemDefault())
        val id = "$secret-plan"
        ownedPlanIds += id
        return AlarmPlan(
            id = id,
            revision = 0,
            name = "$secret-label",
            enabled = true,
            zoneId = at.zone.id,
            defaultWakeLocalTime = at.toLocalTime().withNano(0).toString(),
            arrivalLocalTime = "09:00",
            preparationMinutes = 30,
            maxAdvanceMinutes = 60,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Once(at.toLocalDate().toString()),
        )
    }

    private suspend fun scheduledOccurrence(planId: String): AlarmOccurrence = awaitValue {
        dependencies.occurrences().getByPlanId(planId).firstOrNull {
            it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
        }
    }

    private suspend fun awaitEvents(
        source: RedactingEventLogger,
        planHash: String,
        requiredTypes: Set<DiagnosticEventType>,
    ): List<DiagnosticEvent> = awaitValue {
        source.recentEvents().filter { it.planIdHash == planHash }
            .takeIf { events -> events.map { it.eventType }.containsAll(requiredTypes) }
    }

    private fun assertDiagnosticsAreRedacted(
        events: List<DiagnosticEvent>,
        planId: String,
        occurrenceId: String,
        planHash: String,
        occurrenceHash: String,
    ) {
        assertTrue(events.all { it.appVersion.isNotBlank() && it.sdkInt > 0 })
        assertTrue(events.all { it.planIdHash == planHash })
        assertTrue(events.any { it.occurrenceIdHash == occurrenceHash })
        assertTrue(events.all { it.planIdHash != planId && it.occurrenceIdHash != occurrenceId })
        assertTrue(events.all { it.planIdHash.orEmpty().matches(Regex("[0-9a-f]{64}")) })
        assertTrue(events.filter { it.occurrenceIdHash != null }
            .all { it.occurrenceIdHash.orEmpty().matches(Regex("[0-9a-f]{64}")) })
        assertNotEquals(planId, planHash)
        assertNotEquals(occurrenceId, occurrenceHash)
    }

    private suspend fun assertRecoveryRecorded(source: RedactingEventLogger, startedAt: Long) {
        val recovery = awaitValue {
            source.recentEvents().lastOrNull {
                it.eventType == DiagnosticEventType.ALARM_RECOVERY && it.timestamp >= startedAt
            }
        }
        assertTrue(recovery.resultCode in setOf(DiagnosticResultCode.SUCCESS, DiagnosticResultCode.SKIPPED))
        assertTrue(requireNotNull(recovery.durationMs) >= 0)
    }

    private suspend fun <T : Any> awaitValue(value: suspend () -> T?): T = withTimeout(20_000) {
        var result = value()
        while (result == null) {
            delay(100)
            result = value()
        }
        result
    }

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) delay(100)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
