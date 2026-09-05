package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.data.local.CaiyunConnectionTestResult
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStatus
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Stable inputs used to decide whether a home preview can reuse an older result.
 * Names are deliberately excluded: moving a saved place while retaining its id must
 * invalidate the preview, while a display-name-only change must not issue a request.
 */
internal data class HomePreviewInputs(
    val routeKey: String?,
    val weatherKey: String?,
    val originName: String?,
    val destinationName: String?,
    val mode: CommuteMode,
)

internal fun homePreviewInputs(
    settings: LocalSettings,
    credentials: CredentialStatus,
    now: Instant = Instant.now(),
): HomePreviewInputs {
    val origin = settings.favorites.firstOrNull { it.id == settings.originId }?.placeRef
    val destination = settings.favorites.firstOrNull { it.id == settings.destinationId }?.placeRef
    val points = listOfNotNull(origin, destination)
    val coordinateKey = if (points.size == 2) {
        "${origin!!.longitudeGcj02},${origin.latitudeGcj02},${origin.citycode}:${destination!!.longitudeGcj02},${destination.latitudeGcj02},${destination.citycode}"
    } else null
    val hour = now.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS)
    return HomePreviewInputs(
        routeKey = coordinateKey?.let { "$it:${settings.commuteMode}:${settings.amapConsentGranted}:${credentials.amapWebVersion}:${credentials.loaded}:${credentials.storageError}:${credentials.hasAmapWebKey}" },
        weatherKey = coordinateKey?.let { "$it:${credentials.caiyunVersion}:${credentials.caiyunTestResult}:${credentials.loaded}:${credentials.storageError}:${credentials.hasCaiyunAppKey}:${credentials.hasCaiyunSecret}:${hour.toInstant()}" },
        originName = origin?.name,
        destinationName = destination?.name,
        mode = settings.commuteMode,
    )
}

internal object HomePreviewPolicy {
    const val ROUTE_TTL_MILLIS: Long = 5 * 60 * 1_000L
    const val WEATHER_TTL_MILLIS: Long = 15 * 60 * 1_000L

    fun isFresh(fetchedAtEpochMillis: Long?, ttlMillis: Long, nowEpochMillis: Long): Boolean =
        fetchedAtEpochMillis != null && nowEpochMillis - fetchedAtEpochMillis in 0 until ttlMillis
}

internal sealed interface HomeWeatherCardState {
    data object ReadingConfiguration : HomeWeatherCardState
    data object CredentialStorageError : HomeWeatherCardState
    data object MissingCredentials : HomeWeatherCardState
    data object AwaitingConnectionTest : HomeWeatherCardState
    data object FailedConnectionTest : HomeWeatherCardState
    data object MissingPlaces : HomeWeatherCardState
    data class Loading(val route: String) : HomeWeatherCardState
    data class Success(
        val severity: WeatherSeverity,
        val route: String,
        val reportTime: Instant,
        val source: WeatherDataSource,
        val refreshError: String? = null,
    ) : HomeWeatherCardState
    data class Error(val message: String, val route: String?) : HomeWeatherCardState
    data object Ready : HomeWeatherCardState
}

internal sealed interface HomeRouteCardState {
    data object ReadingConfiguration : HomeRouteCardState
    data object CredentialStorageError : HomeRouteCardState
    data object ConsentRequired : HomeRouteCardState
    data object MissingWebKey : HomeRouteCardState
    data object MissingPlaces : HomeRouteCardState
    data class Loading(val route: String, val mode: CommuteMode) : HomeRouteCardState
    data class Success(
        val route: String,
        val mode: CommuteMode,
        val alternative: RouteAlternative,
        val fetchedAtEpochMillis: Long,
        val source: RouteDataSource,
        val refreshError: String? = null,
    ) : HomeRouteCardState
    data class Empty(val route: String, val mode: CommuteMode) : HomeRouteCardState
    data class Error(val message: String, val route: String?, val mode: CommuteMode) : HomeRouteCardState
    data object Ready : HomeRouteCardState
}

internal data class HomeUiState(
    val weather: HomeWeatherCardState = HomeWeatherCardState.ReadingConfiguration,
    val route: HomeRouteCardState = HomeRouteCardState.ReadingConfiguration,
    val isRefreshing: Boolean = false,
)

internal data class HomePreviewSnapshot(
    val inputs: HomePreviewInputs,
    val credentials: CredentialStatus,
    val settings: LocalSettings,
    val weather: WeatherUiState,
    val route: RouteUiState,
)

/**
 * Single-flight gate for the aggregate home gesture. It lets an ordinary refresh
 * coalesce, upgrades it once for a user force refresh, and prevents a cancelled
 * generation from clearing the indicator owned by its replacement.
 */
