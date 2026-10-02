package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityCheck
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityResult
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEvent
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshDiagnostic
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshFailure
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarRefreshOutcome
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarSourceAttempt
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarSourceOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        compose.onNodeWithTag("permission_diagnostics").performScrollToNode(hasTestTag("permissions_return"))
        compose.onNodeWithTag("permissions_return").performClick()

        assertEquals(PermissionSetting.FullScreenIntent, setting.value)
        assertEquals(1, returns.value)
    }

    @Test
    fun diagnosticsTimelineAndRingtoneReadabilityRenderWithoutSensitiveIdentifiers() {
        compose.setContent {
            ZhituTheme {
                PermissionDiagnosticsContent(
                    snapshot = snapshot(isXiaomi = false),
                    confirmations = emptySet(),
                    appVersion = "0.1.0",
                    sdkInt = 36,
                    onSetting = {},
                    onConfirm = {},
                    onRefresh = {},
                    onBack = {},
                    onNotificationRequest = {},
                    ringtoneReadability = RingtoneReadabilityCheck(
                        result = RingtoneReadabilityResult.DEFAULT_FALLBACK,
                        configuredSoundCount = 2,
                        fallbackCount = 1,
                    ),
                    diagnosticEvents = listOf(
                        DiagnosticEvent(
                            eventType = DiagnosticEventType.CALENDAR_REFRESH,
                            resultCode = DiagnosticResultCode.NETWORK,
                            appVersion = "0.1.0",
                            sdkInt = 36,
                            planIdHash = "never-render-this",
                            occurrenceIdHash = "never-render-this-either",
                            durationMs = 1_230L,
                            timestamp = 1L,
                        ),
                        DiagnosticEvent(
                            eventType = DiagnosticEventType.RINGTONE_CHECK,
                            resultCode = DiagnosticResultCode.DEFAULT_FALLBACK,
                            appVersion = "0.1.0",
                            sdkInt = 36,
                            durationMs = 20L,
                            timestamp = 2L,
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("最近本地记录").performScrollTo().assertExists()
        compose.onNodeWithTag("ringtone_readability").assertExists()
        compose.onNodeWithText("备用铃声可读取").assertExists()
        compose.onNodeWithText("仅验证来源可读取，不播放铃声，也不改变闹钟。").assertExists()
        compose.onNodeWithTag("permission_diagnostics").performScrollToNode(hasTestTag("diagnostic_event_2_RINGTONE_CHECK"))
        compose.onNodeWithTag("diagnostic_event_2_RINGTONE_CHECK").assertExists()
        compose.onNodeWithTag("permission_diagnostics").performScrollToNode(hasTestTag("diagnostic_event_1_CALENDAR_REFRESH"))
        compose.onNodeWithTag("diagnostic_event_1_CALENDAR_REFRESH").assertExists()
        compose.onNodeWithText("never-render-this", substring = true).assertDoesNotExist()

    }

    @Test
    fun calendarRefreshDiagnosticsRenderFailureCategoryAndLimitedSource() {
        compose.setContent {
            ZhituTheme {
                PermissionDiagnosticsContent(
                    snapshot = snapshot(isXiaomi = false),
                    confirmations = emptySet(),
                    onSetting = {},
                    onConfirm = {},
                    onRefresh = {},
                    onBack = {},
                    onNotificationRequest = {},
                    calendarDiagnostics = listOf(
                        CalendarRefreshDiagnostic(
                            startedAt = 0L,
                            durationMillis = 1_230L,
                            outcome = CalendarRefreshOutcome.FAILED,
                            failure = CalendarRefreshFailure.NETWORK,
                            consecutiveFailures = 2,
                            attempts = listOf(
                                CalendarSourceAttempt(
                                    year = 2026,
                                    sourceHost = "raw.githubusercontent.com",
                                    outcome = CalendarSourceOutcome.SKIPPED_LIMIT,
                                    durationMillis = 0L,
                                    consecutiveFailures = 4,
                                    dailyFailures = 4,
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithTag("permission_diagnostics").performScrollToNode(hasText("最近日历刷新"))
        compose.onNodeWithText("最近日历刷新").assertExists()
        compose.onNodeWithTag("permission_diagnostics").performScrollToNode(hasText("今日失败已达上限", substring = true))
        compose.onNodeWithText("网络失败", substring = true).assertExists()
        compose.onNodeWithText("今日失败已达上限", substring = true).assertExists()
        compose.onNodeWithText("连续失败 2 次").assertExists()
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
}
