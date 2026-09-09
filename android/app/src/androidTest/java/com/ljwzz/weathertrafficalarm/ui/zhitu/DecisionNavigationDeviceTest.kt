package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import android.content.Intent
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.DeviceTestDependencies
import com.ljwzz.weathertrafficalarm.MainActivity
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Production navigation and Room graph; fixtures are explicitly persisted historical records. */
@RunWith(AndroidJUnit4::class)
class DecisionNavigationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var deps: DeviceTestDependencies
    private lateinit var settings: LocalSettings
    private val ownedPlans = mutableListOf<String>()

    @Before fun prepare() = runBlocking {
        deps = EntryPointAccessors.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext, DeviceTestDependencies::class.java)
        settings = deps.settings().loadInitial()
        deps.settings().update { it.copy(privacyAccepted = true, amapConsentPromptedVersion = 1, amapConsentGranted = false, originId = null, destinationId = null) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_content").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun clean() = runBlocking {
        ownedPlans.forEach {
            deps.coordinator().delete(it)
            deps.decisions().deleteByPlanId(it)
            deps.occurrences().deleteByPlanId(it)
        }
        deps.settings().update { settings }
        // This test intentionally changes launch actions and recreates MainActivity. Use the
        // live lifecycle registry instead of ActivityScenario's original-Intent matching.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct().forEach {
                    it.finish()
                }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun homePlanAndHistoryOpenTheirExactRecordAndRetainSnapshotAfterEditAndDelete() = runBlocking {
        val a = plan("早班通勤")
        val b = plan("晚班通勤")
        val aOld = decision(a, "已应用提前提醒", -120)
        val aNew = decision(a, "本次无需提前", -60)
        val bLatest = decision(b, "评估失败", 0)
        val beforePlans = deps.plans().observeAll().first()
        val beforeOccurrences = deps.occurrences().getAll()
        val beforeDecisions = deps.decisions().getByPlanId(a.id)

        compose.onNodeWithTag("home_content").performScrollToNode(hasTestTag("home_decision_${bLatest.decisionId}"))
        compose.onNodeWithTag("home_decision_${bLatest.decisionId}").performClick()
        waitDetail(bLatest)
        compose.onAllNodesWithText("评估失败").onFirst().assertExists()
        screenshot("history-failure-top.png")
        compose.onNodeWithTag("decision-detail-recovery-授权").performScrollTo().performClick()
        Espresso.pressBack()
        waitDetail(bLatest)
        compose.onNodeWithTag("decision-detail-refresh").performScrollTo().performClick()
        assertEquals(beforePlans, deps.plans().observeAll().first())
        assertEquals(beforeOccurrences, deps.occurrences().getAll())
        assertEquals(beforeDecisions, deps.decisions().getByPlanId(a.id))
        Espresso.pressBack()

        compose.onNodeWithTag("home_content").performScrollToIndex(0)
        compose.onNodeWithTag("home_content").performScrollToNode(hasTestTag("plan_decision_${a.id}"))
        compose.onNodeWithTag("plan_decision_${a.id}").performClick()
        waitDetail(aNew)
        compose.onNodeWithText("本次无需提前").assertExists()
        compose.onNodeWithTag("decision-detail-history").performScrollTo().performClick()
        compose.onNodeWithTag("history_content").performScrollToNode(hasTestTag("history_decision_${aOld.decisionId}"))
        compose.onNodeWithTag("history_decision_${aOld.decisionId}").performClick()
        waitDetail(aOld)
        screenshot("history-success-top.png")

        deps.coordinator().save(a.copy(name = "已修改名称", defaultWakeLocalTime = "08:10"))
        compose.onNodeWithTag("decision-detail-refresh").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("当前计划已更新；以下为评估时快照。").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(aOld, deps.decisions().getById(aOld.decisionId))
        deps.coordinator().delete(a.id)
        compose.onNodeWithTag("decision-detail-refresh").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("当前计划已删除；以下为本次历史记录。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("decision-detail-reevaluate").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("decision-detail-feedback").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(aOld, deps.decisions().getById(aOld.decisionId))
        compose.onNodeWithTag("decision-detail-${aOld.decisionId}").performScrollToIndex(0)
        compose.onNodeWithText("早班通勤").assertExists()
        screenshot("deleted-plan-historical-detail.png")
    }

    @Test fun newOccurrenceIntentsAndMissingIdentifiersNeverFallBackToThePreviousDecision() = runBlocking {
        val a = plan("实例甲")
        val b = plan("实例乙")
        val aRecord = decision(a, "已应用提前提醒", -60)
        val bRecord = decision(b, "已应用提前提醒", 0)
        suspend fun occurrence(plan: AlarmPlan, record: AlarmDecision): String {
            val id = UUID.randomUUID().toString()
            deps.occurrences().save(AlarmOccurrence(id, plan.id, plan.revision, record.targetDate,
                Instant.parse(record.actualWakeAt).toEpochMilli(), OccurrenceState.DISMISSED,
                record.decisionId, OccurrenceKind.ADVANCE))
            return id
        }
        val aId = occurrence(a, aRecord)
        val bId = occurrence(b, bRecord)
        fun open(occurrenceId: String?) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                val intent = Intent(activity, MainActivity::class.java).apply {
                    action = ACTION_OPEN_DECISION_DETAIL
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    occurrenceId?.let { putExtra(EXTRA_DECISION_OCCURRENCE_ID, it) }
                }
                activity.startActivity(intent)
            }
        }
        open(aId)
        waitDetail(aRecord)
        open("already-cleaned-occurrence")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("本次响铃实例已不存在或已清理。").fetchSemanticsNodes().isNotEmpty() }
        open(bId)
        waitDetail(bRecord)
        open(null)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("详情入口缺少唯一记录标识。").fetchSemanticsNodes().isNotEmpty() }
        screenshot("missing-detail-identity.png")
        assertEquals(aRecord, deps.decisions().getById(aRecord.decisionId))
        assertEquals(bRecord, deps.decisions().getById(bRecord.decisionId))
    }

    private suspend fun plan(name: String): AlarmPlan {
        val id = "decision-navigation-${UUID.randomUUID()}"
        ownedPlans += id
        return deps.coordinator().save(AlarmPlan(id = id, revision = 0, name = name, enabled = true,
            zoneId = "Asia/Shanghai", defaultWakeLocalTime = "07:30", arrivalLocalTime = "09:00", preparationMinutes = 30,
            maxAdvanceMinutes = 60, commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Once(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString())))
    }

    private suspend fun decision(plan: AlarmPlan, title: String, offset: Long): AlarmDecision {
        val day = (plan.schedule as AlarmSchedule.Once).date
        fun at(time: String) = LocalDate.parse(day).atTime(java.time.LocalTime.parse(time)).atZone(ZoneId.of(plan.zoneId)).toInstant().toString()
        return AlarmDecision(decisionId = UUID.randomUUID().toString(), planId = plan.id, planRevision = plan.revision,
            targetDate = day, workdayStatus = WorkdayStatus.WORKDAY, estimatedDepartureAt = at("08:03"), commuteSeconds = 2821,
            weatherSeverity = 1, weatherBufferMinutes = 10, recommendedWakeAt = if (title == "本次无需提前") at("07:30") else at("07:18"),
            routeProvider = "AMAP", routeProviderReportTime = Instant.now().toString(), weatherProvider = "CAIYUN", weatherProviderReportTime = Instant.now().toString(),
            weatherWindowStart = at("06:30"), weatherWindowEnd = at("09:00"), fallbackReason = FallbackReason.NONE, insufficientAdvance = false,
            generatedAt = Instant.now().plusSeconds(offset).toString(), expiresAt = Instant.now().plusSeconds(7200).toString(),
            evaluationOutcome = if (title == "评估失败") EvaluationOutcome.FAILED else EvaluationOutcome.SUCCESS,
            failureReason = if (title == "评估失败") "ROUTE_CONSENT_REQUIRED" else null,
            applicationOutcome = when(title) { "评估失败" -> "UNCHANGED"; "本次无需提前" -> "CANCELLED"; else -> "APPLIED" },
            preparationMinutes = 45, defaultWakeAt = at("07:30"), actualWakeAt = if (title == "已应用提前提醒") at("07:18") else null,
            calendarSource = "HOLIDAY_CN", weatherDataSource = "NETWORK", planName = plan.name, zoneId = plan.zoneId,
        ).let { deps.decisions().save(it); requireNotNull(deps.decisions().getById(it.decisionId)) }
    }

    private fun waitDetail(decision: AlarmDecision) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("decision-detail-${decision.decisionId}").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa/phase2-decision-navigation")
        directory.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
