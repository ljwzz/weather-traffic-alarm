package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
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
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideState
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
        // A just-installed debug build has no notification grant, and the platform refuses to
        // register an alarm without it. Grant it so the cases exercise a real registration.
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
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
        seed(
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

        tapDayControl("day_status_work")
        // The buffer profile is edited by three relative steps so the test does not depend
        // on the global values the user configured on the device. The day screen is a lazy
        // list that composes the whole weather card as soon as one of its rows is visible, so
        // scrolling to the first stepper alone leaves the moderate and severe rows below the
        // fold on a shorter display and their taps are dropped without an error. Scrolling to
        // the row directly below the card keeps all three steppers inside the viewport.
        scrollDayTo("day_origin_${home.poiId}")
        tapDayControl("day_buffer_light_plus")
        tapDayControl("day_buffer_moderate_plus")
        tapDayControl("day_buffer_severe_plus")
        tapDayControl("day_preparation_15")
        tapDayControl("day_origin_${home.poiId}")
        tapDayControl("day_destination_${office.poiId}")
        tapDayControl("day_commute_mode_TRANSIT", anchor = "day_summary")
        tapDayControl("day_wake_row_value")
        compose.onNodeWithTag("day_wake_picker_confirm").assertIsDisplayed().performClick()
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
        val stored = seed(
            SingleDayOverride(plan.id, target.toString(), DayStatus.WORKDAY, wakeLocalTime = "07:05"),
        )
        openCalendar(plan)
        scrollDayTo("day_wake_row_value")
        compose.onNodeWithTag("day_wake_row_value").assertTextEquals("07:05")

        tapDayControl("day_status_rest")
        tapDayControl("day_preparation_60")
        screenshot("day-override-cancel-draft.png")
        waitForAppInForeground()
        compose.onNodeWithTag("day_top_back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isNotEmpty() }
        // The scenario rule needs the activity resumed before it can destroy it.
        waitForAppInForeground()

        val afterCancel = dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())
        assertEquals(stored.override, afterCancel)
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
        seed(
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

        tapDayControl("day_status_auto")
        tapDayControl("day_wake_row_clear")
        tapDayControl("day_arrival_row_clear")
        tapDayControl("day_preparation_clear")
        tapDayControl("day_buffer_clear")
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
        val neighbour = seed(
            SingleDayOverride(plan.id, other.toString(), DayStatus.WORKDAY, preparationMinutes = 5),
        )
        openCalendar(plan)
        val revisionBefore = dependencies.workdayOverrides()
            .getForPlanDate(plan.id, today.toString())?.dayRevision ?: 0L

        repeat(2) { index ->
            tapDayControl("day_status_work")
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
        assertEquals(neighbour.override, dependencies.workdayOverrides().getForPlanDate(plan.id, other.toString()))
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
     * Scrolls a day control fully into view on both axes, then taps it. Controls live in the
     * vertical day list and, for status, preparation, buffers and commute, in a nested
     * horizontal row; without the inner scroll the control exists but its touch point can sit
     * outside the viewport and the injected tap is silently dropped.
     */
    private fun tapDayControl(tag: String, anchor: String? = null) {
        // The lazy list stops composing as soon as the enclosing card exists, so a control in the
        // last row of a card can exist while its touch point is still outside the viewport.
        // [anchor] scrolls one known row further so the whole card is inside before the tap.
        anchor?.let { scrollDayTo(it) }
        compose.onNodeWithTag("day_screen_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
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

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }

    /**
     * Seeds one stored day snapshot through the immediate commit path. The revision is read from
     * the same table the editor loads, so the page and the stored row agree on the generation.
     */
    private suspend fun seed(override: SingleDayOverride): DayOverrideState = requireNotNull(
        dependencies.workdayOverrides().commitCurrent(
            planId = override.planId,
            date = override.date,
            replacement = override,
            now = System.currentTimeMillis(),
        ),
    ) { "无法预置日期覆盖" }

    /** Clearing one field must clear exactly that field and keep every other submitted value. */
    @Test
    fun restoringOneFieldClearsOnlyThatField() = runBlocking {
        val plan = createPlan("单日覆盖验收-单字段")
        val target = today
        seed(SingleDayOverride(plan.id, target.toString(), wakeLocalTime = "07:05", preparationMinutes = 45))
        openCalendar(plan)
        scrollDayTo("day_wake_row_value")
        compose.onNodeWithTag("day_wake_row_value").assertTextEquals("07:05")

        tapDayControl("day_wake_row_clear")
        compose.onNodeWithTag("day_override_save").performClick()

        compose.waitUntil(10_000) {
            runBlocking { dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())?.wakeLocalTime == null }
        }
        val stored = requireNotNull(dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString()))
        assertNull("restoring wake inheritance must clear the stored wake", stored.wakeLocalTime)
        assertEquals(45, stored.preparationMinutes)
        assertEquals(2L, stored.dayRevision)
        screenshot("day-override-single-field.png")
    }

    /** Restoring the day-level commute removes the stored combination as one unit. */
    @Test
    fun restoringCommuteInheritanceClearsTheStoredCombination() = runBlocking {
        val plan = createPlan("单日覆盖验收-通勤恢复")
        val target = today
        seed(
            SingleDayOverride(
                planId = plan.id,
                date = target.toString(),
                preparationMinutes = 45,
                origin = home,
                destination = office,
                commuteMode = CommuteMode.TRANSIT,
            ),
        )
        openCalendar(plan)
        scrollDayTo("day_summary")
        compose.onNodeWithTag("day_summary").assertIsDisplayed()

        tapDayControl("day_commute_inherit")
        compose.onNodeWithTag("day_override_save").performClick()

        compose.waitUntil(10_000) {
            runBlocking { dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())?.origin == null }
        }
        val stored = requireNotNull(dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString()))
        assertNull("inherit commute must remove the stored combination", stored.origin)
        assertNull(stored.destination)
        assertNull(stored.commuteMode)
        assertEquals(45, stored.preparationMinutes)
        screenshot("day-override-commute-restored.png")
    }

    /** An incomplete commute is a field error: the page must not call the save or write anything. */
    @Test
    fun incompleteCommuteIsRejectedWithoutWriting() = runBlocking {
        val plan = createPlan("单日覆盖验收-非法通勤")
        val target = today
        val stored = seed(SingleDayOverride(plan.id, target.toString(), preparationMinutes = 45))
        openCalendar(plan)

        tapDayControl("day_commute_mode_TRANSIT", anchor = "day_summary")
        compose.onNodeWithTag("day_override_save").performClick()

        compose.waitForIdle()
        compose.onAllNodesWithText("本日通勤需要同时选择起点和终点").assertCountEquals(1)
        val after = dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())
        assertEquals("an invalid draft must not write", stored.committedRevision, after?.dayRevision)
        assertNull(after?.commuteMode)
        screenshot("day-override-invalid-commute.png")
    }

    /** A conflicting save keeps the draft, reports the reason and offers an explicit reload. */
    @Test
    fun conflictingSaveKeepsTheDraftAndOffersReload() = runBlocking {
        val plan = createPlan("单日覆盖验收-冲突")
        val target = today
        seed(SingleDayOverride(plan.id, target.toString(), preparationMinutes = 45))
        openCalendar(plan)

        // Another surface commits this date while the page is open.
        seed(SingleDayOverride(plan.id, target.toString(), preparationMinutes = 15))
        tapDayControl("day_preparation_60")
        compose.onNodeWithTag("day_override_save").performClick()

        // The failure card is the last list item, so it must be scrolled into view first.
        awaitDayRow("day_override_reload")
        compose.onAllNodesWithText("该日期已被其他操作修改，请重新载入后再保存").assertCountEquals(1)
        assertEquals(15, dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())?.preparationMinutes)
        // The user's draft is still selected while the stored row keeps the other surface's value.
        scrollDayTo("day_preparation_60")
        compose.onAllNodesWithText("本日 60 分钟").assertCountEquals(1)
        screenshot("day-override-conflict.png")

        tapDayControl("day_override_reload")
        awaitDayRow("day_preparation_15")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("本日 15 分钟").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The weather stepper starts from the user's configured global profile. */
    @Test
    fun customGlobalWeatherProfileDrivesTheFirstStep() = runBlocking {
        val plan = createPlan("单日覆盖验收-全局缓冲")
        val target = today
        dependencies.settings().update { it.copy(workdayWeatherBuffers = WeatherBuffers(1, 2, 3)) }
        openCalendar(plan)

        scrollDayTo("day_origin_${home.poiId}")
        tapDayControl("day_buffer_light_plus")
        compose.onNodeWithTag("day_override_save").performClick()

        compose.waitUntil(10_000) {
            runBlocking { dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString())?.weatherProfile != null }
        }
        val stored = requireNotNull(dependencies.workdayOverrides().getForPlanDate(plan.id, target.toString()))
        assertEquals(WeatherBufferProfile(6, 2, 3), stored.weatherProfile)
        screenshot("day-override-global-buffers.png")
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
