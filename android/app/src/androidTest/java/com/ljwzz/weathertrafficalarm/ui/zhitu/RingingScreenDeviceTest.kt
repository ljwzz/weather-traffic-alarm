package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RingingScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ringingActionsInvokeDismissAndSnooze() {
        val dismisses = mutableIntStateOf(0)
        val snoozes = mutableIntStateOf(0)
        setRingingContent(
            state = RingingUiState(
                phase = RingingPhase.RINGING,
                alarmTime = "07:30",
                dateLabel = "9月2日  星期二",
                badge = "按设定时间提醒",
                reasonTitle = "基础闹钟",
                reason = "按计划 07:30 提醒；\n不依赖天气或路线。",
            ),
            onDismiss = { dismisses.intValue++ },
            onSnooze = { snoozes.intValue++ },
        )

        compose.onNodeWithTag("ringing_dismiss").assertHasClickAction().performClick()
        compose.onNodeWithTag("ringing_snooze").assertHasClickAction().performClick()

        assertEquals(1, dismisses.intValue)
        assertEquals(1, snoozes.intValue)
    }

    @Test
    fun busyRingingDisablesBothActionsAndShowsFeedback() {
        val dismisses = mutableIntStateOf(0)
        val snoozes = mutableIntStateOf(0)
        setRingingContent(
            state = RingingUiState(phase = RingingPhase.RINGING, busy = true),
            onDismiss = { dismisses.intValue++ },
            onSnooze = { snoozes.intValue++ },
        )

        compose.onNodeWithTag("ringing_dismiss").assertIsNotEnabled()
        compose.onNodeWithTag("ringing_snooze").assertIsNotEnabled()
        compose.onNodeWithText("正在处理").assertExists()
        assertEquals(0, dismisses.intValue)
        assertEquals(0, snoozes.intValue)
    }

    @Test
    fun scheduledAndUnavailableCannotInvokeRingingActions() {
        val dismisses = mutableIntStateOf(0)
        val snoozes = mutableIntStateOf(0)
        val opens = mutableIntStateOf(0)
        val closes = mutableIntStateOf(0)
        val state = mutableStateOf(RingingUiState(phase = RingingPhase.SCHEDULED))
        compose.setContent {
            ZhituTheme {
                RingingScreen(
                    state = state.value,
                    onDismiss = { dismisses.intValue++ },
                    onSnooze = { snoozes.intValue++ },
                    onOpenPlans = { opens.intValue++ },
                    onClose = { closes.intValue++ },
                )
            }
        }

        compose.onNodeWithTag("ringing_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("ringing_snooze").assertDoesNotExist()
        compose.onNodeWithTag("ringing_open_plans").performClick()
        compose.onNodeWithTag("ringing_close").performClick()
        state.value = RingingUiState(phase = RingingPhase.UNAVAILABLE)
        compose.waitForIdle()
        compose.onNodeWithTag("ringing_open_plans").performClick()
        compose.onNodeWithTag("ringing_close").performClick()

        assertEquals(0, dismisses.intValue)
        assertEquals(0, snoozes.intValue)
        assertEquals(2, opens.intValue)
        assertEquals(2, closes.intValue)
    }

    @Test
    fun ringingErrorKeepsRealRingingActionsAvailableForRetry() {
        setRingingContent(
            state = RingingUiState(
                phase = RingingPhase.RINGING,
                errorMessage = "操作失败，请重试",
            ),
        )

        compose.onNodeWithText("操作失败，请重试").assertExists()
        compose.onNodeWithTag("ringing_dismiss").assertHasClickAction()
        compose.onNodeWithTag("ringing_snooze").assertHasClickAction()
    }

    @Test
    fun unavailableErrorOnlyOffersReturnAndClose() {
        setRingingContent(
            state = RingingUiState(
                phase = RingingPhase.UNAVAILABLE,
                errorMessage = "操作失败，请重试",
            ),
        )

        compose.onNodeWithText("操作失败，请重试").assertExists()
        compose.onNodeWithTag("ringing_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("ringing_snooze").assertDoesNotExist()
        compose.onNodeWithTag("ringing_open_plans").assertHasClickAction()
        compose.onNodeWithTag("ringing_close").assertHasClickAction()
    }

    @Test
    fun enlargedFontLayoutRemainsScrollableAndExposesActions() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 3f, fontScale = 1.8f)) {
                ZhituTheme {
                    RingingScreen(
                        state = RingingUiState(
                            phase = RingingPhase.RINGING,
                            dateLabel = "9月2日  星期二",
                            reasonTitle = "今天路上有小雨",
                            reason = "通勤约 47 分钟，建议 08:03 出发。\n慢慢准备，也能准时到达。",
                            footer = "由本 App 负责响铃、停止与贪睡。\n仅操作本次闹钟，不改变其他计划。",
                        ),
                        onDismiss = {},
                        onSnooze = {},
                        onOpenPlans = {},
                        onClose = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("ringing_content").assert(hasScrollAction())
        compose.onNodeWithTag("ringing_dismiss").assertExists()
        compose.onNodeWithTag("ringing_snooze").assertExists()
    }

    private fun setRingingContent(
        state: RingingUiState,
        onDismiss: () -> Unit = {},
        onSnooze: () -> Unit = {},
    ) {
        compose.setContent {
            ZhituTheme {
                RingingScreen(
                    state = state,
                    onDismiss = onDismiss,
                    onSnooze = onSnooze,
                    onOpenPlans = {},
                    onClose = {},
                )
            }
        }
    }
}
