package com.ljwzz.weathertrafficalarm.evaluation

import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommute
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommuteResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.WorkdayOverrideRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmTimeCalculator
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteProvider
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherTimeWindow
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import com.ljwzz.weathertrafficalarm.core.model.WorkdayResolver
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result consumed by [EvaluationWorker]; provider errors are recorded as decisions, not thrown. */
data class EvaluationRunResult(
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
    val decision: AlarmDecision? = null,
)

/**
 * The only app-side composition point for the evaluation inputs. Providers produce data only;
 * this class creates an auditable decision and delegates the guarded local-alarm mutation to
 * [LocalAlarmCoordinator].
 */
@Singleton
class EvaluationCoordinator @Inject constructor(
    private val plans: AlarmPlanRepository,
    private val settings: LocalSettingsStore,
    private val commutes: EffectiveCommuteResolver,
    private val overrides: WorkdayOverrideRepository,
    private val calendar: WorkdayCalendarRepository,
    private val routes: RouteProvider,
    private val weather: WeatherProvider,
    private val decisions: DecisionRepository,
    private val alarms: LocalAlarmCoordinator,
    private val clock: Clock,
) {
    suspend fun evaluate(
        planId: String,
        attemptNumber: Int = 0,
        targetDate: LocalDate? = null,
        deadline: Instant? = null,
        evaluationId: String = UUID.randomUUID().toString(),
    ): EvaluationRunResult {
        val initialPlan = plans.getById(planId)?.takeIf { it.enabled } ?: return EvaluationRunResult(false)
        val date = targetDate ?: clock.instant().atZone(initialPlan.zoneIdInstance()).toLocalDate().plusDays(1)
        val startedAt = clock.instant()
        val snapshot = resolveInputs(initialPlan, date, evaluationId) ?: return persistFailure(
            initialPlan, date, evaluationId, attemptNumber, deadline, startedAt, "COMMUTE_NOT_CONFIGURED", FallbackReason.ROUTE_NOT_FOUND,
        )
        if (deadline != null && !startedAt.isBefore(deadline)) {
            return persistStale(snapshot, attemptNumber, startedAt, deadline, "EVALUATION_WINDOW_EXPIRED")
        }
        if (!EvaluationCoordinatorPolicy.isEligible(snapshot.plan.schedule, date, snapshot.dayStatus, snapshot.override)) {
            return persistSkipped(snapshot, attemptNumber, startedAt, deadline)
        }

        val route = try {
            resolveRoute(snapshot)
        } catch (failure: Throwable) {
            return persistProviderFailure(snapshot, attemptNumber, startedAt, deadline, failure, ProviderKind.ROUTE)
        }
        val weatherWindow = try {
            EvaluationCoordinatorPolicy.weatherWindow(snapshot.plan, date)
        } catch (_: IllegalArgumentException) {
            return persistFailure(
                snapshot.plan, date, evaluationId, attemptNumber, deadline, startedAt,
                "INVALID_TIME_WINDOW", FallbackReason.NONE, snapshot = snapshot, route = route,
            )
        }
        val weatherResult = try {
            weather.evaluate(
                WeatherRequest(
                    home = WeatherLocation(WeatherLocationRole.HOME, snapshot.commute.origin.toPoint()),
                    work = WeatherLocation(WeatherLocationRole.WORK, snapshot.commute.destination.toPoint()),
                    window = weatherWindow,
                    weatherBufferProfile = snapshot.weatherProfile,
                    weatherRuleVersion = snapshot.plan.weatherRuleVersion,
                    requestedAt = startedAt,
                ),
            )
        } catch (failure: Throwable) {
            return persistProviderFailure(snapshot, attemptNumber, startedAt, deadline, failure, ProviderKind.WEATHER)
        }
        if (!weatherResult.isUsableForScheduling || weatherResult.providerReportTime == null || weatherResult.source == null) {
            return persistFailure(
                snapshot.plan, date, evaluationId, attemptNumber, deadline, startedAt,
                "WEATHER_${weatherResult.fallbackReason.name}", weatherResult.fallbackReason,
                snapshot = snapshot, route = route,
            )
        }

        val calculation = AlarmTimeCalculator.calculate(
            defaultWakeTime = LocalTime.parse(snapshot.plan.defaultWakeLocalTime),
            arrivalTime = LocalTime.parse(snapshot.plan.arrivalLocalTime),
            preparationMinutes = snapshot.plan.preparationMinutes,
            maxAdvanceMinutes = snapshot.plan.maxAdvanceMinutes,
            commuteSeconds = route.calculationCommuteSeconds,
            weatherBufferMinutes = weatherResult.bufferMinutes,
            targetDate = date,
            zoneId = snapshot.plan.zoneIdInstance(),
        )
        val recommendedWake = Instant.ofEpochMilli(calculation.recommendedWakeAt)
        val weatherReportTime = requireNotNull(weatherResult.providerReportTime)
        val expiresAt = listOfNotNull(
            deadline,
            startedAt.plus(Duration.ofMinutes(15)),
            weatherReportTime.plus(Duration.ofMinutes(15)),
            recommendedWake,
        ).minOrNull() ?: recommendedWake
        val completedAt = clock.instant()
        if (EvaluationCoordinatorPolicy.isExpired(completedAt, expiresAt) || !hasSameInputs(snapshot)) {
            return persistStale(snapshot, attemptNumber, completedAt, expiresAt, if (EvaluationCoordinatorPolicy.isExpired(completedAt, expiresAt)) {
                "EVALUATION_RESULT_EXPIRED"
            } else {
                "EVALUATION_INPUTS_CHANGED"
            })
        }

        val decision = decision(
            snapshot = snapshot,
            attempt = attemptNumber,
            generatedAt = completedAt,
            expiresAt = expiresAt,
            outcome = EvaluationOutcome.SUCCESS,
            failureReason = null,
            fallbackReason = route.fallbackReason ?: weatherResult.fallbackReason,
            estimatedDeparture = route.estimatedDeparture,
            commuteSeconds = route.calculationCommuteSeconds,
            weatherSeverity = weatherResult.severity.level,
            weatherBufferMinutes = weatherResult.bufferMinutes,
            recommendedWake = recommendedWake,
            routeProvider = "AMAP_WEB",
            routeProviderReportTime = null,
            weatherProvider = "CAIYUN_V2_6",
            weatherProviderReportTime = weatherReportTime,
            weatherWindow = weatherWindow,
            weatherDataSource = weatherResult.source,
            insufficientAdvance = calculation.insufficientAdvance,
        )
        decisions.save(decision)
        // The coordinator owns its own mutex and repeats the enabled/revision/expiry checks.
        alarms.applyEvaluation(decision)
        return EvaluationRunResult(false, decision = decisions.getById(decision.decisionId) ?: decision)
    }

    private suspend fun resolveInputs(plan: AlarmPlan, date: LocalDate, evaluationId: String): EvaluationInputs? {
        val persistedSettings = settings.loadInitial()
        val commute = commutes.resolveForPlan(plan.id, persistedSettings) ?: return null
        val calendarDays = calendar.statuses()
        val override = overrides.getForPlan(plan.id).firstOrNull { it.date == date.toString() }
        val status = override?.status ?: calendarDays[date.toString()] ?: WorkdayResolver.weekdayFallback(date)
        return EvaluationInputs(
            plan = plan.copy(defaultWakeLocalTime = override?.wakeLocalTime ?: plan.defaultWakeLocalTime),
            date = date,
            evaluationId = evaluationId,
            settings = persistedSettings,
            commute = commute,
            calendarDays = calendarDays,
            override = override,
            dayStatus = status,
            weatherProfile = EvaluationCoordinatorPolicy.weatherProfile(date, calendarDays, persistedSettings),
        )
    }

    private suspend fun hasSameInputs(before: EvaluationInputs): Boolean {
        val latestPlan = plans.getById(before.plan.id) ?: return false
        if (!latestPlan.enabled || latestPlan.revision != before.plan.revision || latestPlan.zoneId != before.plan.zoneId) return false
        return resolveInputs(latestPlan, before.date, before.evaluationId)?.fingerprint == before.fingerprint
    }

    private suspend fun resolveRoute(inputs: EvaluationInputs): RouteResult = when (inputs.commute.commuteMode) {
        CommuteMode.TRANSIT -> resolveTransitRoute(inputs)
        else -> {
            val estimate = routes.estimate(inputs.routeRequest(departureAt = null))
            val duration = estimate.alternatives.map(RouteAlternative::durationSeconds).filter { it >= 0 }.minOrNull()
                ?: throw ProviderError(ProviderError.Category.ROUTE_NOT_FOUND, message = "No usable route duration")
            RouteResult(
                calculationCommuteSeconds = duration,
                estimatedDeparture = inputs.arrival.minusSeconds(duration).toInstant(),
                fallbackReason = if (inputs.commute.commuteMode == CommuteMode.DRIVING) FallbackReason.CURRENT_TRAFFIC_FALLBACK else null,
            )
        }
    }

    private suspend fun resolveTransitRoute(inputs: EvaluationInputs): RouteResult {
        val arrival = inputs.arrival
        val candidateDepartures = EvaluationCoordinatorPolicy.transitCandidateDepartures(arrival)
        for (departure in candidateDepartures) {
            val estimate = routes.estimate(inputs.routeRequest(departure.toLocalDateTime()))
            val duration = estimate.alternatives.map(RouteAlternative::durationSeconds)
                .filter { it >= 0 && !departure.plusSeconds(it).isAfter(arrival) }
                .minOrNull()
            if (duration != null) {
                // Include waiting implied by the chosen departure in the calculator's travel budget.
                return RouteResult(
                    calculationCommuteSeconds = Duration.between(departure, arrival).seconds,
                    estimatedDeparture = departure.toInstant(),
                )
            }
        }
        throw ProviderError(ProviderError.Category.ROUTE_NOT_FOUND, message = "No transit route arrives before the target")
    }

    private suspend fun persistProviderFailure(
        inputs: EvaluationInputs,
        attempt: Int,
        generatedAt: Instant,
        deadline: Instant?,
        failure: Throwable,
        kind: ProviderKind,
    ): EvaluationRunResult {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        val provider = failure as? ProviderError
        val retryable = EvaluationCoordinatorPolicy.isRetryable(provider)
        return persistFailure(
            inputs.plan, inputs.date, inputs.evaluationId, attempt, deadline, generatedAt,
            "${kind.name}_${provider?.category?.name ?: "UNEXPECTED"}",
            when (kind) {
                ProviderKind.ROUTE -> provider.toRouteFallback()
                ProviderKind.WEATHER -> provider.toWeatherFallback()
            },
            snapshot = inputs,
            retryable = retryable,
            retryAfterSeconds = provider?.retryAfterSeconds,
        )
    }

    private suspend fun persistFailure(
        plan: AlarmPlan,
        date: LocalDate,
        evaluationId: String,
        attempt: Int,
        deadline: Instant?,
        generatedAt: Instant,
        failureReason: String,
        fallbackReason: FallbackReason,
        snapshot: EvaluationInputs? = null,
        route: RouteResult? = null,
        retryable: Boolean = false,
        retryAfterSeconds: Long? = null,
    ): EvaluationRunResult {
        val inputs = snapshot ?: resolveInputs(plan, date, evaluationId)
        val defaultWake = ZonedDateTime.of(date, LocalTime.parse(plan.defaultWakeLocalTime), plan.zoneIdInstance()).toInstant()
        val decision = if (inputs == null) simpleDecision(plan, date, evaluationId, attempt, generatedAt, deadline ?: defaultWake, EvaluationOutcome.FAILED, failureReason, fallbackReason, defaultWake)
        else decision(
            inputs, attempt, generatedAt, deadline ?: defaultWake, EvaluationOutcome.FAILED, failureReason, fallbackReason,
            route?.estimatedDeparture, route?.calculationCommuteSeconds, 0, 0, defaultWake,
            route?.let { "AMAP_WEB" }, null, null, null, null, null, false,
        )
        decisions.save(decision)
        return EvaluationRunResult(retryable, retryAfterSeconds, decision)
    }

    private suspend fun persistSkipped(inputs: EvaluationInputs, attempt: Int, generatedAt: Instant, deadline: Instant?): EvaluationRunResult {
        val wake = inputs.defaultWake.toInstant()
        val decision = decision(
            inputs, attempt, generatedAt, deadline ?: wake, EvaluationOutcome.SKIPPED, "DATE_NOT_APPLICABLE", FallbackReason.NONE,
            null, null, 0, 0, wake, null, null, null, null, null, null, false,
        )
        decisions.save(decision)
        return EvaluationRunResult(false, decision = decision)
    }

    private suspend fun persistStale(inputs: EvaluationInputs, attempt: Int, generatedAt: Instant, expiresAt: Instant, reason: String): EvaluationRunResult {
        val wake = inputs.defaultWake.toInstant()
        val decision = decision(
            inputs, attempt, generatedAt, expiresAt, EvaluationOutcome.STALE, reason, FallbackReason.STALE_RESPONSE,
            null, null, 0, 0, wake, null, null, null, null, null, null, false,
        )
        decisions.save(decision)
        return EvaluationRunResult(false, decision = decision)
    }

    private fun simpleDecision(
        plan: AlarmPlan, date: LocalDate, evaluationId: String, attempt: Int, generatedAt: Instant, expiresAt: Instant,
        outcome: EvaluationOutcome, failure: String, fallback: FallbackReason, defaultWake: Instant,
    ) = AlarmDecision(
        decisionId = decisionId(plan, date, attempt, evaluationId), planId = plan.id, planRevision = plan.revision,
        targetDate = date.toString(), workdayStatus = null, estimatedDepartureAt = null, commuteSeconds = null,
        weatherSeverity = 0, weatherBufferMinutes = 0, recommendedWakeAt = defaultWake.toString(), routeProvider = null,
        routeProviderReportTime = null, weatherProvider = null, weatherProviderReportTime = null, weatherWindowStart = null,
        weatherWindowEnd = null, fallbackReason = fallback, insufficientAdvance = false, generatedAt = generatedAt.toString(),
        expiresAt = expiresAt.toString(), evaluationOutcome = outcome, failureReason = failure, attemptNumber = attempt,
        preparationMinutes = plan.preparationMinutes, defaultWakeAt = defaultWake.toString(),
    )

    private fun decision(
        snapshot: EvaluationInputs, attempt: Int, generatedAt: Instant, expiresAt: Instant, outcome: EvaluationOutcome,
        failureReason: String?, fallbackReason: FallbackReason, estimatedDeparture: Instant?, commuteSeconds: Long?,
        weatherSeverity: Int, weatherBufferMinutes: Int, recommendedWake: Instant, routeProvider: String?,
        routeProviderReportTime: Instant?, weatherProvider: String?, weatherProviderReportTime: Instant?,
        weatherWindow: WeatherTimeWindow?, weatherDataSource: WeatherDataSource?, insufficientAdvance: Boolean,
    ) = AlarmDecision(
        decisionId = decisionId(snapshot.plan, snapshot.date, attempt, snapshot.evaluationId), planId = snapshot.plan.id,
        planRevision = snapshot.plan.revision, targetDate = snapshot.date.toString(),
        workdayStatus = snapshot.dayStatus.toDecisionStatus(), estimatedDepartureAt = estimatedDeparture?.toString(),
        commuteSeconds = commuteSeconds, weatherSeverity = weatherSeverity, weatherBufferMinutes = weatherBufferMinutes,
        recommendedWakeAt = recommendedWake.toString(), routeProvider = routeProvider,
        routeProviderReportTime = routeProviderReportTime?.toString(), weatherProvider = weatherProvider,
        weatherProviderReportTime = weatherProviderReportTime?.toString(), weatherWindowStart = weatherWindow?.start?.toInstant()?.toString(),
        weatherWindowEnd = weatherWindow?.end?.toInstant()?.toString(), fallbackReason = fallbackReason,
        insufficientAdvance = insufficientAdvance, generatedAt = generatedAt.toString(), expiresAt = expiresAt.toString(),
        evaluationOutcome = outcome, failureReason = failureReason, attemptNumber = attempt,
        preparationMinutes = snapshot.plan.preparationMinutes, defaultWakeAt = snapshot.defaultWake.toInstant().toString(),
        calendarSource = snapshot.calendarSource, weatherDataSource = weatherDataSource?.name,
    )

    private fun decisionId(plan: AlarmPlan, date: LocalDate, attempt: Int, salt: String? = null): String {
        val key = "${plan.id}:${plan.revision}:$date:$attempt" + salt?.let { ":$it" }.orEmpty()
        return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
    }
}