internal class HomeRefreshGate {
    private var nextToken = 0L
    private var active: Active? = null

    fun begin(inputs: HomePreviewInputs, forceRefresh: Boolean): HomeRefreshStart {
        val current = active
        if (current != null && current.inputs == inputs && (!forceRefresh || current.forceRefresh)) {
            return HomeRefreshStart(accepted = false, cancelCurrent = false, token = current.token)
        }
        val next = Active(token = ++nextToken, inputs = inputs, forceRefresh = forceRefresh)
        active = next
        return HomeRefreshStart(accepted = true, cancelCurrent = current != null, token = next.token)
    }

    fun finish(token: Long): Boolean {
        if (active?.token != token) return false
        active = null
        return true
    }

    private data class Active(val token: Long, val inputs: HomePreviewInputs, val forceRefresh: Boolean)
}

internal data class HomeRefreshStart(val accepted: Boolean, val cancelCurrent: Boolean, val token: Long)

internal fun HomePreviewInputs.routeLabel(): String? =
    listOfNotNull(originName, destinationName).takeIf { it.size == 2 }?.joinToString(" → ")

internal fun homeWeatherCard(
    credentials: CredentialStatus,
    inputs: HomePreviewInputs,
    state: WeatherUiState,
): HomeWeatherCardState {
    val route = inputs.routeLabel()
    if (!credentials.loaded) return HomeWeatherCardState.ReadingConfiguration
    if (credentials.storageError) return HomeWeatherCardState.CredentialStorageError
    if (!credentials.hasCaiyunAppKey || !credentials.hasCaiyunSecret) return HomeWeatherCardState.MissingCredentials
    if (credentials.caiyunTestResult == CaiyunConnectionTestResult.NEVER_TESTED) return HomeWeatherCardState.AwaitingConnectionTest
    if (credentials.caiyunTestResult == CaiyunConnectionTestResult.FAILED) return HomeWeatherCardState.FailedConnectionTest
    if (inputs.weatherKey == null || route == null) return HomeWeatherCardState.MissingPlaces
    return when (state) {
        is WeatherUiState.Loading -> if (state.inputKey == inputs.weatherKey) HomeWeatherCardState.Loading(route) else HomeWeatherCardState.Ready
        is WeatherUiState.Success -> if (state.inputKey == inputs.weatherKey) HomeWeatherCardState.Success(
            severity = state.severity,
            route = route,
            reportTime = state.reportTime,
            source = state.source,
            refreshError = state.refreshError,
        ) else HomeWeatherCardState.Ready
        is WeatherUiState.Error -> if (state.inputKey == inputs.weatherKey) HomeWeatherCardState.Error(state.message, route) else HomeWeatherCardState.Ready
        WeatherUiState.Idle -> HomeWeatherCardState.Ready
    }
}

internal fun homeRouteCard(
    credentials: CredentialStatus,
    settings: LocalSettings,
    inputs: HomePreviewInputs,
    state: RouteUiState,
): HomeRouteCardState {
    val route = inputs.routeLabel()
    if (!credentials.loaded) return HomeRouteCardState.ReadingConfiguration
    if (credentials.storageError) return HomeRouteCardState.CredentialStorageError
    if (!settings.amapConsentGranted) return HomeRouteCardState.ConsentRequired
    if (!credentials.hasAmapWebKey) return HomeRouteCardState.MissingWebKey
    if (inputs.routeKey == null || route == null) return HomeRouteCardState.MissingPlaces
    if (state.inputKey != null && state.inputKey != inputs.routeKey) return HomeRouteCardState.Ready
    if (state.loading) return HomeRouteCardState.Loading(route, inputs.mode)
    val selected = state.alternatives.firstOrNull { it.id == state.selectedRouteId } ?: state.alternatives.firstOrNull()
    if (selected != null && state.fetchedAtEpochMillis != null && state.fetchedAtEpochMillis > 0 && state.source != null) return HomeRouteCardState.Success(
        route = route,
        mode = inputs.mode,
        alternative = selected,
        fetchedAtEpochMillis = state.fetchedAtEpochMillis,
        source = state.source,
        refreshError = state.refreshError,
    )
    return if (state.noRoute) HomeRouteCardState.Empty(route, inputs.mode)
    else state.message?.let { HomeRouteCardState.Error(it, route, inputs.mode) } ?: HomeRouteCardState.Ready
}

internal fun weatherWindow(now: Instant): Pair<ZonedDateTime, ZonedDateTime> {
    val start = now.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS)
    return start to start.plusHours(23)
}
