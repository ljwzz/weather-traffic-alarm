package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.DeviceTestDependencies
import com.ljwzz.weathertrafficalarm.MainActivity
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N004-6 device acceptance for the single-day override page. It drives the production
 * application graph through Settings > 工作日日历 and verifies persistence, cancel, undo
 * and target-date isolation on a real device.
 */
@RunWith(AndroidJUnit4::class)
class SingleDayOverrideDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var dependencies: DeviceTestDependencies
    private lateinit var previousSettings: LocalSettings
    private val ownedPlanIds = mutableListOf<String>()

    private val home = PlaceRef("day-home", "日覆盖起点", "日覆盖起点", 116.30, 39.90, "110000", "010")
    private val office = PlaceRef("day-office", "日覆盖终点", "日覆盖终点", 116.40, 39.80, "110000", "010")

    private val today: LocalDate get() = LocalDate.now(ZoneId.systemDefault())
    private val tomorrow: LocalDate get() = today.plusDays(1)

    @Before
    fun prepare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dependencies = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        previousSettings = dependencies.settings().loadInitial()
        dependencies.settings().update {
            it.copy(
                privacyAccepted = true,
                amapConsentPromptedVersion = it.amapConsentPromptedVersion ?: 1,
                favorites = listOf(
                    FavoritePlace(home.poiId!!, home.name, home.displayAddress, home),
                    FavoritePlace(office.poiId!!, office.name, office.displayAddress, office),
                ),
                originId = home.poiId,
                destinationId = office.poiId,
                commuteMode = CommuteMode.DRIVING,
            )
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("home_content").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("暂不授权").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("暂不授权").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("暂不授权").performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("home_content").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun restoreOwnedState() = runBlocking {
        ownedPlanIds.forEach { dependencies.coordinator().delete(it) }
        dependencies.settings().update { previousSettings }
    }

    /**
     * Saving the page writes exactly the edited plan and date, and the resolved inputs see
     * the day values while the neighbouring date keeps the plan values.
     */
    @Test
    fun editingOneDayPersistsItsValuesAndLeavesTheNeighbourDateAlone() = runBlocking {
        val plan = createPlan("单日覆盖验收-保存")
        val target = today
        val neighbour = tomorrow
        dependencies.workdayOverrides().save(
            SingleDayOverride(plan.id, neighbour.toString(), DayStatus.HOLIDAY),
        )
        openCalendar(plan)
        scrollDayTo("day_status_auto")
        compose.onNodeWithTag("day_status_auto").assertIsDisplayed()
        scrollDayTo("day_wake_row_value")
        compose.onNodeWithTag("day_wake_row_value").assertTextEquals("${plan.defaultWakeLocalTime}（计划配置）")
        compose.onNodeWithTag("day_arrival_row_value").assertTextEquals("${plan.arrivalLocalTime}（计划配置）")
        scrollDayTo("day_summary")
        compose.onNodeWithTag("day_summary").assertIsDisplayed()
        screenshot("day-override-inherited.png")

        scrollDayTo("day_status_work")
        compose.onNodeWithTag("day_status_work").performClick()
        // The buffer profile is edited by three relative steps so the test does not depend
        // on the global values the user configured on the device. The day screen is a lazy
        // list that composes the whole weather card as soon as one of its rows is visible, so
        // scrolling to the first stepper alone leaves the moderate and severe rows below the
        // fold on a shorter display and their taps are dropped without an error. Scrolling to
        // the row directly below the card keeps all three steppers inside the viewport.
        scrollDayTo("day_origin_${home.poiId}")
        compose.onNodeWithTag("day_buffer_light_plus").assertIsDisplayed().performClick()
        compose.onNodeWithTag("day_buffer_moderate_plus").assertIsDisplayed().performClick()
        compose.onNodeWithTag("day_buffer_severe_plus").assertIsDisplayed().performClick()
        scrollDayTo("day_preparation_15")
        compose.onNodeWithTag("day_preparation_15").performClick()
        scrollDayTo("day_origin_${home.poiId}")
        compose.onNodeWithTag("day_origin_${home.poiId}").performClick()
        compose.onNodeWithTag("day_destination_${office.poiId}").performClick()
        compose.onNodeWithTag("day_commute_mode_TRANSIT").performClick()
        scrollDayTo("day_wake_row_value")
        compose.onNodeWithTag("day_wake_row_value").performClick()
        compose.onNodeWithTag("day_wake_picker_confirm").performClick()
        compose.waitForIdle()
        screenshot("day-override-edited.png")
        compose.onNodeWithTag("day_override_save").performClick()

        compose.waitUntil(10_000) {
            runBlocking { dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())?.status == DayStatus.WORKDAY }
        }
        val stored = requireNotNull(dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString()))
        assertEquals(1L, stored.dayRevision)
        assertEquals(15, stored.preparationMinutes)
        assertEquals(CommuteMode.TRANSIT, stored.commuteMode)
        assertEquals(home, stored.origin)
        assertEquals(office, stored.destination)
        assertEquals(plan.defaultWakeLocalTime, stored.wakeLocalTime)
        val inheritedBefore = dependencies.settings().loadInitial().workdayWeatherBuffers
        assertEquals(
            WeatherBufferProfile(
                lightMinutes = (inheritedBefore.lightMinutes + 5).coerceAtMost(60),
                moderateMinutes = (inheritedBefore.moderateMinutes + 5).coerceAtMost(60),
                severeMinutes = (inheritedBefore.severeMinutes + 5).coerceAtMost(60),
            ),
            stored.weatherProfile,
        )

        val resolvedTarget = dependencies.effectiveCommutes().resolveForPlanDate(plan.id, target.toString(), dependencies.settings().loadInitial(), stored)
        assertEquals(CommuteMode.TRANSIT, resolvedTarget?.commuteMode)
        assertEquals(home, resolvedTarget?.origin)
        val neighbourOverride = dependencies.workdayOverrides().getForPlanDate(plan.id, neighbour.toString())
        assertEquals(DayStatus.HOLIDAY, neighbourOverride?.status)
        assertEquals(1L, neighbourOverride?.dayRevision)
        assertNull(neighbourOverride?.preparationMinutes)
        assertEquals(plan.id, dependencies.plans().getById(plan.id)?.id)
        // The day override never bumps the plan revision: the save in setUp did it once.
        val storedPlan = requireNotNull(dependencies.plans().getById(plan.id))
        assertEquals(plan.revision + 1, storedPlan.revision)
        screenshot("day-override-saved.png")
    }

    /** Leaving the page without saving must not change the stored day at all. */
    @Test
    fun cancelWithoutSavingKeepsTheStoredDayValues() {
        runBlocking { cancelWithoutSaving() }
    }

    private suspend fun cancelWithoutSaving() {
        val plan = createPlan("单日覆盖验收-取消")
        val target = today
        val stored = dependencies.workdayOverrides().save(
            SingleDayOverride(plan.id, target.toString(), DayStatus.WORKDAY, wakeLocalTime = "07:05"),
        )
        openCalendar(plan)
        scrollDayTo("day_wake_row_value")
        compose.onNodeWithTag("day_wake_row_value").assertTextEquals("07:05")

        scrollDayTo("day_status_rest")
        compose.onNodeWithTag("day_status_rest").performClick()
        scrollDayTo("day_preparation_60")
        compose.onNodeWithTag("day_preparation_60").performClick()
        screenshot("day-override-cancel-draft.png")
        waitForAppInForeground()
        compose.onNodeWithTag("day_top_back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isNotEmpty() }
        // The scenario rule needs the activity resumed before it can destroy it.
        waitForAppInForeground()

        val afterCancel = dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())
        assertEquals(stored, afterCancel)
        assertEquals(DayStatus.WORKDAY, afterCancel?.status)
        assertEquals("07:05", afterCancel?.wakeLocalTime)
        assertNull(afterCancel?.preparationMinutes)
        screenshot("day-override-cancelled.png")
    }

    /** Clearing every day field removes the row, restores inheritance and bumps the revision on save. */
    @Test
    fun clearingEveryFieldRemovesTheRowAndRestoresInheritance() = runBlocking {
        val plan = createPlan("单日覆盖验收-撤销")
        val target = today
        dependencies.workdayOverrides().save(
            SingleDayOverride(
                planId = plan.id,
                date = target.toString(),
                status = DayStatus.HOLIDAY,
                wakeLocalTime = "07:15",
                arrivalLocalTime = "08:50",
                preparationMinutes = 45,
                weatherProfile = WeatherBufferProfile(3, 6, 9),
            ),
        )
        openCalendar(plan)
        scrollDayTo("day_wake_row_value")
        compose.onNodeWithTag("day_wake_row_value").assertTextEquals("07:15")
        compose.onNodeWithTag("day_arrival_row_value").assertTextEquals("08:50")
        screenshot("day-override-stored.png")

        scrollDayTo("day_status_auto")
        compose.onNodeWithTag("day_status_auto").performClick()
        scrollDayTo("day_wake_row_clear")
        compose.onNodeWithTag("day_wake_row_clear").performClick()
        compose.onNodeWithTag("day_arrival_row_clear").performClick()
        scrollDayTo("day_preparation_clear")
        compose.onNodeWithTag("day_preparation_clear").performClick()
        scrollDayTo("day_buffer_clear")
        compose.onNodeWithTag("day_buffer_clear").performClick()
        compose.onNodeWithTag("day_override_save").performClick()

        compose.waitUntil(10_000) {
            runBlocking { dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString()) == null }
        }
        assertNull(dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString()))
        compose.onNodeWithTag("day_wake_row_value").performScrollTo()
            .assertTextEquals("${plan.defaultWakeLocalTime}（计划配置）")
        screenshot("day-override-restored.png")
    }

    /** Saving the same day twice stays scoped to that date and keeps incrementing the revision. */
    @Test
    fun repeatedSavesOnOneDayDoNotTouchAnotherDay() = runBlocking {
        val plan = createPlan("单日覆盖验收-相邻日期")
        val other = today.plusDays(2)
        val neighbour = dependencies.workdayOverrides().save(
            SingleDayOverride(plan.id, other.toString(), DayStatus.WORKDAY, preparationMinutes = 5),
        )
        openCalendar(plan)
        val revisionBefore = dependencies.workdayOverrides()
            .getForPlanDate(plan.id, today.toString())?.dayRevision ?: 0L

        repeat(2) { index ->
            scrollDayTo("day_status_work")
        compose.onNodeWithTag("day_status_work").performClick()
            compose.onNodeWithTag("day_override_save").performClick()
            val expectedRevision = revisionBefore + index + 1
            compose.waitUntil(10_000) {
                runBlocking {
                    dependencies.workdayOverrides().getForPlanDate(plan.id, today.toString())?.dayRevision == expectedRevision
                }
            }
        }

        val own = requireNotNull(dependencies.workdayOverrides().getForPlanDate(plan.id, today.toString()))
        assertEquals(revisionBefore + 2, own.dayRevision)
        assertEquals(neighbour, dependencies.workdayOverrides().getForPlanDate(plan.id, other.toString()))
        screenshot("day-override-neighbour-safe.png")
    }

    private fun openCalendar(plan: AlarmPlan) {
        waitForAppInForeground()
        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("setting-calendar"))
        compose.onNodeWithTag("setting-calendar").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("day_screen_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("day_screen_list").performScrollToNode(hasTestTag("day_plan_${plan.id}"))
        compose.onNodeWithTag("day_plan_${plan.id}").performClick()
        awaitDayRow("day_status_auto")
        compose.onNodeWithTag("day_status_auto").assertIsDisplayed()
    }

    /** Scrolls the day editor's own vertical list; other rows scroll horizontally. */
    private fun scrollDayTo(tag: String) {
        compose.onNodeWithTag("day_screen_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).fetchSemanticsNode()
    }

    /**
     * Waits for one day-editor row and scrolls it into view. The day screen is a `LazyColumn`
     * that only composes its visible window, so an editor card below the fold is absent from
     * the semantics tree until the list scrolls; the cards are also appended asynchronously
     * once the plan and the stored day load. Both make a plain existence wait viewport
     * dependent, and the API 36 emulator (480 dpi, ~1066 dp tall) and the physical device
     * (600 dpi, ~853 dp tall) do not compose the same window.
     */
    private fun awaitDayRow(tag: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            compose.waitForIdle()
            if (runCatching { scrollDayTo(tag) }.onFailure { lastFailure = it }.isSuccess) return
            SystemClock.sleep(200L)
        }
        throw AssertionError("等待日期编辑项 $tag 超时", lastFailure)
    }

    private suspend fun createPlan(name: String): AlarmPlan {
        val plan = AlarmPlan(
            id = UUID.randomUUID().toString(),
            revision = 0,
            name = name,
            enabled = true,
            zoneId = ZoneId.systemDefault().id,
            defaultWakeLocalTime = "06:20",
            arrivalLocalTime = "09:10",
            preparationMinutes = 30,
            maxAdvanceMinutes = 60,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Workdays,
            armedState = AlarmArmedState.DISABLED,
        )
        dependencies.coordinator().save(plan)
        ownedPlanIds += plan.id
        assertNotNull(dependencies.plans().getById(plan.id))
        return plan
    }

    /**
     * Compose idling never settles while another window owns the foreground, so every
     * interaction step first waits for the app under test to be resumed again.
     */
    private fun waitForAppInForeground() {
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            if (isAppInForeground()) {
                compose.waitForIdle()
                return
            }
            SystemClock.sleep(500L)
        }
        throw AssertionError("等待应用回到前台超时")
    }

    private fun isAppInForeground(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val expected = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        return automation.rootInActiveWindow?.packageName?.toString() == expected
    }

    private fun bringAppToForeground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun screenshot(name: String) {
        waitForAppInForeground()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = screenshotDirectory(instrumentation.targetContext)
        directory.mkdirs()
        FileOutputStream(File(directory, name)).use {
            assertTrue("无法保存截图", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    /**
     * `additionalTestOutputDir` lives on shared storage so Gradle can copy the screenshots
     * into the build's test-result directory before the test APKs are uninstalled.
     */
    private fun screenshotDirectory(context: android.content.Context): File {
        val arguments = InstrumentationRegistry.getArguments()
        val outputDir = arguments.getString("additionalTestOutputDir")
        if (!outputDir.isNullOrBlank()) {
            val target = File(outputDir)
            if (target.isDirectory || target.mkdirs()) return target
        }
        return requireNotNull(context.getExternalFilesDir("single-day-override-qa"))
    }
}
