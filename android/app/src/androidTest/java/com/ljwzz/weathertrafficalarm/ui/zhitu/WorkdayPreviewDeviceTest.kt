package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarUiState
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class WorkdayPreviewDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun workdaySelectionRefreshesOnceAndCalendarStateUpdatesThePreview() {
        val draft = mutableStateOf(EditorDraft(repeat = RepeatChoice.ONCE))
        val calendar = mutableStateOf(CalendarUiState())
        val refreshes = mutableStateOf(0)

        setEditor(draft, calendar, refreshes)

        compose.onNodeWithTag("workday_preview").assertDoesNotExist()
        assertEquals(0, refreshes.value)

        compose.onNodeWithTag("repeat_workdays").performClick()
        compose.waitForIdle()
        assertEquals(1, refreshes.value)

        compose.onNodeWithTag("workday_preview").performScrollTo().assertExists()
        compose.onNodeWithTag("workday_preview_status").assertExists()
        compose.onNodeWithText("日历数据不可用，使用星期规则", substring = true).assertExists()

        val today = LocalDate.now(ZoneId.of(draft.value.zoneId))
        val nextWorkday = today.plusDays(7)
        compose.runOnIdle {
            calendar.value = CalendarUiState(
                loaded = true,
                days = (0L..6L).associate { today.plusDays(it).toString() to DayStatus.HOLIDAY } +
                    (nextWorkday.toString() to DayStatus.WORKDAY),
            )
        }
        compose.waitForIdle()
        assertEquals(1, refreshes.value)
        compose.onNodeWithTag("workday_preview_status").assertDoesNotExist()
        compose.onNodeWithTag("workday_day_${nextWorkday}")
            .assertContentDescriptionContains("$nextWorkday，特殊工作日")
        compose.onNodeWithTag("workday_day_${today}")
            .assertContentDescriptionContains("$today，特殊节假日，今天")

        compose.onNodeWithTag("repeat_weekly").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, refreshes.value)
        compose.onNodeWithTag("workday_preview").assertDoesNotExist()

        compose.onNodeWithTag("repeat_workdays").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, refreshes.value)
        compose.onNodeWithTag("workday_preview").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun initialWorkdaysRefreshesOnEachEditorEntry() {
        val visible = mutableStateOf(true)
        val draft = mutableStateOf(EditorDraft(repeat = RepeatChoice.WORKDAYS))
        val calendar = mutableStateOf(CalendarUiState())
        val refreshes = mutableStateOf(0)

        compose.setContent {
            ZhituTheme {
                if (visible.value) Editor(draft, calendar, refreshes)
            }
        }
        compose.waitForIdle()
        assertEquals(1, refreshes.value)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(2, refreshes.value)
    }

    @Test
    fun fourWeekCalendarUsesEqualColumnsAndRendersLegalDayColors() {
        val officialDays = mapOf(
            "2026-09-20" to DayStatus.WORKDAY,
            "2026-09-25" to DayStatus.HOLIDAY,
            "2026-09-26" to DayStatus.HOLIDAY,
            "2026-09-27" to DayStatus.HOLIDAY,
        )
        val today = LocalDate.parse("2026-09-25")
        compose.setContent {
            ZhituTheme {
                Box(Modifier.width(364.dp)) {
                    WorkdayPreviewCard(
                        calendarState = CalendarUiState(loaded = true, days = officialDays),
                        today = today,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("workday_preview").assertIsDisplayed()
        compose.onNodeWithTag("workday_preview_grid").assertIsDisplayed()
        (1..7).forEach { compose.onNodeWithTag("workday_weekday_$it").assertExists() }
        (0..3).forEach { compose.onNodeWithTag("workday_week_$it").assertExists() }

        val start = LocalDate.parse("2026-09-14")
        (0L until 28L).forEach { offset ->
            compose.onNodeWithTag("workday_day_${start.plusDays(offset)}").assertExists()
        }
        compose.onNodeWithContentDescription("2026-09-20，特殊工作日", substring = true).assertExists()
        compose.onNodeWithContentDescription("2026-09-25，特殊节假日", substring = true).assertExists()
        compose.onNodeWithContentDescription("2026-09-25，特殊节假日，今天").assertExists()

        assertSevenEqualColumnsAndFilledRows(start)

        val card = compose.onNodeWithTag("workday_preview")
        val image = card.captureToImage().asAndroidBitmap()
        val cardBounds = card.fetchSemanticsNode().boundsInRoot
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-18"), 0xFF303133.toInt(), "工作日文字")
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-19"), 0xFF79BBFF.toInt(), "休息日文字")
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-20"), 0xFFE6A23C.toInt(), "特殊工作日文字")
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-20"), 0xFFFDF6EC.toInt(), "特殊工作日背景")
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-25"), 0xFF79BBFF.toInt(), "特殊节假日文字")
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-25"), 0xFFECF5FF.toInt(), "特殊节假日背景")
        assertContainsArgb(image, cardBounds, dayBounds("2026-09-25"), 0xFFECF5FF.toInt(), "今天仍保留特殊节假日背景")

        image.recycle()
    }

    private fun setEditor(
        draft: MutableState<EditorDraft>,
        calendar: MutableState<CalendarUiState>,
        refreshes: MutableState<Int>,
    ) {
        compose.setContent {
            ZhituTheme { Editor(draft, calendar, refreshes) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Editor(
        draft: MutableState<EditorDraft>,
        calendar: MutableState<CalendarUiState>,
        refreshes: MutableState<Int>,
    ) {
        AlarmEditorScreen(
            draft = draft.value,
            calendarState = calendar.value,
            calendarOverrides = emptyMap(),
            update = { draft.value = it },
            onCalendarPreviewRefresh = { refreshes.value += 1 },
            onCancel = {},
            onSave = {},
            onDelete = {},
        )
    }

    private fun assertSevenEqualColumnsAndFilledRows(start: LocalDate) {
        val grid = compose.onNodeWithTag("workday_preview_grid").fetchSemanticsNode().boundsInRoot
        val headings = (1..7).map { compose.onNodeWithTag("workday_weekday_$it").fetchSemanticsNode().boundsInRoot }
        val expectedWidth = headings.first().width
        headings.forEachIndexed { index, bounds ->
            assertClose(expectedWidth, bounds.width, "表头第 ${index + 1} 列宽度")
        }
        assertClose(grid.left, headings.first().left, "表头首列对齐网格")
        assertClose(grid.right, headings.last().right, "表头末列撑满网格")

        (0..3).forEach { row ->
            val rowBounds = compose.onNodeWithTag("workday_week_$row").fetchSemanticsNode().boundsInRoot
            assertClose(grid.left, rowBounds.left, "第 ${row + 1} 行首列对齐网格")
            assertClose(grid.right, rowBounds.right, "第 ${row + 1} 行末列撑满网格")
            (0..6).forEach { column ->
                val date = start.plusDays((row * 7 + column).toLong()).toString()
                val bounds = dayBounds(date)
                assertClose(expectedWidth, bounds.width, "$date 列宽")
                assertClose(headings[column].left, bounds.left, "$date 与表头对齐")
            }
        }
    }

    private fun dayBounds(date: String): Rect =
        compose.onNodeWithTag("workday_day_$date").fetchSemanticsNode().boundsInRoot

    private fun assertContainsArgb(image: Bitmap, card: Rect, day: Rect, expected: Int, label: String) {
        val left = (day.left - card.left).toInt().coerceAtLeast(0)
        val top = (day.top - card.top).toInt().coerceAtLeast(0)
        val right = (day.right - card.left).toInt().coerceAtMost(image.width)
        val bottom = (day.bottom - card.top).toInt().coerceAtMost(image.height)
        val found = (top until bottom).any { y -> (left until right).any { x -> image.getPixel(x, y) == expected } }
        assertTrue("$label 未出现预期色 #${Integer.toHexString(expected)}", found)
    }

    private fun assertClose(expected: Float, actual: Float, label: String) {
        assertTrue("$label：expected=$expected actual=$actual", abs(expected - actual) <= 1f)
    }
}
