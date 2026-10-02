package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmRingingService
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import com.ljwzz.weathertrafficalarm.ui.zhitu.AlarmRingingActivity
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device coverage for a naturally delivered lock-screen full-screen alarm.
 * It never launches AlarmRingingActivity itself and only removes the UUID plan it created.
 */
@RunWith(AndroidJUnit4::class)
class LockedRingingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var deps: DeviceTestDependencies

    @Test fun lockedDeviceNaturallyOpensFullScreenAlarmThenStops(): Unit = runBlocking {
        assumeTrue(
            "Set the explicit runLockedRinging=true instrumentation argument to run this opt-in test",
            InstrumentationRegistry.getArguments().getString("runLockedRinging") == "true",
        )
        context = instrumentation.targetContext
        deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val planId = "locked-ringing-device-${UUID.randomUUID()}"
        val plansBefore = deps.plans().observeAll().first().filterNot { it.id == planId }.associateBy { it.id }

        assumeTrue("POST_NOTIFICATIONS is required", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("App notifications are disabled", notificationManager.areNotificationsEnabled())
        assumeTrue("USE_FULL_SCREEN_INTENT is not enabled", notificationManager.canUseFullScreenIntent())
        assumeTrue("Device is already locked", !keyguardManager.isKeyguardLocked)
        assumeTrue("Screen must be interactive before the controlled sleep", powerManager.isInteractive)
        assumeTrue("An app alarm is already active", AlarmRingingService.activeAlarms.value.isEmpty())
        assumeTrue("A ringing Activity is already active", activeRingingActivities().isEmpty())

        var bodyFailure: Throwable? = null
        var saved = false
        var screenSlept = false
        var alarmTriggered = false
        var keyguardLockedAtTrigger = false
        var fired = false
        var stopped = false
        var ownedOccurrenceId: String? = null
        var ownedPlanDeleted = false
        try {
            val plan = futurePlan(planId)
            deps.coordinator().save(plan)
            saved = true
            val occurrence = regularOccurrence(planId)
            ownedOccurrenceId = occurrence.occurrenceId
            assertEquals(OccurrenceState.SCHEDULED, occurrence.state)

            shell("input keyevent KEYCODE_SLEEP")
            screenSlept = true
            await(10_000) { !powerManager.isInteractive && keyguardManager.isKeyguardLocked }
            assertFalse(powerManager.isInteractive)
            assertTrue(keyguardManager.isKeyguardLocked)

            await(45_000) { deps.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.FIRING }
            alarmTriggered = true
            keyguardLockedAtTrigger = keyguardManager.isKeyguardLocked
            assertTrue("Device was unlocked before the alarm triggered; lock-screen conditions no longer hold", keyguardLockedAtTrigger)
            await(15_000) { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == occurrence.occurrenceId } }
            await(15_000) { activeRingingActivities().any { it.intentOccurrenceId() == occurrence.occurrenceId } }
            assertTrue("Device unlocked before lock-screen presentation could be verified", keyguardManager.isKeyguardLocked)
            awaitNode("ringing_dismiss", 15_000)
            fired = true

            compose.onNodeWithTag("ringing_dismiss").performClick()
            await(15_000) { deps.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.DISMISSED }
            await(15_000) { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == occurrence.occurrenceId } }
            awaitText("本次响铃已停止", 15_000)
            stopped = true

        } catch (error: Throwable) {
            bodyFailure = error
        }

        var cleanupFailure: Throwable? = null
        runCatching { ownedOccurrenceId?.let(::finishOwnedRingingActivities) }
            .exceptionOrNull()
            ?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        runCatching { deps.coordinator().delete(planId) }
            .exceptionOrNull()
            ?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        runCatching {
            ownedPlanDeleted = deps.plans().getById(planId) == null
            assertTrue("Owned test plan was not removed", ownedPlanDeleted)
            await(10_000) { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == ownedOccurrenceId } }
        }
            .exceptionOrNull()
            ?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        if (screenSlept) {
            runCatching { shell("input keyevent KEYCODE_WAKEUP") }
                .exceptionOrNull()
                ?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        }
        runCatching {
            val plansAfter = deps.plans().observeAll().first().filterNot { it.id == planId }.associateBy { it.id }
            assertTrue("A non-owned alarm plan changed during the locked-ring test", plansBefore == plansAfter)
        }.exceptionOrNull()?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("scenario", "locked-full-screen-alarm")
                putBoolean("alarmTriggered", alarmTriggered)
                putBoolean("keyguardLockedAtTrigger", keyguardLockedAtTrigger)
                putBoolean("fullScreenPresented", fired)
                putBoolean("stopped", stopped)
                putBoolean("ownedPlanDeleted", saved && ownedPlanDeleted)
                putInt("nonOwnedPlanCount", plansBefore.size)
            },
        )
        if (bodyFailure != null) {
            cleanupFailure?.let(bodyFailure::addSuppressed)
            throw bodyFailure
        }
        cleanupFailure?.let { throw it }
    }

    /**
     * Runs the actual lock-screen path for an independently scheduled ADVANCE
     * occurrence. It verifies the post-unlock screen is bound to that exact
     * persisted decision, then Back returns to the still-ringing occurrence.
     */
    @Test fun lockedAdvanceOpensItsPersistedDecisionAndBackKeepsTheAlarmRinging(): Unit = runBlocking {
        assumeTrue(
            "Set runLockedAdvanceDetail=true to run this controlled lock-screen scenario",
            InstrumentationRegistry.getArguments().getString("runLockedAdvanceDetail") == "true",
        )
        context = instrumentation.targetContext
        deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val planId = "locked-advance-detail-${UUID.randomUUID()}"
        assumeTrue("POST_NOTIFICATIONS is required", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("App notifications are disabled", notificationManager.areNotificationsEnabled())
        assumeTrue("USE_FULL_SCREEN_INTENT is not enabled", notificationManager.canUseFullScreenIntent())
        assumeTrue("Device is already locked", !keyguardManager.isKeyguardLocked)
        assumeTrue("Screen must be interactive before the controlled sleep", powerManager.isInteractive)
        assumeTrue("An app alarm is already active", AlarmRingingService.activeAlarms.value.isEmpty())

        var advanceId: String? = null
        try {
            // Keep the regular alarm far enough ahead that its minute-rounded
            // schedule cannot overtake the deliberately near-term advance.
            val plan = futurePlan(planId, leadSeconds = 10 * 60)
            val savedPlan = deps.coordinator().save(plan)
            val regular = regularOccurrence(savedPlan.id)
            val decision = advanceDecision(savedPlan, regular)
            val applyResult = deps.coordinator().applyEvaluation(decision)
            assertEquals("APPLIED", applyResult.outcome)
            val advances = deps.occurrences().getByPlanId(plan.id).filter {
                it.kind == OccurrenceKind.ADVANCE && it.decisionId == decision.decisionId &&
                    it.state == OccurrenceState.SCHEDULED
            }
            assertEquals("Expected exactly one registered ADVANCE occurrence", 1, advances.size)
            val advance = advances.single()
            advanceId = advance.occurrenceId

            shell("input keyevent KEYCODE_SLEEP")
            await(10_000) { !powerManager.isInteractive && keyguardManager.isKeyguardLocked }
            await(55_000) { deps.occurrences().getById(advance.occurrenceId)?.state == OccurrenceState.FIRING }
            await(15_000) { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == advance.occurrenceId } }
            await(15_000) { activeRingingActivities().any { it.intentOccurrenceId() == advance.occurrenceId } }
            awaitNode("ringing_open_advance_detail", 15_000)

            compose.onNodeWithTag("ringing_open_advance_detail").performClick()
            unlockWithTemporaryPinIfRequested(keyguardManager)
            awaitNode("decision-detail-${decision.decisionId}", 15_000)
            assertEquals(decision.decisionId, deps.decisions().getById(decision.decisionId)?.decisionId)
            assertEquals(OccurrenceState.FIRING, deps.occurrences().getById(advance.occurrenceId)?.state)
            assertTrue(AlarmRingingService.activeAlarms.value.any { it.occurrenceId == advance.occurrenceId })

            Espresso.pressBack()
            awaitNode("ringing_dismiss", 15_000)
            assertEquals(OccurrenceState.FIRING, deps.occurrences().getById(advance.occurrenceId)?.state)
            assertTrue(AlarmRingingService.activeAlarms.value.any { it.occurrenceId == advance.occurrenceId })
            compose.onNodeWithTag("ringing_dismiss").performClick()
            await(15_000) { deps.occurrences().getById(advance.occurrenceId)?.state == OccurrenceState.DISMISSED }
        } finally {
            advanceId?.let { id -> runCatching { finishOwnedRingingActivities(id) } }
            runCatching { deps.coordinator().delete(planId) }
            runCatching { shell("input keyevent KEYCODE_WAKEUP") }
        }
    }

    private fun futurePlan(planId: String, leadSeconds: Long = 18): AlarmPlan {
        val triggerAt = Instant.now().plusSeconds(leadSeconds)
        val local = triggerAt.atZone(ZoneId.systemDefault())
        return AlarmPlan(
            id = planId,
            revision = 0,
            name = "锁屏响铃设备验证",
            enabled = true,
            zoneId = local.zone.id,
            defaultWakeLocalTime = local.toLocalTime().withNano(0).toString(),
            arrivalLocalTime = "09:00",
            preparationMinutes = 30,
            maxAdvanceMinutes = 60,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Once(local.toLocalDate().toString()),
        )
    }

    private suspend fun regularOccurrence(planId: String) = deps.occurrences().getByPlanId(planId).first {
        it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
    }

    private fun advanceDecision(plan: AlarmPlan, regular: com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence): AlarmDecision {
        val recommended = System.currentTimeMillis() + 35_000L
        return AlarmDecision(
            decisionId = UUID.randomUUID().toString(),
            planId = plan.id,
            planRevision = plan.revision,
            targetDate = regular.targetDate,
            workdayStatus = WorkdayStatus.WORKDAY,
            estimatedDepartureAt = Instant.ofEpochMilli(regular.scheduledWakeAt).toString(),
            commuteSeconds = 1_800,
            weatherSeverity = 1,
            weatherBufferMinutes = 10,
            recommendedWakeAt = Instant.ofEpochMilli(recommended).toString(),
            routeProvider = "AMAP",
            routeProviderReportTime = Instant.now().toString(),
            weatherProvider = "CAIYUN",
            weatherProviderReportTime = Instant.now().toString(),
            weatherWindowStart = null,
            weatherWindowEnd = null,
            fallbackReason = FallbackReason.NONE,
            insufficientAdvance = false,
            generatedAt = Instant.now().toString(),
            expiresAt = Instant.now().plusSeconds(120).toString(),
            evaluationOutcome = EvaluationOutcome.SUCCESS,
            preparationMinutes = plan.preparationMinutes,
            defaultWakeAt = Instant.ofEpochMilli(regular.scheduledWakeAt).toString(),
            planName = plan.name,
            zoneId = plan.zoneId,
        )
    }

    private fun activeRingingActivities(): List<AlarmRingingActivity> {
        var activities = emptyList<AlarmRingingActivity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            activities = monitor.getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AlarmRingingActivity>()
        }
        return activities
    }

    /**
     * Opt-in test-only bridge for a real emulator PIN. The value is supplied
     * only as an instrumentation argument, never persisted by the app, and is
     * used after the ringing page has requested the system unlock flow.
     */
    private suspend fun unlockWithTemporaryPinIfRequested(keyguardManager: KeyguardManager) {
        val pin = InstrumentationRegistry.getArguments().getString("temporaryPin")?.takeIf(String::isNotBlank) ?: return
        require(pin.matches(Regex("\\d{4,16}"))) { "temporaryPin must be 4-16 digits" }
        shell("input swipe 720 2400 720 700 300")
        shell("input text $pin")
        shell("input keyevent KEYCODE_ENTER")
        await(10_000) { !keyguardManager.isKeyguardLocked }
    }

    private fun finishOwnedRingingActivities(occurrenceId: String) {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<AlarmRingingActivity>()
                .distinct()
                .filter { it.intentOccurrenceId() == occurrenceId }
                .forEach(AlarmRingingActivity::finish)
        }
        instrumentation.waitForIdleSync()
    }

    private fun AlarmRingingActivity.intentOccurrenceId(): String? =
        intent.getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID)

    private suspend fun await(timeoutMillis: Long, condition: suspend () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(100)
        }
    }

    private fun awaitNode(tag: String, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun appendFailure(primary: Throwable?, next: Throwable): Throwable =
        primary?.also { it.addSuppressed(next) } ?: next
}
