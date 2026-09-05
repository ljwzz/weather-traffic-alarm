package com.ljwzz.weathertrafficalarm

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationWorkScheduler
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies preview isolation against the installed application's Room and WorkManager graph. */
@RunWith(AndroidJUnit4::class)
class HomePreviewIsolationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun repeatedHomePullsPreservePlansOccurrencesDecisionsAndEvaluationWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        val originalSettings = deps.settings().loadInitial()
        val planId = "home-preview-isolation-${UUID.randomUUID()}"
        val workManager = WorkManager.getInstance(context)

        try {
            // Missing global endpoints and consent make this installed-app test deterministic.
            deps.settings().update {
                originalSettings.copy(
                    privacyAccepted = true,
                    amapConsentPromptedVersion = 1,
                    amapConsentGranted = false,
                    originId = null,
                    destinationId = null,
                )
            }
            val wake = Instant.now().plusSeconds(60 * 60).atZone(ZoneId.systemDefault())
            deps.coordinator().save(
                AlarmPlan(
                    id = planId,
                    revision = 0,
                    name = "首页预览隔离验证",
                    enabled = true,
                    zoneId = wake.zone.id,
                    defaultWakeLocalTime = wake.toLocalTime().withNano(0).toString(),
                    arrivalLocalTime = wake.toLocalTime().plusHours(1).withNano(0).toString(),
                    preparationMinutes = 20,
                    maxAdvanceMinutes = 45,
                    commuteMode = CommuteMode.DRIVING,
                    schedule = AlarmSchedule.Once(wake.toLocalDate().toString()),
                ),
            )
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("全部闹钟").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("home_pull_refresh").assertExists()

            val plansBefore = deps.plans().observeAll().first().sortedBy { it.id }
            val occurrencesBefore = deps.occurrences().getAll().sortedBy { it.occurrenceId }
            val decisionsBefore = deps.decisions().observeAll().first().sortedBy { it.decisionId }
            val workBefore = workManager.getWorkInfosByTag(EvaluationWorkScheduler.ALL_WORK_TAG)
                .get(10, TimeUnit.SECONDS).map { it.id }.toSet()
            assertTrue(occurrencesBefore.any {
                it.planId == planId && it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
            })

            repeat(3) {
                compose.onNodeWithTag("home_pull_refresh").performTouchInput { swipeDown() }
                compose.waitForIdle()
            }

            assertEquals(plansBefore, deps.plans().observeAll().first().sortedBy { it.id })
            assertEquals(occurrencesBefore, deps.occurrences().getAll().sortedBy { it.occurrenceId })
            assertEquals(decisionsBefore, deps.decisions().observeAll().first().sortedBy { it.decisionId })
            assertEquals(
                workBefore,
                workManager.getWorkInfosByTag(EvaluationWorkScheduler.ALL_WORK_TAG)
                    .get(10, TimeUnit.SECONDS).map { it.id }.toSet(),
            )
        } finally {
            runCatching { deps.coordinator().delete(planId) }
            deps.settings().update { originalSettings }
        }
    }
}