private enum class ProviderKind { ROUTE, WEATHER }

private data class RouteResult(
    val calculationCommuteSeconds: Long,
    val estimatedDeparture: Instant,
    val fallbackReason: FallbackReason? = null,
)

private data class EvaluationInputs(
    val plan: AlarmPlan,
    val date: LocalDate,
    val evaluationId: String,
    val settings: LocalSettings,
    val commute: EffectiveCommute,
    val calendarDays: Map<String, DayStatus>,
    val override: WorkdayOverride?,
    val dayStatus: DayStatus,
    val weatherProfile: WeatherBufferProfile,
) {
    val defaultWake: ZonedDateTime get() = ZonedDateTime.of(date, LocalTime.parse(plan.defaultWakeLocalTime), plan.zoneIdInstance())
    val arrival: ZonedDateTime get() = ZonedDateTime.of(date, LocalTime.parse(plan.arrivalLocalTime), plan.zoneIdInstance())
    val calendarSource: String get() = when {
        override != null -> "PLAN_OVERRIDE"
        calendarDays.containsKey(date.toString()) -> "HOLIDAY_CN"
        else -> "WEEKDAY_FALLBACK"
    }
    val fingerprint: List<Any?> get() = EvaluationCoordinatorPolicy.fingerprint(
        plan, commute, override, dayStatus, weatherProfile, calendarSource, settings,
    )

    fun routeRequest(departureAt: LocalDateTime?): RouteRequest = RouteRequest(
        origin = commute.origin.toPoint(), destination = commute.destination.toPoint(), mode = commute.commuteMode,
        policy = plan.routePolicy, originCity = commute.origin.citycode.takeIf(String::isNotBlank),
        destinationCity = commute.destination.citycode.takeIf(String::isNotBlank), departureAt = departureAt,
    )
}

