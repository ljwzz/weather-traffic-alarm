package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.text.format.DateFormat
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmTimeWheelDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun minuteWheelWrapsFrom59To00() {
        var saved = ""
        compose.setContent {
            AlarmTimeWheelSheet("23:59", "添加闹钟", { "下次响铃" }, { saved = it }, {})
        }
        val is24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        if (is24Hour) compose.onNodeWithContentDescription("时 00").performClick()
        compose.onNodeWithContentDescription("分 00").performClick()
        compose.onNodeWithText("✓").performClick()
        compose.runOnIdle { assertEquals(if (is24Hour) "00:00" else "23:00", saved) }
    }
}
