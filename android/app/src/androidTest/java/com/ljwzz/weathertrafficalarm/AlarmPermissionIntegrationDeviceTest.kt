package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires notifications already denied; never changes permissions or deletes other plans. */
@RunWith(AndroidJUnit4::class)
class AlarmPermissionIntegrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var dependencies: DeviceTestDependencies? = null
    private var previousSettings: LocalSettings? = null
    private val planName = "权限流程-${UUID.randomUUID()}"

    @Before fun prepare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        dependencies = deps
        previousSettings = deps.settings().loadInitial()
        deps.settings().update { it.copy(privacyAccepted = true, amapConsentPromptedVersion = it.amapConsentPromptedVersion ?: 1) }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("全部闹钟").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restoreOwnedState() = runBlocking<Unit> {
        dependencies?.let { deps ->
            deps.plans().observeAll().first().filter { it.name == planName }.forEach { deps.coordinator().delete(it.id) }
            previousSettings?.let { original -> deps.settings().update { original } }
        }
    }

    @Test fun cancelAndSettingsReturnPreserveDraftAndContinueSavesExactlyOnce() {
        val deps = requireNotNull(dependencies)
        compose.onNodeWithText("全部闹钟").performClick()
        compose.waitForIdle()
        val add = compose.onAllNodesWithText("添加闹钟")
        if (add.fetchSemanticsNodes().isNotEmpty()) add.onFirst().performClick()
        else compose.onNodeWithText("＋").performClick()
        compose.onNodeWithTag("plan_name").performTextReplacement(planName)
        compose.onNodeWithTag("save_alarm").performClick()
        compose.onNodeWithTag("permission_guide").assertExists()
        assertTrue(runBlocking { deps.plans().observeAll().first().none { it.name == planName } })
        screenshot("permission-guide-integrated.png")

        compose.onNodeWithTag("permission_cancel").performClick()
        compose.onNodeWithTag("plan_name").assertTextEquals("名称", planName)
        compose.onNodeWithTag("save_alarm").performClick()
        compose.onNodeWithTag("permission_check").performClick()
        compose.onNodeWithTag("permission_diagnostics").assertExists()
        Espresso.pressBack()
        compose.onNodeWithTag("permission_guide").assertExists()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("permission_guide").assertExists()
        compose.onNodeWithTag("permission_cancel").performClick()
        compose.onNodeWithTag("plan_name").assertTextEquals("名称", planName)
        assertTrue(runBlocking { deps.plans().observeAll().first().none { it.name == planName } })

        compose.onNodeWithTag("save_alarm").performClick()
        compose.onNodeWithTag("permission_continue").performClick()
        compose.waitUntil(10_000) {
            runBlocking { deps.plans().observeAll().first().count { it.name == planName } == 1 }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(planName).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { deps.plans().observeAll().first().count { it.name == planName } })
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("permission-qa"))
        directory.mkdirs()
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
