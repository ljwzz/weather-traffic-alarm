package com.ljwzz.weathertrafficalarm.evaluation

import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputResolver
import com.ljwzz.weathertrafficalarm.core.data.repository.DailyEvaluationInputs
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.AlarmTimeCalculator
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EffectiveDailySettings
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.ProviderError
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.RouteProvider
import com.ljwzz.weathertrafficalarm.core.model.RouteRequest
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherDataSource
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocation
import com.ljwzz.weathertrafficalarm.core.model.WeatherLocationRole
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.model.WeatherRequest
import com.ljwzz.weathertrafficalarm.core.model.WeatherTimeWindow
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

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
 *
 * Inputs are resolved once through [DailyEvaluationInputResolver] and the resulting fingerprint
 * travels with the decision, so the alarm coordinator can reject a run whose date was edited
 * while the providers were running.
 */
@Singleton
class EvaluationCoordinator @Inject constructor(
    private val plans: AlarmPlanRepository,
    private val inputs: DailyEvaluationInputResolver,
    private val routes: RouteProvider,
    private val weather: WeatherProvider,
    private val decisions: DecisionRepository,
    private val alarms: LocalAlarmCoordinator,
    private val clock: Clock,
    private val diagnostics: RedactingEventLogger,
) {
    suspend fun evaluate(
        planId: String,
        attemptNumber: Int = 0,
        targetDate: LocalDate? = null,
        deadline: Instant? = null,
        evaluationId: String = UUID.randomUUID().toString(),
    ): EvaluationRunResult {
        val started = System.nanoTime()
        var resultCode = DiagnosticResultCode.FAILED
        try {
            val result = evaluateRun(planId, attemptNumber, targetDate, deadline, evaluationId)
            resultCode = when (result.decision?.evaluationOutcome) {
                EvaluationOutcome.SUCCESS -> DiagnosticResultCode.SUCCESS
                EvaluationOutcome.FAILED -> DiagnosticResultCode.FAILED
                EvaluationOutcome.STALE -> DiagnosticResultCode.STALE
                EvaluationOutcome.SKIPPED, null -> DiagnosticResultCode.SKIPPED
            }
            return result
        } catch (cancelled: CancellationException) {
            resultCode = DiagnosticResultCode.CANCELLED
            throw cancelled
        } finally {
            diagnostics.record(
                DiagnosticEventType.EVALUATION, resultCode, planId = planId,
                durationMs = (System.nanoTime() - started) / 1_000_000,
                timestamp = clock.millis(),
            )
        }
    }

    private suspend fun evaluateRun(
        planId: String,
        attemptNumber: Int,
        targetDate: LocalDate?,
        deadline: Instant?,
        evaluationId: String,
    ): EvaluationRunResult {
        val initialPlan = plans.getById(planId)?.takeIf { it.enabled } ?: return EvaluationRunResult(false)
        val date = targetDate ?: clock.instant().atZone(initialPlan.zoneIdInstance()).toLocalDate().plusDays(1)
        val startedAt = clock.instant()
        val resolved = inputs.resolve(initialPlan, date)
        val effectiveCommute = resolved.effectiveCommute
        if (effectiveCommute == null) {
            return persistFailure(
                resolved, evaluationId, attemptNumber, deadline, startedAt,
                "COMMUTE_NOT_CONFIGURED", FallbackReason.ROUTE_NOT_FOUND,
            )
        }
        if (deadline != null && !startedAt.isBefore(deadline)) {
            return persistStale(resolved, evaluationId, attemptNumber, startedAt, deadline, "EVALUATION_WINDOW_EXPIRED")
        }
        if (!EvaluationCoordinatorPolicy.isEligible(resolved.plan.schedule, date, resolved.dayStatus, resolved.override)) {
            return persistSkipped(resolved, evaluationId, attemptNumber, startedAt, deadline)
        }

        val route = try {
            resolveRoute(resolved)
        } catch (failure: Throwable) {
            return persistProviderFailure(resolved, evaluationId, attemptNumber, startedAt, deadline, failure, ProviderKind.ROUTE)
        }
        val weatherWindow = try {
            EvaluationCoordinatorPolicy.weatherWindow(resolved.plan, resolved.effective)
        } catch (_: IllegalArgumentException) {
            return persistFailure(
                resolved, evaluationId, attemptNumber, deadline, startedAt,
                "INVALID_TIME_WINDOW", FallbackReason.NONE, route = route,
            )
        }
        val weatherResult = try {
            weather.evaluate(
                WeatherRequest(
                    home = WeatherLocation(WeatherLocationRole.HOME, effectiveCommute.origin.toPoint()),
                    work = WeatherLocation(WeatherLocationRole.WORK, effectiveCommute.destination.toPoint()),
                    window = weatherWindow,
                    weatherBufferProfile = resolved.effective.weatherProfile,
                    weatherRuleVersion = resolved.plan.weatherRuleVersion,
                    requestedAt = startedAt,
                ),
            )
        } catch (failure: Throwable) {
            return persistProviderFailure(resolved, evaluationId, attemptNumber, startedAt, deadline, failure, ProviderKind.WEATHER)
        }
        if (!weatherResult.isUsableForScheduling || weatherResult.providerReportTime == null || weatherResult.source == null) {
            return persistFailure(
                resolved, evaluationId, attemptNumber, deadline, startedAt,
                "WEATHER_${weatherResult.fallbackReason.name}", weatherResult.fallbackReason, route = route,
            )
        }

        val calculation = AlarmTimeCalculator.calculate(
            defaultWakeTime = requireNotNull(resolved.effective.wakeLocalTime),
            arrivalTime = requireNotNull(resolved.effective.arrivalLocalTimeValue),
            preparationMinutes = resolved.effective.preparationMinutes,
            maxAdvanceMinutes = resolved.plan.maxAdvanceMinutes,
            commuteSeconds = route.calculationCommuteSeconds,
            weatherBufferMinutes = weatherResult.bufferMinutes,
            targetDate = date,
            zoneId = resolved.plan.zoneIdInstance(),
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
        val expired = EvaluationCoordinatorPolicy.isExpired(completedAt, expiresAt)
        if (expired || !hasSameInputs(resolved)) {
            return persistStale(
                resolved, evaluationId, attemptNumber, completedAt, expiresAt,
                if (expired) "EVALUATION_RESULT_EXPIRED" else "EVALUATION_INPUTS_CHANGED",
            )
        }

        val decision = decision(
            inputs = resolved,
            evaluationId = evaluationId,
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
        // The coordinator owns its own mutex and re-resolves the fingerprint before committing.
        alarms.applyEvaluation(decision, resolved.fingerprint)
        return EvaluationRunResult(false, decision = decisions.getById(decision.decisionId) ?: decision)
    }

    /** Re-resolves the day and reports whether the evaluation inputs are still the same. */
    private suspend fun hasSameInputs(before: DailyEvaluationInputs): Boolean {
        val latestPlan = plans.getById(before.plan.id) ?: return false
        if (!latestPlan.enabled || latestPlan.revision != before.plan.revision || latestPlan.zoneId != before.plan.zoneId) return false
        val latest = inputs.resolve(latestPlan, before.date)
        return latest.fingerprint == before.fingerprint
    }

    private suspend fun resolveRoute(inputs: DailyEvaluationInputs): RouteResult {
        val commute = requireNotNull(inputs.effectiveCommute)
        return when (commute.commuteMode) {
            CommuteMode.DRIVING -> resolveDrivingRoute(inputs)
            CommuteMode.TRANSIT -> resolveTransitRoute(inputs)
            else -> {
                val estimate = routes.estimate(inputs.routeRequest(departureAt = null))
                val duration = estimate.alternatives.map(RouteAlternative::durationSeconds).filter { it >= 0 }.minOrNull()
                    ?: throw ProviderError(ProviderError.Category.ROUTE_NOT_FOUND, message = "No usable route duration")
                RouteResult(
                    calculationCommuteSeconds = duration,
                    estimatedDeparture = inputs.arrivalInstant.minusSeconds(duration),
                )
            }
        }
    }

    private suspend fun resolveDrivingRoute(inputs: DailyEvaluationInputs): RouteResult {
        // The v5 driving API has no departure-time parameter. Reuse its current-traffic
        // estimate across the local candidates and retain the fallback in the decision.
        // https://lbs.amap.com/api/webservice/guide/api/newroute
        val estimate = routes.estimate(inputs.routeRequest(departureAt = null))
        val duration = estimate.alternatives.map(RouteAlternative::durationSeconds).filter { it >= 0 }.minOrNull()
            ?: throw ProviderError(ProviderError.Category.ROUTE_NOT_FOUND, message = "No usable route duration")
        val arrival = inputs.arrival
        val departure = EvaluationCoordinatorPolicy.drivingCandidateDepartures(arrival).lastOrNull {
            duration <= Duration.between(it, arrival).seconds
        } ?: throw ProviderError(ProviderError.Category.ROUTE_NOT_FOUND, message = "No driving candidate arrives before the target")
        return RouteResult(
            // Include the candidate's early-arrival margin so wake calculation uses this departure.
            calculationCommuteSeconds = Duration.between(departure, arrival).seconds,
            estimatedDeparture = departure.toInstant(),
            fallbackReason = FallbackReason.CURRENT_TRAFFIC_FALLBACK,
        )
    }

    private suspend fun resolveTransitRoute(inputs: DailyEvaluationInputs): RouteResult {
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
        inputs: DailyEvaluationInputs,
        evaluationId: String,
        attempt: Int,
        generatedAt: Instant,
        deadline: Instant?,
        failure: Throwable,
        kind: ProviderKind,
    ): EvaluationRunResult {
        if (failure is CancellationException) throw failure
        val provider = failure as? ProviderError
        val retryable = EvaluationCoordinatorPolicy.isRetryable(provider)
        return persistFailure(
            inputs, evaluationId, attempt, deadline, generatedAt,
            "${kind.name}_${provider?.category?.name ?: "UNEXPECTED"}",
            when (kind) {
                ProviderKind.ROUTE -> provider.toRouteFallback()
                ProviderKind.WEATHER -> provider.toWeatherFallback()
            },
            retryable = retryable,
            retryAfterSeconds = provider?.retryAfterSeconds,
        )
    }

    private suspend fun persistFailure(
        inputs: DailyEvaluationInputs,
        evaluationId: String,
        attempt: Int,
        deadline: Instant?,
        generatedAt: Instant,
        failureReason: String,
        fallbackReason: FallbackReason,
        route: RouteResult? = null,
        retryable: Boolean = false,
        retryAfterSeconds: Long? = null,
    ): EvaluationRunResult {
        val defaultWake = inputs.defaultWakeInstant
        val decision = decision(
            inputs, evaluationId, attempt, generatedAt, deadline ?: defaultWake, EvaluationOutcome.FAILED,
            failureReason, fallbackReason,
            route?.estimatedDeparture, route?.calculationCommuteSeconds, 0, 0, defaultWake,
            route?.let { "AMAP_WEB" }, null, null, null, null, null, false,
        )
        decisions.save(decision)
        return EvaluationRunResult(retryable, retryAfterSeconds, decision)
    }

    private suspend fun persistSkipped(
        inputs: DailyEvaluationInputs,
        evaluationId: String,
        attempt: Int,
        generatedAt: Instant,
        deadline: Instant?,
    ): EvaluationRunResult {
        val wake = inputs.defaultWakeInstant
        val decision = decision(
            inputs, evaluationId, attempt, generatedAt, deadline ?: wake, EvaluationOutcome.SKIPPED,
            "DATE_NOT_APPLICABLE", FallbackReason.NONE,
            null, null, 0, 0, wake, null, null, null, null, null, null, false,
        )
        decisions.save(decision)
        return EvaluationRunResult(false, decision = decision)
    }

    private suspend fun persistStale(
        inputs: DailyEvaluationInputs,
        evaluationId: String,
        attempt: Int,
        generatedAt: Instant,
        expiresAt: Instant,
        reason: String,
    ): EvaluationRunResult {
        val wake = inputs.defaultWakeInstant
        val decision = decision(
            inputs, evaluationId, attempt, generatedAt, expiresAt, EvaluationOutcome.STALE,
            reason, FallbackReason.STALE_RESPONSE,
            null, null, 0, 0, wake, null, null, null, null, null, null, false,
        )
        decisions.save(decision)
        return EvaluationRunResult(false, decision = decision)
    }

    private fun decision(
        inputs: DailyEvaluationInputs,
        evaluationId: String,
        attempt: Int,
        generatedAt: Instant,
        expiresAt: Instant,
        outcome: EvaluationOutcome,
        failureReason: String?,
        fallbackReason: FallbackReason,
        estimatedDeparture: Instant?,
        commuteSeconds: Long?,
        weatherSeverity: Int,
        weatherBufferMinutes: Int,
        recommendedWake: Instant,
        routeProvider: String?,
        routeProviderReportTime: Instant?,
        weatherProvider: String?,
        weatherProviderReportTime: Instant?,
        weatherWindow: WeatherTimeWindow?,
        weatherDataSource: WeatherDataSource?,
        insufficientAdvance: Boolean,
    ) = AlarmDecision(
        decisionId = decisionId(inputs.plan, inputs.date, attempt, evaluationId),
        planId = inputs.plan.id,
        planRevision = inputs.plan.revision,
        targetDate = inputs.date.toString(),
        workdayStatus = inputs.dayStatus.toDecisionStatus(),
        estimatedDepartureAt = estimatedDeparture?.toString(),
        commuteSeconds = commuteSeconds, weatherSeverity = weatherSeverity, weatherBufferMinutes = weatherBufferMinutes,
        recommendedWakeAt = recommendedWake.toString(), routeProvider = routeProvider,
        routeProviderReportTime = routeProviderReportTime?.toString(), weatherProvider = weatherProvider,
        weatherProviderReportTime = weatherProviderReportTime?.toString(), weatherWindowStart = weatherWindow?.start?.toInstant()?.toString(),
        weatherWindowEnd = weatherWindow?.end?.toInstant()?.toString(), fallbackReason = fallbackReason,
        insufficientAdvance = insufficientAdvance, generatedAt = generatedAt.toString(), expiresAt = expiresAt.toString(),
        evaluationOutcome = outcome, failureReason = failureReason, attemptNumber = attempt,
        // The decision stores the values this evaluation actually used. Editing the day
        // override later never rewrites an earlier decision snapshot.
        preparationMinutes = inputs.effective.preparationMinutes,
        defaultWakeAt = inputs.defaultWakeInstant.toString(),
        arrivalLocalTime = inputs.effective.arrivalLocalTime,
        dayRevision = inputs.committedRevision,
        calendarSource = inputs.calendarSource, weatherDataSource = weatherDataSource?.name,
        planName = inputs.plan.name, zoneId = inputs.plan.zoneId,
        applicationOutcome = if (outcome == EvaluationOutcome.SUCCESS) null else "NOT_APPLIED",
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

/** Local wall-clock instants of the resolved day, derived from the effective values only. */
private val DailyEvaluationInputs.defaultWakeInstant: Instant
    get() = ZonedDateTime.of(date, requireNotNull(effective.wakeLocalTime), plan.zoneIdInstance()).toInstant()

private val DailyEvaluationInputs.arrivalInstant: Instant
    get() = ZonedDateTime.of(date, requireNotNull(effective.arrivalLocalTimeValue), plan.zoneIdInstance()).toInstant()

private val DailyEvaluationInputs.arrival: ZonedDateTime
    get() = ZonedDateTime.of(date, requireNotNull(effective.arrivalLocalTimeValue), plan.zoneIdInstance())

private fun DailyEvaluationInputs.routeRequest(departureAt: LocalDateTime?): RouteRequest {
    val commute = requireNotNull(effectiveCommute)
    return RouteRequest(
        origin = commute.origin.toPoint(), destination = commute.destination.toPoint(), mode = commute.commuteMode,
        policy = plan.routePolicy, originCity = commute.origin.citycode.takeIf(String::isNotBlank),
        destinationCity = commute.destination.citycode.takeIf(String::isNotBlank), departureAt = departureAt,
    )
}

internal object EvaluationCoordinatorPolicy {
    fun isExpired(now: Instant, expiresAt: Instant): Boolean = !now.isBefore(expiresAt)

    fun isRetryable(error: ProviderError?): Boolean = error?.retryable == true ||
        (error?.category == ProviderError.Category.PROVIDER_FAILURE && error.providerCode?.startsWith("HTTP_5") == true)

    fun isEligible(
        schedule: AlarmSchedule?,
        date: LocalDate,
        status: DayStatus,
        override: SingleDayOverride?,
    ): Boolean = when (schedule) {
        is AlarmSchedule.Once ->
            schedule.date == date.toString() && override?.status != DayStatus.HOLIDAY
        is AlarmSchedule.Weekly -> when (override?.status) {
            DayStatus.WORKDAY -> true
            DayStatus.HOLIDAY -> false
            null -> date.dayOfWeek.value in schedule.days
        }
        AlarmSchedule.Workdays ->
            status == DayStatus.WORKDAY
        null -> false
    }

    /**
     * Weather window for the resolved day inputs. The window starts from the effective wake
     * time minus the advance allowance and ends at the effective arrival time.
     */
    fun weatherWindow(
        plan: AlarmPlan,
        effective: EffectiveDailySettings,
    ): WeatherTimeWindow {
        val zone = plan.zoneIdInstance()
        val end = ZonedDateTime.of(effective.date, requireNotNull(effective.arrivalLocalTimeValue), zone)
        return WeatherTimeWindow(
            ZonedDateTime.of(effective.date, requireNotNull(effective.wakeLocalTime), zone)
                .minusMinutes(plan.maxAdvanceMinutes.toLong()),
            end,
        )
    }

    fun drivingCandidateDepartures(arrival: ZonedDateTime): List<ZonedDateTime> =
        (180 downTo 0 step 15).map { arrival.minusMinutes(it.toLong()) }

    fun transitCandidateDepartures(arrival: ZonedDateTime): List<ZonedDateTime> =
        (0..3).map { arrival.minusMinutes(90L + it * 15L) }
}

private fun PlaceRef.toPoint() = GeoPoint(longitudeGcj02, latitudeGcj02)
private fun DayStatus.toDecisionStatus() =
    if (this == DayStatus.WORKDAY) WorkdayStatus.WORKDAY else WorkdayStatus.HOLIDAY

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
