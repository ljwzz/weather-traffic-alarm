package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers

enum class WeatherBufferKind { Workday, Weekend, LegalRest }

/**
 * The settings landing page only summarizes reliability. Detailed capability
 * checks and Xiaomi's manual confirmations remain in the diagnostic flow.
 */
@Composable
fun SettingsScreen(
    settings: LocalSettings,
    onWeatherBufferChange: (WeatherBufferKind, WeatherBuffers) -> Unit,
    onCalendar: () -> Unit,
    onRoute: () -> Unit,
    onNavigate: (ZhituDestination) -> Unit,
    onCredentials: () -> Unit,
    onDiagnostics: () -> Unit,
    onHistory: () -> Unit,
    onWeather: () -> Unit,
    onOnboarding: () -> Unit,
    permissionSnapshot: PermissionSnapshot,
    permissionConfirmations: Set<XiaomiDisplayPermission>,
) {
    var weatherExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("设置", subtitle = "闹钟与通勤") },
        bottomBar = { ZhituNav(selected = ZhituDestination.SETTINGS, onNavigate = onNavigate) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings-screen"),
            contentPadding = PaddingValues(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { PermissionSummaryCard(permissionSnapshot, permissionConfirmations, onDiagnostics) }
            item {
                SettingsLandingCard("通勤与数据") {
                    SettingsLandingRow("通勤地点与路线", "管理", onRoute, "setting-route")
                    SettingsLandingRow("工作日日历", "查看", onCalendar, "setting-calendar")
                    SettingsLandingRow("数据与凭据", "管理", onCredentials, "setting-credentials")
                    SettingsLandingRow(
                        title = "天气缓冲",
                        value = if (weatherExpanded) "收起" else "展开",
                        onClick = { weatherExpanded = !weatherExpanded },
                        tag = "setting-weather-buffer",
                    )
                }
            }
            if (weatherExpanded) {
                item(key = "weather-buffer-workday") {
                    WeatherBufferEditor(
                        title = "工作日天气缓冲",
                        current = settings.workdayWeatherBuffers,
                        tag = "weather-buffer-workday",
                        onSave = { onWeatherBufferChange(WeatherBufferKind.Workday, it) },
                    )
                }
                item(key = "weather-buffer-weekend") {
                    WeatherBufferEditor(
                        title = "周末天气缓冲",
                        current = settings.weekendWeatherBuffers,
                        tag = "weather-buffer-weekend",
                        onSave = { onWeatherBufferChange(WeatherBufferKind.Weekend, it) },
                    )
                }
                item(key = "weather-buffer-legal-rest") {
                    WeatherBufferEditor(
                        title = "法定休息日天气缓冲",
                        current = settings.holidayWeatherBuffers,
                        tag = "weather-buffer-legal-rest",
                        onSave = { onWeatherBufferChange(WeatherBufferKind.LegalRest, it) },
                    )
                }
            }
            item {
                SettingsLandingCard("隐私") {
                    SettingsLandingRow("隐私与地图授权", "查看", onOnboarding, "setting-privacy")
                }
            }
            item {
                SettingsLandingCard("更多") {
                    SettingsLandingRow("闹钟记录", "查看", onHistory, "setting-history")
                    SettingsLandingRow("天气预览", "查看", onWeather, "setting-weather-preview")
                }
            }
        }
    }
}

@Composable
private fun SettingsLandingCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Card(
    modifier = modifier,
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = Color.White),
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = ZhituColors.Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun SettingsLandingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    tag: String,
) = Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, modifier = Modifier.weight(1f), color = ZhituColors.Ink)
    Text(value, color = ZhituColors.Brand, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text("›", modifier = Modifier.width(18.dp), color = ZhituColors.Brand, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun WeatherBufferEditor(
    title: String,
    current: WeatherBuffers,
    tag: String,
    onSave: (WeatherBuffers) -> Unit,
) {
    var light by remember(current) { mutableStateOf(current.lightMinutes.toString()) }
    var moderate by remember(current) { mutableStateOf(current.moderateMinutes.toString()) }
    var severe by remember(current) { mutableStateOf(current.severeMinutes.toString()) }
    val values = listOf(light, moderate, severe)
    val valid = values.all { it.toIntOrNull()?.let { minutes -> minutes in 0..60 } == true }
    SettingsLandingCard(title, modifier = Modifier.testTag(tag)) {
        Text(
            if (valid) "三级缓冲分别独立保存，范围为 0–60 分钟。" else "请输入 0–60 的整数后再保存。",
            color = if (valid) ZhituColors.Muted else ZhituColors.Amber,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("$tag-validation"),
        )
        WeatherBufferField(light, { light = it }, "轻度天气分钟", "$tag-light")
        WeatherBufferField(moderate, { moderate = it }, "中度天气分钟", "$tag-moderate")
        WeatherBufferField(severe, { severe = it }, "重度天气分钟", "$tag-severe")
        Button(
            onClick = {
                onSave(
                    WeatherBuffers(
                        lightMinutes = light.toInt(),
                        moderateMinutes = moderate.toInt(),
                        severeMinutes = severe.toInt(),
                    ),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().testTag("$tag-save"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
        ) { Text("保存缓冲") }
    }
}

@Composable
private fun WeatherBufferField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
) = OutlinedTextField(
    value = value,
    onValueChange = { onValueChange(it.filter(Char::isDigit)) },
    label = { Text(label) },
    modifier = Modifier.fillMaxWidth().testTag(tag),
    singleLine = true,
)
