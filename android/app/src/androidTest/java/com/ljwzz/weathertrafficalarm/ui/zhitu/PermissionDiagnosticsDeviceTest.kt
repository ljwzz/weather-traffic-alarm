package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Pure Compose rendering contract; no activity, settings intent, or application state is used. */
@RunWith(AndroidJUnit4::class)
class PermissionDiagnosticsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun generalAndroidDoesNotRenderXiaomiControls() {
        setDiagnostics(snapshot = snapshot(isXiaomi = false))

        compose.onNodeWithTag("permission_diagnostics").assertExists()
        compose.onNodeWithText("小米系统显示").assertDoesNotExist()
        writeScreenshot("permission-diagnostics-general.png")
    }

    @Test
    fun xiaomiRendersManualConfirmationExplanationAndDispatchesConfirmation() {
        val confirmed = mutableStateOf<XiaomiDisplayPermission?>(null)
        setDiagnostics(
            snapshot = snapshot(isXiaomi = true),
            confirmations = setOf(XiaomiDisplayPermission.LockScreen),
            onConfirm = { confirmed.value = it },
        )

        compose.onNodeWithTag("confirm_xiaomi_background").performScrollTo()
        compose.onNodeWithText("用户确认不等于系统检测。", substring = true).assertExists()
        compose.onNodeWithText("用户已确认 · 未自动核验").assertExists()
        compose.onNodeWithTag("confirm_xiaomi_lock").performScrollTo().performClick()
        assertEquals(XiaomiDisplayPermission.LockScreen, confirmed.value)
        compose.onNodeWithTag("confirm_xiaomi_background").performScrollTo()
        writeScreenshot("permission-diagnostics-xiaomi.png")
    }

    @Test
    fun fullScreenSettingsAndReturnActionsDispatchTheirDedicatedCallbacks() {
        val setting = mutableStateOf<PermissionSetting?>(null)
        val returns = mutableStateOf(0)
        setDiagnostics(
            snapshot = snapshot(isXiaomi = false),
            returningToAlarm = true,
            onSetting = { setting.value = it },
            onBack = { returns.value++ },
        )

        compose.onNodeWithTag("settings_full_screen").performClick()
        compose.onNodeWithTag("permissions_return").performScrollTo().performClick()

        assertEquals(PermissionSetting.FullScreenIntent, setting.value)
        assertEquals(1, returns.value)
    }

    private fun setDiagnostics(
        snapshot: PermissionSnapshot,
        returningToAlarm: Boolean = false,
        confirmations: Set<XiaomiDisplayPermission> = emptySet(),
        onSetting: (PermissionSetting) -> Unit = {},
        onConfirm: (XiaomiDisplayPermission) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            ZhituTheme {
                PermissionDiagnosticsContent(
                    snapshot = snapshot,
                    confirmations = confirmations,
                    onSetting = onSetting,
                    onConfirm = onConfirm,
                    onRefresh = {},
                    onBack = onBack,
                    onNotificationRequest = {},
                    returningToAlarm = returningToAlarm,
                )
            }
        }
    }

    private fun snapshot(isXiaomi: Boolean) = PermissionSnapshot(
        notificationRuntimeGranted = false,
        notificationsAvailable = false,
        alarmChannelAvailable = false,
        exactAlarmAvailable = false,
        fullScreenIntentAvailable = false,
        isXiaomi = isXiaomi,
        location = LocationPermissionSnapshot(
            coarseGranted = false,
            fineGranted = false,
            servicesEnabled = true,
        ),
    )

    private fun writeScreenshot(fileName: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("permission-qa"),
        )
        directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use {
            assertTrue("Unable to write screenshot $fileName", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