internal object EvaluationCoordinatorPolicy {
    fun isExpired(now: Instant, expiresAt: Instant): Boolean = !now.isBefore(expiresAt)

    fun isRetryable(error: ProviderError?): Boolean = error?.retryable == true ||
        (error?.category == ProviderError.Category.PROVIDER_FAILURE && error.providerCode?.startsWith("HTTP_5") == true)

    fun fingerprint(
        plan: AlarmPlan,
        commute: EffectiveCommute,
        override: WorkdayOverride?,
        dayStatus: DayStatus,
        weatherProfile: WeatherBufferProfile,
        calendarSource: String,
        settings: LocalSettings,
    ): List<Any?> = listOf(
        plan.revision, plan.enabled, plan.zoneId, plan.defaultWakeLocalTime, plan.arrivalLocalTime,
        plan.preparationMinutes, plan.maxAdvanceMinutes, plan.schedule, plan.routePolicy, plan.weatherRuleVersion,
        commute, override, dayStatus, weatherProfile, calendarSource,
        settings.amapConsentGranted, settings.amapConsentPromptedVersion,
    )

    fun isEligible(schedule: AlarmSchedule?, date: LocalDate, status: DayStatus, override: WorkdayOverride?): Boolean = when (schedule) {
        is AlarmSchedule.Once -> schedule.date == date.toString() && override?.status != DayStatus.HOLIDAY
        is AlarmSchedule.Weekly -> when (override?.status) {
            DayStatus.WORKDAY -> true
            DayStatus.HOLIDAY -> false
            null -> date.dayOfWeek.value in schedule.days
        }
        AlarmSchedule.Workdays -> status == DayStatus.WORKDAY
        null -> false
    }

