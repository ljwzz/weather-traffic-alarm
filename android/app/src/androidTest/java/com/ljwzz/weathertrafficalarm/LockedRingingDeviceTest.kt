package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmRingingService
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.ui.zhitu.AlarmRingingActivity
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
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
            screenshotDevice("locked-firing-device.png")
            screenshotRoot("locked-firing-root.png")

            compose.onNodeWithTag("ringing_dismiss").performClick()
            await(15_000) { deps.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.DISMISSED }
            await(15_000) { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == occurrence.occurrenceId } }
            awaitText("本次响铃已停止", 15_000)
            stopped = true
            screenshotDevice("locked-stopped-device.png")
            screenshotRoot("locked-stopped-root.png")
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

    private fun futurePlan(planId: String): AlarmPlan {
        val triggerAt = Instant.now().plusSeconds(18)
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

    private fun activeRingingActivities(): List<AlarmRingingActivity> {
        var activities = emptyList<AlarmRingingActivity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            activities = monitor.getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AlarmRingingActivity>()
        }
        return activities
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

    private fun screenshotDevice(fileName: String) {
        settleForScreenshot()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        writePng(fileName, bitmap)
        bitmap.recycle()
    }

    private fun screenshotRoot(fileName: String) {
        settleForScreenshot()
        val bitmap = compose.onNodeWithTag("ringing_screen", useUnmergedTree = true)
            .captureToImage()
            .asAndroidBitmap()
        writePng(fileName, bitmap)
    }

    private fun settleForScreenshot() {
        compose.waitForIdle()
        SystemClock.sleep(750)
        compose.waitForIdle()
    }

    private fun writePng(fileName: String, bitmap: Bitmap) {
        val directory = requireNotNull(context.getExternalFilesDir("ringing-qa"))
        directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use {
            assertTrue("Unable to write screenshot $fileName", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun appendFailure(primary: Throwable?, next: Throwable): Throwable =
        primary?.also { it.addSuppressed(next) } ?: next
}
