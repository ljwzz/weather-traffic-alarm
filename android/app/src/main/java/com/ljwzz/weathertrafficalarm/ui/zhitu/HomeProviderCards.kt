package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.RouteDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeWeatherCard(
    state: HomeWeatherCardState,
    onDetail: () -> Unit,
    onCredentials: () -> Unit,
    onRoute: () -> Unit,
    onRetry: () -> Unit,
) = HomeProviderCard(
    tag = "home_weather_card",
    title = "彩云天气",
    onClick = onDetail,
    containerColor = ZhituColors.Blue.copy(alpha = .10f),
) {
    when (state) {
        HomeWeatherCardState.ReadingConfiguration -> HomeCardBody("正在读取天气配置")
        HomeWeatherCardState.CredentialStorageError -> HomeCardBody("无法读取天气凭据", action = "配置凭据", onAction = onCredentials, warning = true)
        HomeWeatherCardState.MissingCredentials -> HomeCardBody("尚未配置彩云凭据", action = "配置凭据", onAction = onCredentials)
        HomeWeatherCardState.AwaitingConnectionTest -> HomeCardBody("彩云凭据等待连接测试", action = "测试凭据", onAction = onCredentials)
        HomeWeatherCardState.FailedConnectionTest -> HomeCardBody("彩云凭据连接测试失败", action = "配置凭据", onAction = onCredentials)
        HomeWeatherCardState.MissingPlaces -> HomeCardBody("尚未配置通勤地点", action = "完善地点", onAction = onRoute)
        is HomeWeatherCardState.Loading -> HomeCardBody("正在获取天气", state.route)
        is HomeWeatherCardState.Success -> {
            HomeCardBody(
                headline = "${state.severity.homeWeatherLabel()}天气",
                detail = state.route,
                metadata = "数据时间：${state.reportTime.homePreviewTime()} · ${state.source.homeWeatherSourceLabel()}",
            )
            state.refreshError?.let { HomeCardBody("更新失败，保留上次结果", it, action = "重试", onAction = onRetry, warning = true) }
        }
        is HomeWeatherCardState.Error -> HomeCardBody("无法获取天气", state.message, action = "重试", onAction = onRetry, warning = true)
        HomeWeatherCardState.Ready -> HomeCardBody("天气预览尚未更新", action = "刷新", onAction = onRetry)
    }
}

@Composable
internal fun HomeRouteCard(
    state: HomeRouteCardState,
    mapStatus: MapStatus,
    onDetail: () -> Unit,
    onCredentials: () -> Unit,
    onConsent: () -> Unit,
    onRetry: () -> Unit,
) = HomeProviderCard(
    tag = "home_route_card",
    title = "通勤路线",
    onClick = onDetail,
) {
    when (state) {
        HomeRouteCardState.ReadingConfiguration -> HomeCardBody("正在读取路线配置")
        HomeRouteCardState.CredentialStorageError -> HomeCardBody("无法读取路线凭据", action = "配置凭据", onAction = onCredentials, warning = true)
        HomeRouteCardState.ConsentRequired -> HomeCardBody("等待高德地图专项授权", action = "完成授权", onAction = onConsent)
        HomeRouteCardState.MissingWebKey -> HomeCardBody("尚未配置高德 Web Key", action = "配置凭据", onAction = onCredentials)
        HomeRouteCardState.MissingPlaces -> HomeCardBody("尚未配置通勤地点", action = "完善地点", onAction = onDetail)
        is HomeRouteCardState.Loading -> HomeCardBody("正在查询${state.mode.homeModeLabel()}路线", state.route)
        is HomeRouteCardState.Success -> {
            val route = state.alternative
            HomeCardBody(
                headline = "${state.mode.homeModeLabel()} · ${route.homeDistance()} · ${route.homeDuration()}",
                detail = state.route,
                metadata = "数据时间：${state.fetchedAtEpochMillis.homePreviewTime()} · ${state.source.homeRouteSourceLabel()}",
            )
            state.refreshError?.let { HomeCardBody("更新失败，保留上次结果", it, action = "重试", onAction = onRetry, warning = true) }
        }
        is HomeRouteCardState.Empty -> HomeCardBody("未找到可用${state.mode.homeModeLabel()}路线", state.route, action = "重试", onAction = onRetry, warning = true)
        is HomeRouteCardState.Error -> HomeCardBody("无法获取路线", state.message, action = "重试", onAction = onRetry, warning = true)
        HomeRouteCardState.Ready -> HomeCardBody("路线预览尚未更新", action = "刷新", onAction = onRetry)
    }
    if (mapStatus != MapStatus.Ready) {
        HomeCardBody(mapStatus.homeMapLabel(), "路线结果由高德提供")
    }
}

