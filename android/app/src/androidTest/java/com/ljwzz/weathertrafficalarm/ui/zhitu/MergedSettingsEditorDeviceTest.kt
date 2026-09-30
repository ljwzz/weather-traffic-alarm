package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.DeviceTestDependencies
import com.ljwzz.weathertrafficalarm.MainActivity
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverride
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DailySettingsResolver
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfiles
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.LocalDate
import java.util.UUID

/**
 * Exercises the merged settings and alarm-editor flows against the production
 * application graph. It restores the user's settings and deletes only plans
 * created with this test's generated IDs.
 */
@RunWith(AndroidJUnit4::class)
class MergedSettingsEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var dependencies: DeviceTestDependencies
    private lateinit var previousSettings: LocalSettings
    private val ownedPlanIds = mutableListOf<String>()

    @Before
    fun prepare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dependencies = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        previousSettings = dependencies.settings().loadInitial()
        dependencies.settings().update {
            it.copy(
                privacyAccepted = true,
                amapConsentPromptedVersion = it.amapConsentPromptedVersion ?: 1,
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

    @Test
    fun settingsSummarizesReliabilityAndPersistsThreeIndependentWeatherBuffers() {
        openSettings()

        compose.onNodeWithTag("settings-screen").assertExists()
        compose.onAllNodesWithText("设置").onFirst().assertExists()
        compose.onNodeWithText("闹钟与通勤").assertExists()
        compose.onNodeWithTag("setting-reliability-summary").assertExists()
        compose.onNodeWithText("权限与诊断").assertExists()
        screenshot("settings-collapsed.png")
        compose.onNodeWithTag("setting-open-diagnostics").performClick()
        compose.onNodeWithTag("permission_diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("permission_diagnostics").performScrollToNode(hasTestTag("settings_full_screen"))
        compose.onNodeWithTag("settings_full_screen").performClick()
        waitForForegroundPackage("com.android.settings")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
        waitForForegroundPackage(InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("permission_diagnostics").fetchSemanticsNodes().isNotEmpty() }
        Espresso.pressBack()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()

        listOf(
            "setting-route" to "通勤路线",
            "setting-calendar" to "工作日日历",
            "setting-credentials" to "数据与凭据",
            "setting-privacy" to "高德地图专项授权",
        ).forEach { (tag, title) ->
            scrollSettingsTo(tag)
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            Espresso.pressBack()
            if (compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isEmpty()) openSettings()
        }
        compose.onNodeWithText("通知摘要").assertDoesNotExist()
        compose.onNodeWithText("锁屏摘要").assertDoesNotExist()

        compose.onNodeWithTag("weather-buffer-workday").assertDoesNotExist()
        compose.onNodeWithTag("setting-weather-buffer").performScrollTo().performClick()
        compose.onNodeWithTag("weather-buffers-screen").assertExists()
        listOf("weather-buffer-workday", "weather-buffer-weekend", "weather-buffer-legal-rest").forEach(::scrollSettingsTo)

        saveBuffer("weather-buffer-workday", WeatherBuffers(11, 12, 13)) { it.workdayWeatherBuffers }
        saveBuffer("weather-buffer-weekend", WeatherBuffers(21, 22, 23)) { it.weekendWeatherBuffers }
        saveBuffer("weather-buffer-legal-rest", WeatherBuffers(31, 32, 33)) { it.holidayWeatherBuffers }
        val persisted = runBlocking { dependencies.settings().loadInitial() }
        val profiles = WeatherBufferProfiles(
            workday = persisted.workdayWeatherBuffers.toProfile(),
            weekend = persisted.weekendWeatherBuffers.toProfile(),
            statutoryRest = persisted.holidayWeatherBuffers.toProfile(),
        )
        assertEquals(
            WeatherBufferProfile(11, 12, 13),
            resolveProfile(LocalDate.of(2026, 9, 7), emptyMap(), profiles),
        )
        assertEquals(
            WeatherBufferProfile(21, 22, 23),
            resolveProfile(LocalDate.of(2026, 9, 6), emptyMap(), profiles),
        )
        assertEquals(
            WeatherBufferProfile(31, 32, 33),
            resolveProfile(LocalDate.of(2026, 9, 7), mapOf("2026-09-07" to DayStatus.HOLIDAY), profiles),
        )
        screenshot("settings-weather-buffers.png")
    }

    @Test
    fun secondPlanOverrideCancelsWithoutWritingAndCustomSaveAppliesToThatPlan() = runBlocking {
        val first = createPlan("UI合并验收-第一计划", "06:10")
        val second = createPlan("UI合并验收-第二计划", "07:20")
        val existingOverride = overrideFor(second.id)
        dependencies.commuteOverrides().save(existingOverride)
        openPlans()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("alarm_${second.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("alarm_${second.id}").performClick()
        compose.onNodeWithTag("workday_preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("workday_preview_grid").assertExists()
        (0..3).forEach { compose.onNodeWithTag("workday_week_$it").assertExists() }
        screenshot("editor-workday-preview.png")

        compose.onNodeWithTag("alarm_editor_commute_advance").performScrollTo()
        compose.onNodeWithTag("arrival_time").assertDoesNotExist()
        screenshot("editor-commute-collapsed.png")
        compose.onNodeWithTag("alarm_editor_commute_summary", useUnmergedTree = true)
            .assertTextEquals("使用本计划通勤覆盖")
        compose.onNodeWithTag("alarm_editor_commute_advance").performClick()
        compose.onNodeWithTag("arrival_time").assertExists()
        compose.onNodeWithTag("preparation_minutes").assertExists()
        compose.onNodeWithTag("max_advance_minutes").assertExists()
        compose.onNodeWithTag("open_plan_commute_override").performScrollTo()
        screenshot("editor-commute-expanded.png")

        compose.onNodeWithTag("open_plan_commute_override").performScrollTo().performClick()
        waitForPlanCommute(second.name)
        compose.onNodeWithTag("plan_commute_selected_plan").assertTextEquals(second.name)
        screenshot("editor-second-plan-commute.png")

        compose.onNodeWithText("‹").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("plan_note"))
        compose.onNodeWithTag("plan_note").assertTextEquals("备注（可选）", second.name)
        Espresso.pressBack()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("本机闹钟").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(existingOverride, dependencies.commuteOverrides().getByPlanId(second.id))

        compose.onNodeWithTag("alarm_${second.id}").performClick()
        compose.onNodeWithTag("alarm_editor_commute_advance").performScrollTo().performClick()
        compose.onNodeWithTag("open_plan_commute_override").performScrollTo().performClick()
        waitForPlanCommute(second.name)
        compose.onNodeWithText("步行").performClick()
        compose.onNodeWithText("完成通勤配置").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("plan_note"))
        compose.onNodeWithTag("plan_note").assertTextEquals("备注（可选）", second.name)
        saveEditorThroughPermissionGuide()
        compose.waitUntil(10_000) { runBlocking { dependencies.commuteOverrides().getByPlanId(second.id)?.commuteMode == CommuteMode.WALKING } }
        assertEquals(second.id, dependencies.plans().getById(second.id)?.id)
        assertTrue(dependencies.plans().getById(first.id) != null)
    }

    @Test
    fun newPlanDraftCompletesCommuteChoiceAndSavesWithItsStablePlanId() {
        configureGlobalCommute()
        val draftName = "UI合并验收-新建草稿-${UUID.randomUUID()}"
        openPlans()
        val emptyAdd = compose.onAllNodesWithText("添加闹钟")
        if (emptyAdd.fetchSemanticsNodes().isNotEmpty()) emptyAdd.onFirst().performClick()
        else compose.onNodeWithText("＋").performClick()
        compose.onNodeWithTag("plan_note").performScrollTo().performTextReplacement(draftName)
        compose.onNodeWithTag("alarm_editor_commute_advance").performScrollTo().performClick()
        compose.onNodeWithTag("arrival_time").assertExists()
        compose.onNodeWithTag("plan_note").assertTextEquals("备注（可选）", draftName)
        compose.onNodeWithTag("open_plan_commute_override").performScrollTo().performClick()
        val draftPlanId = waitForPlanCommute(draftName)
        compose.onNodeWithTag("plan_commute_selected_plan").assertTextEquals(draftName)
        selectCustomCommuteUsingGlobalPlaces()
        compose.onNodeWithText("完成通勤配置").performClick()
        compose.onNodeWithTag("plan_note").assertTextEquals("备注（可选）", draftName)
        screenshot("editor-new-plan-draft.png")

        saveEditorThroughPermissionGuide()
        val saved = runBlocking { dependencies.plans().observeAll().first().single { it.name == draftName } }
        ownedPlanIds += saved.id
        assertEquals(draftPlanId, saved.id)
        val override = runBlocking { dependencies.commuteOverrides().getByPlanId(saved.id) }
        assertEquals(saved.id, override?.planId)
        assertEquals(globalOrigin, override?.origin)
        assertEquals(globalDestination, override?.destination)
    }

    @Test
    fun newPlanCustomCommuteCancelDoesNotWritePlanOrOverride() {
        configureGlobalCommute()
        val draftName = "UI合并验收-取消草稿-${UUID.randomUUID()}"
        openPlans()
        val emptyAdd = compose.onAllNodesWithText("添加闹钟")
        if (emptyAdd.fetchSemanticsNodes().isNotEmpty()) emptyAdd.onFirst().performClick()
        else compose.onNodeWithText("＋").performClick()
        compose.onNodeWithTag("plan_note").performScrollTo().performTextReplacement(draftName)
        compose.onNodeWithTag("alarm_editor_commute_advance").performScrollTo().performClick()
        compose.onNodeWithTag("open_plan_commute_override").performScrollTo().performClick()
        val draftPlanId = waitForPlanCommute(draftName)
        selectCustomCommuteUsingGlobalPlaces()
        compose.onNodeWithText("完成通勤配置").performClick()
        compose.onNodeWithTag("plan_note").assertTextEquals("备注（可选）", draftName)

        Espresso.pressBack()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("本机闹钟").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("添加闹钟").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { dependencies.plans().observeAll().first().none { it.name == draftName } })
        assertEquals(null, runBlocking { dependencies.commuteOverrides().getByPlanId(draftPlanId) })
    }

    private fun resolveProfile(
        date: LocalDate,
        officialDays: Map<String, DayStatus>,
        profiles: WeatherBufferProfiles,
    ): WeatherBufferProfile {
        val plan = AlarmPlan(
            id = "buffer-probe", revision = 0, name = "probe", enabled = false,
            zoneId = ZoneId.systemDefault().id,
            defaultWakeLocalTime = AlarmPlan.DEFAULT_WAKE_TIME,
            arrivalLocalTime = AlarmPlan.DEFAULT_ARRIVAL_TIME,
            preparationMinutes = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
            maxAdvanceMinutes = AlarmPlan.DEFAULT_MAX_ADVANCE_MINUTES,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Workdays,
            armedState = AlarmArmedState.DISABLED,
        )
        return DailySettingsResolver.resolve(plan, date, null, officialDays, profiles).weatherProfile
    }

    private fun com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers.toProfile() =
        WeatherBufferProfile(lightMinutes, moderateMinutes, severeMinutes)

    private fun openSettings() {
        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openPlans() {
        compose.onAllNodesWithText("闹钟").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("本机闹钟").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("添加闹钟").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveBuffer(tag: String, expected: WeatherBuffers, read: (LocalSettings) -> WeatherBuffers) {
        scrollSettingsTo(tag)
        compose.onNodeWithTag("$tag-light").performScrollTo().performTextReplacement(expected.lightMinutes.toString())
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("$tag-moderate").performScrollTo().performTextReplacement(expected.moderateMinutes.toString())
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("$tag-severe").performScrollTo().performTextReplacement(expected.severeMinutes.toString())
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("$tag-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { read(dependencies.settings().loadInitial()) == expected } }
    }

    private fun scrollSettingsTo(tag: String) {
        compose.onNodeWithTag(if (compose.onAllNodesWithTag("weather-buffers-screen").fetchSemanticsNodes().isEmpty()) "settings-screen" else "weather-buffers-screen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertExists()
    }

    private fun waitForPlanCommute(expectedPlanName: String): String {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("plan_commute_selected_plan").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plan_commute_selected_plan").assertTextEquals(expectedPlanName)
        val taggedParent = requireNotNull(compose.onNodeWithTag("plan_commute_selected_plan").fetchSemanticsNode().parent)
        val tag = taggedParent.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
        return requireNotNull(tag).removePrefix("plan_commute_")
    }

    private fun saveEditorThroughPermissionGuide() {
        compose.onNodeWithTag("save_alarm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("permission_guide").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("本机闹钟").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("permission_guide").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("permission_continue").performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("本机闹钟").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun selectCustomCommuteUsingGlobalPlaces() {
        compose.onNodeWithText("专属起点与终点").assertExists()
        compose.onNodeWithText(globalOrigin.name).assertExists()
        compose.onNodeWithText(globalDestination.name).assertExists()
    }

    private fun waitForForegroundPackage(expected: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (automation.rootInActiveWindow?.packageName?.toString() == expected) return
            Thread.sleep(100L)
        }
        throw AssertionError("等待前台页面 $expected 超时，当前为 ${automation.rootInActiveWindow?.packageName}")
    }

    private fun configureGlobalCommute() = runBlocking {
        dependencies.settings().update {
            it.copy(
                amapConsentGranted = true,
                favorites = listOf(
                    FavoritePlace("ui-global-origin", globalOrigin.name, globalOrigin.displayAddress, globalOrigin),
                    FavoritePlace("ui-global-destination", globalDestination.name, globalDestination.displayAddress, globalDestination),
                ),
                originId = "ui-global-origin",
                destinationId = "ui-global-destination",
                commuteMode = CommuteMode.TRANSIT,
            )
        }
    }

    private suspend fun createPlan(name: String, wakeTime: String): AlarmPlan {
        val plan = AlarmPlan(
            id = UUID.randomUUID().toString(),
            revision = 0,
            name = name,
            enabled = false,
            zoneId = ZoneId.systemDefault().id,
            defaultWakeLocalTime = wakeTime,
            arrivalLocalTime = AlarmPlan.DEFAULT_ARRIVAL_TIME,
            preparationMinutes = AlarmPlan.DEFAULT_PREPARATION_MINUTES,
            maxAdvanceMinutes = AlarmPlan.DEFAULT_MAX_ADVANCE_MINUTES,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Workdays,
            armedState = AlarmArmedState.DISABLED,
        )
        dependencies.coordinator().save(plan)
        ownedPlanIds += plan.id
        return plan
    }

    private fun overrideFor(planId: String): PlanCommuteOverride = PlanCommuteOverride(
        planId = planId,
        origin = PlaceRef("ui-home", "测试起点", "测试起点", 116.30, 39.90, "110000", "010"),
        destination = PlaceRef("ui-work", "测试终点", "测试终点", 116.40, 39.80, "110000", "010"),
        commuteMode = CommuteMode.TRANSIT,
        updatedAt = 1L,
    )

    private val globalOrigin = PlaceRef("ui-global-home", "全局测试起点", "全局测试起点", 116.30, 39.90, "110000", "010")
    private val globalDestination = PlaceRef("ui-global-work", "全局测试终点", "全局测试终点", 116.40, 39.80, "110000", "010")

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("merged-settings-editor-qa"))
        directory.mkdirs()
        FileOutputStream(File(directory, name)).use {
            assertTrue("无法保存截图", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
