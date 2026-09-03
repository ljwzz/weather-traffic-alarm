package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Device-only regression coverage for the full-screen ringing surface. Each test
 * creates an isolated, owned plan and reaches the Activity through the real
 * receiver/service chain; it never invokes coordinator dismiss or snooze directly.
 */
@RunWith(AndroidJUnit4::class)
class NativeRingingActivityDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var deps: DeviceTestDependencies
    private val ownedPlans = mutableListOf<String>()
    private val scenarios = mutableListOf<RingingScenario>()

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    }

    @After fun cleanup(): Unit = runBlocking {
        var failure: Throwable? = null
        scenarios.asReversed().forEach {
            runCatching { closeScenario(it) }.exceptionOrNull()?.let { error ->
                failure = appendFailure(failure, error)
            }
        }
        ownedPlans.forEach { planId ->
            runCatching { deps.coordinator().delete(planId) }.exceptionOrNull()?.let { error ->
                failure = appendFailure(failure, error)
            }
        }
        failure?.let { throw it }
    }

    /** Expected duration: about 25 seconds on a healthy emulator. */
    @Test fun scheduledAlarmRingsStopsAndKeepsStoppedReceiptAfterRecreate() = runBlocking {
        val plan = saveFuturePlan()
        val occurrence = regularOccurrence(plan.id)
        val scenario = launchRinging(occurrence.occurrenceId)

        awaitText("尚未到响铃时间")
        compose.onNodeWithTag("ringing_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("ringing_snooze").assertDoesNotExist()

        await { deps.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.FIRING }
        await { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == occurrence.occurrenceId } }
        await { isAlarmAudioActive() }
        awaitNode("ringing_dismiss")
        compose.onNodeWithTag("ringing_snooze").assertExists()
        screenshot("basic-firing.png")

        compose.onNodeWithTag("ringing_dismiss").performClick()
        await { deps.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.DISMISSED }
        await { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == occurrence.occurrenceId } }
        awaitText("本次响铃已停止")
        compose.onNodeWithText("本次响铃已停止").assertExists()
        screenshot("basic-stopped.png")

        scenario.recreate()
        awaitText("本次响铃已停止")
        compose.onNodeWithTag("ringing_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("ringing_snooze").assertDoesNotExist()
    }

    /** Expected duration: about 95 seconds: initial trigger plus one real one-minute snooze. */
    @Test fun snoozeCreatesRealChildWhichReturnsToTheSameActivityAndRingsAgain() = runBlocking {
        val plan = saveFuturePlan(snoozeMinutes = 1, repeating = true)
        val parent = regularOccurrence(plan.id)
        launchRinging(parent.occurrenceId)

        await { deps.occurrences().getById(parent.occurrenceId)?.state == OccurrenceState.FIRING }
        await { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == parent.occurrenceId } }
        awaitNode("ringing_snooze")
        compose.onNodeWithTag("ringing_snooze").performClick()

        await { deps.occurrences().getById(parent.occurrenceId)?.state == OccurrenceState.SNOOZED }
        val child = deps.occurrences().getByPlanId(plan.id).single {
            it.kind == OccurrenceKind.SNOOZE && it.parentOccurrenceId == parent.occurrenceId
        }
        awaitText("已贪睡 1 分钟")
        screenshot("basic-snoozed.png")

        await(80_000) { deps.occurrences().getById(child.occurrenceId)?.state == OccurrenceState.FIRING }
        await(80_000) { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == child.occurrenceId } }
        awaitNode("ringing_dismiss", 80_000)
        compose.onNodeWithText("贪睡后再次响铃").assertExists()
        screenshot("snooze-firing.png")

        compose.onNodeWithTag("ringing_dismiss").performClick()
        await { deps.occurrences().getById(child.occurrenceId)?.state == OccurrenceState.DISMISSED }
        await { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == child.occurrenceId } }
        awaitNode("ringing_open_plans")
        assertTrue(requireNotNull(deps.plans().getById(plan.id)).enabled)
    }

    /** Expected duration: about 30 seconds. The retry uses the same still-ringing parent instance. */
    @Test fun snoozeFailureFromNotificationAppOpKeepsParentRingingAndRetrySucceeds() = runBlocking {
        val originalMode = postNotificationAppOpMode()
        assumeTrue("POST_NOTIFICATION AppOp mode is unreadable; skipped without changing it", originalMode != null)
        assumeTrue(
            "POST_NOTIFICATION is initially disabled; skipped without changing it",
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        )

        val plan = saveFuturePlan(snoozeMinutes = 1, repeating = true)
        val parent = regularOccurrence(plan.id)
        launchRinging(parent.occurrenceId)
        await { deps.occurrences().getById(parent.occurrenceId)?.state == OccurrenceState.FIRING }
        await { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == parent.occurrenceId } }
        awaitNode("ringing_snooze")

        try {
            shell("appops set ${context.packageName} POST_NOTIFICATION ignore")
            val appliedMode = postNotificationAppOpMode()
            val notificationsDisabled = !context
                .getSystemService(NotificationManager::class.java)
                .areNotificationsEnabled()
            assumeTrue(
                "POST_NOTIFICATION ignore was not applied (readback=$appliedMode, enabled=${!notificationsDisabled}); " +
                    "skipped after restoring the original AppOp",
                appliedMode == "ignore" && notificationsDisabled,
            )
            compose.onNodeWithTag("ringing_snooze").performClick()
            awaitText("贪睡未能注册，请重试或停止闹钟")
            assertTrue(deps.occurrences().getById(parent.occurrenceId)?.state == OccurrenceState.FIRING)
            assertTrue(AlarmRingingService.activeAlarms.value.any { it.occurrenceId == parent.occurrenceId })
            assertTrue(deps.occurrences().getByPlanId(plan.id).none {
                it.kind == OccurrenceKind.SNOOZE && it.state == OccurrenceState.SCHEDULED
            })
            screenshot("snooze-retry-failed.png")
        } finally {
            shell("appops set ${context.packageName} POST_NOTIFICATION $originalMode")
        }

        await {
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
        compose.onNodeWithTag("ringing_snooze").performClick()
        await { deps.occurrences().getById(parent.occurrenceId)?.state == OccurrenceState.SNOOZED }
        assertTrue(deps.occurrences().getByPlanId(plan.id).any {
            it.kind == OccurrenceKind.SNOOZE && it.parentOccurrenceId == parent.occurrenceId &&
                it.state == OccurrenceState.SCHEDULED
        })
    }

    /** Expected duration: under 10 seconds. A tampered show Intent cannot expose ringing actions. */
    @Test fun tamperedShowIntentWithoutAnActiveAlarmCannotOperate() {
        val malformed = PendingIntentFactory(context)
            .createShowAlarmIntent("owned-missing-${UUID.randomUUID()}")
            .putExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID, "tampered-${UUID.randomUUID()}")
        launchRinging(malformed)
        awaitText("无法操作此实例")
        compose.onNodeWithTag("ringing_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("ringing_snooze").assertDoesNotExist()
        compose.onNodeWithTag("ringing_open_plans").assertExists()
        compose.onNodeWithTag("ringing_close").assertExists()
    }

    private suspend fun saveFuturePlan(
        snoozeMinutes: Int = 10,
        repeating: Boolean = false,
    ): AlarmPlan {
        val triggerAt = Instant.now().plusSeconds(12)
        val local = triggerAt.atZone(ZoneId.systemDefault())
        val id = "ringing-device-${UUID.randomUUID()}"
        ownedPlans += id
        val schedule = if (repeating) AlarmSchedule.Weekly((1..7).toSet())
        else AlarmSchedule.Once(local.toLocalDate().toString())
        return deps.coordinator().save(
            AlarmPlan(
                id = id,
                revision = 0,
                name = "设备响铃验证",
                enabled = true,
                zoneId = local.zone.id,
                defaultWakeLocalTime = local.toLocalTime().withNano(0).toString(),
                arrivalLocalTime = "09:00",
                preparationMinutes = 30,
                maxAdvanceMinutes = 60,
                commuteMode = CommuteMode.DRIVING,
                snoozeMinutes = snoozeMinutes,
                schedule = schedule,
            ),
        )
    }

    private suspend fun regularOccurrence(planId: String) = deps.occurrences().getByPlanId(planId).first {
        it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
    }

    private fun launchRinging(occurrenceId: String): ActivityScenario<AlarmRingingActivity> =
        launchRinging(PendingIntentFactory(context).createShowAlarmIntent(occurrenceId))

    private fun launchRinging(intent: Intent): ActivityScenario<AlarmRingingActivity> =
        ActivityScenario.launch<AlarmRingingActivity>(intent).also {
            scenarios += RingingScenario(it, Intent(intent))
        }

    /**
     * A child snooze full-screen PendingIntent can retarget the resumed Activity.
     * Finish the current ringing surface before closing its original Scenario, then
     * retain any lifecycle failure for the test result after all owned plans are deleted.
     */
    private fun closeScenario(entry: RingingScenario) {
        var failure: Throwable? = null
        runCatching {
            entry.scenario.onActivity { activity ->
                activity.setIntent(entry.launchIntent)
                activity.finish()
            }
        }.exceptionOrNull()?.let { failure = appendFailure(failure, it) }
        runCatching { finishLiveRingingActivities(entry.launchIntent) }
            .exceptionOrNull()
            ?.let { failure = appendFailure(failure, it) }
        runCatching { entry.scenario.close() }.exceptionOrNull()?.let { failure = appendFailure(failure, it) }
        failure?.let { throw it }
    }

    private fun finishLiveRingingActivities(launchIntent: Intent) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<AlarmRingingActivity>()
                .distinct()
                .forEach { activity ->
                    activity.setIntent(launchIntent)
                    activity.finish()
                }
        }
        instrumentation.waitForIdleSync()
    }

    private fun appendFailure(primary: Throwable?, next: Throwable): Throwable =
        primary?.also { it.addSuppressed(next) } ?: next

    private suspend fun await(timeoutMillis: Long = 30_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(100)
        }
    }

    private fun awaitNode(tag: String, timeoutMillis: Long = 30_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String, timeoutMillis: Long = 30_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun isAlarmAudioActive(): Boolean =
        context.getSystemService(AudioManager::class.java).activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ALARM
        }

    /** Returns only a mode safe to restore; unknown shell output leaves AppOps untouched. */
    private fun postNotificationAppOpMode(): String? {
        val output = shell("appops get ${context.packageName} POST_NOTIFICATION")
        if (output.contains("No operations.") &&
            Regex("""Default mode:\s*(allow|default|ignore|deny)\b""").containsMatchIn(output)
        ) return "default"
        return Regex("""POST_NOTIFICATION:\s*(allow|default|ignore|deny)\b""")
            .find(output)
            ?.groupValues
            ?.get(1)
    }

    private fun screenshot(fileName: String) {
        compose.waitForIdle()
        // Screenshot-only stabilization for the system heads-up exit animation.
        SystemClock.sleep(750)
        compose.waitForIdle()
        val directory = context.getExternalFilesDir("ringing-qa") ?: return
        directory.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(directory, fileName)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        // Capture the app window separately; retain the full device screenshot
        // above so system heads-up notifications are not hidden from evidence.
        val content = compose.onNodeWithTag("ringing_screen").captureToImage().asAndroidBitmap()
        FileOutputStream(File(directory, fileName.removeSuffix(".png") + "-content.png")).use {
            content.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun shell(command: String): String {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { return it.readBytes().toString(Charsets.UTF_8) }
    }

    private data class RingingScenario(
        val scenario: ActivityScenario<AlarmRingingActivity>,
        val launchIntent: Intent,
    )
}