@Composable
private fun HomeProviderCard(
    tag: String,
    title: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = ZhituColors.Surface,
    content: @Composable () -> Unit,
) = Card(
    modifier = Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
) {
    Column(Modifier.padding(20.dp)) {
        Text(title, color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun HomeCardBody(
    headline: String,
    detail: String? = null,
    metadata: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    warning: Boolean = false,
) {
    Text(headline, color = if (warning) ZhituColors.Amber else ZhituColors.Brand, fontWeight = FontWeight.Medium)
    detail?.let { Text(it, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall) }
    metadata?.let { Text(it, color = ZhituColors.Muted, style = MaterialTheme.typography.labelSmall) }
    if (action != null && onAction != null) {
        TextButton(onClick = onAction, modifier = Modifier.testTag("home_provider_action_${action}")) { Text(action) }
    }
}

private fun WeatherSeverity.homeWeatherLabel(): String = when (this) {
    WeatherSeverity.FINE -> "晴好"
    WeatherSeverity.LIGHT -> "轻度"
    WeatherSeverity.MODERATE -> "中度"
    WeatherSeverity.SEVERE -> "严重"
}

private fun WeatherDataSource.homeWeatherSourceLabel(): String = when (this) {
    WeatherDataSource.NETWORK -> "数据来自彩云天气"
    WeatherDataSource.CACHE -> "数据来自本地缓存"
    WeatherDataSource.MIXED -> "数据混合来自彩云天气和本地缓存"
}

private fun RouteDataSource.homeRouteSourceLabel(): String = when (this) {
    RouteDataSource.NETWORK -> "数据来自高德路线服务"
    RouteDataSource.CACHE -> "数据来自本地缓存"
}

private fun CommuteMode.homeModeLabel(): String = when (this) {
    CommuteMode.DRIVING -> "驾车"
    CommuteMode.TRANSIT -> "公交"
    CommuteMode.WALKING -> "步行"
    CommuteMode.BICYCLING -> "骑行"
    CommuteMode.ELECTRIC_BICYCLE -> "电动车"
}

private fun com.ljwzz.weathertrafficalarm.core.model.RouteAlternative.homeDistance(): String =
    if (distanceMeters >= 1_000) "%.1f km".format(distanceMeters / 1_000.0) else "$distanceMeters m"

private fun com.ljwzz.weathertrafficalarm.core.model.RouteAlternative.homeDuration(): String =
    "${((durationSeconds + 59) / 60).coerceAtLeast(1)} 分钟"

private fun Instant.homePreviewTime(): String = atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
private fun Long.homePreviewTime(): String = Instant.ofEpochMilli(this).homePreviewTime()

private fun MapStatus.homeMapLabel(): String = when (this) {
    MapStatus.NotInitialized -> "地图尚未初始化"
    MapStatus.RendererUnavailable -> "地图暂不可用"
    MapStatus.ConsentRequired -> "地图需要完成授权"
    MapStatus.MissingAndroidKey -> "地图需要配置"
    MapStatus.Failed -> "地图暂不可用"
    MapStatus.Ready -> "地图可用"
}
