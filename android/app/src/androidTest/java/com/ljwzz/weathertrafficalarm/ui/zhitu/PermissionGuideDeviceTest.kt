package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Isolated visual contract for the pre-save permission guide. It neither starts
 * an activity nor reads or changes a device permission, alarm, or application
 * database.
 */
@RunWith(AndroidJUnit4::class)
class PermissionGuideDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun guideExposesCheckContinueAndCancelAsIndependentActions() {
        val checks = mutableIntStateOf(0)
        val continues = mutableIntStateOf(0)
        val cancels = mutableIntStateOf(0)

        compose.setContent {
            ZhituTheme {
                AlarmPermissionGuide(
                    missing = listOf("通知权限", "精确闹钟", "全屏提醒"),
                    onCheck = { checks.intValue++ },
                    onContinue = { continues.intValue++ },
                    onCancel = { cancels.intValue++ },
                )
            }
        }

        compose.onNodeWithTag("permission_guide").assertExists()
        writeScreenshot("permission-guide.png")
        compose.onNodeWithTag("permission_check").assertHasClickAction().performClick()
        compose.onNodeWithTag("permission_continue").assertHasClickAction().performClick()
        compose.onNodeWithTag("permission_cancel").assertHasClickAction().performClick()

        assertEquals(1, checks.intValue)
        assertEquals(1, continues.intValue)
        assertEquals(1, cancels.intValue)
    }

    private fun writeScreenshot(fileName: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("permission_guide")
            .captureToImage()
            .asAndroidBitmap()
        val directory = requireNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("permission-qa"),
        )
        directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use {
            assertTrue("Unable to write screenshot $fileName", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