    fun weatherProfile(date: LocalDate, calendarDays: Map<String, DayStatus>, settings: LocalSettings): WeatherBufferProfile {
        val buffers = when {
            calendarDays[date.toString()] == DayStatus.HOLIDAY -> settings.holidayWeatherBuffers
            calendarDays[date.toString()] == DayStatus.WORKDAY || date.dayOfWeek.value in 1..5 -> settings.workdayWeatherBuffers
            else -> settings.weekendWeatherBuffers
        }
        return buffers.toProfile()
    }

    fun weatherWindow(plan: AlarmPlan, date: LocalDate): WeatherTimeWindow {
        val zone = plan.zoneIdInstance()
        val end = ZonedDateTime.of(date, LocalTime.parse(plan.arrivalLocalTime), zone)
        return WeatherTimeWindow(
            ZonedDateTime.of(date, LocalTime.parse(plan.defaultWakeLocalTime), zone).minusMinutes(plan.maxAdvanceMinutes.toLong()),
            end,
        )
    }

    fun transitCandidateDepartures(arrival: ZonedDateTime): List<ZonedDateTime> =
        (0..3).map { arrival.minusMinutes(90L + it * 15L) }
}

private fun PlaceRef.toPoint() = GeoPoint(longitudeGcj02, latitudeGcj02)
private fun WeatherBuffers.toProfile() = WeatherBufferProfile(lightMinutes, moderateMinutes, severeMinutes)
private fun DayStatus.toDecisionStatus() = if (this == DayStatus.WORKDAY) WorkdayStatus.WORKDAY else WorkdayStatus.HOLIDAY
private fun ProviderError?.toRouteFallback(): FallbackReason = when (this?.category) {
    ProviderError.Category.TIMEOUT -> FallbackReason.ROUTE_PROVIDER_TIMEOUT
    ProviderError.Category.QUOTA_EXCEEDED, ProviderError.Category.RATE_LIMITED -> FallbackReason.ROUTE_PROVIDER_QUOTA
    ProviderError.Category.ROUTE_NOT_FOUND -> FallbackReason.ROUTE_NOT_FOUND
    else -> FallbackReason.ROUTE_NOT_FOUND
}
private fun ProviderError?.toWeatherFallback(): FallbackReason = when (this?.category) {
    ProviderError.Category.TIMEOUT -> FallbackReason.WEATHER_PROVIDER_TIMEOUT
    ProviderError.Category.INVALID_KEY, ProviderError.Category.MISSING_KEY, ProviderError.Category.CONSENT_REQUIRED -> FallbackReason.WEATHER_PROVIDER_AUTH
    ProviderError.Category.QUOTA_EXCEEDED, ProviderError.Category.RATE_LIMITED -> FallbackReason.WEATHER_PROVIDER_QUOTA
    else -> FallbackReason.WEATHER_PROVIDER_TIMEOUT
}
