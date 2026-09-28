package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savesCompleteWeatherCredentialsDirectly() {
        var saved: CredentialInput? = null
        var tests = 0
        compose.setContent {
            MaterialTheme {
                CredentialSettingsScreen(
                    status = CredentialStatus(),
                    onLoadKeys = { error("No stored credentials") },
                    onSave = { input, done -> saved = input; done(null) },
                    onClear = { done -> done(null) },
                    onTestCaiyun = { _, done -> tests++; done(null) },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("彩云 App Key").performScrollTo().performTextReplacement("weather-key")
        compose.onNodeWithText("彩云 Secret").performScrollTo().performTextReplacement("weather-secret")
        compose.onNodeWithText("保存凭据").performClick()
        compose.onNodeWithText("凭据已加密保存").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("weather-key", saved?.caiyunAppKey)
            assertEquals("weather-secret", saved?.caiyunSecret)
            assertEquals(0, tests)
        }
    }

    @Test fun freshInstallTestsDraftsWithoutSavingAndExpiresBanner() {
        var testedAmap = ""
        var testedWeather = ""
        var saves = 0
        compose.setContent {
            MaterialTheme {
                CredentialSettingsScreen(
                    status = CredentialStatus(),
                    onLoadKeys = { error("No stored credentials") },
                    onSave = { _, done -> saves++; done(null) },
                    onClear = { done -> done(null) },
                    onTestAmapWebKey = { key, done -> testedAmap = key; done(null) },
                    onTestCaiyun = { candidate, done ->
                        testedWeather = "${candidate?.appKey}/${candidate?.secret}"
                        done(null)
                    },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("高德 Web 服务 Key").performScrollTo().performTextReplacement("draft-map")
        compose.onAllNodesWithText("测试连接")[0].performScrollTo().performClick()
        compose.onNodeWithText("高德连接测试成功").assertIsDisplayed()
        compose.onNodeWithText("彩云 App Key").performScrollTo().performTextReplacement("draft-weather")
        compose.onNodeWithText("彩云 Secret").performScrollTo().performTextReplacement("draft-secret")
        compose.onAllNodesWithText("测试连接")[1].performScrollTo().performClick()
        compose.onNodeWithText("彩云连接测试成功").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("draft-map", testedAmap)
            assertEquals("draft-weather/draft-secret", testedWeather)
            assertEquals(0, saves)
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(59_000)
        compose.onNodeWithText("彩云连接测试成功").assertExists()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("彩云连接测试成功").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("高德 Web 服务 Key").performScrollTo().performTextReplacement("")
        compose.onAllNodesWithText("测试连接")[0].performScrollTo().performClick()
        compose.onNodeWithText("请填写高德 Web Key。").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(59_000)
        compose.onAllNodesWithText("测试连接")[0].performClick()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("请填写高德 Web Key。").assertExists()
        compose.mainClock.advanceTimeBy(59_000)
        compose.onNodeWithText("请填写高德 Web Key。").assertDoesNotExist()
    }
}
