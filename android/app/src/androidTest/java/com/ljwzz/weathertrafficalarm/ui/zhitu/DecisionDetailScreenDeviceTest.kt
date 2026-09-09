package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Pure screen coverage: data is supplied directly so that opening the detail
 * proves it only renders recorded values and invokes explicit callbacks.
 */
@RunWith(AndroidJUnit4::class)
class DecisionDetailScreenDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun successShowsRecordedBreakdownAndLocalActionsOnlyInvokeCallbacks() {
        var refreshes = 0
        var histories = 0
        setScreen(
            detail = sample(),
            onRefresh = { refreshes++ },
            onHistory = { histories++ },
        )

        compose.onNodeWithText("晨间通勤").assertIsDisplayed()
        compose.onNodeWithText("已应用提前提醒").assertIsDisplayed()
        compose.onNodeWithTag("decision-detail-sources").performScrollTo().performClick()
        compose.onNodeWithText("天气来源：彩云 · 9月7日 21:02").assertIsDisplayed()
        compose.onNodeWithTag("decision-detail-refresh").performScrollTo().performClick()
        compose.onNodeWithTag("decision-detail-history").performClick()
        assertEquals(1, refreshes)
        assertEquals(1, histories)
        screenshot("decision-detail-success.png")
    }

    @Test
    fun failureUsesRecordedReasonAndBusyGuardPreventsAnotherReevaluation() {
        var reevaluations = 0
        var credentials = 0
        var diagnostics = 0
        val busy = mutableStateOf(false)
        setScreen(
            detail = sample(
                title = "评估失败",
                evaluationLabel = "评估失败",
                evaluationTone = DecisionDetailTone.WARNING,
                applicationLabel = "本次未新增或调整提醒",
                failureReason = "天气服务授权不可用",
                recoveryActions = listOf(DecisionRecoveryAction.CREDENTIALS, DecisionRecoveryAction.DIAGNOSTICS),
            ),
            busy = busy,
            onReevaluate = { reevaluations++ },
            onCredentials = { credentials++ },
            onDiagnostics = { diagnostics++ },
            nextRetryLabel = "下次重试：9月7日 21:30",
        )

        compose.onNodeWithText("天气服务授权不可用").assertIsDisplayed()
        compose.onNodeWithTag("decision-detail-reevaluate").performScrollTo().performClick()
        assertEquals(1, reevaluations)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("decision-detail-reevaluate").assertIsNotEnabled()
        compose.onNodeWithTag("decision-detail-recovery-凭据").performScrollTo().performClick()
        compose.onNodeWithTag("decision-detail-recovery-诊断").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, credentials)
        assertEquals(1, diagnostics)
        screenshot("decision-detail-failure.png")
    }

    @Test
    fun unavailableRecordDoesNotExposeAnotherDecisionOrActions() {
        setScreen(
            detail = DecisionDetailUi(
                available = false,
                unavailableMessage = "本次实例缺少决策关联。",
            ),
        )

        compose.onNodeWithText("无法显示本次决策").assertIsDisplayed()
        compose.onNodeWithText("本次实例缺少决策关联。").assertIsDisplayed()
        compose.onAllNodesWithTag("decision-detail-reevaluate").assertCountEquals(0)
        compose.onAllNodesWithTag("decision-detail-sources").assertCountEquals(0)
    }

    private fun setScreen(
        detail: DecisionDetailUi,
        busy: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(false),
        onReevaluate: () -> Unit = {},
        onCredentials: () -> Unit = {},
        onDiagnostics: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onHistory: () -> Unit = {},
        nextRetryLabel: String? = null,
    ) {
        compose.setContent {
            ZhituTheme {
                DecisionDetailScreen(
                    detail = detail,
                    onBack = {},
                    onReevaluate = onReevaluate,
                    reevaluateInProgress = busy.value,
                    reevaluateFeedback = null,
                    onCredentials = onCredentials,
                    onOnboarding = {},
                    onCommute = {},
                    onDiagnostics = onDiagnostics,
                    onRefresh = onRefresh,
                    nextRetryLabel = nextRetryLabel,
                    onHistory = onHistory,
                )
            }
        }
    }

    private fun screenshot(name: String) {
        val image = compose.onNodeWithTag("decision-detail-decision-1").captureToImage().asAndroidBitmap()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa/phase2-decision-detail")
        check(directory.exists() || directory.mkdirs())
        FileOutputStream(File(directory, name)).use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output) }
    }

    private fun sample(
        title: String = "已应用提前提醒",
        evaluationLabel: String = "评估完成",
        evaluationTone: DecisionDetailTone = DecisionDetailTone.POSITIVE,
        applicationLabel: String = "已应用：已注册本次提前提醒",
        failureReason: String? = null,
        recoveryActions: List<DecisionRecoveryAction> = emptyList(),
    ) = DecisionDetailUi(
        available = true,
        decisionId = "decision-1",
        planName = "晨间通勤",
        title = title,
        targetDate = "2026-09-08",
        evaluatedAt = "9月7日 21:02",
        zoneLabel = "Asia/Shanghai",
        evaluationLabel = evaluationLabel,
        evaluationTone = evaluationTone,
        baseWake = "9月8日 07:30",
        recommendedWake = if (evaluationTone == DecisionDetailTone.WARNING) "本次未生成建议" else "9月8日 07:18",
        actualWake = if (evaluationTone == DecisionDetailTone.WARNING) "本次未提供" else "9月8日 07:18",
        departure = "9月8日 08:03",
        commute = "47分钟",
        preparation = "45分钟",
        weather = "中度影响",
        weatherBuffer = "10分钟",
        dayRule = "工作日",
        applicationLabel = applicationLabel,
        occurrenceLabel = if (evaluationTone == DecisionDetailTone.WARNING) "本次未提供实例记录" else "提前实例：已注册",
        failureReason = failureReason,
        recoveryActions = recoveryActions,
        sourceLines = listOf("天气来源：彩云 · 9月7日 21:02"),
    )
}
