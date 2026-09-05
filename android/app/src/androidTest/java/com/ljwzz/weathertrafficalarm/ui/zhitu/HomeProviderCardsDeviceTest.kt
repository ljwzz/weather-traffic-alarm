package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Visual and gesture contract for home provider cards with deterministic state fixtures. */
@RunWith(AndroidJUnit4::class)
class HomeProviderCardsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun successCacheLoadingAndPartialFailureRenderAndPullRefreshDispatches() {
        val refreshed = mutableStateOf(0)
        val state = mutableStateOf(successState())
        setHome(state, refreshed)

        compose.onNodeWithTag("home_weather_card").assertIsDisplayed()
        compose.onNodeWithText("轻度天气").assertExists()
        compose.onAllNodesWithText("数据来自本地缓存", substring = true).assertCountEquals(2)
        compose.onNodeWithTag("home_content").performScrollToIndex(0)
        writeScreenshot("home-preview-success-top.png")
        compose.onNodeWithTag("home_route_card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("驾车 · 12.4 km · 18 分钟").assertExists()

        compose.runOnIdle {
            state.value = HomeUiState(
                weather = HomeWeatherCardState.MissingCredentials,
                route = HomeRouteCardState.MissingWebKey,
            )
        }
        compose.onNodeWithText("尚未配置彩云凭据").assertExists()
        compose.onNodeWithText("尚未配置高德 Web Key").performScrollTo().assertExists()
        compose.onNodeWithTag("home_content").performScrollToIndex(0)
        writeScreenshot("home-preview-missing-configuration.png")

        compose.runOnIdle {
            state.value = successState().copy(route = HomeRouteCardState.Empty("家 → 公司", CommuteMode.DRIVING))
        }
        compose.onNodeWithText("未找到可用驾车路线").performScrollTo().assertExists()
        writeScreenshot("home-preview-no-route.png")

        compose.runOnIdle {
            state.value = successState().copy(
                weather = HomeWeatherCardState.Loading("家 → 公司"),
            )
        }
        compose.onNodeWithText("正在获取天气").assertExists()
        compose.onNodeWithTag("home_weather_card").performScrollTo()
        writeScreenshot("home-preview-loading.png")

        compose.runOnIdle {
            state.value = successState().copy(
                weather = HomeWeatherCardState.Error("网络不可用，请检查连接", "家 → 公司"),
                route = (successState().route as HomeRouteCardState.Success).copy(refreshError = "服务响应超时，请稍后重试"),
            )
        }
        compose.onNodeWithText("无法获取天气").assertExists()
        compose.onNodeWithText("更新失败，保留上次结果", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag("home_weather_card").performScrollTo()
        writeScreenshot("home-preview-partial-error.png")

        compose.onNodeWithTag("home_content").performScrollToIndex(7)
        val routeBounds = compose.onNodeWithTag("home_route_card").fetchSemanticsNode().boundsInRoot
        val fabBounds = compose.onNodeWithTag("home_add_alarm").fetchSemanticsNode().boundsInRoot
        assertTrue("末卡内容不得被添加闹钟按钮覆盖", routeBounds.bottom <= fabBounds.top)

        compose.onNodeWithTag("home_content").performScrollToIndex(0)
        compose.onNodeWithTag("home_pull_refresh").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { refreshed.value > 0 }
        assertTrue(refreshed.value > 0)
    }

    private fun setHome(state: MutableState<HomeUiState>, refreshed: MutableState<Int>) {
        compose.setContent {
            ZhituTheme {
                HomeScreen(
                    plans = emptyList(),
                    decisions = emptyList(),
                    evaluationTaskStates = emptyMap(),
                    evaluablePlanIds = emptySet(),
                    schedulingError = null,
                    homeUiState = state.value,
                    mapStatus = MapStatus.Ready,
                    onPlans = {}, onAdd = { _ -> }, onEvaluate = { _ -> }, onRoute = {}, onWeather = {},
                    onCredentials = {}, onAmapConsent = {}, onSettings = {},
                    onRefreshPreviews = { refreshed.value += 1 },
                )
            }
        }
    }

    private fun successState() = HomeUiState(
        weather = HomeWeatherCardState.Success(
            severity = WeatherSeverity.LIGHT,
            route = "家 → 公司",
            reportTime = Instant.parse("2026-09-05T12:45:00Z"),
            source = WeatherDataSource.CACHE,
        ),
        route = HomeRouteCardState.Success(
            route = "家 → 公司",
            mode = CommuteMode.DRIVING,
            alternative = RouteAlternative("fixture", durationSeconds = 18 * 60L, distanceMeters = 12_400L, polyline = emptyList()),
            fetchedAtEpochMillis = Instant.parse("2026-09-05T12:45:00Z").toEpochMilli(),
            source = RouteDataSource.CACHE,
        ),
    )

    private fun writeScreenshot(fileName: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("qa/home-preview"))
        directory.mkdirs()
        FileOutputStream(File(directory, fileName)).use {
            assertTrue("无法保存截图", image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }
}
