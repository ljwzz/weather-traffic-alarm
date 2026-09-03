package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var deps: DeviceTestDependencies

    @Before fun prepare() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
        )).use { it.readBytes() }
        deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        deps.settings().update { it.copy(privacyAccepted = true) }
        cleanupPlans()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("全部闹钟").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun cleanup() = runBlocking { cleanupPlans() }

    @Test fun createsWeeklyAlarmAndCancelsUncommittedEdit() {
        openEditor()
        compose.onNodeWithTag("plan_name").performTextReplacement("UI验证-每周")
        compose.onNodeWithTag("repeat_weekly").performClick()
        compose.onNodeWithTag("save_alarm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("plan_name").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("UI验证-每周").fetchSemanticsNodes().isNotEmpty()
        }
        val saved = runBlocking { deps.plans().observeAll().first().single { it.name == "UI验证-每周" } }
        assertTrue(saved.schedule is AlarmSchedule.Weekly)
        compose.onNodeWithText("UI验证-每周").performClick()
        compose.onNodeWithTag("repeat_weekly").assertIsSelected()
        compose.onNodeWithTag("plan_name").performTextReplacement("UI验证-不保存")
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals("UI验证-每周", runBlocking { deps.plans().getById(saved.id) }?.name)
    }

    @Test fun defaultSingleDateIsFutureAndBlankNameCannotSave() {
        openEditor()
        compose.onNodeWithTag("repeat_once").assertIsSelected()
        compose.onNodeWithTag("plan_name").performTextReplacement("")
        compose.onNodeWithTag("save_alarm").assertIsNotEnabled()
        compose.onNodeWithTag("plan_name").performTextReplacement("UI验证-单次")
        compose.onNodeWithTag("save_alarm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("plan_name").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("UI验证-单次").fetchSemanticsNodes().isNotEmpty()
        }
        val saved = runBlocking { deps.plans().observeAll().first().single { it.name == "UI验证-单次" } }
        assertTrue(saved.schedule is AlarmSchedule.Once)
        val occurrences = runBlocking { deps.occurrences().getByPlanId(saved.id) }
        assertTrue(occurrences.any { it.scheduledWakeAt > System.currentTimeMillis() })
    }

    private fun openEditor() {
        compose.onNodeWithText("全部闹钟").performClick()
        compose.waitForIdle()
        val emptyAdd = compose.onAllNodesWithText("添加闹钟").fetchSemanticsNodes()
        if (emptyAdd.isNotEmpty()) compose.onAllNodesWithText("添加闹钟").onFirst().performClick()
        else compose.onNodeWithText("＋").performClick()
        compose.onNodeWithTag("plan_name").assertExists()
    }

    private suspend fun cleanupPlans() {
        deps.plans().observeAll().first().filter { it.name.startsWith("UI验证-") }
            .forEach { deps.coordinator().delete(it.id) }
    }
}
